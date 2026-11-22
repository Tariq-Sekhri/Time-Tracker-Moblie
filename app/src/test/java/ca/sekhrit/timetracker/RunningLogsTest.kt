package ca.sekhrit.timetracker

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningLogsTest {
    private fun log(id: Long) = LogEntry(
        id, "pkg", "App", null, id * 1_000, null, 0, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null
    )

    private fun uploadable(ids: List<Long>, running: Set<Long>) =
        RunningLogs.olderThanRunning(ids.map(::log), running).map { it.id }

    @Test fun everythingIsUploadedWhenNothingIsRunning() {
        assertEquals(listOf(4L, 5L, 6L), uploadable(listOf(4, 5, 6), emptySet()))
    }

    @Test fun runningLogAndNewerLogsWait() {
        assertEquals(listOf(4L), uploadable(listOf(4, 5, 6), setOf(5)))
    }

    @Test fun oldestRunningLogDecides() {
        assertEquals(listOf(4L), uploadable(listOf(6, 5, 4, 7), setOf(7, 5)))
    }

    @Test fun runningLogsOutsideTheListAreIgnored() {
        assertEquals(listOf(4L, 5L), uploadable(listOf(4, 5), setOf(2, 9)))
    }

    @Test fun logsWaitUntilTheRunningLogFinishes() {
        val first = RunningLogs.begin { 101L }
        val second = RunningLogs.begin { 102L }
        val logs = listOf(log(100), log(101), log(102), log(103))
        assertEquals(listOf(100L), RunningLogs.finished { logs }.map { it.id })
        RunningLogs.finish(first)
        assertEquals(listOf(100L, 101L), RunningLogs.finished { logs }.map { it.id })
        RunningLogs.finish(second)
        assertEquals(listOf(100L, 101L, 102L, 103L), RunningLogs.finished { logs }.map { it.id })
    }
}
