package ca.sekhrit.timetrackermoblie

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "time_tracker.db"
        private const val DATABASE_VERSION = 10

        const val TABLE_LOGS = "logs"
        const val TABLE_SKIPPED_APPS = "skipped_apps"
        const val TABLE_CATEGORIES = "categories"
        const val TABLE_REGEX = "category_regex"

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

        insertDefaultCategories(db)
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
        if (oldVersion < 10) {
            db?.delete(TABLE_SKIPPED_APPS, null, null)
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

    fun deleteAllLogs() {
        writableDatabase.delete(TABLE_LOGS, null, null)
    }

    fun wipeEverything() {
        val db = writableDatabase
        db.delete(TABLE_LOGS, null, null)
        db.delete(TABLE_SKIPPED_APPS, null, null)
        db.delete(TABLE_CATEGORIES, null, null)
        db.delete(TABLE_REGEX, null, null)
        insertDefaultCategories(db)
        insertDefaultSkippedApps(db)
    }

    fun deleteLog(id: Long) {
        writableDatabase.delete(TABLE_LOGS, "$COLUMN_ID=?", arrayOf(id.toString()))
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

        if (idsToDelete.isNotEmpty()) {
            db.beginTransaction()
            try {
                idsToDelete.forEach { id ->
                    db.delete(TABLE_LOGS, "$COLUMN_ID=?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // 2. Add to skipped_apps table
        val values = ContentValues().apply { put(COLUMN_PATTERN, pattern) }
        return db.insertWithOnConflict(TABLE_SKIPPED_APPS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
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
}

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
