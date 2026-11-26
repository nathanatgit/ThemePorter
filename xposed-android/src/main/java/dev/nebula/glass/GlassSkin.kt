package dev.nebula.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Liquid Glass "skin" for surfaces that float over a system window blur (notification shade,
 * control centre, widget board). Supports vivid independent [edgeColor], [glowColor], and rotatable
 * light direction ([lightX], [lightY]).
 */
class GlassSkin {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val inner = RectF()
    private val m = Matrix()

    private fun isCustomColor(c: Int): Boolean = (c and 0x00FFFFFF) != 0x00FFFFFF

    fun draw(
        c: Canvas,
        rect: RectF,
        radii: FloatArray,
        tint: Int,
        specular: Float,
        density: Float,
        edgeColor: Int = Color.WHITE,
        glowColor: Int = Color.WHITE,
        lightX: Float = LightSourceController.DEFAULT_X,
        lightY: Float = LightSourceController.DEFAULT_Y,
    ) {
        if (rect.width() <= 1f || rect.height() <= 1f) return
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        body.color = tint
        c.drawPath(path, body)
        if (specular <= 0f) return

        val gr = Color.red(glowColor); val gg = Color.green(glowColor); val gb = Color.blue(glowColor)
        val er = Color.red(edgeColor); val eg = Color.green(edgeColor); val eb = Color.blue(edgeColor)
        val customGlow = isCustomColor(glowColor)
        val customEdge = isCustomColor(edgeColor)

        // 1. Directional inner sheen + inward rim bloom in glowColor
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfSpan = 0.5f * hypot(rect.width(), rect.height())
        val sheenA = ((if (customGlow) 0x54 else 0x24) * specular).toInt().coerceIn(0, 240)
        sheen.shader = LinearGradient(
            cx - lightX * halfSpan, cy - lightY * halfSpan,
            cx + lightX * 0.30f * halfSpan, cy + lightY * 0.30f * halfSpan,
            Color.argb(sheenA, gr, gg, gb), Color.argb(0, gr, gg, gb), Shader.TileMode.CLAMP,
        )
        c.drawPath(path, sheen)

        // Nested inward glow strokes so square/card edges bloom visibly in glowColor
        val lit = Math.toDegrees(atan2(-lightY.toDouble(), -lightX.toDouble())).toFloat()
        val gPeak = ((if (customGlow) 0x4C else 0x1E) * specular).toInt().coerceIn(0, 220)
        val gLow = ((if (customGlow) 0x1A else 0x06) * specular).toInt().coerceIn(0, 160)
        val gHi = Color.argb(gPeak, gr, gg, gb)
        val gLo = Color.argb(gLow, gr, gg, gb)
        val glowSweep = SweepGradient(cx, cy,
            intArrayOf(gHi, gLo, gLo, gHi, gLo, gLo, gHi), floatArrayOf(0f, 0.16f, 0.34f, 0.5f, 0.66f, 0.84f, 1f))
        m.setRotate(lit, cx, cy)
        glowSweep.setLocalMatrix(m)
        c.save()
        c.clipPath(path)
        rim.shader = glowSweep
        for (width in GLOW_WIDTHS_DP) {
            rim.strokeWidth = (width * density).coerceAtLeast(2f)
            c.drawPath(path, rim)
        }
        c.restore()

        // 2. Crisp specular hairline rim in edgeColor
        val sw = ((if (customEdge) 1.55f else 1.15f) * density).coerceAtLeast(1.4f)
        inner.set(rect.left + sw / 2, rect.top + sw / 2, rect.right - sw / 2, rect.bottom - sw / 2)
        path.reset()
        path.addRoundRect(inner, radii, Path.Direction.CW)
        val peak = ((if (customEdge) 0xEE else 0xD2) * specular).toInt().coerceIn(0, 255)
        val low = ((if (customEdge) 0x52 else 0x28) * specular).toInt().coerceIn(0, 255)
        val hi = Color.argb(peak, er, eg, eb)
        val lo = Color.argb(low, er, eg, eb)
        val edgeSweep = SweepGradient(cx, cy,
            intArrayOf(hi, lo, lo, hi, lo, lo, hi), floatArrayOf(0f, 0.16f, 0.34f, 0.5f, 0.66f, 0.84f, 1f))
        m.setRotate(lit, cx, cy)
        edgeSweep.setLocalMatrix(m)
        rim.shader = edgeSweep
        rim.strokeWidth = sw
        c.drawPath(path, rim)
    }

