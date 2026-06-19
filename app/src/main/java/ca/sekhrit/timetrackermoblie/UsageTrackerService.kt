package ca.sekhrit.timetrackermoblie

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.concurrent.timerTask

class UsageTrackerService : Service() {

    private var timer: Timer? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var metadataHelper: AppMetadataHelper
    private lateinit var usageStatsManager: UsageStatsManager
    private var activeLogId: Long = -1
    private var activePackageName: String? = null
    private var activeActivityClass: String? = null
    private var lastEventQueryTime: Long = 0L
    private var insertedServiceStartLog = false

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        metadataHelper = AppMetadataHelper(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TRACKING) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildTrackingNotification())
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        if (timer != null) return
        timer = Timer()
        timer?.scheduleAtFixedRate(timerTask {
            tick()
        }, 0, 1000)
    }

    private fun tick() {
        processUsageEvents()
        val sawForegroundApp = pollForegroundApp()
        if (activeLogId != -1L) {
            dbHelper.increaseDuration(activeLogId)
        } else if (!insertedServiceStartLog && !sawForegroundApp) {
            insertedServiceStartLog = true
            startSession(
                metadataHelper.synthetic(packageName, "Time Tracker service started", "SERVICE_STARTED_NO_FOREGROUND_APP"),
                System.currentTimeMillis()
            )
        }
    }

    private fun pollForegroundApp(): Boolean {
        val currentApp = getForegroundApp() ?: return false
        if (activePackageName == currentApp) return true
        if (activeLogId != -1L) {
            endActiveSession(System.currentTimeMillis(), "POLL_SWITCHED")
        }
        startSession(metadataHelper.fromPackage(currentApp), System.currentTimeMillis())
        return true
    }

    private fun processUsageEvents() {
        val now = System.currentTimeMillis()
        if (lastEventQueryTime == 0L) {
            lastEventQueryTime = now - 60_000L
        }
        val events = usageStatsManager.queryEvents(lastEventQueryTime, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            handleUsageEvent(event)
        }
        lastEventQueryTime = now
    }

    private fun handleUsageEvent(event: UsageEvents.Event) {
        val packageName = event.packageName ?: return
        when {
            AppMetadataHelper.isForegroundEvent(event.eventType) -> {
                val metadata = metadataHelper.fromEvent(event)
                if (activeLogId != -1L) {
                    if (activePackageName == packageName && activeActivityClass == metadata.activityClass) {
                        return
                    }
                    endActiveSession(event.timeStamp, "SESSION_SWITCHED")
                }
                startSession(metadata, event.timeStamp)
            }
            AppMetadataHelper.isBackgroundEvent(event.eventType) -> {
                if (activeLogId != -1L) {
                    if (activePackageName == packageName) {
                        endActiveSession(event.timeStamp, AppMetadataHelper.eventTypeName(event.eventType))
                    }
                }
            }
        }
    }

    private fun startSession(metadata: RichLogMetadata, startTimestampMs: Long) {
        if (isSkipped(metadata)) {
            activeLogId = -1L
            activePackageName = null
            activeActivityClass = null
            return
        }
        activeLogId = dbHelper.insertLog(metadata, startTimestampMs)
        activePackageName = metadata.packageName
        activeActivityClass = metadata.activityClass
    }

    private fun isSkipped(metadata: RichLogMetadata): Boolean {
        val target = listOfNotNull(metadata.packageName, metadata.appLabel).joinToString(" ")
        return dbHelper.getSkippedApps().any { row ->
            val pattern = row.pattern.trim()
            pattern.isNotEmpty() && (
                target.contains(pattern, ignoreCase = true) ||
                    runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(target) }.getOrDefault(false)
                )
        }
    }

    private fun endActiveSession(endTimestampMs: Long, endEventType: String) {
        if (activeLogId == -1L) return
        dbHelper.endLog(activeLogId, endTimestampMs, endEventType)
        activeLogId = -1L
        activePackageName = null
        activeActivityClass = null
    }

    private fun getForegroundApp(): String? {
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 60_000L,
            time
        ) ?: return null
        if (stats.isEmpty()) return null
        var activeStats = stats[0]
        for (usageStats in stats) {
            if (usageStats.lastTimeUsed > activeStats.lastTimeUsed) {
                activeStats = usageStats
            }
        }
        return activeStats.packageName
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Usage Tracker",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildTrackingNotification(): Notification {
        val stopIntent = Intent(this, UsageTrackerService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Time Tracker is running")
            .setContentText("Generating local usage logs in the background")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        endActiveSession(System.currentTimeMillis(), "SERVICE_STOPPED")
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_TRACKING = "ca.sekhrit.timetrackermoblie.action.STOP_TRACKING"
        private const val CHANNEL_ID = "UsageTrackerChannel"
        private const val NOTIFICATION_ID = 1
    }
}
