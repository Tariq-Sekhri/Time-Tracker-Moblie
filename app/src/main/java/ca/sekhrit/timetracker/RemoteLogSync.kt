package ca.sekhrit.timetracker

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException

/** Pure-Kotlin helpers for filtering, parsing, and summarizing downloaded device logs. */
object RemoteLogSync {
    const val LOCAL_DEVICE_KEY = "__local__"

    data class DeviceFilterSelection(val includeLocal: Boolean, val remoteUuids: List<String>)

    fun resolveDeviceFilter(
        scope: String,
        requestedDeviceUuid: String?,
        localUuid: String?,
        localInCal: Boolean,
        localInStats: Boolean,
        remoteDevices: List<RemoteDeviceRow>
    ): DeviceFilterSelection {
        if (!requestedDeviceUuid.isNullOrBlank()) {
            if (requestedDeviceUuid == LOCAL_DEVICE_KEY || (localUuid != null && requestedDeviceUuid == localUuid)) {
                return DeviceFilterSelection(includeLocal = true, remoteUuids = emptyList())
            }
            val known = remoteDevices.any { it.uuid == requestedDeviceUuid && (it.isTracking || it.hasLocalLogs) }
            return DeviceFilterSelection(
                includeLocal = false,
                remoteUuids = if (known) listOf(requestedDeviceUuid) else emptyList()
            )
        }
        return when (scope) {
            "stats" -> DeviceFilterSelection(
                includeLocal = localInStats,
                remoteUuids = remoteDevices.filter { (it.isTracking || it.hasLocalLogs) && it.inStats }.map { it.uuid }
            )
            "calendar" -> DeviceFilterSelection(
                includeLocal = localInCal,
                remoteUuids = remoteDevices.filter { (it.isTracking || it.hasLocalLogs) && it.inCal }.map { it.uuid }
            )
            else -> DeviceFilterSelection(
                includeLocal = true,
                remoteUuids = remoteDevices.filter { it.isTracking || it.hasLocalLogs }.map { it.uuid }
            )
        }
    }

    fun shouldSkipLegacyDeviceFetch(localLastSyncId: Long, serverLastSyncId: Long?): Boolean =
        localLastSyncId > 0L && serverLastSyncId != null && serverLastSyncId <= localLastSyncId

    fun toRemoteLogRow(
        log: SyncManager.BackendLog,
        allowedDeviceUuids: Set<String>,
        minExclusiveIdByDevice: Map<String, Long>
    ): RemoteLogRow? {
        val uuid = log.device_uuid
        val app = log.app
        if (uuid.isNullOrBlank() || uuid !in allowedDeviceUuids) return null
        if (app.isNullOrBlank() || log.timestamp < 0L) return null
        val minExclusive = minExclusiveIdByDevice[uuid]
        if (minExclusive != null && log.id <= minExclusive) return null
        val startMs = log.timestamp * 1_000L
        val durationSec = maxOf(0L, log.duration)
        val endMs = startMs + durationSec * 1_000L
        return RemoteLogRow(
            deviceUuid = uuid,
            id = log.id,
            app = app,
            startTimestamp = startMs,
            endTimestamp = endMs,
            duration = durationSec
        )
    }

    fun parseBackendLogStream(
        reader: JsonReader,
        gson: Gson,
        allowedDeviceUuids: Set<String>,
        minExclusiveIdByDevice: Map<String, Long>,
        batchSize: Int = 1000,
        onBatch: (List<RemoteLogRow>) -> Unit
    ): Pair<Int, Map<String, Long>> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw IOException("Expected JSON array of logs")
        }
        reader.beginArray()
        val batch = ArrayList<RemoteLogRow>(batchSize)
        val maxIdByDevice = HashMap<String, Long>()
        var count = 0
        while (reader.hasNext()) {
            val item = gson.fromJson<SyncManager.BackendLog>(reader, SyncManager.BackendLog::class.java)
                ?: continue
            val row = toRemoteLogRow(item, allowedDeviceUuids, minExclusiveIdByDevice) ?: continue
            batch.add(row)
            count++
            val previous = maxIdByDevice[row.deviceUuid]
            if (previous == null || row.id > previous) {
                maxIdByDevice[row.deviceUuid] = row.id
            }
            if (batch.size >= batchSize) {
                onBatch(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            onBatch(batch.toList())
            batch.clear()
        }
        reader.endArray()
        return count to maxIdByDevice
    }

    fun formatSyncSummary(pushedLogs: Int, deletedLogs: Int, downloadedLogs: Int): String {
        val parts = mutableListOf<String>()
        if (pushedLogs > 0) parts.add("pushed $pushedLogs ${if (pushedLogs == 1) "log" else "logs"}")
        if (deletedLogs > 0) parts.add("deleted $deletedLogs on server")
        if (downloadedLogs > 0) parts.add("downloaded $downloadedLogs ${if (downloadedLogs == 1) "log" else "logs"}")
        if (parts.isEmpty()) return "Notes synced"
        return (parts + "notes synced").joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    fun isBrowserAppName(name: String): Boolean =
        BrowserTitleCache.isBrowserPackage(name) || name.lowercase().contains("safari")
}
