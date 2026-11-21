package ca.tariq_sekhri.time_tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar

class WeeklyRecapWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val end = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = (end.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
        val previousStart = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
        val prefs = applicationContext.getSharedPreferences("WeeklyRecap", Context.MODE_PRIVATE)
        if (prefs.getLong("last_week", 0) >= end.timeInMillis) return Result.success()
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()

        val apps = mutableMapOf<String, Long>()
        var previousTotal = 0L
        val devices = mutableSetOf<String>()
        val db = DatabaseHelper(applicationContext)
        try {
            val sql = """
                SELECT COALESCE(NULLIF(app_label, ''), package_name), start_timestamp,
                       COALESCE(end_timestamp, start_timestamp + duration * 1000), duration, 'local'
                FROM logs WHERE start_timestamp < ? AND COALESCE(end_timestamp, start_timestamp + duration * 1000) > ?
                UNION ALL
                SELECT r.app, r.start_timestamp, r.end_timestamp, r.duration, r.device_uuid
                FROM remote_logs r JOIN remote_devices d ON d.uuid = r.device_uuid
                WHERE d.is_tracking = 1 AND d.in_stats = 1 AND r.start_timestamp < ? AND r.end_timestamp > ?
            """.trimIndent()
            val args = arrayOf(end.timeInMillis.toString(), previousStart.timeInMillis.toString(), end.timeInMillis.toString(), previousStart.timeInMillis.toString())
            db.readableDatabase.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val from = maxOf(previousStart.timeInMillis, cursor.getLong(1))
                    val to = minOf(end.timeInMillis, cursor.getLong(2))
                    val recordedSeconds = cursor.getLong(3)
                    val previousSeconds = minOf(recordedSeconds, (minOf(to, start.timeInMillis) - from).coerceAtLeast(0) / 1000)
                    previousTotal += previousSeconds
                    val seconds = minOf((recordedSeconds - previousSeconds).coerceAtLeast(0), (to - maxOf(from, start.timeInMillis)).coerceAtLeast(0) / 1000)
                    if (seconds <= 0) continue
                    val app = cursor.getString(0)
                    apps[app] = (apps[app] ?: 0) + seconds
                    devices.add(cursor.getString(4))
                }
            }
        } finally { db.close() }
        if (apps.isEmpty()) return Result.success()
        val top = apps.maxByOrNull { it.value }!!
        fun duration(seconds: Long): String = "${seconds / 3600}h ${seconds % 3600 / 60}m"
        val total = apps.values.sum()
        val summary = "${duration(total)} tracked across ${devices.size} ${if (devices.size == 1) "device" else "devices"}"
        val comparison = if (previousTotal == 0L) "No tracked time last week" else {
            val percent = ((total - previousTotal) * 100.0 / previousTotal).toInt()
            "${if (percent > 0) "+" else ""}$percent% vs last week"
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("weekly_recap", "Weekly recap", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "weekly_recap")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Time Tracker · Your week")
            .setContentText(summary)
            .setStyle(NotificationCompat.InboxStyle().addLine(summary)
                .addLine("Top app: ${top.key} · ${duration(top.value)}")
                .addLine(comparison))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(102, notification)
            prefs.edit().putLong("last_week", end.timeInMillis).apply()
        } catch (_: SecurityException) { return Result.success() }
        return Result.success()
    }
}
