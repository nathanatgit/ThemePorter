package dev.nebula.glass

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * Programmatic glass panel (no XML attributes). Samples [backdropSource] or its parent.
 * Intended for overlays we add to host trees (launcher dock, folders) and for GlassLab.
 */
class GlassSurfaceView(context: Context) : View(context) {
    val glass = LensGlass()
    var backdropSource: View? = null
        set(value) { field = value; invalidate() }
    /** Redraw every frame; needed when the backdrop animates or scrolls. */
    var live = true

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassRegistry.register(this)
        glass.refreshAccessibility(context)
    }

    override fun onDetachedFromWindow() {
        GlassRegistry.unregister(this)
        glass.release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (GlassRegistry.isCapturing(this)) return
        if (!glass.draw(canvas, this, backdropSource)) glass.drawOpaque(canvas, this)
        if (live) postInvalidateOnAnimation()
    }
}
