package ca.tariq_sekhri.time_tracker

data class PendingUsageSession(
    val packageName: String,
    val startTimestampMs: Long,
    val activityClass: String? = null,
    val startEventType: String = "ACTIVITY_RESUMED"
)

data class CompletedUsageSession(
    val pending: PendingUsageSession,
    val endTimestampMs: Long,
    val endEventType: String
)

enum class NativeUsageEventKind {
    FOREGROUND,
    END_FOREGROUND
}

data class NativeUsageEvent(
    val kind: NativeUsageEventKind,
    val timestampMs: Long,
    val packageName: String? = null,
    val activityClass: String? = null,
    val eventTypeName: String
)

data class SessionizationResult(
    val completed: List<CompletedUsageSession>,
    val pending: PendingUsageSession?
)

object UsageSessionizer {
    fun process(
        initialPending: PendingUsageSession?,
        events: List<NativeUsageEvent>
    ): SessionizationResult {
        var pending = initialPending
        val completed = mutableListOf<CompletedUsageSession>()

        events.sortedBy { it.timestampMs }.forEach { event ->
            when (event.kind) {
                NativeUsageEventKind.FOREGROUND -> {
                    val packageName = event.packageName?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val current = pending
                    if (current == null) {
                        pending = PendingUsageSession(
                            packageName,
                            event.timestampMs,
                            event.activityClass,
                            event.eventTypeName
                        )
                    } else if (current.packageName != packageName) {
                        completeIfPositive(completed, current, event.timestampMs, "APP_SWITCH")
                        pending = PendingUsageSession(
                            packageName,
                            event.timestampMs,
                            event.activityClass,
                            event.eventTypeName
                        )
                    }
                }

                NativeUsageEventKind.END_FOREGROUND -> {
                    pending?.let {
                        completeIfPositive(completed, it, event.timestampMs, event.eventTypeName)
                    }
                    pending = null
                }
            }
        }

        return SessionizationResult(completed, pending)
    }

    fun snapshot(
        result: SessionizationResult,
        snapshotTimestampMs: Long
    ): SessionizationResult {
        val pending = result.pending ?: return result
        val completed = result.completed.toMutableList()
        completeIfPositive(completed, pending, snapshotTimestampMs, "IMPORT_SNAPSHOT")
        return SessionizationResult(
            completed = completed,
            pending = if (snapshotTimestampMs > pending.startTimestampMs) {
                pending.copy(startTimestampMs = snapshotTimestampMs, startEventType = "IMPORT_CONTINUATION")
            } else {
                pending
            }
        )
    }

    private fun completeIfPositive(
        target: MutableList<CompletedUsageSession>,
        pending: PendingUsageSession,
        endTimestampMs: Long,
        endEventType: String
    ) {
        if (endTimestampMs - pending.startTimestampMs >= 1_000L) {
            target += CompletedUsageSession(pending, endTimestampMs, endEventType)
        }
    }
}
