package com.nathanhanapps.nebulaThemePorter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TintBlendModeTest {
    @Test
    fun multiplyMatchesTheOriginalGrayscaleTimesTintFormula() {
        assertEquals(0, TintBlendMode.MULTIPLY.blend(0, 200))
        assertEquals(255, TintBlendMode.MULTIPLY.blend(255, 255))
        assertEquals(100, TintBlendMode.MULTIPLY.blend(200, 128))
    }

    @Test
    fun multiplyIsIdentityAtWhiteAndAlwaysBlackAtBlack() {
        // Multiply's own identity point is white (a*1=a); black (a*0=0) always wins regardless of base.
        for (base in listOf(0, 64, 128, 192, 255)) {
            assertEquals(base, TintBlendMode.MULTIPLY.blend(base, 255))
            assertEquals(0, TintBlendMode.MULTIPLY.blend(base, 0))
        }
    }

    @Test
    fun lightenIsIdentityAtBlackAndAlwaysWhiteAtWhite() {
        // Lighten keeps whichever is brighter, so black (the minimum) never wins and white (the maximum) always does.
        for (base in listOf(0, 64, 128, 192, 255)) {
            assertEquals(base, TintBlendMode.LIGHTEN.blend(base, 0))
            assertEquals(255, TintBlendMode.LIGHTEN.blend(base, 255))
        }
    }

    @Test
    fun overlayAndSoftLightAreNeutralAtMiddleGrayTint() {
        // b = 0.5 is the documented no-op point for both Overlay and Soft Light, within a 1-level rounding gap.
        for (base in listOf(0, 64, 128, 192, 255)) {
            assertTrue(kotlin.math.abs(base - TintBlendMode.OVERLAY.blend(base, 128)) <= 1)
            assertTrue(kotlin.math.abs(base - TintBlendMode.SOFT_LIGHT.blend(base, 128)) <= 1)
        }
    }

    @Test
    fun lightenPicksTheBrighterOfBaseAndTint() {
        assertEquals(200, TintBlendMode.LIGHTEN.blend(100, 200))
        assertEquals(200, TintBlendMode.LIGHTEN.blend(200, 100))
    }

    @Test
    fun everyModeStaysInByteRangeAcrossTheFullGrid() {
        for (mode in TintBlendMode.entries) {
            for (base in 0..255 step 17) {
                for (color in 0..255 step 17) {
                    val result = mode.blend(base, color)
                    assertTrue("$mode($base,$color)=$result out of range", result in 0..255)
                }
            }
        }
    }
}
