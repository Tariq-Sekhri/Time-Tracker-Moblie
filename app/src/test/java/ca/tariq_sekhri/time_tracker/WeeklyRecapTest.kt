package ca.tariq_sekhri.time_tracker

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeeklyRecapTest {
    @Test fun mondayAlwaysSummarizesCompletedWeek() {
        val now = ZonedDateTime.parse("2026-10-26T09:00:00-04:00[America/Toronto]")
        val window = RecapWindow.completed(now)
        assertEquals(LocalDate.parse("2026-10-19"), window.start)
        assertEquals(LocalDate.parse("2026-10-26"), window.end)
        assertEquals(now.plusWeeks(1), RecapWindow.nextDelivery(now))
        assertEquals(now, RecapWindow.nextDelivery(now.minusMinutes(1)))
    }
    @Test fun calendarBoundariesHandleDstAndYearRollover() {
        val zone = ZoneId.of("America/Toronto")
        val window = RecapWindow(LocalDate.parse("2026-10-26"), zone)
        assertEquals(169L, (window.boundary(window.end) - window.boundary(window.start)) / 3600000)
        assertEquals(LocalDate.parse("2026-12-28"), RecapWindow.completed(ZonedDateTime.parse("2027-01-04T09:00:00Z")).start)
        val spring = RecapWindow(LocalDate.parse("2026-03-02"), zone)
        assertEquals(167L, (spring.boundary(spring.end) - spring.boundary(spring.start)) / 3600000)
    }
    @Test fun comparisonsHandleZeroIncreaseDecreaseAndTie() {
        val window = RecapWindow(LocalDate.parse("2026-10-19"), ZoneId.of("UTC"))
        assertEquals("+1h 0m vs the week before", WeeklyRecap(window, listOf(3600L), 0).comparison)
        assertEquals("−1h 0m vs the week before", WeeklyRecap(window, listOf(0L), 3600).comparison)
        assertEquals("±0m vs the week before", WeeklyRecap(window, listOf(3600L), 3600).comparison)
        assertFalse(WeeklyRecap(window, listOf(3600L), 0).comparison.contains("%"))
    }
}
