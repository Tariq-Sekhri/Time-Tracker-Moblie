package ca.tariq_sekhri.time_tracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import org.json.JSONObject
import java.util.concurrent.Executors

/** The only bridge exposed to the bundled React UI. Credentials stay in Kotlin. */
@TauriPlugin
class TrackerPlugin(private val host: Activity) : Plugin(host) {
    private val context = host.applicationContext
    private val db by lazy { DatabaseHelper(context) }
    private val sync by lazy { SyncManager(context) }
    private val prefs by lazy { context.getSharedPreferences("TimeTrackerPrefs", Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val labels = mutableMapOf<String, String>()
    private val integerSettings = mapOf("day_start_hour" to 6, "min_app_duration" to 0,
        "min_log_duration" to 1, "max_attach_distance" to 400, "lookahead_window" to 500,
        "min_timeblock_duration" to 300)

    @Command
    fun request(invoke: Invoke) {
        val args = invoke.getArgs()
        val operation = args.optString("operation")
        val payload = args.optJSONObject("payload") ?: JSONObject()
        // SQLite/import and note commits must never block the UI thread.
        worker.execute {
            try {
                when (operation) {
                    "state" -> {
                        val remoteDevices = db.getRemoteDevices()
                        val visibleRemoteUuids = remoteDevices.filter { it.isTracking || it.hasLocalLogs }.map { it.uuid }
                        val firstLog = db.earliestLogTimestampMs(includeLocal = true, remoteDeviceUuids = visibleRemoteUuids)
                        val devices = sync.getLocalDevicesSnapshot().map(::managedDeviceMap)
                        host.runOnUiThread { safely(invoke) { state(firstLog, devices) } }
                    }
                    "logs" -> invoke.resolveObject(logs(payload))
                    "usage" -> invoke.resolveObject(usage(payload))
                    "import" -> { UsageLogImporter(context).importNow(); invoke.resolve() }
                    "permission" -> host.runOnUiThread {
                        safely(invoke) {
                            val action = when (payload.getString("kind")) {
                                "usage" -> Settings.ACTION_USAGE_ACCESS_SETTINGS
                                "browser" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                                "media" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                                else -> error("Unknown permission")
                            }
                            host.startActivity(Intent(action)); null
                        }
                    }
                    "settings.save" -> { saveSettings(payload); invoke.resolve() }
                    "notes.get" -> invoke.resolveObject(mapOf("enabled" to prefs.getBoolean("notes_enabled", true),
                        "text" to context.getSharedPreferences(NotesStore.PREFS, Context.MODE_PRIVATE).getString(NotesStore.CONTENT, "")))
                    "notes.save" -> invoke.resolveObject(NotesStore.save(context, payload.getString("text"),
                        if (payload.isNull("expectedText")) null else payload.getString("expectedText")))
                    "skipped.list" -> invoke.resolveObject(db.getSkippedApps())
                    "skipped.count" -> invoke.resolveObject(db.countMatchingLogs(payload.getString("pattern")))
                    "skipped.add" -> {
                        val pattern = payload.getString("pattern").trim()
                        require(pattern.isNotEmpty() && pattern.length <= 500) { "Enter a pattern up to 500 characters" }
                        invoke.resolveObject(db.addSkippedApp(pattern))
                    }
                    "skipped.delete" -> { db.deleteSkippedApp(payload.getLong("id")); invoke.resolve() }
                    "skipped.clear" -> { db.deleteAllSkippedApps(); invoke.resolve() }
                    "log.delete" -> {
                        val id = payload.getLong("id")
                        val isRemote = payload.optBoolean("is_remote", false)
                        val deviceUuid = if (payload.isNull("device_uuid")) null else payload.optString("device_uuid").takeIf { it.isNotBlank() }
                        if (isRemote && deviceUuid != null) db.deleteRemoteLog(deviceUuid, id)
                        else db.deleteLog(id)
                        invoke.resolve()
                    }
                    "sync.check" -> sync.checkServer(payload.getString("server")) { result ->
                        result.fold({ invoke.resolveObject(it) }, { invoke.reject(it.message) })
                    }
                    "sync.change" -> {
                        // Changing server resets only registration, never local logs/notes.
                        host.runOnUiThread { ManualTimerSync.disconnect() }
                        sync.unlockServer(); invoke.resolve()
                    }
                    "sync.register" -> sync.register(payload.optString("name", sync.defaultDeviceName()), completion(invoke))
                    "sync.approval" -> sync.checkActivation(completion(invoke))
                    "sync.now" -> sync.sync(completion(invoke))
                    "sync.reupload" -> sync.reuploadAllLogs(completion(invoke))
                    "sync.devices" -> invoke.resolveObject(sync.getDevicesBlocking(refreshFromServer = true).map(::managedDeviceMap))
                    "sync.subscribe" -> {
                        val uuid = payload.getString("uuid")
                        val name = if (payload.isNull("name")) null else payload.optString("name").takeIf { it.isNotBlank() }
                        sync.subscribeDeviceBlocking(uuid, name).fold(
                            { count -> invoke.resolveObject(mapOf("downloaded" to count)) },
                            { error -> invoke.reject(error.message ?: "Could not subscribe") }
                        )
                    }
                    "sync.unsubscribe" -> {
                        sync.unsubscribeDeviceBlocking(payload.getString("uuid"))
                        invoke.resolve()
                    }
                    "device.update" -> {
                        val uuid = payload.getString("uuid")
                        val inCal = if (payload.has("in_cal") && !payload.isNull("in_cal")) payload.getBoolean("in_cal") else null
                        val inStats = if (payload.has("in_stats") && !payload.isNull("in_stats")) payload.getBoolean("in_stats") else null
                        sync.updateDeviceVisibility(uuid, inCal, inStats)
                        invoke.resolve()
                    }
                    "manual.category.create" -> {
                        val name = ManualTimerStore.createCategory(context, payload.getString("name"),
                            if (payload.isNull("parent")) null else payload.getString("parent"))
                        ManualTimerSync.pushPending()
                        invoke.resolveObject(name)
                    }
                    "timer.save", "timer.stop" -> host.runOnUiThread {
                        val title = payload.optString("title").trim()
                        val project = if (payload.isNull("project")) null else payload.optString("project").takeIf { it.isNotBlank() }
                        if (operation == "timer.save") ManualTimerSync.save(title, project, { invoke.reject(it) }, { invoke.resolve() })
                        else if (ManualTimerSync.timer == null) invoke.reject("No running timer")
                        else ManualTimerSync.stop(title, project, { invoke.reject(it) }, { invoke.resolve() })
                    }
                    else -> invoke.reject("Unknown mobile operation: $operation")
                }
            } catch (error: Exception) { invoke.reject(error.message ?: "Operation failed") }
        }
    }

    private fun safely(invoke: Invoke, block: () -> Any?) {
        try { block()?.let { invoke.resolveObject(it) } ?: invoke.resolve() }
        catch (error: Exception) { invoke.reject(error.message ?: "Operation failed") }
    }

    private fun completion(invoke: Invoke): (Boolean, String) -> Unit = { ok, message ->
        if (ok) {
            host.runOnUiThread { ManualTimerSync.connect(context) }
            invoke.resolveObject(message)
        } else invoke.reject(message)
    }

    private fun managedDeviceMap(device: SyncManager.ManagedDevice): Map<String, Any?> = mapOf(
        "uuid" to device.uuid,
        "name" to device.name,
        "is_local" to device.isLocal,
        "is_tracking" to device.isTracking,
        "last_sync_id" to device.lastSyncId,
        "in_cal" to device.inCal,
        "in_stats" to device.inStats,
        "available_on_server" to device.availableOnServer,
        "has_local_logs" to device.hasLocalLogs
    )

    private fun state(firstLogMs: Long?, devices: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "permissions" to mapOf("usage" to UsageLogImporter.hasUsageAccess(context),
            "browser" to ((Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').any { it.substringBefore('/') == context.packageName }),
            "media" to NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)),
        "settings" to (integerSettings.mapValues { (key, default) -> prefs.getInt(key, default) } +
            mapOf("notes_enabled" to prefs.getBoolean("notes_enabled", true), "use_bar_usage_chart" to prefs.getBoolean("use_bar_usage_chart", false))),
        "sync" to mapOf("server" to sync.getServerIp(), "registered" to sync.isRegistered(), "active" to sync.isActive(),
            "uuid" to sync.getDeviceUuid(), "name" to sync.getLocalDeviceName(), "nextSyncSeconds" to sync.secondsUntilNextAutoPush()),
        "devices" to devices,
        "timer" to ManualTimerSync.timer, "timerStatus" to ManualTimerSync.status.name,
        "manualEntries" to ManualTimerStore.entries(context),
        "projects" to ManualTimerSync.projects,
        "version" to host.packageManager.getPackageInfo(context.packageName, 0).versionName,
        "firstLogMs" to firstLogMs)

    private fun saveSettings(payload: JSONObject) {
        val editor = prefs.edit()
        payload.keys().forEach { key ->
            when {
                integerSettings.containsKey(key) -> {
                    val value = payload.getInt(key)
                    require(value >= 0 && value <= if (key == "day_start_hour") 23 else 86400) { "Invalid value for $key" }
                    editor.putInt(key, value)
                }
                key == "notes_enabled" || key == "use_bar_usage_chart" -> editor.putBoolean(key, payload.getBoolean(key))
                else -> error("Unknown setting: $key")
            }
        }
        check(editor.commit()) { "Could not save settings" }
    }

    private fun range(payload: JSONObject): Pair<Long, Long> {
        val start = payload.getLong("startMs"); val end = payload.getLong("endMs")
        require(start >= 0 && end > start) { "Invalid time range" }
        return start to end
    }

    private fun appName(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun logs(payload: JSONObject): List<Map<String, Any?>> {
        val (start, end) = range(payload)
        val limit = payload.optInt("limit", 100).coerceIn(1, 500)
        val offset = payload.optInt("offset", 0).coerceAtLeast(0)
        val scope = payload.optString("scope", "logs")
        val requestedDeviceUuid = if (payload.isNull("deviceUuid")) null else payload.optString("deviceUuid").takeIf { it.isNotBlank() }
        val remoteDevices = db.getRemoteDevices()
        val remoteNameByUuid = remoteDevices.associate { it.uuid to it.name }
        val localUuid = sync.getDeviceUuid() ?: RemoteLogSync.LOCAL_DEVICE_KEY
        val localName = sync.getLocalDeviceName()
        val selection = RemoteLogSync.resolveDeviceFilter(
            scope = scope,
            requestedDeviceUuid = requestedDeviceUuid,
            localUuid = sync.getDeviceUuid(),
            localInCal = sync.isLocalInCal(),
            localInStats = sync.isLocalInStats(),
            remoteDevices = remoteDevices
        )
        if (!selection.includeLocal && selection.remoteUuids.isEmpty()) return emptyList()

        val branches = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (selection.includeLocal) {
            branches.add(
                """SELECT id, package_name, app_label, activity_class, start_timestamp, end_timestamp, duration, category,
                          last_time_used, total_time_in_foreground_ms, last_time_visible, total_time_visible_ms,
                          last_time_fgs_used, total_time_fgs_used_ms, is_system_app, version_name, version_code,
                          install_time, update_time, task_root_package, task_root_class, instance_id,
                          start_event_type, end_event_type,
                          NULL AS device_uuid, 0 AS is_remote
                   FROM logs
                   WHERE start_timestamp < CAST(? AS INTEGER)
                     AND COALESCE(end_timestamp, start_timestamp + duration * 1000) > CAST(? AS INTEGER)"""
            )
            args.add(end.toString())
            args.add(start.toString())
        }
        if (selection.remoteUuids.isNotEmpty()) {
            val placeholders = selection.remoteUuids.joinToString(",") { "?" }
            branches.add(
                """SELECT id, app AS package_name, app AS app_label, NULL AS activity_class, start_timestamp, end_timestamp, duration, NULL AS category,
                          NULL AS last_time_used, NULL AS total_time_in_foreground_ms, NULL AS last_time_visible, NULL AS total_time_visible_ms,
                          NULL AS last_time_fgs_used, NULL AS total_time_fgs_used_ms, NULL AS is_system_app, NULL AS version_name, NULL AS version_code,
                          NULL AS install_time, NULL AS update_time, NULL AS task_root_package, NULL AS task_root_class, NULL AS instance_id,
                          'REMOTE_SYNC' AS start_event_type, NULL AS end_event_type,
                          device_uuid, 1 AS is_remote
                   FROM remote_logs
                   WHERE device_uuid IN ($placeholders)
                     AND start_timestamp < CAST(? AS INTEGER)
                     AND end_timestamp > CAST(? AS INTEGER)"""
            )
            args.addAll(selection.remoteUuids)
            args.add(end.toString())
            args.add(start.toString())
        }
        args.add(limit.toString())
        args.add(offset.toString())

        val result = mutableListOf<Map<String, Any?>>()
        val sql = """SELECT * FROM (${branches.joinToString(" UNION ALL ")})
            ORDER BY start_timestamp DESC, is_remote ASC, device_uuid ASC, id DESC
            LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)"""
        db.readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                cursor.columnNames.forEachIndexed { index, name ->
                    row[name] = when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        else -> cursor.getString(index)
                    }
                }
                val isRemote = (row["is_remote"] as? Long ?: 0L) == 1L
                row["is_remote"] = isRemote
                if (isRemote) {
                    val uuid = row["device_uuid"]?.toString().orEmpty()
                    row["app_name"] = row["package_name"].toString()
                    row["device_name"] = remoteNameByUuid[uuid] ?: uuid
                } else {
                    row["app_name"] = appName(row["package_name"].toString())
                    row["device_uuid"] = localUuid
                    row["device_name"] = localName
                }
                result.add(row)
            }
        }
        return result
    }

    private fun usage(payload: JSONObject): Map<String, Any?> {
        val (start, end) = range(payload)
        val browserOnly = payload.optBoolean("browserOnly", false)
        val scope = payload.optString("scope", "stats")
        val requestedDeviceUuid = if (payload.isNull("deviceUuid")) null else payload.optString("deviceUuid").takeIf { it.isNotBlank() }
        val selection = RemoteLogSync.resolveDeviceFilter(
            scope = scope,
            requestedDeviceUuid = requestedDeviceUuid,
            localUuid = sync.getDeviceUuid(),
            localInCal = sync.isLocalInCal(),
            localInStats = sync.isLocalInStats(),
            remoteDevices = db.getRemoteDevices()
        )
        val apps = linkedMapOf<String, MutableMap<String, Any>>()
        val days = linkedMapOf<String, Long>()
        var total = 0L

        fun accumulateUsage(pkg: String, label: String, rawStart: Long, rawEnd: Long, rawDuration: Long, isRemote: Boolean) {
            val from = maxOf(start, rawStart)
            val to = minOf(end, rawEnd)
            val seconds = minOf(rawDuration, maxOf(0L, (to - from) / 1000L))
            if (seconds <= 0L) return
            val row = apps.getOrPut(label) { mutableMapOf("packageName" to pkg, "label" to label, "seconds" to 0L) }
            if (!isRemote) row["packageName"] = pkg
            row["seconds"] = (row["seconds"] as Long) + seconds
            total += seconds
            // Split across local calendar days, respecting the configured start hour and DST.
            var current = from
            var remaining = seconds
            while (current < to && remaining > 0L) {
                val day = java.util.Calendar.getInstance().apply {
                    timeInMillis = current; set(java.util.Calendar.HOUR_OF_DAY, prefs.getInt("day_start_hour", 6))
                    set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis > current) add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                val keyDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(day.time)
                day.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val boundary = minOf(to, day.timeInMillis)
                val part = minOf(remaining, maxOf(0L, (boundary - current) / 1000L))
                days[keyDay] = (days[keyDay] ?: 0L) + part
                remaining -= part; current = boundary
            }
        }

        if (selection.includeLocal) {
            // Iterate the range without sending the full log history across the bridge.
            db.readableDatabase.rawQuery("""SELECT package_name, app_label, start_timestamp,
                COALESCE(end_timestamp, start_timestamp + duration * 1000), duration FROM logs
                WHERE start_timestamp < CAST(? AS INTEGER) AND COALESCE(end_timestamp, start_timestamp + duration * 1000) > CAST(? AS INTEGER)""",
                arrayOf(end.toString(), start.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val pkg = cursor.getString(0)
                    if (browserOnly && !BrowserTitleCache.isBrowserPackage(pkg)) continue
                    val label = if (browserOnly) cursor.getString(1) ?: appName(pkg) else appName(pkg)
                    accumulateUsage(pkg, label, cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), isRemote = false)
                }
            }
        }
        if (selection.remoteUuids.isNotEmpty()) {
            val placeholders = selection.remoteUuids.joinToString(",") { "?" }
            val args = selection.remoteUuids.toTypedArray() + arrayOf(end.toString(), start.toString())
            db.readableDatabase.rawQuery("""SELECT app, start_timestamp, end_timestamp, duration FROM remote_logs
                WHERE device_uuid IN ($placeholders)
                  AND start_timestamp < CAST(? AS INTEGER) AND end_timestamp > CAST(? AS INTEGER)""",
                args).use { cursor ->
                while (cursor.moveToNext()) {
                    val app = cursor.getString(0)
                    if (browserOnly && !RemoteLogSync.isBrowserAppName(app)) continue
                    accumulateUsage(app, app, cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), isRemote = true)
                }
            }
        }
        return mapOf("total" to total, "apps" to apps.values.sortedByDescending { it["seconds"] as Long },
            "days" to days.toSortedMap().map { mapOf("date" to it.key, "seconds" to it.value) })
    }
}
