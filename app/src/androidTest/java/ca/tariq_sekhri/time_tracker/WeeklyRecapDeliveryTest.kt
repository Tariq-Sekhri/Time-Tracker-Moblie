package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeeklyRecapDeliveryTest {
    @Test fun clipsBoundariesAndDeliversOnlyOnceAfterMondayMorning() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "weekly-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String) = target.getDatabasePath(prefix + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix + name, mode)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                target.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                target.openOrCreateDatabase(prefix + name, mode, factory, handler)
        }
        val now = ZonedDateTime.parse("2026-10-26T09:00:00-04:00[America/Toronto]")
        val window = RecapWindow.completed(now)
        try {
            DatabaseHelper(context).use { db ->
                fun insert(start: Long, end: Long?, seconds: Long) {
                    db.writableDatabase.insertOrThrow("logs", null, ContentValues().apply {
                        put("package_name", "test.usage"); put("start_timestamp", start)
                        if (end == null) putNull("end_timestamp") else put("end_timestamp", end)
                        put("duration", seconds)
                    })
                }
                val start = window.boundary(window.start); val end = window.boundary(window.end)
                insert(start - 3600000, start + 3600000, 7200) // 1h in each week
                insert(start + 7200000, null, 1800) // fallback endpoint
                insert(end - 1800000, end + 1800000, 3600) // 30m in recap, 30m excluded
                insert(end, end + 3600000, 3600) // new week excluded
                insert(start, start, 0)
            }
            val recap = WeeklyRecap.read(context, window)
            assertEquals(7200L, recap.seconds)
            assertEquals(3600L, recap.previousSeconds)
            assertEquals(5400L, recap.days.first())
            assertEquals(1800L, recap.days.last())
            DatabaseHelper(context).use { db ->
                val start = window.boundary(window.start.plusDays(2))
                db.writableDatabase.insertOrThrow("logs", null, ContentValues().apply {
                    put("package_name", "test.capped"); put("start_timestamp", start)
                    put("end_timestamp", start + 2 * 86400000L); put("duration", 3600)
                })
            }
            assertEquals(10800L, WeeklyRecap.read(context, window).seconds)
            assertFalse(WeeklyRecapWorker.deliver(context, now.minusMinutes(1)))
            assertTrue(WeeklyRecapWorker.deliver(context, now))
            assertFalse(WeeklyRecapWorker.deliver(context, now.plusHours(1)))
            assertFalse(WeeklyRecapWorker.deliver(context, now.minusWeeks(1)))
        } finally {
            target.deleteDatabase(prefix + "time_tracker.db")
            listOf("WeeklyRecap", "TimeTrackerPrefs", "AndroidUsageImporter").forEach { target.deleteSharedPreferences(prefix + it) }
        }
    }
}
