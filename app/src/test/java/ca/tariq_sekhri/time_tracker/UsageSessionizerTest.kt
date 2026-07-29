package ca.tariq_sekhri.time_tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSessionizerTest {
    @Test
    fun appSwitchCompletesPreviousSession() {
        val result = UsageSessionizer.process(
            null,
            listOf(
                foreground("one", 1_000),
                foreground("two", 6_000)
            )
        )

        assertEquals(1, result.completed.size)
        assertEquals("one", result.completed.single().pending.packageName)
        assertEquals(6_000, result.completed.single().endTimestampMs)
        assertEquals("two", result.pending?.packageName)
    }

    @Test
    fun samePackageActivityChangesDoNotSplitSession() {
        val result = UsageSessionizer.process(
            null,
            listOf(
                foreground("one", 1_000, "FirstActivity"),
                foreground("one", 2_000, "SecondActivity")
            )
        )

        assertEquals(0, result.completed.size)
        assertEquals(1_000L, result.pending?.startTimestampMs)
        assertEquals("FirstActivity", result.pending?.activityClass)
    }

    @Test
    fun screenOffCompletesForegroundSession() {
        val result = UsageSessionizer.process(
            null,
            listOf(
                foreground("one", 1_000),
                NativeUsageEvent(
                    NativeUsageEventKind.END_FOREGROUND,
                    5_000,
                    eventTypeName = "SCREEN_NON_INTERACTIVE"
                )
            )
        )

        assertEquals(1, result.completed.size)
        assertEquals("SCREEN_NON_INTERACTIVE", result.completed.single().endEventType)
        assertNull(result.pending)
    }

    @Test
    fun snapshotCreatesImmutableChunkAndContinuation() {
        val processed = UsageSessionizer.process(null, listOf(foreground("one", 1_000)))
        val result = UsageSessionizer.snapshot(processed, 11_000)

        assertEquals(1, result.completed.size)
        assertEquals(11_000L, result.completed.single().endTimestampMs)
        assertEquals(11_000L, result.pending?.startTimestampMs)
        assertEquals("IMPORT_CONTINUATION", result.pending?.startEventType)
    }

    private fun foreground(
        packageName: String,
        timestampMs: Long,
        activityClass: String? = null
    ) = NativeUsageEvent(
        NativeUsageEventKind.FOREGROUND,
        timestampMs,
        packageName,
        activityClass,
        "ACTIVITY_RESUMED"
    )
}
