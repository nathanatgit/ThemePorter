package com.nathanhanapps.nebulaThemePorter.core

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayscaleCurveTest {
    @Test
    fun identityCurvePassesLevelsThroughUnchanged() {
        val lut = GrayscaleCurve().lut()
        assertEquals(0, lut[0])
        assertEquals(128, lut[128])
        assertEquals(255, lut[255])
    }

    @Test
    fun twoPointCurveIsLinear() {
        val lut = GrayscaleCurve(listOf(CurvePoint(0f, 0.25f), CurvePoint(1f, 0.75f))).lut()
        assertEquals(64, lut[0])
        assertEquals(191, lut[255])
        assertEquals(128, lut[128])
    }

    @Test
    fun curveStaysMonotonicThroughASteepMidpoint() {
        val lut = GrayscaleCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.9f), CurvePoint(1f, 1f))).lut()
        for (i in 1 until lut.size) assertTrue("lut[$i]=${lut[i]} < lut[${i - 1}]=${lut[i - 1]}", lut[i] >= lut[i - 1])
    }

    @Test
    fun endpointsMapExactlyToTheirOwnY() {
        val lut = GrayscaleCurve(listOf(CurvePoint(0f, 0.1f), CurvePoint(0.3f, 0.6f), CurvePoint(1f, 0.9f))).lut()
        assertEquals((0.1f * 255f).roundToInt(), lut[0])
        assertEquals((0.9f * 255f).roundToInt(), lut[255])
    }

    /**
     * Normalize shifts an icon by offsetting its curve's control points rather than rebuilding the curve, so
     * the shape the user drew survives. That only holds because the Hermite tangents come from secants, which
     * depend on differences between y values and so don't change when every y moves by the same amount.
     */
    @Test
    fun offsettingEveryControlPointShiftsTheWholeCurveByThatAmount() {
        val shaped = GrayscaleCurve(
            listOf(CurvePoint(0f, 0.1f), CurvePoint(0.3f, 0.2f), CurvePoint(0.7f, 0.72f), CurvePoint(1f, 0.8f)),
        )
        val delta = 0.15f
        val shifted = GrayscaleCurve(shaped.points.map { CurvePoint(it.x, (it.y + delta).coerceIn(0f, 1f)) })

        val before = shaped.lut()
        val after = shifted.lut()
        val step = (delta * 255f).roundToInt()
        for (level in 0..255) {
            // One unit of slack: both sides round to ints independently.
            assertTrue(
                "level $level moved by ${after[level] - before[level]}, expected about $step",
                kotlin.math.abs((after[level] - before[level]) - step) <= 1,
            )
        }
    }

    @Test
    fun offsettingClampsInsteadOfWrappingWhenItRunsOutOfRange() {
        val bright = GrayscaleCurve(listOf(CurvePoint(0f, 0.5f), CurvePoint(1f, 0.95f)))
        val shifted = GrayscaleCurve(bright.points.map { CurvePoint(it.x, (it.y + 0.4f).coerceIn(0f, 1f)) })

        val lut = shifted.lut()
        assertEquals(255, lut[255])
        assertTrue(lut.all { it in 0..255 })
        // Still monotonic after the top end flattens against white.
        assertTrue((1..255).all { lut[it] >= lut[it - 1] })
    }
}
