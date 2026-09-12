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
}
