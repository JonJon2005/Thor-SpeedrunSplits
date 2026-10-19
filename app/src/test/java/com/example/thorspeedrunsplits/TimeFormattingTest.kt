package com.example.thorspeedrunsplits

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormattingTest {
    @Test
    fun precisionTruncatesWithoutRoundingIntoNextMinute() {
        assertEquals("59", formatTimeValue(59_999L, 0))
        assertEquals("59.9", formatTimeValue(59_999L, 1))
        assertEquals("59.99", formatTimeValue(59_999L, 2))
        assertEquals("1:00.00", formatTimeValue(60_000L, 2))
    }

    @Test
    fun formatsHoursAndPadsFractionalSeconds() {
        assertEquals("1:01:01", formatTimeValue(3_661_009L, 0))
        assertEquals("1:01:01.0", formatTimeValue(3_661_009L, 1))
        assertEquals("1:01:01.01", formatTimeValue(3_661_010L, 2))
        assertEquals("0.00", formatTimeValue(-1L, 2))
    }
}
