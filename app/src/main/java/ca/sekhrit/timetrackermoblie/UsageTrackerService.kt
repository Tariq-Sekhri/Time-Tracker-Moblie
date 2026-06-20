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
    private lateinit var categoryManager: CategoryManager
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
        categoryManager = CategoryManager(this)
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

    private var pollingFlickerCount = 0
    private var lastPolledPackage: String? = null

    private fun tick() {
        // 1. Process real UsageEvents first (most accurate)
        processUsageEvents()
        
        // 2. Polling as a fallback to catch app exits or missed events
        val currentAppByPolling = getForegroundApp()
        
        if (activeLogId != -1L) {
            // We have an active session.
            if (currentAppByPolling == null || activePackageName == currentAppByPolling) {
                // Polling confirms we're still in the app (or is inconclusive)
                dbHelper.increaseDuration(activeLogId)
            } else {
                // Polling says we are in a different app.
                // We trust polling if it's consistent.
                if (currentAppByPolling == lastPolledPackage) {
                    pollingFlickerCount++
                } else {
                    pollingFlickerCount = 1
                }
                lastPolledPackage = currentAppByPolling

                if (pollingFlickerCount >= 3) { // Increased to 3 seconds for better stability
                    endActiveSession(System.currentTimeMillis(), "POLL_SWITCHED")
                    // The next tick or event will start the new session
                } else {
                    // Still count time for the active session while we wait for stability
                    dbHelper.increaseDuration(activeLogId)
                }
            }
        } else {
            // No active session. 
            if (currentAppByPolling != null) {
                if (currentAppByPolling == lastPolledPackage) {
                    pollingFlickerCount++
                } else {
                    pollingFlickerCount = 1
                }
                lastPolledPackage = currentAppByPolling

                if (pollingFlickerCount >= 2) {
                    val metadata = metadataHelper.fromPackage(currentAppByPolling)
                    startSession(metadata, System.currentTimeMillis() - 2000) // retroactive start
                    dbHelper.increaseDuration(activeLogId)
                    dbHelper.increaseDuration(activeLogId)
                    pollingFlickerCount = 0
                }
            } else if (!insertedServiceStartLog) {
                insertedServiceStartLog = true
                startSession(
                    metadataHelper.synthetic(packageName, "Time Tracker service started", "SERVICE_STARTED"),
                    System.currentTimeMillis()
                )
                dbHelper.increaseDuration(activeLogId)
            }
        }
    }

    private fun processUsageEvents() {
        val now = System.currentTimeMillis()
        // Query events from the last processed time until now
        val queryStart = if (lastEventQueryTime == 0L) now - 60_000L else lastEventQueryTime
        val events = usageStatsManager.queryEvents(queryStart, now)
        val event = UsageEvents.Event()
        var maxEventTime = queryStart

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp > maxEventTime) {
                maxEventTime = event.timeStamp
            }
            handleUsageEvent(event)
        }
        // Update last processed time to the latest event timestamp or current time
        lastEventQueryTime = if (maxEventTime > queryStart) maxEventTime else now
    }

    private fun handleUsageEvent(event: UsageEvents.Event) {
        val packageName = event.packageName ?: return
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                val metadata = metadataHelper.fromEvent(event)
                if (activeLogId != -1L) {
                    // Only switch sessions if the PACKAGE changed.
                    // This prevents splits when navigating between activities in the same app.
                    if (activePackageName == packageName) {
                        return
                    }
                    endActiveSession(event.timeStamp, "SESSION_SWITCHED")
                }
                startSession(metadata, event.timeStamp)
            }
            UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> {
                // End session when the phone is locked or screen turned off
                if (activeLogId != -1L) {
                    endActiveSession(event.timeStamp, AppMetadataHelper.eventTypeName(event.eventType))
                }
            }
            // We specifically IGNORE ACTIVITY_PAUSED here. 
            // Polling and SCREEN_NON_INTERACTIVE handle app exits more reliably 
            // without splitting sessions during internal app navigation.
        }
    }

    private fun startSession(metadata: RichLogMetadata, startTimestampMs: Long) {
        if (isSkipped(metadata)) {
            activeLogId = -1L
            activePackageName = null
            activeActivityClass = null
            return
        }
        val category = categoryManager.resolveCategory(metadata.packageName, metadata.appLabel)
        activeLogId = dbHelper.insertLog(metadata, startTimestampMs, category)
        activePackageName = metadata.packageName
        activeActivityClass = metadata.activityClass
        
        // Reset polling stability tracking on new session
        pollingFlickerCount = 0
        lastPolledPackage = metadata.packageName
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
