package dev.nebula.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver

/**
 * A background drawable that paints a [LensGlass] over its own bounds, for host views we restyle.
 * [source] supplies the backdrop each frame; when glass cannot draw (no backdrop, live wallpaper,
 * reduced transparency, software canvas) the original [fallback] background is drawn instead.
 *
 * A background drawable lives in its own RenderNode and is only re-recorded when the drawable is
 * invalidated, so a pre-draw check re-records it whenever the host's screen position changes
 * (pages, shade expansion, bouncer, animations), keeping the sampled backdrop aligned.
 */
class GlassDrawable(
    private val host: View,
    val lens: LensGlass,
    private val fallback: Drawable?,
    /** Called before each draw, e.g. to apply the current user settings to [lens]. */
    private val prepare: ((LensGlass) -> Unit)? = null,
    private val source: () -> View?,
) : Drawable() {
    private val rect = RectF()
    private val loc = IntArray(2)
    private var recordedX = Int.MIN_VALUE
    private var recordedY = Int.MIN_VALUE
    private var observer: ViewTreeObserver? = null

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        try {
            if (host.isShown) {
                host.getLocationOnScreen(loc)
                if (loc[0] != recordedX || loc[1] != recordedY) invalidateSelf()
            }
        } catch (_: Throwable) {}
        true
    }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = watch()
            override fun onViewDetachedFromWindow(v: View) = unwatch()
        })
        if (host.isAttachedToWindow) watch()
    }

    private fun watch() {
        if (observer != null) return
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    private fun unwatch() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
    }

    override fun draw(canvas: Canvas) {
        val ok = try {
            prepare?.invoke(lens)
            val src = source()
            rect.set(bounds)
            host.getLocationOnScreen(loc)
            recordedX = loc[0]; recordedY = loc[1]
            src != null && lens.draw(canvas, host, src, rect)
        } catch (t: Throwable) {
            android.util.Log.e("NebulaGlass", "glass drawable", t); false
        }
        if (!ok) fallback?.let { it.bounds = bounds; it.draw(canvas) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
