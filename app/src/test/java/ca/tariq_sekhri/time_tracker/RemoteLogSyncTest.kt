package ca.tariq_sekhri.time_tracker

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.StringReader

class RemoteLogSyncTest {
    private val gson = Gson()

    private fun remote(
        uuid: String,
        name: String = uuid,
        isTracking: Boolean = true,
        inCal: Boolean = true,
        inStats: Boolean = true,
        hasLocalLogs: Boolean = false
    ) = RemoteDeviceRow(
        uuid = uuid,
        name = name,
        isTracking = isTracking,
        lastSyncId = 0L,
        inCal = inCal,
        inStats = inStats,
        hasLocalLogs = hasLocalLogs
    )

    @Test
    fun resolvesDeviceFiltersByScopeAndExplicitSelection() {
        val remotes = listOf(
            remote("desk", isTracking = true, inCal = true, inStats = false),
            remote("laptop", isTracking = false, inCal = true, inStats = true, hasLocalLogs = true),
            remote("hidden", isTracking = false, inCal = true, inStats = true, hasLocalLogs = false)
        )

        val statsAll = resolve("stats", null, localUuid = "phone", localInCal = true, localInStats = false, remotes = remotes)
        assertEquals(RemoteLogSync.DeviceFilterSelection(false, listOf("laptop")), statsAll)

        val calAll = resolve("calendar", null, localUuid = "phone", localInCal = true, localInStats = false, remotes = remotes)
        assertEquals(RemoteLogSync.DeviceFilterSelection(true, listOf("desk", "laptop")), calAll)

        val logsAll = resolve("logs", null, localUuid = "phone", localInCal = false, localInStats = false, remotes = remotes)
        assertEquals(RemoteLogSync.DeviceFilterSelection(true, listOf("desk", "laptop")), logsAll)

        val isolatedLocal = resolve("stats", "phone", localUuid = "phone", localInCal = false, localInStats = false, remotes = remotes)
        assertEquals(RemoteLogSync.DeviceFilterSelection(true, emptyList()), isolatedLocal)

        val isolatedRemote = resolve("stats", "desk", localUuid = "phone", localInCal = true, localInStats = true, remotes = remotes)
        assertEquals(RemoteLogSync.DeviceFilterSelection(false, listOf("desk")), isolatedRemote)

        val unknownRemote = resolve("stats", "missing", localUuid = "phone", localInCal = true, localInStats = true, remotes = remotes)
        assertEquals(RemoteLogSync.DeviceFilterSelection(false, emptyList()), unknownRemote)
    }

    private fun resolve(
        scope: String,
        requested: String?,
        localUuid: String?,
        localInCal: Boolean,
        localInStats: Boolean,
        remotes: List<RemoteDeviceRow>
    ) = RemoteLogSync.resolveDeviceFilter(scope, requested, localUuid, localInCal, localInStats, remotes)

    @Test
    fun skipsLegacyFetchOnlyWhenServerCursorCaughtUp() {
        assertEquals(false, RemoteLogSync.shouldSkipLegacyDeviceFetch(0L, 0L))
        assertEquals(false, RemoteLogSync.shouldSkipLegacyDeviceFetch(10L, null))
        assertEquals(false, RemoteLogSync.shouldSkipLegacyDeviceFetch(10L, 11L))
        assertEquals(true, RemoteLogSync.shouldSkipLegacyDeviceFetch(10L, 10L))
        assertEquals(true, RemoteLogSync.shouldSkipLegacyDeviceFetch(10L, 8L))
    }

    @Test
    fun convertsBackendLogsAndRejectsStaleOrMalformedEntries() {
        val allowed = setOf("desk")
        val cursors = mapOf("desk" to 5L)

        assertNull(RemoteLogSync.toRemoteLogRow(SyncManager.BackendLog(5L, "desk", "Code", 100L, 30L), allowed, cursors))
        assertNull(RemoteLogSync.toRemoteLogRow(SyncManager.BackendLog(6L, "other", "Code", 100L, 30L), allowed, cursors))
        assertNull(RemoteLogSync.toRemoteLogRow(SyncManager.BackendLog(6L, "desk", "   ", 100L, 30L), allowed, cursors))
        assertNull(RemoteLogSync.toRemoteLogRow(SyncManager.BackendLog(6L, "desk", "Code", -1L, 30L), allowed, cursors))

        val valid = RemoteLogSync.toRemoteLogRow(SyncManager.BackendLog(7L, "desk", "Code", 100L, 45L), allowed, cursors)
        assertEquals(RemoteLogRow("desk", 7L, "Code", 100_000L, 145_000L, 45L), valid)
    }

    @Test
    fun streamsBatchesTracksMaxIdsAndFailsOnTruncatedJson() {
        val json = """
            [
              {"id":1,"device_uuid":"desk","app":"Old","timestamp":10,"duration":5},
              {"id":2,"device_uuid":"desk","app":"Code","timestamp":20,"duration":10},
              {"id":3,"device_uuid":"desk","app":"Browser","timestamp":30,"duration":15},
              {"id":4,"device_uuid":"laptop","app":"Terminal","timestamp":40,"duration":20}
            ]
        """.trimIndent()
        val batches = mutableListOf<List<RemoteLogRow>>()
        val (count, maxIds) = RemoteLogSync.parseBackendLogStream(
            JsonReader(StringReader(json)),
            gson,
            setOf("desk", "laptop"),
            mapOf("desk" to 1L, "laptop" to 0L),
            batchSize = 2
        ) { batches.add(it) }

        assertEquals(3, count)
        assertEquals(listOf(2, 1), batches.map { it.size })
        assertEquals(mapOf("desk" to 3L, "laptop" to 4L), maxIds)

        var threw = false
        try {
            RemoteLogSync.parseBackendLogStream(
                JsonReader(StringReader("""[{"id":9,"device_uuid":"desk","app":"Code","timestamp":50,"duration":10}""")),
                gson,
                setOf("desk"),
                emptyMap()
            ) {}
        } catch (_: EOFException) {
            threw = true
        }
        assertTrue("Truncated JSON stream should throw before cursor advances", threw)
    }

    @Test
    fun formatsSyncSummaryAndDetectsBrowserTitles() {
        assertEquals("Notes synced", RemoteLogSync.formatSyncSummary(0, 0, 0))
        assertEquals("Pushed 1 log, downloaded 2 logs, notes synced", RemoteLogSync.formatSyncSummary(1, 0, 2))
        assertEquals("Downloaded 5 logs, notes synced", RemoteLogSync.formatSyncSummary(0, 0, 5))
        assertTrue(RemoteLogSync.isBrowserAppName("YouTube - Google Chrome"))
        assertTrue(RemoteLogSync.isBrowserAppName("Safari"))
        assertEquals(false, RemoteLogSync.isBrowserAppName("Visual Studio Code"))
    }
}
