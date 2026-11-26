package dev.nebula.glass

import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Draws a host TextView's text as [GlassText], aligned with the host's own layout.
 * Shared by the lock-screen clock/date hook and the launcher clock widget hook.
 */
class GlassTextHost {
    private val states = WeakHashMap<TextView, GlassText>()
    private val dbg = WeakHashMap<TextView, Boolean>()
    private val loc = IntArray(2)
    private val shapes = WeakHashMap<TextView, GlassText>()
    private val area = android.graphics.Rect()

    fun draw(
        tv: TextView,
        canvas: Canvas,
        backdrop: WallpaperBackdrop,
        style: GlassStyle,
        neutral: Boolean = false,
        filterTint: Int? = null,
    ): Boolean {
        val layout = tv.layout ?: return false
        if (layout.lineCount < 1 || tv.width <= 0) return false
        if (backdrop.isLive || backdrop.bitmap == null) return false
        val root = tv.rootView
        if (root.width > 0) backdrop.layoutTo(root.width, root.height)
        val gt = states.getOrPut(tv) {
            GlassText().apply { onReady = { tv.postInvalidate() } }
        }
        if (!gt.isAvailable) return false
        val size = tv.paint.textSize
        val luma = lumaBehind(tv, backdrop)
        gt.bevelPx = null
        gt.refractPx = null
        val baseColor = when {
            filterTint != null && !style.tinted -> filterTint
            neutral -> Color.WHITE
            else -> tv.currentTextColor
        }
        val body = gt.tuneFor(size, baseColor, luma, style.glow, style.refraction, style.dispersion, style.vibrancy)
        val p = plain(tv, neutral)
        gt.setText(layout.text, p)
        // Never draw a stale SDF from an earlier font size while resizing a text box: let live vector text draw at 120 fps until ready.
        if (!gt.hasText(layout.text, p)) return false
        val colour = when {
            !style.tinted || size >= SMALL_TINT_PX -> style.color
            Color.luminance(baseColor or 0xFF000000.toInt()) > 0.5f -> Looks.toned(style.color, 0.78f, 1f)
            else -> Looks.toned(style.color, 0f, 0.08f)
        }
        gt.tint = style.body(body, colour, maxOf(GlassStyle.TINT_ALPHA, body ushr 24))
        gt.edgeColor = style.edgeRgb
        gt.glowColor = style.glowRgb
        gt.lightX = style.lightX
        gt.lightY = style.lightY
        gt.dim = 0f
        gt.blurRadius = style.blurPx(BLUR_PX)
        val boxH = tv.height - tv.extendedPaddingTop - tv.extendedPaddingBottom
        val vOff = when (tv.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> 0
            Gravity.BOTTOM -> boxH - layout.height
            else -> (boxH - layout.height) / 2
        }.coerceAtLeast(0)
        val boxW = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
        val lineW = layout.getLineWidth(0)
        val hOff = when (Gravity.getAbsoluteGravity(tv.gravity, tv.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT -> boxW - lineW
            Gravity.CENTER_HORIZONTAL -> (boxW - lineW) / 2f
            else -> 0f
        }
        val x = tv.compoundPaddingLeft + hOff
        val baseline = tv.extendedPaddingTop + vOff + layout.getLineBaseline(0).toFloat()
        val fm = tv.paint.fontMetrics
        area.set((x - AREA_PAD).toInt(), (baseline + fm.ascent).toInt() - AREA_PAD,
            (x + lineW).toInt() + AREA_PAD, (baseline + fm.descent).toInt() + AREA_PAD)
        if (area.left > 0 && area.top > 0 && area.right < tv.width && area.bottom < tv.height) area.set(0, 0, tv.width, tv.height)
        val save = canvas.save()
        canvas.translate(tv.scrollX.toFloat(), tv.scrollY.toFloat())
        val ok = try { gt.draw(canvas, tv, backdrop, x, baseline, area) } finally { canvas.restoreToCount(save) }
        if (dbg.put(tv, ok) != ok) Log.d(TAG, "glass text ${idName(tv)} ok=$ok ready=${gt.isReady} ${tv.width}x${tv.height} size=$size")
        return ok
    }

    fun drawShape(
        tv: TextView,
        canvas: Canvas,
        backdrop: WallpaperBackdrop,
        style: GlassStyle,
        face: ClockFace,
        shape: ClockFace.Shape,
        x: Float,
        top: Float,
        neutral: Boolean = false,
        filterTint: Int? = null,
    ): Boolean {
        if (backdrop.isLive || backdrop.bitmap == null) return false
        val root = tv.rootView
        if (root.width > 0) backdrop.layoutTo(root.width, root.height)
        val gt = shapes.getOrPut(tv) { GlassText().apply { onReady = { tv.postInvalidate() } } }
        if (!gt.isAvailable) return false
        val baseColor = when {
            filterTint != null && !style.tinted -> filterTint
            neutral -> Color.WHITE
            else -> tv.currentTextColor
        }
        val body = gt.tuneFor(face.height, baseColor, lumaBehind(tv, backdrop), style.glow, style.refraction, style.dispersion, style.vibrancy)
        gt.tint = style.body(body, fullAlpha = maxOf(GlassStyle.TINT_ALPHA, body ushr 24))
        gt.edgeColor = style.edgeRgb
        gt.glowColor = style.glowRgb
        gt.lightX = style.lightX
        gt.lightY = style.lightY
        gt.dim = 0f
        gt.blurRadius = style.blurPx(BLUR_PX)
        gt.setShape(shape.path, shape.key, face.stroke, style.refraction)
        if (!gt.hasShape(shape.key)) return false
        val b = shape.bounds
        area.set((x + b.left).toInt() - AREA_PAD, (top + b.top).toInt() - AREA_PAD,
            (x + b.right).toInt() + AREA_PAD, (top + b.bottom).toInt() + AREA_PAD)
        val save = canvas.save()
        canvas.translate(tv.scrollX.toFloat(), tv.scrollY.toFloat())
        return try { gt.draw(canvas, tv, backdrop, x, top, area) } finally { canvas.restoreToCount(save) }
    }

    private fun plain(tv: TextView, neutral: Boolean): android.text.TextPaint {
        if (!neutral) return tv.paint
        return android.text.TextPaint(tv.paint).apply { clearShadowLayer(); shader = null }
    }

    private fun lumaBehind(v: View, b: WallpaperBackdrop): Float {
        val bmp = b.bitmap ?: return 0.5f
        val root = v.rootView
        if (root.width <= 0 || root.height <= 0 || bmp.isRecycled) return 0.5f
        v.getLocationOnScreen(loc)
        val sx = bmp.width / root.width.toFloat()
        val sy = bmp.height / root.height.toFloat()
        var sum = 0f
        var n = 0
        for (i in 0 until 6) for (j in 0 until 3) {
            val x = ((loc[0] + v.width * (i + 0.5f) / 6f) * sx).toInt().coerceIn(0, bmp.width - 1)
            val y = ((loc[1] + v.height * (j + 0.5f) / 3f) * sy).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(x, y)
            sum += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
            n++
        }
        return sum / n
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SMALL_TINT_PX = 100f
        private const val AREA_PAD = 8
        private const val BLUR_PX = 20f

        fun idName(v: View): String? = try {
            if (v.id == View.NO_ID) null else v.resources.getResourceEntryName(v.id)
        } catch (_: Exception) { null }

        fun inList(v: View): Boolean {
            var p = v.parent
            while (p is View) {
                if (isList(p)) return true
                p = p.parent
            }
            return false
        }

        private fun isList(v: View): Boolean {
            var c: Class<*>? = v.javaClass
            while (c != null && c != View::class.java) {
                if (c.simpleName == "RecyclerView") return true
                c = c.superclass
            }
            return false
        }

        fun findOutsideLists(root: View, id: Int): View? {
            if (root.id == id) return root
            if (root !is android.view.ViewGroup || isList(root)) return null
            for (i in 0 until root.childCount) findOutsideLists(root.getChildAt(i), id)?.let { return it }
            return null
        }
    }
}
