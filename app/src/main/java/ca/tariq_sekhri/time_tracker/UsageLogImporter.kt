package ca.tariq_sekhri.time_tracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class UsageImportResult(
    val importedCount: Int,
    val available: Boolean
)

class UsageLogImporter(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val dbHelper = DatabaseHelper(context)
    private val metadataHelper = AppMetadataHelper(context)
    private val categoryManager = CategoryManager(context)

    fun importNow(nowMs: Long = System.currentTimeMillis()): UsageImportResult =
        synchronized(importLock) {
            if (!hasUsageAccess(context)) {
                return@synchronized UsageImportResult(0, false)
            }

            val checkpoint = prefs.getLong(PREF_CHECKPOINT_MS, 0L)
            val initialStart = if (checkpoint > 0L) {
                checkpoint + 1L
            } else {
                maxOf(
                    dbHelper.latestLogBoundaryMs() ?: 0L,
                    nowMs - INITIAL_BACKFILL_MS
                )
            }
            if (initialStart >= nowMs) {
                return@synchronized UsageImportResult(0, true)
            }

            val usageEvents = usageStatsManager.queryEvents(initialStart, nowMs)
                ?: return@synchronized UsageImportResult(0, false)
            val nativeEvents = mutableListOf<NativeUsageEvent>()
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                mapEvent(event)?.let(nativeEvents::add)
            }

            val processed = UsageSessionizer.process(loadPending(), nativeEvents)
            val snapshot = UsageSessionizer.snapshot(processed, nowMs)
            var imported = 0
            snapshot.completed.forEach { session ->
                val metadata = metadataHelper.fromPackage(
                    packageName = session.pending.packageName,
                    activityClass = session.pending.activityClass,
                    eventType = session.pending.startEventType
                )
                if (!isSkipped(metadata)) {
                    val category = categoryManager.resolveCategory(
                        metadata.packageName,
                        metadata.appLabel
                    )
                    val id = dbHelper.insertImportedSession(
                        metadata,
                        session.pending.startTimestampMs,
                        session.endTimestampMs,
                        session.endEventType,
                        category
                    )
                    if (id >= 0L) imported++
                }
            }

            savePending(snapshot.pending)
            prefs.edit().putLong(PREF_CHECKPOINT_MS, nowMs).apply()
            UsageImportResult(imported, true)
        }

    private fun mapEvent(event: UsageEvents.Event): NativeUsageEvent? {
        val typeName = AppMetadataHelper.eventTypeName(event.eventType)
        return when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.MOVE_TO_FOREGROUND -> NativeUsageEvent(
                NativeUsageEventKind.FOREGROUND,
                event.timeStamp,
                event.packageName,
                event.className,
                typeName
            )

            UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.DEVICE_SHUTDOWN -> NativeUsageEvent(
                NativeUsageEventKind.END_FOREGROUND,
                event.timeStamp,
                eventTypeName = typeName
            )

            else -> null
        }
    }

    private fun isSkipped(metadata: RichLogMetadata): Boolean {
        val target = listOfNotNull(metadata.packageName, metadata.appLabel).joinToString(" ")
        return dbHelper.getSkippedApps().any { row ->
            val pattern = row.pattern.trim()
            pattern.isNotEmpty() && (
                target.contains(pattern, ignoreCase = true) ||
                    runCatching {
                        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(target)
                    }.getOrDefault(false)
                )
        }
    }

    private fun loadPending(): PendingUsageSession? {
        val packageName = prefs.getString(PREF_PENDING_PACKAGE, null) ?: return null
        val start = prefs.getLong(PREF_PENDING_START_MS, -1L)
        if (start < 0L) return null
        return PendingUsageSession(
            packageName = packageName,
            startTimestampMs = start,
            activityClass = prefs.getString(PREF_PENDING_ACTIVITY, null),
            startEventType = prefs.getString(PREF_PENDING_EVENT_TYPE, null)
                ?: "ACTIVITY_RESUMED"
        )
    }

    private fun savePending(pending: PendingUsageSession?) {
        val edit = prefs.edit()
        if (pending == null) {
            edit.remove(PREF_PENDING_PACKAGE)
                .remove(PREF_PENDING_START_MS)
                .remove(PREF_PENDING_ACTIVITY)
                .remove(PREF_PENDING_EVENT_TYPE)
        } else {
            edit.putString(PREF_PENDING_PACKAGE, pending.packageName)
                .putLong(PREF_PENDING_START_MS, pending.startTimestampMs)
                .putString(PREF_PENDING_ACTIVITY, pending.activityClass)
                .putString(PREF_PENDING_EVENT_TYPE, pending.startEventType)
        }
        edit.apply()
    }

    companion object {
        private const val PREFS_NAME = "AndroidUsageImporter"
        private const val PREF_CHECKPOINT_MS = "checkpoint_ms"
        private const val PREF_PENDING_PACKAGE = "pending_package"
        private const val PREF_PENDING_START_MS = "pending_start_ms"
        private const val PREF_PENDING_ACTIVITY = "pending_activity"
        private const val PREF_PENDING_EVENT_TYPE = "pending_event_type"
        private const val INITIAL_BACKFILL_MS = 3L * 24L * 60L * 60L * 1_000L
        private val importLock = Any()

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
    }
}
