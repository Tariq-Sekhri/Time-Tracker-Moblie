package ca.tariq_sekhri.time_tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUsagePriorityTest {
    @Test fun consecutiveSongsReplaceForegroundUsage() {
        assertEquals(emptyList<UsageInterval>(), MediaUsagePriority.uncovered(
            1_000, 10_000, listOf(UsageInterval(1_000, 5_000), UsageInterval(5_000, 10_000))
        ))
    }

    @Test fun foregroundTimeBeforeAndAfterPlaybackIsKept() {
        assertEquals(listOf(UsageInterval(1_000, 3_000), UsageInterval(8_000, 10_000)),
            MediaUsagePriority.uncovered(1_000, 10_000, listOf(UsageInterval(3_000, 8_000))))
    }

    @Test fun pauseBetweenSongsAllowsForegroundUsage() {
        assertEquals(listOf(UsageInterval(4_000, 6_000)), MediaUsagePriority.uncovered(
            1_000, 10_000, listOf(UsageInterval(1_000, 4_000), UsageInterval(6_000, 10_000))
        ))
    }

    @Test fun overlappingAndOutOfOrderMediaIntervalsAreMerged() {
        assertEquals(listOf(UsageInterval(1_000, 2_000), UsageInterval(9_000, 10_000)),
            MediaUsagePriority.uncovered(1_000, 10_000,
                listOf(UsageInterval(5_000, 9_000), UsageInterval(2_000, 7_000))))
    }

    @Test fun noMediaPreservesUsage() {
        assertEquals(listOf(UsageInterval(1_000, 10_000)),
            MediaUsagePriority.uncovered(1_000, 10_000, emptyList()))
    }

    @Test fun mediaOutsideUsageDoesNotRemoveTime() {
        assertEquals(listOf(UsageInterval(3_000, 7_000)),
            MediaUsagePriority.uncovered(3_000, 7_000,
                listOf(UsageInterval(0, 3_000), UsageInterval(7_000, 9_000))))
    }

    @Test fun mediaSpanningTheWholeUsageRemovesIt() {
        assertEquals(emptyList<UsageInterval>(), MediaUsagePriority.uncovered(
            3_000, 7_000, listOf(UsageInterval(0, 10_000))))
    }

    @Test fun songChangesDoNotResumeForegroundTracking() {
        val transitions = mutableListOf<Boolean>()
        val listener: (String, Boolean, Long) -> Unit = { _, playing, _ -> transitions.add(playing) }
        MediaUsagePriority.addListener(listener)
        try {
            MediaUsagePriority.setPlaying("music", true, 1_000)
            MediaUsagePriority.setPlaying("music", true, 5_000)
            assertEquals(1_000L, MediaUsagePriority.activeStart("music"))
            MediaUsagePriority.setPlaying("music", false, 10_000)
            assertEquals(listOf(true, false), transitions)
        } finally {
            MediaUsagePriority.removeListener(listener)
            MediaUsagePriority.setPlaying("music", false, 10_000)
        }
    }
}
