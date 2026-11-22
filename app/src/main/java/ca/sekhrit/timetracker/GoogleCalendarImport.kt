package ca.sekhrit.timetracker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class GoogleCalendarImport(private val context: Context, private val prefs: SharedPreferences) {
    private data class Calendar(val id: Long, val key: String, val name: String, val account: String)
    private val selectionPref = "imported_google_calendars"

    fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun selected(): Set<String> = prefs.getStringSet(selectionPref, emptySet()).orEmpty()

    private fun calendars(): List<Calendar> {
        check(allowed()) { "Allow calendar access in Settings" }
        val projection = arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME)
        val cursor = checkNotNull(context.contentResolver.query(Calendars.CONTENT_URI, projection,
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.SYNC_EVENTS} = 1",
            arrayOf("com.google"), Calendars.CALENDAR_DISPLAY_NAME)) {
            "Could not read Google calendars from Android"
        }
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val account = it.getString(3).orEmpty()
                    val sourceId = it.getString(1) ?: id.toString()
                    add(Calendar(id, "$account/$sourceId", it.getString(2) ?: "Google Calendar", account))
                }
            }
        }
    }

    fun sources(): List<Map<String, String>> = calendars().map {
        mapOf("key" to it.key, "name" to it.name, "account" to it.account)
    }

    fun select(key: String, enabled: Boolean) {
        if (enabled) require(calendars().any { it.key == key }) { "This Google calendar is no longer available" }
        val selection = if (enabled) selected() + key else selected() - key
        check(prefs.edit().putStringSet(selectionPref, selection).commit()) { "Could not save calendar selection" }
    }

    fun events(start: Long, end: Long, firstDay: String, endDay: String): List<Map<String, Any>> {
        val firstDate = LocalDate.parse(firstDay)
        val endDate = LocalDate.parse(endDay)
        require(ChronoUnit.DAYS.between(firstDate, endDate) in 1L..7L && end - start <= 8 * 86_400_000L) {
            "Invalid calendar range"
        }
        val included = selected()
        if (included.isEmpty()) return emptyList()
        val calendars = calendars().filter { it.key in included }.associateBy { it.id }
        if (calendars.isEmpty()) return emptyList()

        // Android stores all-day occurrences at UTC midnight. Use their date range as well as the local tracking range.
        val allDayStart = firstDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val allDayEnd = endDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, minOf(start, allDayStart))
            ContentUris.appendId(this, maxOf(end, allDayEnd))
        }.build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE,
            Instances.BEGIN, Instances.END, Instances.ALL_DAY)
        val calendarIds = calendars.keys.map(Long::toString).toTypedArray()
        val placeholders = calendarIds.joinToString(",") { "?" }
        val cursor = checkNotNull(context.contentResolver.query(uri, projection,
            "${Instances.CALENDAR_ID} IN ($placeholders) AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})",
            calendarIds, Instances.BEGIN)) { "Could not read Google Calendar events from Android" }
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val calendar = calendars[it.getLong(1)] ?: continue
                    val begin = it.getLong(3)
                    val finish = it.getLong(4)
                    val allDay = it.getInt(5) != 0
                    val from = if (allDay) allDayStart else start
                    val to = if (allDay) allDayEnd else end
                    if (begin >= to || finish < from || (finish == from && begin < from)) continue
                    add(mapOf(
                        "id" to "google-${calendar.id}-${it.getLong(0)}-$begin",
                        "title" to (it.getString(2)?.takeIf(String::isNotBlank) ?: "Untitled event"),
                        "start" to if (allDay) utcDate(begin) else Instant.ofEpochMilli(begin).toString(),
                        "end" to if (allDay) utcDate(finish) else Instant.ofEpochMilli(finish).toString(),
                        "allDay" to allDay,
                        "calendar" to calendar.name
                    ))
                }
            }
        }
    }

    private fun utcDate(ms: Long): String = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()
}
