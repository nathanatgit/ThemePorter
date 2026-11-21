package dev.nebula.glass

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * Backdrop source 3 (docs/LIQUID_GLASS_PORT.md §3): the wallpaper with in-window content on top,
 * in screen coordinates. Used for glass that floats over launcher icons (popups, sheets).
 *
 * Unattached, like [WallpaperBackdrop]. Set [glass] to the view being drawn before each lens draw;
 * its branch of [content] is skipped so the glass never samples itself.
 */
class CompositeBackdrop(context: Context, private val wallpaper: WallpaperBackdrop) : View(context) {
    var content: View? = null
    var glass: View? = null
    private val capture = BackdropCapture()
    private val loc = IntArray(2)

    fun layoutTo(width: Int, height: Int) {
        wallpaper.layoutTo(width, height)
        if (width == this.width && height == this.height) return
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    override fun onDraw(canvas: Canvas) {
        wallpaper.draw(canvas)
        val c = content ?: return
        val g = glass ?: return
        c.getLocationOnScreen(loc)
        val save = canvas.save()
        canvas.translate(loc[0].toFloat(), loc[1].toFloat())
        capture.draw(canvas, c, g)
        canvas.restoreToCount(save)
    }
}
