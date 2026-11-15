package ca.tariq_sekhri.time_tracker

/**
 * Ids of the logs the trackers are still writing.
 *
 * Other devices download each log once, by ascending id, so a log is uploaded only
 * after it has finished and after every older log has finished too. The ids live in
 * memory: a log left running by a killed process is released when the app restarts.
 */
object RunningLogs {
    private val ids = HashSet<Long>()

    /** Runs [insert] and marks the log it returns as running, as one step for [finished]. */
    fun begin(insert: () -> Long): Long = synchronized(ids) { insert().also { ids.add(it) } }

    fun finish(id: Long) {
        synchronized(ids) { ids.remove(id) }
    }

    /** Reads logs with [load] and returns those that are safe to upload. */
    fun finished(load: () -> List<LogEntry>): List<LogEntry> = synchronized(ids) { olderThanRunning(load(), ids) }

    /** The [logs] with an id below every running log among them. */
    internal fun olderThanRunning(logs: List<LogEntry>, running: Set<Long>): List<LogEntry> {
        val oldestRunning = logs.filter { it.id in running }.minOfOrNull { it.id } ?: return logs
        return logs.filter { it.id < oldestRunning }
    }
}
