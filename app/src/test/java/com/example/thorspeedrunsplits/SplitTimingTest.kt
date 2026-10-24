package com.example.thorspeedrunsplits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitTimingTest {
    @Test fun oldFullyTimedRunsKeepTheirSegmentDurations() {
        assertEquals(10L, isolatedSegmentDuration(listOf(10L, 30L), 0))
        assertEquals(20L, isolatedSegmentDuration(listOf(10L, 30L), 1))
    }

    @Test fun skippedBoundariesNeverBecomeIndividualSegmentTimes() {
        val times = listOf(10L, null, null, 40L, 50L)
        assertNull(isolatedSegmentDuration(times, 1))
        assertNull(isolatedSegmentDuration(times, 3))
        assertEquals(10L, isolatedSegmentDuration(times, 4))
        assertEquals(10L, lastTimedSplitBefore(times, 3))
        assertNull(isolatedSegmentDuration(listOf(null, 20L), 1))
    }

    @Test fun liveComparisonFindsLastSharedBoundaryAcrossSkips() {
        val pb = listOf(1000L, 2000L, 3000L)
        val run = listOf(500L, null, null)
        assertNull(liveSplitDelta(2000L, 2, run, pb))
        assertEquals(-400L, liveSplitDelta(2600L, 2, run, pb))
        assertEquals(1L, liveSplitDelta(3001L, 2, run, pb))
        assertNull(liveSplitDelta(2600L, 2, run, listOf(1000L, 2000L, null)))
    }
    @Test fun pbSelectionUsesFinishTimeAndOnlyUsesCompletenessToBreakTies() {
        val full = listOf(10L, 30L)
        val skipped = listOf(null, 25L)
        assertEquals(true, shouldReplacePersonalBest(25L, skipped, 30L, full))
        assertEquals(false, shouldReplacePersonalBest(30L, full, 25L, skipped))
        assertEquals(true, shouldReplacePersonalBest(25L, listOf(10L, 25L), 25L, skipped))
    }

}
