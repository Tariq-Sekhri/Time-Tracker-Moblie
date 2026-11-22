package ca.sekhrit.timetracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "time_tracker.db"
        private const val DATABASE_VERSION = 12

        const val TABLE_LOGS = "logs"
        const val TABLE_SKIPPED_APPS = "skipped_apps"
        const val TABLE_CATEGORIES = "categories"
        const val TABLE_REGEX = "category_regex"
        const val TABLE_DELETED_LOGS = "deleted_logs"
        const val TABLE_REMOTE_DEVICES = "remote_devices"
        const val TABLE_REMOTE_LOGS = "remote_logs"

        const val COLUMN_ID = "id"
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_APP_LABEL = "app_label"
        const val COLUMN_ACTIVITY_CLASS = "activity_class"
        const val COLUMN_START_TIMESTAMP = "start_timestamp"
        const val COLUMN_END_TIMESTAMP = "end_timestamp"
        const val COLUMN_DURATION = "duration"
        const val COLUMN_CATEGORY = "category"
        const val COLUMN_LAST_TIME_USED = "last_time_used"
        const val COLUMN_TOTAL_TIME_IN_FOREGROUND_MS = "total_time_in_foreground_ms"
        const val COLUMN_LAST_TIME_VISIBLE = "last_time_visible"
        const val COLUMN_TOTAL_TIME_VISIBLE_MS = "total_time_visible_ms"
        const val COLUMN_LAST_TIME_FGS_USED = "last_time_fgs_used"
        const val COLUMN_TOTAL_TIME_FGS_USED_MS = "total_time_fgs_used_ms"
        const val COLUMN_IS_SYSTEM_APP = "is_system_app"
        const val COLUMN_VERSION_NAME = "version_name"
        const val COLUMN_VERSION_CODE = "version_code"
        const val COLUMN_INSTALL_TIME = "install_time"
        const val COLUMN_UPDATE_TIME = "update_time"
        const val COLUMN_TASK_ROOT_PACKAGE = "task_root_package"
        const val COLUMN_TASK_ROOT_CLASS = "task_root_class"
        const val COLUMN_INSTANCE_ID = "instance_id"
        const val COLUMN_START_EVENT_TYPE = "start_event_type"
        const val COLUMN_END_EVENT_TYPE = "end_event_type"
        
        const val COLUMN_PATTERN = "pattern"
        
        const val COLUMN_CAT_NAME = "name"
        const val COLUMN_CAT_COLOR = "color"
        const val COLUMN_CAT_PRIORITY = "priority"
        
        const val COLUMN_REG_CAT_ID = "category_id"
        const val COLUMN_REG_PATTERN = "pattern"
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL("""
            CREATE TABLE $TABLE_LOGS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PACKAGE_NAME TEXT NOT NULL,
                $COLUMN_APP_LABEL TEXT,
                $COLUMN_ACTIVITY_CLASS TEXT,
                $COLUMN_START_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_END_TIMESTAMP INTEGER,
                $COLUMN_DURATION INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CATEGORY TEXT,
                $COLUMN_LAST_TIME_USED INTEGER,
                $COLUMN_TOTAL_TIME_IN_FOREGROUND_MS INTEGER,
                $COLUMN_LAST_TIME_VISIBLE INTEGER,
                $COLUMN_TOTAL_TIME_VISIBLE_MS INTEGER,
                $COLUMN_LAST_TIME_FGS_USED INTEGER,
                $COLUMN_TOTAL_TIME_FGS_USED_MS INTEGER,
                $COLUMN_IS_SYSTEM_APP INTEGER,
                $COLUMN_VERSION_NAME TEXT,
                $COLUMN_VERSION_CODE INTEGER,
                $COLUMN_INSTALL_TIME INTEGER,
                $COLUMN_UPDATE_TIME INTEGER,
                $COLUMN_TASK_ROOT_PACKAGE TEXT,
                $COLUMN_TASK_ROOT_CLASS TEXT,
                $COLUMN_INSTANCE_ID INTEGER,
                $COLUMN_START_EVENT_TYPE TEXT,
                $COLUMN_END_EVENT_TYPE TEXT
            )
        """.trimIndent())

        db?.execSQL("""
            CREATE TABLE $TABLE_SKIPPED_APPS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PATTERN TEXT NOT NULL UNIQUE
            )
        """.trimIndent())

        db?.execSQL("""
            CREATE TABLE $TABLE_CATEGORIES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CAT_NAME TEXT NOT NULL,
                $COLUMN_CAT_COLOR TEXT,
                $COLUMN_CAT_PRIORITY INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db?.execSQL("""
            CREATE TABLE $TABLE_REGEX (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_REG_CAT_ID INTEGER NOT NULL,
                $COLUMN_REG_PATTERN TEXT NOT NULL,
                FOREIGN KEY($COLUMN_REG_CAT_ID) REFERENCES $TABLE_CATEGORIES($COLUMN_ID)
            )
        """.trimIndent())

        db?.execSQL("""
            CREATE TABLE $TABLE_DELETED_LOGS (
                $COLUMN_ID INTEGER PRIMARY KEY
            )
        """.trimIndent())

        createRemoteTables(db)
        insertDefaultCategories(db)
    }

    private fun createRemoteTables(db: SQLiteDatabase?) {
        db?.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_REMOTE_DEVICES (
                uuid TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                is_tracking INTEGER NOT NULL DEFAULT 0,
                last_sync_id INTEGER NOT NULL DEFAULT 0,
                in_cal INTEGER NOT NULL DEFAULT 1,
                in_stats INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db?.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_REMOTE_LOGS (
                device_uuid TEXT NOT NULL,
                id INTEGER NOT NULL,
                app TEXT NOT NULL,
                start_timestamp INTEGER NOT NULL,
                end_timestamp INTEGER NOT NULL,
                duration INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (device_uuid, id)
            )
        """.trimIndent())
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_remote_logs_time ON $TABLE_REMOTE_LOGS(start_timestamp, end_timestamp)")
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_remote_logs_device ON $TABLE_REMOTE_LOGS(device_uuid, start_timestamp)")
    }

    private fun insertDefaultSkippedApps(db: SQLiteDatabase?) {
        // Removed default skipped apps per user request
    }

    private fun insertDefaultCategories(db: SQLiteDatabase?) {
        val categories = listOf(
            Triple("Social", "#E91E63", 10),
            Triple("Watching", "#FF9800", 20),
            Triple("Communication", "#4CAF50", 30),
            Triple("Productivity", "#2196F3", 40),
            Triple("Games", "#9C27B0", 50)
        )
        val regexes = mapOf(
            "Social" to listOf("tiktok", "instagram", "facebook", "twitter", "reddit"),
            "Watching" to listOf("youtube", "netflix", "twitch", "primevideo", "disneyplus"),
            "Communication" to listOf("whatsapp", "discord", "messenger", "telegram", "slack"),
            "Productivity" to listOf("docs", "sheets", "outlook", "gmail", "calendar", "chrome"),
            "Games" to listOf("candycrush", "roblox", "genshin", "pubg")
        )

        categories.forEach { (name, color, priority) ->
            val values = ContentValues().apply {
                put(COLUMN_CAT_NAME, name)
                put(COLUMN_CAT_COLOR, color)
                put(COLUMN_CAT_PRIORITY, priority)
            }
            val catId = db?.insert(TABLE_CATEGORIES, null, values) ?: -1
            regexes[name]?.forEach { pattern ->
                val regValues = ContentValues().apply {
                    put(COLUMN_REG_CAT_ID, catId)
                    put(COLUMN_REG_PATTERN, ".*$pattern.*")
                }
                db?.insert(TABLE_REGEX, null, regValues)
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Upgrade surgically to avoid deleting user logs and skipped apps
        if (oldVersion < 7) {
            db?.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SKIPPED_APPS ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COLUMN_PATTERN TEXT NOT NULL UNIQUE)")
        }
        if (oldVersion < 8) {
            db?.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_CATEGORIES ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COLUMN_CAT_NAME TEXT NOT NULL, $COLUMN_CAT_COLOR TEXT, $COLUMN_CAT_PRIORITY INTEGER NOT NULL DEFAULT 0)")
            db?.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_REGEX ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COLUMN_REG_CAT_ID INTEGER NOT NULL, $COLUMN_REG_PATTERN TEXT NOT NULL, FOREIGN KEY($COLUMN_REG_CAT_ID) REFERENCES $TABLE_CATEGORIES($COLUMN_ID))")
            try {
                db?.execSQL("ALTER TABLE $TABLE_LOGS ADD COLUMN $COLUMN_CATEGORY TEXT")
            } catch (e: Exception) {
                // Column might already exist
            }
            insertDefaultCategories(db)
        }
        if (oldVersion < 11) {
            db?.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_DELETED_LOGS ($COLUMN_ID INTEGER PRIMARY KEY)")
        }
        if (oldVersion < 12) {
            createRemoteTables(db)
        }
    }

    fun insertLog(metadata: RichLogMetadata, startTimestampMs: Long, category: String? = null): Long {
        val values = ContentValues().apply {
            put(COLUMN_PACKAGE_NAME, metadata.packageName)
            put(COLUMN_APP_LABEL, metadata.appLabel)
            put(COLUMN_ACTIVITY_CLASS, metadata.activityClass)
            put(COLUMN_START_TIMESTAMP, startTimestampMs)
            put(COLUMN_DURATION, 0)
            put(COLUMN_CATEGORY, category)
            put(COLUMN_LAST_TIME_USED, metadata.lastTimeUsed)
            put(COLUMN_TOTAL_TIME_IN_FOREGROUND_MS, metadata.totalTimeInForegroundMs)
            put(COLUMN_LAST_TIME_VISIBLE, metadata.lastTimeVisible)
            put(COLUMN_TOTAL_TIME_VISIBLE_MS, metadata.totalTimeVisibleMs)
            put(COLUMN_LAST_TIME_FGS_USED, metadata.lastTimeForegroundServiceUsed)
            put(COLUMN_TOTAL_TIME_FGS_USED_MS, metadata.totalTimeForegroundServiceUsedMs)
            put(COLUMN_IS_SYSTEM_APP, metadata.isSystemApp?.let { if (it) 1 else 0 })
            put(COLUMN_VERSION_NAME, metadata.versionName)
            put(COLUMN_VERSION_CODE, metadata.versionCode)
            put(COLUMN_INSTALL_TIME, metadata.installTime)
            put(COLUMN_UPDATE_TIME, metadata.updateTime)
            put(COLUMN_TASK_ROOT_PACKAGE, metadata.taskRootPackage)
            put(COLUMN_TASK_ROOT_CLASS, metadata.taskRootClass)
            put(COLUMN_INSTANCE_ID, metadata.instanceId)
            put(COLUMN_START_EVENT_TYPE, metadata.startEventType)
        }
        return writableDatabase.insert(TABLE_LOGS, null, values)
    }

    fun insertImportedSession(
        metadata: RichLogMetadata,
        startTimestampMs: Long,
        endTimestampMs: Long,
        endEventType: String,
        category: String? = null
    ): Long {
        val durationSeconds = (endTimestampMs - startTimestampMs) / 1_000L
        if (durationSeconds <= 0L) return -1L

        val existing = readableDatabase.query(
            TABLE_LOGS,
            arrayOf(COLUMN_ID),
            "$COLUMN_PACKAGE_NAME=? AND $COLUMN_START_TIMESTAMP=? AND COALESCE($COLUMN_START_EVENT_TYPE, '') != 'MEDIA_PLAYING'",
            arrayOf(metadata.packageName, startTimestampMs.toString()),
            null,
            null,
            null,
            "1"
        )
        val existingId = if (existing.moveToFirst()) existing.getLong(0) else null
        existing.close()

        val values = ContentValues().apply {
            put(COLUMN_PACKAGE_NAME, metadata.packageName)
            put(COLUMN_APP_LABEL, metadata.appLabel)
            put(COLUMN_ACTIVITY_CLASS, metadata.activityClass)
            put(COLUMN_START_TIMESTAMP, startTimestampMs)
            put(COLUMN_END_TIMESTAMP, endTimestampMs)
            put(COLUMN_DURATION, durationSeconds)
            put(COLUMN_CATEGORY, category)
            put(COLUMN_LAST_TIME_USED, metadata.lastTimeUsed)
            put(COLUMN_TOTAL_TIME_IN_FOREGROUND_MS, metadata.totalTimeInForegroundMs)
            put(COLUMN_LAST_TIME_VISIBLE, metadata.lastTimeVisible)
            put(COLUMN_TOTAL_TIME_VISIBLE_MS, metadata.totalTimeVisibleMs)
            put(COLUMN_LAST_TIME_FGS_USED, metadata.lastTimeForegroundServiceUsed)
            put(COLUMN_TOTAL_TIME_FGS_USED_MS, metadata.totalTimeForegroundServiceUsedMs)
            put(COLUMN_IS_SYSTEM_APP, metadata.isSystemApp?.let { if (it) 1 else 0 })
            put(COLUMN_VERSION_NAME, metadata.versionName)
            put(COLUMN_VERSION_CODE, metadata.versionCode)
            put(COLUMN_INSTALL_TIME, metadata.installTime)
            put(COLUMN_UPDATE_TIME, metadata.updateTime)
            put(COLUMN_START_EVENT_TYPE, metadata.startEventType)
            put(COLUMN_END_EVENT_TYPE, endEventType)
        }

        return if (existingId == null) {
            writableDatabase.insert(TABLE_LOGS, null, values)
        } else {
            writableDatabase.update(
                TABLE_LOGS,
                values,
                "$COLUMN_ID=?",
                arrayOf(existingId.toString())
            )
            existingId
        }
    }

    fun latestLogBoundaryMs(): Long? {
        val cursor = readableDatabase.rawQuery(
            "SELECT MAX(COALESCE($COLUMN_END_TIMESTAMP, " +
                "$COLUMN_START_TIMESTAMP + ($COLUMN_DURATION * 1000))) FROM $TABLE_LOGS",
            null
        )
        val value = if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        cursor.close()
        return value
    }

    fun foregroundIntervals(packageName: String, startMs: Long, endMs: Long): List<UsageInterval> {
        val media = mutableListOf<UsageInterval>()
        readableDatabase.rawQuery(
            "SELECT $COLUMN_START_TIMESTAMP, COALESCE($COLUMN_END_TIMESTAMP, " +
                "$COLUMN_START_TIMESTAMP + $COLUMN_DURATION * 1000) FROM $TABLE_LOGS " +
                "WHERE $COLUMN_PACKAGE_NAME=? AND $COLUMN_START_EVENT_TYPE='MEDIA_PLAYING' " +
                "AND $COLUMN_START_TIMESTAMP < ? AND COALESCE($COLUMN_END_TIMESTAMP, " +
                "$COLUMN_START_TIMESTAMP + $COLUMN_DURATION * 1000) > ?",
            arrayOf(packageName, endMs.toString(), startMs.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(1) > startMs) media.add(UsageInterval(cursor.getLong(0), cursor.getLong(1)))
            }
        }
        // A live media row's heartbeat may be a second behind the importer.
        MediaUsagePriority.activeStart(packageName)?.let { media.add(UsageInterval(it, endMs)) }
        return MediaUsagePriority.uncovered(startMs, endMs, media)
    }

    fun endLog(id: Long, endTimestampMs: Long, endEventType: String) {
        // Retrieve start timestamp to calculate total duration as a fallback
        val cursor = readableDatabase.query(TABLE_LOGS, arrayOf(COLUMN_START_TIMESTAMP, COLUMN_DURATION), "$COLUMN_ID=?", arrayOf(id.toString()), null, null, null)
        var finalDuration: Long? = null
        if (cursor.moveToFirst()) {
            val startTs = cursor.getLong(0)
            val currentDuration = cursor.getLong(1)
            val calculatedDuration = (endTimestampMs - startTs) / 1000
            // If the counter-based duration is significantly lower than the time-based one, trust the time-based one.
            if (calculatedDuration > currentDuration) {
                finalDuration = calculatedDuration
            }
        }
        cursor.close()

        val values = ContentValues().apply {
            put(COLUMN_END_TIMESTAMP, endTimestampMs)
            put(COLUMN_END_EVENT_TYPE, endEventType)
            if (finalDuration != null) {
                put(COLUMN_DURATION, finalDuration)
            }
        }
        writableDatabase.update(TABLE_LOGS, values, "$COLUMN_ID=?", arrayOf(id.toString()))
    }

    fun increaseDuration(id: Long) {
        writableDatabase.execSQL("UPDATE $TABLE_LOGS SET $COLUMN_DURATION = $COLUMN_DURATION + 1 WHERE $COLUMN_ID = $id")
    }

    fun getLogPackageName(id: Long): String? {
        val cursor = readableDatabase.query(TABLE_LOGS, arrayOf(COLUMN_PACKAGE_NAME), "$COLUMN_ID=?", arrayOf(id.toString()), null, null, null)
        val packageName = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return packageName
    }

    fun getLogActivityClass(id: Long): String? {
        val cursor = readableDatabase.query(TABLE_LOGS, arrayOf(COLUMN_ACTIVITY_CLASS), "$COLUMN_ID=?", arrayOf(id.toString()), null, null, null)
        val activityClass = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return activityClass
    }

    fun getAllLogs(): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM $TABLE_LOGS ORDER BY $COLUMN_START_TIMESTAMP DESC", null)
        if (cursor.moveToFirst()) {
            do { list.add(cursorToLogEntry(cursor)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getLogsAfter(id: Long): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM $TABLE_LOGS WHERE $COLUMN_ID > ? ORDER BY $COLUMN_ID ASC", arrayOf(id.toString()))
        if (cursor.moveToFirst()) {
            do { list.add(cursorToLogEntry(cursor)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun deleteAllLogs() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Record all existing IDs as deleted
            db.execSQL("INSERT OR IGNORE INTO $TABLE_DELETED_LOGS ($COLUMN_ID) SELECT $COLUMN_ID FROM $TABLE_LOGS")
            db.delete(TABLE_LOGS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun wipeEverything() {
        val db = writableDatabase
        db.delete(TABLE_LOGS, null, null)
        db.delete(TABLE_SKIPPED_APPS, null, null)
        db.delete(TABLE_CATEGORIES, null, null)
        db.delete(TABLE_REGEX, null, null)
        db.delete(TABLE_DELETED_LOGS, null, null)
        db.delete(TABLE_REMOTE_LOGS, null, null)
        db.delete(TABLE_REMOTE_DEVICES, null, null)
        insertDefaultCategories(db)
        insertDefaultSkippedApps(db)
    }

    fun deleteLog(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val deletedRows = db.delete(TABLE_LOGS, "$COLUMN_ID=?", arrayOf(id.toString()))
            if (deletedRows > 0) {
                recordDeletedLogId(db, id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getDeletedLogIds(): List<Long> {
        val list = mutableListOf<Long>()
        val cursor = readableDatabase.rawQuery("SELECT $COLUMN_ID FROM $TABLE_DELETED_LOGS", null)
        if (cursor.moveToFirst()) {
            do { list.add(cursor.getLong(0)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun clearDeletedLogs(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.delete(TABLE_DELETED_LOGS, "$COLUMN_ID IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }

    fun getSkippedApps(): List<SkippedAppRow> {
        val list = mutableListOf<SkippedAppRow>()
        val cursor = readableDatabase.rawQuery("SELECT $COLUMN_ID, $COLUMN_PATTERN FROM $TABLE_SKIPPED_APPS ORDER BY $COLUMN_PATTERN ASC", null)
        if (cursor.moveToFirst()) {
            do { list.add(SkippedAppRow(cursor.getLong(0), cursor.getString(1))) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun addSkippedApp(pattern: String): Long {
        val db = writableDatabase

        // 1. Delete existing logs that match the new pattern
        val logs = getAllLogs()
        val idsToDelete = logs.filter { entry ->
            val target = listOfNotNull(entry.packageName, entry.appLabel).joinToString(" ")
            pattern.trim().isNotEmpty() && (
                target.contains(pattern, ignoreCase = true) ||
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(target) }.getOrDefault(false)
            )
        }.map { it.id }

        db.beginTransaction()
        return try {
            idsToDelete.forEach { id ->
                if (db.delete(TABLE_LOGS, "$COLUMN_ID=?", arrayOf(id.toString())) > 0) {
                    recordDeletedLogId(db, id)
                }
            }

            // Persist the skip rule in the same transaction as its matching-log deletions.
            val values = ContentValues().apply { put(COLUMN_PATTERN, pattern) }
            db.insertWithOnConflict(TABLE_SKIPPED_APPS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                .also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun recordDeletedLogId(db: SQLiteDatabase, id: Long) {
        val values = ContentValues().apply { put(COLUMN_ID, id) }
        db.insertWithOnConflict(TABLE_DELETED_LOGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteSkippedApp(id: Long) {
        writableDatabase.delete(TABLE_SKIPPED_APPS, "$COLUMN_ID=?", arrayOf(id.toString()))
    }

    fun deleteAllSkippedApps() {
        writableDatabase.delete(TABLE_SKIPPED_APPS, null, null)
    }

    fun countMatchingLogs(pattern: String): Int {
        val logs = getAllLogs()
        return logs.count { entry ->
            val target = listOfNotNull(entry.packageName, entry.appLabel).joinToString(" ")
            pattern.trim().isNotEmpty() && (
                target.contains(pattern, ignoreCase = true) ||
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(target) }.getOrDefault(false)
            )
        }
    }

    private fun cursorToLogEntry(cursor: android.database.Cursor): LogEntry {
        fun optL(c: String) = cursor.getColumnIndex(c).let { if (it >= 0 && !cursor.isNull(it)) cursor.getLong(it) else null }
        fun optS(c: String) = cursor.getColumnIndex(c).let { if (it >= 0 && !cursor.isNull(it)) cursor.getString(it) else null }
        fun optI(c: String) = cursor.getColumnIndex(c).let { if (it >= 0 && !cursor.isNull(it)) cursor.getInt(it) else null }
        
        return LogEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME)),
            appLabel = optS(COLUMN_APP_LABEL),
            activityClass = optS(COLUMN_ACTIVITY_CLASS),
            startTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_START_TIMESTAMP)),
            endTimestamp = optL(COLUMN_END_TIMESTAMP),
            duration = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION)),
            category = optS(COLUMN_CATEGORY),
            lastTimeUsed = optL(COLUMN_LAST_TIME_USED),
            totalTimeInForegroundMs = optL(COLUMN_TOTAL_TIME_IN_FOREGROUND_MS),
            lastTimeVisible = optL(COLUMN_LAST_TIME_VISIBLE),
            totalTimeVisibleMs = optL(COLUMN_TOTAL_TIME_VISIBLE_MS),
            lastTimeForegroundServiceUsed = optL(COLUMN_LAST_TIME_FGS_USED),
            totalTimeForegroundServiceUsedMs = optL(COLUMN_TOTAL_TIME_FGS_USED_MS),
            isSystemApp = optI(COLUMN_IS_SYSTEM_APP) == 1,
            versionName = optS(COLUMN_VERSION_NAME),
            versionCode = optL(COLUMN_VERSION_CODE),
            installTime = optL(COLUMN_INSTALL_TIME),
            updateTime = optL(COLUMN_UPDATE_TIME),
            taskRootPackage = optS(COLUMN_TASK_ROOT_PACKAGE),
            taskRootClass = optS(COLUMN_TASK_ROOT_CLASS),
            instanceId = optI(COLUMN_INSTANCE_ID),
            startEventType = optS(COLUMN_START_EVENT_TYPE),
            endEventType = optS(COLUMN_END_EVENT_TYPE)
        )
    }

    fun upsertRemoteDevicesFromServer(serverDevices: List<Pair<String, String>>, localUuid: String?) {
        val remote = serverDevices.filter { it.first != localUuid }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((uuid, name) in remote) {
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE_REMOTE_DEVICES (uuid, name, is_tracking, last_sync_id, in_cal, in_stats) VALUES (?, ?, 0, 0, 1, 1)",
                    arrayOf<Any>(uuid, name)
                )
                db.execSQL(
                    "UPDATE $TABLE_REMOTE_DEVICES SET name = ? WHERE uuid = ?",
                    arrayOf<Any>(name, uuid)
                )
            }
            if (remote.isEmpty()) {
                db.execSQL("UPDATE $TABLE_REMOTE_DEVICES SET is_tracking = 0")
            } else {
                val placeholders = remote.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE $TABLE_REMOTE_DEVICES SET is_tracking = 0 WHERE uuid NOT IN ($placeholders)",
                    remote.map { it.first }.toTypedArray()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getRemoteDevices(): List<RemoteDeviceRow> {
        val list = mutableListOf<RemoteDeviceRow>()
        readableDatabase.rawQuery(
            """SELECT d.uuid, d.name, d.is_tracking, d.last_sync_id, d.in_cal, d.in_stats,
                      EXISTS(SELECT 1 FROM $TABLE_REMOTE_LOGS rl WHERE rl.device_uuid = d.uuid) AS has_local_logs
               FROM $TABLE_REMOTE_DEVICES d
               ORDER BY d.name COLLATE NOCASE ASC, d.uuid ASC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    RemoteDeviceRow(
                        uuid = cursor.getString(0),
                        name = cursor.getString(1),
                        isTracking = cursor.getInt(2) != 0,
                        lastSyncId = cursor.getLong(3),
                        inCal = cursor.getInt(4) != 0,
                        inStats = cursor.getInt(5) != 0,
                        hasLocalLogs = cursor.getInt(6) != 0
                    )
                )
            }
        }
        return list
    }

    fun getTrackedRemoteDevices(deviceUuid: String? = null): List<RemoteDeviceRow> {
        val list = mutableListOf<RemoteDeviceRow>()
        val where = if (deviceUuid != null) "WHERE d.is_tracking = 1 AND d.uuid = ?" else "WHERE d.is_tracking = 1"
        val args = if (deviceUuid != null) arrayOf(deviceUuid) else null
        readableDatabase.rawQuery(
            """SELECT d.uuid, d.name, d.is_tracking, d.last_sync_id, d.in_cal, d.in_stats,
                      EXISTS(SELECT 1 FROM $TABLE_REMOTE_LOGS rl WHERE rl.device_uuid = d.uuid) AS has_local_logs
               FROM $TABLE_REMOTE_DEVICES d
               $where
               ORDER BY d.name COLLATE NOCASE ASC, d.uuid ASC""",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    RemoteDeviceRow(
                        uuid = cursor.getString(0),
                        name = cursor.getString(1),
                        isTracking = cursor.getInt(2) != 0,
                        lastSyncId = cursor.getLong(3),
                        inCal = cursor.getInt(4) != 0,
                        inStats = cursor.getInt(5) != 0,
                        hasLocalLogs = cursor.getInt(6) != 0
                    )
                )
            }
        }
        return list
    }

    fun isRemoteDeviceTracked(uuid: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT is_tracking FROM $TABLE_REMOTE_DEVICES WHERE uuid = ? LIMIT 1",
            arrayOf(uuid)
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    fun setRemoteDeviceTracking(uuid: String, name: String?, tracking: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!name.isNullOrBlank()) {
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE_REMOTE_DEVICES (uuid, name, is_tracking, last_sync_id, in_cal, in_stats) VALUES (?, ?, 0, 0, 1, 1)",
                    arrayOf<Any>(uuid, name)
                )
            }
            if (tracking) {
                db.execSQL(
                    "UPDATE $TABLE_REMOTE_DEVICES SET is_tracking = 1, in_cal = 1, in_stats = 1 WHERE uuid = ?",
                    arrayOf<Any>(uuid)
                )
            } else {
                db.execSQL(
                    "UPDATE $TABLE_REMOTE_DEVICES SET is_tracking = 0 WHERE uuid = ?",
                    arrayOf<Any>(uuid)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateRemoteDeviceVisibility(uuid: String, inCal: Boolean?, inStats: Boolean?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (inCal != null) {
                db.execSQL(
                    "UPDATE $TABLE_REMOTE_DEVICES SET in_cal = ? WHERE uuid = ?",
                    arrayOf<Any>(if (inCal) 1 else 0, uuid)
                )
            }
            if (inStats != null) {
                db.execSQL(
                    "UPDATE $TABLE_REMOTE_DEVICES SET in_stats = ? WHERE uuid = ?",
                    arrayOf<Any>(if (inStats) 1 else 0, uuid)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun unsubscribeRemoteDevice(uuid: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_REMOTE_LOGS, "device_uuid = ?", arrayOf(uuid))
            db.execSQL(
                "UPDATE $TABLE_REMOTE_DEVICES SET is_tracking = 0, last_sync_id = 0, in_cal = 0, in_stats = 0 WHERE uuid = ?",
                arrayOf<Any>(uuid)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun untrackAllRemoteDevices() {
        writableDatabase.execSQL("UPDATE $TABLE_REMOTE_DEVICES SET is_tracking = 0")
    }

    fun insertRemoteLogsBatch(logs: List<RemoteLogRow>): Int {
        if (logs.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE_REMOTE_LOGS (device_uuid, id, app, start_timestamp, end_timestamp, duration) VALUES (?, ?, ?, ?, ?, ?)"
            )
            try {
                for (row in logs) {
                    stmt.clearBindings()
                    stmt.bindString(1, row.deviceUuid)
                    stmt.bindLong(2, row.id)
                    stmt.bindString(3, row.app)
                    stmt.bindLong(4, row.startTimestamp)
                    stmt.bindLong(5, row.endTimestamp)
                    stmt.bindLong(6, row.duration)
                    stmt.executeInsert()
                }
            } finally {
                stmt.close()
            }
            db.setTransactionSuccessful()
            return logs.size
        } finally {
            db.endTransaction()
        }
    }

    fun advanceRemoteDeviceSyncId(uuid: String, newLastSyncId: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_REMOTE_DEVICES SET last_sync_id = MAX(last_sync_id, ?) WHERE uuid = ?",
            arrayOf<Any>(newLastSyncId, uuid)
        )
    }

    fun deleteRemoteLog(deviceUuid: String, id: Long) {
        writableDatabase.delete(TABLE_REMOTE_LOGS, "device_uuid = ? AND id = ?", arrayOf(deviceUuid, id.toString()))
    }

    fun earliestLogTimestampMs(includeLocal: Boolean, remoteDeviceUuids: List<String>): Long? {
        var earliest: Long? = null
        if (includeLocal) {
            readableDatabase.rawQuery("SELECT MIN($COLUMN_START_TIMESTAMP) FROM $TABLE_LOGS", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val value = cursor.getLong(0)
                    earliest = earliest?.let { minOf(it, value) } ?: value
                }
            }
        }
        if (remoteDeviceUuids.isNotEmpty()) {
            val placeholders = remoteDeviceUuids.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT MIN(start_timestamp) FROM $TABLE_REMOTE_LOGS WHERE device_uuid IN ($placeholders)",
                remoteDeviceUuids.toTypedArray()
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val value = cursor.getLong(0)
                    earliest = earliest?.let { minOf(it, value) } ?: value
                }
            }
        }
        return earliest
    }
}

data class RemoteDeviceRow(
    val uuid: String,
    val name: String,
    val isTracking: Boolean,
    val lastSyncId: Long,
    val inCal: Boolean,
    val inStats: Boolean,
    val hasLocalLogs: Boolean = false
)

data class RemoteLogRow(
    val deviceUuid: String,
    val id: Long,
    val app: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val duration: Long
)

data class LogEntry(
    val id: Long, val packageName: String, val appLabel: String?, val activityClass: String?,
    val startTimestamp: Long, val endTimestamp: Long?, val duration: Long, val category: String?,
    val lastTimeUsed: Long?, val totalTimeInForegroundMs: Long?, val lastTimeVisible: Long?,
    val totalTimeVisibleMs: Long?, val lastTimeForegroundServiceUsed: Long?,
    val totalTimeForegroundServiceUsedMs: Long?, val isSystemApp: Boolean?,
    val versionName: String?, val versionCode: Long?, val installTime: Long?,
    val updateTime: Long?, val taskRootPackage: String?, val taskRootClass: String?,
    val instanceId: Int?, val startEventType: String?, val endEventType: String?
)

data class SkippedAppRow(val id: Long, val pattern: String)
