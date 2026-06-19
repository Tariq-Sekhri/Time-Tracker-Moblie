package ca.sekhrit.timetrackermoblie

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

class AppMetadataHelper(private val context: Context) {

    private val packageManager = context.packageManager
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun fromEvent(event: UsageEvents.Event): RichLogMetadata {
        val packageName = event.packageName ?: return RichLogMetadata(packageName = "")
        val activityClass = event.className?.takeIf { it.isNotBlank() }
        return buildMetadata(
            packageName = packageName,
            activityClass = activityClass,
            eventType = eventTypeName(event.eventType)
        )
    }

    fun fromPackage(packageName: String): RichLogMetadata {
        return buildMetadata(packageName = packageName, eventType = "POLL_DETECTED")
    }

    fun synthetic(packageName: String, appLabel: String, eventType: String): RichLogMetadata {
        return RichLogMetadata(
            packageName = packageName,
            appLabel = appLabel,
            startEventType = eventType
        )
    }

    private fun buildMetadata(
        packageName: String,
        activityClass: String? = null,
        taskRootPackage: String? = null,
        taskRootClass: String? = null,
        instanceId: Int? = null,
        eventType: String? = null
    ): RichLogMetadata {
        val appInfo = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull()
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
        val usageStats = queryUsageStats(packageName)
        val versionCode = if (packageInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } else null

        return RichLogMetadata(
            packageName = packageName,
            appLabel = appInfo?.let { packageManager.getApplicationLabel(it).toString() },
            activityClass = activityClass,
            lastTimeUsed = usageStats?.lastTimeUsed,
            totalTimeInForegroundMs = usageStats?.totalTimeInForeground,
            lastTimeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                usageStats?.lastTimeVisible
            } else null,
            totalTimeVisibleMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                usageStats?.totalTimeVisible
            } else null,
            lastTimeForegroundServiceUsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                usageStats?.lastTimeForegroundServiceUsed
            } else null,
            totalTimeForegroundServiceUsedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                usageStats?.totalTimeForegroundServiceUsed
            } else null,
            isSystemApp = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 },
            versionName = packageInfo?.versionName,
            versionCode = versionCode,
            installTime = packageInfo?.firstInstallTime,
            updateTime = packageInfo?.lastUpdateTime,
            taskRootPackage = taskRootPackage,
            taskRootClass = taskRootClass,
            instanceId = instanceId,
            startEventType = eventType
        )
    }

    private fun queryUsageStats(packageName: String): UsageStats? {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 86_400_000L,
            now
        ) ?: return null
        return stats.firstOrNull { it.packageName == packageName }
    }

    companion object {
        fun eventTypeName(eventType: Int): String = when (eventType) {
            UsageEvents.Event.NONE -> "NONE"
            UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
            UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
            UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
            UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
            UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
            UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
            UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
            UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
            UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
            UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
            UsageEvents.Event.MOVE_TO_BACKGROUND -> "MOVE_TO_BACKGROUND"
            UsageEvents.Event.MOVE_TO_FOREGROUND -> "MOVE_TO_FOREGROUND"
            UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
            UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
            UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
            UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
            else -> "EVENT_$eventType"
        }

        fun isForegroundEvent(eventType: Int): Boolean {
            return eventType == UsageEvents.Event.ACTIVITY_RESUMED
        }

        fun isBackgroundEvent(eventType: Int): Boolean {
            return eventType == UsageEvents.Event.ACTIVITY_PAUSED
        }
    }
}

data class RichLogMetadata(
    val packageName: String,
    val appLabel: String? = null,
    val activityClass: String? = null,
    val lastTimeUsed: Long? = null,
    val totalTimeInForegroundMs: Long? = null,
    val lastTimeVisible: Long? = null,
    val totalTimeVisibleMs: Long? = null,
    val lastTimeForegroundServiceUsed: Long? = null,
    val totalTimeForegroundServiceUsedMs: Long? = null,
    val isSystemApp: Boolean? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val taskRootPackage: String? = null,
    val taskRootClass: String? = null,
    val instanceId: Int? = null,
    val startEventType: String? = null
)