    fun drawPanel(
        c: Canvas,
        rect: RectF,
        radii: FloatArray,
        fill: Int,
        strength: Float,
        density: Float,
        edgeColor: Int = Color.WHITE,
        glowColor: Int = Color.WHITE,
        lightX: Float = LightSourceController.DEFAULT_X,
        lightY: Float = LightSourceController.DEFAULT_Y,
    ) {
        if (rect.width() <= 1f || rect.height() <= 1f) return
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        body.color = fill
        c.drawPath(path, body)
        if (strength <= 0f) return

        val gr = Color.red(glowColor); val gg = Color.green(glowColor); val gb = Color.blue(glowColor)
        val er = Color.red(edgeColor); val eg = Color.green(edgeColor); val eb = Color.blue(edgeColor)
        val customGlow = isCustomColor(glowColor)
        val customEdge = isCustomColor(edgeColor)

        fun tintAlpha(a: Int, r: Int, g: Int, b: Int) = Color.argb((a * strength).toInt().coerceIn(0, 255), r, g, b)

        val cx = rect.centerX()
        val cy = rect.centerY()
        val hw = rect.width() / 2f
        val hh = rect.height() / 2f
        val span = (kotlin.math.abs(lightX) * hw + kotlin.math.abs(lightY) * hh).coerceAtLeast(1f)
        val sx = cx - lightX * span
        val sy = cy - lightY * span
        val ex = cx + lightX * span
        val ey = cy + lightY * span
        val reach = (maxOf(radii.max(), 6 * density) / (2f * span)).coerceIn(0.06f, 0.48f)
        val stops = floatArrayOf(0f, reach, 1f - reach, 1f)
        fun gradient(front: Int, mid: Int, back: Int, r: Int, g: Int, b: Int) = LinearGradient(
            sx, sy, ex, ey,
            intArrayOf(tintAlpha(front, r, g, b), tintAlpha(mid, r, g, b), tintAlpha(mid, r, g, b), tintAlpha(back, r, g, b)),
            stops, Shader.TileMode.CLAMP,
        )

        // 1. Inner glow: wider nested strokes + subtle directional sheen when custom glowColor is active!
        if (customGlow) {
            sheen.shader = LinearGradient(
                sx, sy, cx + lightX * 0.25f * span, cy + lightY * 0.25f * span,
                tintAlpha(0x36, gr, gg, gb), Color.argb(0, gr, gg, gb), Shader.TileMode.CLAMP,
            )
            c.drawPath(path, sheen)
        }
        c.save()
        c.clipPath(path)
        rim.shader = if (customGlow) gradient(0x46, 0x16, 0x38, gr, gg, gb) else gradient(0x1E, 0x06, 0x18, gr, gg, gb)
        for (width in GLOW_WIDTHS_DP) {
            rim.strokeWidth = (width * density).coerceAtLeast(2f)
            c.drawPath(path, rim)
        }
        c.restore()

        // 2. Crisp hairline rim in edgeColor (minimum 1.5 px so previews and high-DPI tiles show vivid colour!).
        val sw = ((if (customEdge) 1.45f else 1.0f) * density).coerceAtLeast(1.5f)
        inner.set(rect.left + sw / 2, rect.top + sw / 2, rect.right - sw / 2, rect.bottom - sw / 2)
        path.reset()
        path.addRoundRect(inner, radii, Path.Direction.CW)
        rim.shader = if (customEdge) gradient(0xE8, 0x58, 0xC8, er, eg, eb) else gradient(0x92, 0x2E, 0x74, er, eg, eb)
        rim.strokeWidth = sw
        c.drawPath(path, rim)
    }

    fun draw(c: Canvas, rect: RectF, radii: FloatArray, tint: Int, specular: Float, density: Float, st: GlassStyle) =
        draw(c, rect, radii, tint, specular, density, st.edgeRgb, st.glowRgb, st.lightX, st.lightY)

    fun drawPanel(c: Canvas, rect: RectF, radii: FloatArray, fill: Int, strength: Float, density: Float, st: GlassStyle) =
        drawPanel(c, rect, radii, fill, strength, density, st.edgeRgb, st.glowRgb, st.lightX, st.lightY)

    companion object {
        private val GLOW_WIDTHS_DP = floatArrayOf(3.5f, 8f, 14f, 22f)

        fun uniform(r: Float) = FloatArray(8) { r }

        fun tileCorner(r: Float, w: Float, h: Float): Float {
            val short = minOf(w, h)
            val long = maxOf(w, h)
            return minOf(r, if (long >= short * 1.5f) short / 2 else short / 4)
        }
    }
}
