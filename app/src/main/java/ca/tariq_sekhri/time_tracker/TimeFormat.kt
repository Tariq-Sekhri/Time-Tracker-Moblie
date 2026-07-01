package ca.tariq_sekhri.time_tracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDisplayTimestamp(timestampMs: Long): String {
    val date = Date(timestampMs)
    val locale = Locale.getDefault()
    val time = SimpleDateFormat("hh:mm:ss", locale).format(date)
    val amPm = SimpleDateFormat("a", locale).format(date).lowercase(locale)
    val datePart = SimpleDateFormat("MMM d, yyyy", locale).format(date)
    return "$time $amPm $datePart"
}
