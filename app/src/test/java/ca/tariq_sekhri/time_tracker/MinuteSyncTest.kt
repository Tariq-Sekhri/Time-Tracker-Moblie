package ca.tariq_sekhri.time_tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class MinuteSyncTest {
    @Test fun retriesAnUnreachableServerLessOften() {
        assertEquals(listOf(0, 1, 3, 7, 14, 14), (0..5).map(MinuteSync::minutesToSkipAfter))
    }
}
