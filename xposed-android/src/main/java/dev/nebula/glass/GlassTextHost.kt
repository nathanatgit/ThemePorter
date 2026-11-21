package dev.nebula.glass

import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Draws a host TextView's single-line text as [GlassText], aligned with the host's own layout.
 * Shared by the lock-screen clock/date hook and the launcher clock widget hook.
 *
 * Call from a `TextView.onDraw` before-hook; when [draw] returns true the stock draw must be skipped.
 * Returns false (draw stock) whenever glass cannot draw, so the text is never blank.
 */
class GlassTextHost {
    private val states = WeakHashMap<TextView, GlassText>()
    private val dbg = WeakHashMap<TextView, Boolean>()
    private val loc = IntArray(2)

    fun draw(tv: TextView, canvas: Canvas, backdrop: WallpaperBackdrop, style: GlassStyle): Boolean {
        val layout = tv.layout ?: return false
        if (layout.lineCount < 1 || tv.width <= 0) return false
        if (backdrop.isLive || backdrop.bitmap == null) return false
        val root = tv.rootView
        if (root.width > 0) backdrop.layoutTo(root.width, root.height)
        val gt = states.getOrPut(tv) {
            GlassText().apply { onReady = { tv.postInvalidate() } }
        }
        if (!gt.isAvailable) return false
        // Upstream scales refraction/bevel with text size; on a 300 px clock's thin strokes that swallows the glyphs. Cap in px.
        val size = tv.paint.textSize
        gt.refractFactor = minOf(0.45f, 28f / size)
        gt.bevelFactor = minOf(0.02f, 8f / size)
        gt.setText(layout.text, tv.paint)
        // Glass shows the wallpaper through the glyphs: on a bright sky it vanishes, so darken the body with the backdrop.
        val luma = lumaBehind(tv, backdrop)
        // Follow the host's colour (the lock-screen clock/calendar colour setting, the widget's text colour):
        // the glass body is the refracted wallpaper tinted towards it. No luma darkening: it turned white grey.
        // A user accent replaces the text colour.
        val c = tv.currentTextColor
        gt.tint = if (style.accent != GlassStyle.AUTO) style.accent
            else Color.argb((TINT * Color.alpha(c)).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        gt.dim = 0f
        gt.specStrength = (if (luma > 0.5f) 1.6f else 2.49f) * style.edge / 100f
        gt.blurRadius = style.blurPx(BLUR_PX)
        val boxH = tv.height - tv.extendedPaddingTop - tv.extendedPaddingBottom
        val vOff = when (tv.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> 0
            Gravity.BOTTOM -> boxH - layout.height
            else -> (boxH - layout.height) / 2
        }.coerceAtLeast(0)
        // getLineLeft is unusable: TextClocks lay out in a huge centred box (x ~ 5e5), so align by gravity.
        val boxW = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
        val lineW = layout.getLineWidth(0)
        val hOff = when (Gravity.getAbsoluteGravity(tv.gravity, tv.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT -> boxW - lineW
            Gravity.CENTER_HORIZONTAL -> (boxW - lineW) / 2f
            else -> 0f
        }
        val x = tv.compoundPaddingLeft + hOff
        val baseline = tv.extendedPaddingTop + vOff + layout.getLineBaseline(0).toFloat()
        // View.draw has already applied translate(-scrollX, -scrollY), and that huge layout box is
        // scrolled into view, so undo it and draw in view-local coordinates.
        val save = canvas.save()
        canvas.translate(tv.scrollX.toFloat(), tv.scrollY.toFloat())
        val ok = try { gt.draw(canvas, tv, backdrop, x, baseline) } finally { canvas.restoreToCount(save) }
        if (dbg.put(tv, ok) != ok) Log.d(TAG, "glass text ${idName(tv)} ok=$ok ready=${gt.isReady} ${tv.width}x${tv.height} scroll=${tv.scrollX} luma=$luma color=#${Integer.toHexString(c)} shader=${tv.paint.shader}")
        return ok
    }

    /** Mean luma (0..1) of the wallpaper behind [v], sampled on a coarse grid; 0.5 if unknown. */
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
        /** Share of the host text colour in the glass body; the rest is refracted wallpaper. */
        private const val TINT = 0.62f
        /** Built-in blur of the light seen through the glyphs (upstream default). */
        private const val BLUR_PX = 20f

        fun idName(v: View): String? = try {
            if (v.id == View.NO_ID) null else v.resources.getResourceEntryName(v.id)
        } catch (_: Exception) { null }
    }
}
