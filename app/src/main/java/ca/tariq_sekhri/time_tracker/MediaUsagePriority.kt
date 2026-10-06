package ca.tariq_sekhri.time_tracker

data class UsageInterval(val startMs: Long, val endMs: Long)

/** Shared by the two live services; callbacks run on the main thread. */
object MediaUsagePriority {
    private val playingSince = mutableMapOf<String, Long>()
    private val listeners = mutableSetOf<(String, Boolean, Long) -> Unit>()

    @Synchronized fun activeStart(packageName: String): Long? = playingSince[packageName]

    fun setPlaying(packageName: String, playing: Boolean, timestampMs: Long) {
        val callbacks = synchronized(this) {
            if (playing == playingSince.containsKey(packageName)) return
            if (playing) playingSince[packageName] = timestampMs else playingSince.remove(packageName)
            listeners.toList()
        }
        callbacks.forEach { it(packageName, playing, timestampMs) }
    }

    @Synchronized fun addListener(listener: (String, Boolean, Long) -> Unit) { listeners.add(listener) }
    @Synchronized fun removeListener(listener: (String, Boolean, Long) -> Unit) { listeners.remove(listener) }

    /** Keep only foreground time outside the same package's media intervals. */
    fun uncovered(startMs: Long, endMs: Long, media: List<UsageInterval>): List<UsageInterval> {
        val result = mutableListOf<UsageInterval>()
        var cursor = startMs
        for (interval in media.sortedBy { it.startMs }) {
            val start = maxOf(startMs, interval.startMs)
            val end = minOf(endMs, interval.endMs)
            if (end <= cursor || start >= endMs) continue
            if (start > cursor) result.add(UsageInterval(cursor, start))
            cursor = maxOf(cursor, end)
        }
        if (cursor < endMs) result.add(UsageInterval(cursor, endMs))
        return result.filter { it.endMs - it.startMs >= 1_000L }
    }
}
