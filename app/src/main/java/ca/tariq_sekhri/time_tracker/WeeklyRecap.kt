package ca.tariq_sekhri.time_tracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

data class RecapWindow(val start: LocalDate, val zone: ZoneId) {
    val end: LocalDate get() = start.plusWeeks(1)
    fun boundary(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val key: String get() = start.toString()
    companion object {
        fun completed(now: ZonedDateTime) = RecapWindow(
            now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1), now.zone)
        fun nextDelivery(now: ZonedDateTime): ZonedDateTime {
            val monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atTime(9, 0).atZone(now.zone)
            return if (now.isBefore(monday)) monday else monday.plusWeeks(1)
        }
    }
}

data class WeeklyRecap(val window: RecapWindow, val days: List<Long>, val previousSeconds: Long) {
    val seconds get() = days.sum()
    val delta get() = seconds - previousSeconds
    val totalText get() = duration(seconds)
    val comparison: String get() = when {
        delta == 0L -> "±0m vs the week before"
        else -> "${if (delta > 0) "+" else "−"}${duration(kotlin.math.abs(delta))} vs the week before"
    }
    val message get() = when {
        seconds == 0L -> "A quiet week. A fresh chapter starts today. 🌱"
        days.all { it > 0 } -> "Seven days, one story. Your week, captured. ✨"
        else -> "Little moments add up. Here's your week in perspective. ✨"
    }
    val dates: String get() {
        val format = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault())
        return "${window.start.format(format)} – ${window.end.minusDays(1).format(format)}"
    }
    companion object {
        fun duration(seconds: Long): String {
            val minutes = seconds / 60
            return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
        }
        fun read(context: Context, window: RecapWindow): WeeklyRecap {
            DatabaseHelper(context).use { helper ->
                fun totals(start: LocalDate, end: LocalDate): List<Long> {
                    val from = window.boundary(start); val to = window.boundary(end)
                    val days = MutableList(7) { 0L }
                    // Same duration/overlap rules as the mobile Usage screen. Do not include manual
                    // timers as well: those can overlap with the device's automatic usage logs.
                    helper.readableDatabase.rawQuery("""SELECT start_timestamp,
                        COALESCE(end_timestamp, start_timestamp + duration * 1000), duration FROM logs
                        WHERE start_timestamp < ? AND COALESCE(end_timestamp, start_timestamp + duration * 1000) > CAST(? AS INTEGER)""",
                        arrayOf(to.toString(), from.toString())).use { cursor ->
                        while (cursor.moveToNext()) {
                            val clippedStart = maxOf(from, cursor.getLong(0))
                            val clippedEnd = minOf(to, cursor.getLong(1))
                            var remaining = minOf(cursor.getLong(2), maxOf(0L, (clippedEnd - clippedStart) / 1000))
                            // Cap once per log before splitting; a long interval with a shorter
                            // duration must not contribute that duration again on every day.
                            for (day in 0..6) {
                                val overlap = maxOf(0L, (minOf(clippedEnd, window.boundary(start.plusDays(day + 1L))) -
                                    maxOf(clippedStart, window.boundary(start.plusDays(day.toLong())))) / 1000)
                                val seconds = minOf(maxOf(0L, remaining), overlap)
                                days[day] += seconds; remaining -= seconds
                            }
                        }
                    }
                    return days
                }
                return WeeklyRecap(window, totals(window.start, window.end), totals(window.start.minusWeeks(1), window.start).sum())
            }
        }
    }
}

object WeeklyRecapNotifications {
    const val CHANNEL = "weekly-recap"
    const val ID = 7301
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly recap", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your completed week of device usage, every Monday morning"
            })
    }
    fun show(context: Context, recap: WeeklyRecap, demo: Boolean = false): Boolean {
        channel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, WeeklyRecapActivity::class.java).putExtra("week", recap.window.key)
        // The preview extra is consumed only by debug builds; release summaries always read SQLite.
        if (demo) intent.putExtra("demo", true)
        val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_weekly_recap)
            .setColor(android.graphics.Color.rgb(124, 102, 255))
            .setContentTitle("Your week, wrapped ✨")
            .setContentText("${recap.totalText} last week · ${recap.comparison}")
            .setSubText(if (demo) "Preview · sample data" else recap.dates)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${recap.totalText} of device usage last week\n${recap.comparison}\n\n${recap.message}"))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true)
            .addAction(0, "View my week", pending).build())
        return true
    }
}

class WeeklyRecapWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        deliver(applicationContext, ZonedDateTime.now())
        Result.success()
    } catch (_: Exception) { Result.retry() }
    companion object {
        @Synchronized fun deliver(context: Context, now: ZonedDateTime): Boolean {
            val window = RecapWindow.completed(now)
            if (now.isBefore(window.end.atTime(9, 0).atZone(now.zone))) return false
            val prefs = context.getSharedPreferences("WeeklyRecap", Context.MODE_PRIVATE)
            if (prefs.getString("delivered", null)?.let { it >= window.key } == true) return false
            UsageLogImporter(context).importNow()
            if (!WeeklyRecapNotifications.show(context, WeeklyRecap.read(context, window))) return false
            check(prefs.edit().putString("delivered", window.key).commit())
            return true
        }
        fun schedule(context: Context) {
            WeeklyRecapNotifications.channel(context)
            val now = ZonedDateTime.now()
            // Persistent work survives reboots. Hourly checks use local calendar weeks so DST
            // and travel cannot shift which week is summarized. Android may defer for battery.
            val request = PeriodicWorkRequestBuilder<WeeklyRecapWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, RecapWindow.nextDelivery(now)).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("weekly-recap", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
