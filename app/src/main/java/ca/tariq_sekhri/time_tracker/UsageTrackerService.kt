package ca.tariq_sekhri.time_tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

/** Restored live tracker: keeps the active app as a row in the existing log table. */
class UsageTrackerService : Service() {
    private lateinit var db: DatabaseHelper
    private lateinit var metadata: AppMetadataHelper
    private lateinit var categories: CategoryManager
    private lateinit var usageStats: UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private var tracking = false
    private var foregroundEntry: RichLogMetadata? = null
    private val ticker = object : Runnable {
        override fun run() { tick(); if (tracking) handler.postDelayed(this, 1_000L) }
    }
    private val mediaListener: (String, Boolean, Long) -> Unit = { pkg, playing, timestamp ->
        foregroundEntry?.takeIf { it.packageName == pkg }?.let { entry ->
            if (playing) finishActive(timestamp, "MEDIA_PLAYING")
            else if (activeLogId == -1L) begin(entry, timestamp)
        }
    }
    private var activeLogId = -1L
    private var activeStartMs = 0L
    private var lastEventMs = 0L

    override fun onCreate() {
        super.onCreate()
        db = DatabaseHelper(this)
        metadata = AppMetadataHelper(this)
        categories = CategoryManager(this)
        usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createChannel()
        MediaUsagePriority.addListener(mediaListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (!tracking) {
            // The app starts this service while its own activity is foregrounded.  Do not
            // guess from UsageStats here: emulators can report Pixel Launcher late.
            foregroundEntry = metadata.fromPackage(packageName)
            begin(foregroundEntry!!, System.currentTimeMillis())
            lastEventMs = System.currentTimeMillis()
            tracking = true
            handler.post(ticker)
        }
        return START_STICKY
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val from = if (lastEventMs == 0L) now - 60_000L else lastEventMs
        val events = usageStats.queryEvents(from, now)
        val event = UsageEvents.Event()
        var latest = from
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            latest = maxOf(latest, event.timeStamp)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val packageName = event.packageName ?: continue
                    if (packageName != foregroundEntry?.packageName) {
                        finishActive(event.timeStamp, "SESSION_SWITCHED")
                        foregroundEntry = metadata.fromEvent(event)
                        begin(foregroundEntry!!, event.timeStamp)
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> {
                    finishActive(event.timeStamp, "SCREEN_INACTIVE")
                    foregroundEntry = null
                }
            }
        }
        lastEventMs = if (latest > from) latest else now
        if (activeLogId != -1L) db.endLog(activeLogId, now, "TRACKING")
    }

    private fun begin(entry: RichLogMetadata, startedAt: Long) {
        if (MediaUsagePriority.activeStart(entry.packageName) != null) return
        val target = "${entry.packageName} ${entry.appLabel}"
        if (db.getSkippedApps().any { row -> row.pattern.isNotBlank() && (target.contains(row.pattern, true) || runCatching { Regex(row.pattern, RegexOption.IGNORE_CASE).containsMatchIn(target) }.getOrDefault(false)) }) return
        activeLogId = db.insertLog(entry, startedAt, categories.resolveCategory(entry.packageName, entry.appLabel))
        activeStartMs = startedAt
    }

    private fun finishActive(endedAt: Long, reason: String) {
        if (activeLogId != -1L) {
            if (endedAt - activeStartMs < 1_000L) db.deleteLog(activeLogId)
            else db.endLog(activeLogId, endedAt, reason)
        }
        activeLogId = -1L
    }
    override fun onDestroy() {
        tracking = false
        handler.removeCallbacks(ticker)
        MediaUsagePriority.removeListener(mediaListener)
        finishActive(System.currentTimeMillis(), "SERVICE_STOPPED")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Usage tracking", NotificationManager.IMPORTANCE_LOW))
        }
    }
    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("Time Tracker is running")
        .setContentText("Tracking active app usage")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object { private const val CHANNEL = "usage_tracker"; private const val NOTIFICATION_ID = 101 }
}
