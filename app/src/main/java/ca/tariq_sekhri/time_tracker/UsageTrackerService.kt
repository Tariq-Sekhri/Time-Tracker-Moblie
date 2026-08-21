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
import androidx.core.app.NotificationCompat
import java.util.Timer
import kotlin.concurrent.timerTask

/** Restored live tracker: keeps the active app as a row in the existing log table. */
class UsageTrackerService : Service() {
    private lateinit var db: DatabaseHelper
    private lateinit var metadata: AppMetadataHelper
    private lateinit var categories: CategoryManager
    private lateinit var usageStats: UsageStatsManager
    private var timer: Timer? = null
    private var activeLogId = -1L
    private var activePackage: String? = null
    private var lastEventMs = 0L

    override fun onCreate() {
        super.onCreate()
        db = DatabaseHelper(this)
        metadata = AppMetadataHelper(this)
        categories = CategoryManager(this)
        usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (activeLogId == -1L) {
            // The app starts this service while its own activity is foregrounded.  Do not
            // guess from UsageStats here: emulators can report Pixel Launcher late.
            begin(metadata.fromPackage(packageName), System.currentTimeMillis())
            lastEventMs = System.currentTimeMillis()
        }
        if (timer == null) timer = Timer().also { it.scheduleAtFixedRate(timerTask { tick() }, 0, 1_000L) }
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
                    if (packageName != activePackage) {
                        finishActive(event.timeStamp, "SESSION_SWITCHED")
                        begin(metadata.fromEvent(event), event.timeStamp)
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> finishActive(event.timeStamp, "SCREEN_INACTIVE")
            }
        }
        lastEventMs = if (latest > from) latest else now
        if (activeLogId != -1L) db.increaseDuration(activeLogId)
    }

    private fun begin(entry: RichLogMetadata, startedAt: Long) {
        val target = "${entry.packageName} ${entry.appLabel}"
        if (db.getSkippedApps().any { row -> row.pattern.isNotBlank() && (target.contains(row.pattern, true) || runCatching { Regex(row.pattern, RegexOption.IGNORE_CASE).containsMatchIn(target) }.getOrDefault(false)) }) return
        activeLogId = db.insertLog(entry, startedAt, categories.resolveCategory(entry.packageName, entry.appLabel))
        activePackage = entry.packageName
    }

    private fun finishActive(endedAt: Long, reason: String) {
        if (activeLogId != -1L) db.endLog(activeLogId, endedAt, reason)
        activeLogId = -1L
        activePackage = null
    }
    override fun onDestroy() { finishActive(System.currentTimeMillis(), "SERVICE_STOPPED"); timer?.cancel(); timer = null; super.onDestroy() }
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
