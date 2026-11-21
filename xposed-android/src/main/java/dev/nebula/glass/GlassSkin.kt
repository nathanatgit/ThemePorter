package dev.nebula.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Matrix
import kotlin.math.atan2

/**
 * Liquid Glass "skin" for surfaces that float over a system window blur (notification shade,
 * control centre). Their backdrop is other apps' windows, which no in-process code can sample, so
 * the refracting lens has nothing to bend and would draw an opaque slab. Instead this paints what
 * the lens adds on top of a blur: a translucent body, the two-lobe specular rim on the lens's
 * light axis (upstream: peaks at 30° / 210°, `LightSourceController.DEFAULT_*`) and a soft top
 * sheen. The system blur shows through the body, which gives the frosting. Plain Canvas, no
 * RenderNode recording, so it is cheap enough for dozens of tiles.
 */
class GlassSkin {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val inner = RectF()
    private val m = Matrix()

    /**
     * @param radii 8 floats (TL x,y, TR, BR, BL), as GradientDrawable/Path use
     * @param tint body colour, straight ARGB; alpha is the body opacity
     * @param specular rim strength (1 = default)
     */
    fun draw(c: Canvas, rect: RectF, radii: FloatArray, tint: Int, specular: Float, density: Float) {
        if (rect.width() <= 1f || rect.height() <= 1f) return
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        body.color = tint
        c.drawPath(path, body)

        // Top sheen: light falls in from the upper left.
        val sheenA = (0x1E * specular).toInt().coerceIn(0, 255)
        sheen.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.45f,
            Color.argb(sheenA, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(path, sheen)

        // Rim: two symmetric lobes facing the light (upper left) and its reflection (lower right).
        val sw = (1.1f * density).coerceAtLeast(1f)
        inner.set(rect.left + sw / 2, rect.top + sw / 2, rect.right - sw / 2, rect.bottom - sw / 2)
        path.reset()
        path.addRoundRect(inner, radii, Path.Direction.CW)
        val peak = (0xD0 * specular).toInt().coerceIn(0, 255)
        val low = (0x26 * specular).toInt().coerceIn(0, 255)
        val hi = Color.argb(peak, 255, 255, 255)
        val lo = Color.argb(low, 255, 255, 255)
        // Lobe angle: the light travels along (DEFAULT_X, DEFAULT_Y); the lit edge faces against it.
        val lit = Math.toDegrees(atan2(-LightSourceController.DEFAULT_Y.toDouble(), -LightSourceController.DEFAULT_X.toDouble())).toFloat()
        val g = SweepGradient(rect.centerX(), rect.centerY(),
            intArrayOf(hi, lo, lo, hi, lo, lo, hi), floatArrayOf(0f, 0.12f, 0.38f, 0.5f, 0.62f, 0.88f, 1f))
        m.setRotate(lit, rect.centerX(), rect.centerY())
        g.setLocalMatrix(m)
        rim.shader = g
        rim.strokeWidth = sw
        c.drawPath(path, rim)
    }

    companion object {
        fun uniform(r: Float) = FloatArray(8) { r }
    }
}
