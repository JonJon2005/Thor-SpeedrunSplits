package com.example.thorspeedrunsplits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartOffsetTest {
    @Test
    fun validatesWholeSecondCountdownRange() {
        assertEquals(0, parseStartCountdownSeconds("0"))
        assertEquals(5, parseStartCountdownSeconds("005"))
        assertEquals(3600, parseStartCountdownSeconds("3600"))
        listOf("", "-5", "1.5", "3601", "999999999999", "five").forEach {
            assertNull(parseStartCountdownSeconds(it))
        }
    }

    @Test
    fun countdownNeverShowsNegativeZeroAtAnyPrecision() {
        assertEquals("-5", formatRunTimer(-4999L, 0))
        assertEquals("-5.0", formatRunTimer(-4999L, 1))
        assertEquals("-5.00", formatRunTimer(-4999L, 2))
        assertEquals("-1", formatRunTimer(-1L, 0))
        assertEquals("-0.1", formatRunTimer(-1L, 1))
        assertEquals("-0.01", formatRunTimer(-1L, 2))
    }

    @Test
    fun crossesZeroAndPreservesNormalRunFormatting() {
        assertEquals("-1:00.0", formatRunTimer(-60000L, 1))
        assertEquals("0.0", formatRunTimer(0L, 1))
        assertEquals("0.1", formatRunTimer(123L, 1))
        assertEquals("1:00.12", formatRunTimer(60123L, 2))
    }
}
