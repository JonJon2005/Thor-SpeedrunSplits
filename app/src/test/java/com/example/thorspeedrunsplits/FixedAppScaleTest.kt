package com.example.thorspeedrunsplits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedAppScaleTest {
    @Test
    fun usesMeasuredThorDensityInEitherOrientation() {
        assertEquals(369f / 160f, fixedAppDensity(1240, 1080), 0.0001f)
        assertEquals(369f / 160f, fixedAppDensity(1080, 1240), 0.0001f)
    }

    @Test
    fun proportionalWindowsKeepTheSameLogicalLayout() {
        val baseline = fixedAppDensity(1240, 1080)
        val halfSize = fixedAppDensity(620, 540)
        assertEquals(1240f / baseline, 620f / halfSize, 0.0001f)
        assertEquals(1080f / baseline, 540f / halfSize, 0.0001f)
    }

    @Test
    fun differentAspectRatioFitsBothReferenceDimensions() {
        val density = fixedAppDensity(1280, 960)
        val baseline = 369f / 160f
        assertTrue(1280f / density >= 1240f / baseline)
        assertEquals(1080f / baseline, 960f / density, 0.0001f)
    }
}
