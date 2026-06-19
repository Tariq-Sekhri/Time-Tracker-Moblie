package ca.sekhrit.timetrackermoblie

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "time_tracker.db"
        private const val DATABASE_VERSION = 7

        const val TABLE_LOGS = "logs"
        const val TABLE_SKIPPED_APPS = "skipped_apps"
        const val COLUMN_ID = "id"
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_APP_LABEL = "app_label"
        const val COLUMN_ACTIVITY_CLASS = "activity_class"
        const val COLUMN_START_TIMESTAMP = "start_timestamp"
        const val COLUMN_END_TIMESTAMP = "end_timestamp"
        const val COLUMN_DURATION = "duration"
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
    }

    override fun onCreate(db: SQLiteDatabase?) {
        createLogsTable(db)
        createSkippedAppsTable(db)
    }

    private fun createLogsTable(db: SQLiteDatabase?) {
        db?.execSQL(
            """
                CREATE TABLE $TABLE_LOGS (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_PACKAGE_NAME TEXT NOT NULL,
                    $COLUMN_APP_LABEL TEXT,
                    $COLUMN_ACTIVITY_CLASS TEXT,
                    $COLUMN_START_TIMESTAMP INTEGER NOT NULL,
                    $COLUMN_END_TIMESTAMP INTEGER,
                    $COLUMN_DURATION INTEGER NOT NULL DEFAULT 0,
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
            """.trimIndent()
        )
    }

    private fun createSkippedAppsTable(db: SQLiteDatabase?) {
        db?.execSQL(
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SKIPPED_APPS (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_PATTERN TEXT NOT NULL UNIQUE
                )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 6) {
            db?.execSQL("DROP TABLE IF EXISTS category_regex")
            db?.execSQL("DROP TABLE IF EXISTS categories")
            db?.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
            db?.execSQL("DROP TABLE IF EXISTS $TABLE_SKIPPED_APPS")
            onCreate(db)
            return
        }

        if (oldVersion < 7) {
            createSkippedAppsTable(db)
        }
    }

    fun insertLog(metadata: RichLogMetadata, startTimestampMs: Long): Long {
        val values = ContentValues().apply {
            put(COLUMN_PACKAGE_NAME, metadata.packageName)
            put(COLUMN_APP_LABEL, metadata.appLabel)
            put(COLUMN_ACTIVITY_CLASS, metadata.activityClass)
            put(COLUMN_START_TIMESTAMP, startTimestampMs)
            put(COLUMN_DURATION, 0)
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
        val values = ContentValues().apply {
            put(COLUMN_END_TIMESTAMP, endTimestampMs)
            put(COLUMN_END_EVENT_TYPE, endEventType)
        }
        writableDatabase.update(TABLE_LOGS, values, "$COLUMN_ID=?", arrayOf(id.toString()))
    }

    fun increaseDuration(id: Long) {
        writableDatabase.execSQL("UPDATE $TABLE_LOGS SET $COLUMN_DURATION = $COLUMN_DURATION + 1 WHERE $COLUMN_ID = $id")
    }

    fun getLogPackageName(id: Long): String? {
        val cursor = readableDatabase.query(
            TABLE_LOGS,
            arrayOf(COLUMN_PACKAGE_NAME),
            "$COLUMN_ID=?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        val packageName = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return packageName
    }

    fun getLogActivityClass(id: Long): String? {
        val cursor = readableDatabase.query(
            TABLE_LOGS,
            arrayOf(COLUMN_ACTIVITY_CLASS),
            "$COLUMN_ID=?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        val activityClass = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return activityClass
    }

    fun getAllLogs(): List<LogEntry> {
        val rows = mutableListOf<LogEntry>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_LOGS ORDER BY $COLUMN_START_TIMESTAMP DESC",
            null
        )
        if (cursor.moveToFirst()) {
            do {
                rows.add(cursorToLogEntry(cursor))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return rows
    }

    fun deleteAllLogs() {
        writableDatabase.delete(TABLE_LOGS, null, null)
    }

    fun getSkippedApps(): List<SkippedAppRow> {
        val rows = mutableListOf<SkippedAppRow>()
        val cursor = readableDatabase.rawQuery(
            "SELECT $COLUMN_ID, $COLUMN_PATTERN FROM $TABLE_SKIPPED_APPS ORDER BY $COLUMN_PATTERN ASC",
            null
        )
        if (cursor.moveToFirst()) {
            do {
                rows.add(SkippedAppRow(cursor.getLong(0), cursor.getString(1)))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return rows
    }

    fun addSkippedApp(pattern: String): Long {
        val values = ContentValues().apply {
            put(COLUMN_PATTERN, pattern)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_SKIPPED_APPS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun deleteSkippedApp(id: Long) {
        writableDatabase.delete(TABLE_SKIPPED_APPS, "$COLUMN_ID=?", arrayOf(id.toString()))
    }

    private fun cursorToLogEntry(cursor: android.database.Cursor): LogEntry {
        fun optionalLong(column: String): Long? {
            val index = cursor.getColumnIndex(column)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
        fun optionalString(column: String): String? {
            val index = cursor.getColumnIndex(column)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
        }
        fun optionalInt(column: String): Int? {
            val index = cursor.getColumnIndex(column)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getInt(index) else null
        }
        val systemAppIndex = cursor.getColumnIndex(COLUMN_IS_SYSTEM_APP)
        val isSystemApp = if (systemAppIndex >= 0 && !cursor.isNull(systemAppIndex)) {
            cursor.getInt(systemAppIndex) == 1
        } else null

        return LogEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME)),
            appLabel = optionalString(COLUMN_APP_LABEL),
            activityClass = optionalString(COLUMN_ACTIVITY_CLASS),
            startTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_START_TIMESTAMP)),
            endTimestamp = optionalLong(COLUMN_END_TIMESTAMP),
            duration = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION)),
            lastTimeUsed = optionalLong(COLUMN_LAST_TIME_USED),
            totalTimeInForegroundMs = optionalLong(COLUMN_TOTAL_TIME_IN_FOREGROUND_MS),
            lastTimeVisible = optionalLong(COLUMN_LAST_TIME_VISIBLE),
            totalTimeVisibleMs = optionalLong(COLUMN_TOTAL_TIME_VISIBLE_MS),
            lastTimeForegroundServiceUsed = optionalLong(COLUMN_LAST_TIME_FGS_USED),
            totalTimeForegroundServiceUsedMs = optionalLong(COLUMN_TOTAL_TIME_FGS_USED_MS),
            isSystemApp = isSystemApp,
            versionName = optionalString(COLUMN_VERSION_NAME),
            versionCode = optionalLong(COLUMN_VERSION_CODE),
            installTime = optionalLong(COLUMN_INSTALL_TIME),
            updateTime = optionalLong(COLUMN_UPDATE_TIME),
            taskRootPackage = optionalString(COLUMN_TASK_ROOT_PACKAGE),
            taskRootClass = optionalString(COLUMN_TASK_ROOT_CLASS),
            instanceId = optionalInt(COLUMN_INSTANCE_ID),
            startEventType = optionalString(COLUMN_START_EVENT_TYPE),
            endEventType = optionalString(COLUMN_END_EVENT_TYPE)
        )
    }
}

data class LogEntry(
    val id: Long,
    val packageName: String,
    val appLabel: String?,
    val activityClass: String?,
    val startTimestamp: Long,
    val endTimestamp: Long?,
    val duration: Long,
    val lastTimeUsed: Long?,
    val totalTimeInForegroundMs: Long?,
    val lastTimeVisible: Long?,
    val totalTimeVisibleMs: Long?,
    val lastTimeForegroundServiceUsed: Long?,
    val totalTimeForegroundServiceUsedMs: Long?,
    val isSystemApp: Boolean?,
    val versionName: String?,
    val versionCode: Long?,
    val installTime: Long?,
    val updateTime: Long?,
    val taskRootPackage: String?,
    val taskRootClass: String?,
    val instanceId: Int?,
    val startEventType: String?,
    val endEventType: String?
)

data class SkippedAppRow(
    val id: Long,
    val pattern: String
)
