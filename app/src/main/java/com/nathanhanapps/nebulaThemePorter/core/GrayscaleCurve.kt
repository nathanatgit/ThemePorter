package com.nathanhanapps.nebulaThemePorter.core

import kotlin.math.hypot
import kotlin.math.roundToInt

/** One draggable control point on a [GrayscaleCurve], both axes normalized to 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/**
 * A user-edited tone curve remapping grayscale levels before [BuildOptions.fixedTintColor] multiplies them -
 * lets a batch of icons that started out differently lit still land on a consistent brightness once tinted.
 * [points] are sorted by x; by convention the first and last points stay pinned to x=0 and x=1 so the curve
 * always covers the full input range, matching how every other curve editor (Photoshop, Lightroom, ...) works.
 */
data class GrayscaleCurve(val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) {
    val isIdentity: Boolean
        get() = points.size == 2 && points[0] == CurvePoint(0f, 0f) && points[1] == CurvePoint(1f, 1f)

    /**
     * Samples a monotonic cubic Hermite spline (Fritsch-Carlson tangent limiting, so the curve never overshoots
     * and wiggles between control points) through [points] into a 256-entry 0..255 lookup table.
     */
    fun lut(): IntArray {
        if (isIdentity) return IntArray(256) { it }
        val sorted = points.sortedBy { it.x }
        val n = sorted.size
        val xs = FloatArray(n) { sorted[it].x }
        val ys = FloatArray(n) { sorted[it].y }
        val secants = FloatArray(n - 1) { i -> (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]).coerceAtLeast(1e-6f) }
        val tangents = FloatArray(n)
        tangents[0] = secants[0]
        tangents[n - 1] = secants[n - 2]
        for (i in 1 until n - 1) tangents[i] = (secants[i - 1] + secants[i]) / 2f
        for (i in 0 until n - 1) {
            if (secants[i] == 0f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
            } else {
                val a = tangents[i] / secants[i]
                val b = tangents[i + 1] / secants[i]
                val h = hypot(a.toDouble(), b.toDouble()).toFloat()
                if (h > 3f) {
                    val t = 3f / h
                    tangents[i] = t * a * secants[i]
                    tangents[i + 1] = t * b * secants[i]
                }
            }
        }
        return IntArray(256) { level ->
            val x = level / 255f
            val segment = (0 until n - 1).lastOrNull { xs[it] <= x } ?: 0
            val x0 = xs[segment]
            val x1 = xs[segment + 1]
            val y0 = ys[segment]
            val y1 = ys[segment + 1]
            val h = (x1 - x0).coerceAtLeast(1e-6f)
            val t = ((x - x0) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2 * t3 - 3 * t2 + 1
            val h10 = t3 - 2 * t2 + t
            val h01 = -2 * t3 + 3 * t2
            val h11 = t3 - t2
            val y = h00 * y0 + h10 * h * tangents[segment] + h01 * y1 + h11 * h * tangents[segment + 1]
            (y * 255f).roundToInt().coerceIn(0, 255)
        }
    }
}
