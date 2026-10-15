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
                    "state" -> host.runOnUiThread { safely(invoke) { state() } }
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
                    "log.delete" -> { db.deleteLog(payload.getLong("id")); invoke.resolve() }
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
                    "sync.devices" -> sync.fetchActiveDevices { result ->
                        result.fold({ invoke.resolveObject(it) }, { invoke.reject(it.message) })
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

    private fun state(): Map<String, Any?> = mapOf(
        "permissions" to mapOf("usage" to UsageLogImporter.hasUsageAccess(context),
            "browser" to ((Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').any { it.substringBefore('/') == context.packageName }),
            "media" to NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)),
        "settings" to (integerSettings.mapValues { (key, default) -> prefs.getInt(key, default) } +
            mapOf("notes_enabled" to prefs.getBoolean("notes_enabled", true), "use_bar_usage_chart" to prefs.getBoolean("use_bar_usage_chart", false))),
        "sync" to mapOf("server" to sync.getServerIp(), "registered" to sync.isRegistered(), "active" to sync.isActive(),
            "uuid" to sync.getDeviceUuid(), "name" to sync.defaultDeviceName(), "nextSyncSeconds" to sync.secondsUntilNextAutoPush()),
        "timer" to ManualTimerSync.timer, "timerStatus" to ManualTimerSync.status.name,
        "projects" to ManualTimerSync.projects,
        "version" to host.packageManager.getPackageInfo(context.packageName, 0).versionName,
        "firstLogMs" to db.readableDatabase.rawQuery("SELECT MIN(start_timestamp) FROM logs", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        })

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
        val result = mutableListOf<Map<String, Any?>>()
        db.readableDatabase.rawQuery("""SELECT * FROM logs WHERE start_timestamp < ?
            AND COALESCE(end_timestamp, start_timestamp + duration * 1000) > ?
            ORDER BY start_timestamp DESC, id DESC LIMIT ? OFFSET ?""", arrayOf(end.toString(), start.toString(), limit.toString(), offset.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                cursor.columnNames.forEachIndexed { index, name ->
                    row[name] = when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        else -> cursor.getString(index)
                    }
                }
                row["app_name"] = appName(row["package_name"].toString()); result.add(row)
            }
        }
        return result
    }

    private fun usage(payload: JSONObject): Map<String, Any?> {
        val (start, end) = range(payload)
        val browserOnly = payload.optBoolean("browserOnly", false)
        val apps = linkedMapOf<String, MutableMap<String, Any>>()
        val days = linkedMapOf<String, Long>()
        var total = 0L
        // Iterate the range without sending the full log history across the bridge.
        db.readableDatabase.rawQuery("""SELECT package_name, app_label, start_timestamp,
            COALESCE(end_timestamp, start_timestamp + duration * 1000), duration FROM logs
            WHERE start_timestamp < ? AND COALESCE(end_timestamp, start_timestamp + duration * 1000) > ?""",
            arrayOf(end.toString(), start.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val pkg = cursor.getString(0)
                if (browserOnly && !BrowserTitleCache.isBrowserPackage(pkg)) continue
                val from = maxOf(start, cursor.getLong(2)); val to = minOf(end, cursor.getLong(3))
                val seconds = minOf(cursor.getLong(4), maxOf(0, (to - from) / 1000))
                if (seconds <= 0) continue
                val label = if (browserOnly) cursor.getString(1) ?: appName(pkg) else appName(pkg)
                val key = "$pkg\n$label"
                val row = apps.getOrPut(key) { mutableMapOf("packageName" to pkg, "label" to label, "seconds" to 0L) }
                row["seconds"] = (row["seconds"] as Long) + seconds
                total += seconds
                // Split across local calendar days, respecting the configured start hour and DST.
                var current = from
                var remaining = seconds
                while (current < to && remaining > 0) {
                    val day = java.util.Calendar.getInstance().apply {
                        timeInMillis = current; set(java.util.Calendar.HOUR_OF_DAY, prefs.getInt("day_start_hour", 6))
                        set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                        if (timeInMillis > current) add(java.util.Calendar.DAY_OF_YEAR, -1)
                    }
                    val keyDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(day.time)
                    day.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    val boundary = minOf(to, day.timeInMillis)
                    val part = minOf(remaining, maxOf(0, (boundary-current)/1000))
                    days[keyDay] = (days[keyDay] ?: 0L) + part
                    remaining -= part; current = boundary
                }
            }
        }
        return mapOf("total" to total, "apps" to apps.values.sortedByDescending { it["seconds"] as Long },
            "days" to days.toSortedMap().map { mapOf("date" to it.key, "seconds" to it.value) })
    }
}
