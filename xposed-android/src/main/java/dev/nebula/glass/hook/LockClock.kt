package dev.nebula.glass.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.nebula.glass.ClockFace
import dev.nebula.glass.ClockFonts
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.GlassTextHost
import dev.nebula.glass.WallpaperBackdrop
import java.util.WeakHashMap

/**
 * The resized clock on Nubia's own `clock_view`, and on the digit rows of the other clock styles
 * (`hours`, `minute`, ...). The TextView keeps its layout box, which Nubia's notification limit is
 * measured from, and the digits draw past it, iOS-style ([ClockFace]: taller at the same stroke).
 * [LockArrange] and [LockEditor] say how tall; [KeyguardText] calls [draw] from its onDraw hook.
 */
internal object LockClock {
    /** The clock's box in px: [height] is the most digit height, [width] the most width. */
    private data class Spec(val height: Float, val width: Float, val boldness: Int, val gravity: Int)

    private val specs = WeakHashMap<TextView, Spec>()
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Where the digits' top sits, below the top of the TextView's box. */
    const val TOP_INSET = 8f

    /** [height] null = Nubia's own clock; [width] the box's width in px. */
    fun set(tv: TextView, height: Float?, boldness: Int, width: Float, gravity: Int = android.view.Gravity.CENTER_HORIZONTAL) {
        // The editor's style thumbnails keep Nubia's own small clock.
        val next = height?.takeIf { !GlassTextHost.inList(tv) }?.let { Spec(it, width, boldness, gravity) }
        if (specs[tv] == next) return
        if (next == null) specs.remove(tv) else {
            specs[tv] = next
            // A view's own clipping follows its parent's clipChildren, so freeing clock_area and
            // depth_down_area takes their parents' too, up to the screen-sized ConstraintLayout.
            var child: View = tv
            // A fade through an offscreen layer is clipped to the view's box: fade draw by draw instead.
            child.forceHasOverlappingRendering(false)
            // Up to and including the screen-sized container (its clipping cuts its children): the
            // drawn digits can sit above or below a row's own box.
            val screen = tv.rootView.height
            for (level in 0 until 8) {
                val parent = child.parent as? ViewGroup ?: break
                if (parent.clipChildren) {
                    parent.clipChildren = false
                    parent.clipToPadding = false
                }
                parent.forceHasOverlappingRendering(false)
                parent.invalidate()
                if (screen > 0 && parent.height >= screen * 0.6f) break
                child = parent
            }
        }
        tv.invalidate()
    }

    /**
     * True when the resized clock drew, so the stock draw must be skipped.
     * @param glass the style to draw it as glass with, or null for plain digits
     */
    /**
     * The face for [tv], in the font picked in Nubia's editor so it matches the editor's previews:
     * the official variable version for Rubik and Oswald ([ClockFonts]), Nubia's own font otherwise.
     */
    private fun face(tv: TextView, height: Float, width: Float, boldness: Int, text: String): ClockFace? {
        // The font picked in Nubia's editor: its variable version where there is one, else itself.
        val name = nubiaFont(tv)
        val variable = ClockFonts.forNubia(name)?.let { ClockFonts.load(it) }
        if (variable != null) return ClockFace.of(variable, height, boldness, width, text)
        // Nubia's line digits are drawn as their lines, as in its previews.
        return ClockFace.ofFont(tv.typeface ?: return null, height, boldness, width, text, lineFont = name == "zline")
    }

    /** Nubia's name for the font on [tv] ("zdigit", "zoswald", ...), from its editor's font lists. */
    private val nubiaNames = WeakHashMap<android.graphics.Typeface, String>()

    private fun nubiaFont(tv: TextView): String? {
        val face = tv.typeface ?: return null
        nubiaNames[face]?.let { return it.ifEmpty { null } }
        val found = try {
            val util = Class.forName("com.zte.mifavor.keyguard.personalclock.FontResUtil", true, tv.context.classLoader)
            var name: String? = null
            fun check(item: Any?) {
                if (item == null || name != null) return
                if (item.javaClass.getField("typeface").get(item) == face) name = item.javaClass.getField("name").get(item) as? String
            }
            (util.getField("TEXT_FONT_ARRAYS").get(null) as? Array<*>)?.forEach { list -> (list as? Array<*>)?.forEach(::check) }
            if (name == null) (util.getField("DUAL_CLOCK_FONT_MAP").get(null) as? Map<*, *>)?.forEach { (k, v) -> if (name == null && v == face) name = k as? String }
            name
        } catch (t: Throwable) {
            android.util.Log.w("NebulaGlass", "nubia clock font", t); null
        }
        android.util.Log.i("NebulaGlass", "nubia clock font on ${tv.javaClass.simpleName}: $found")
        nubiaNames[face] = found ?: ""
        return found
    }

    /** The digit height actually drawn for [height]: less when the font would be wider than the screen. */
    fun fitted(tv: TextView, height: Float, width: Float, boldness: Int): Float {
        val text = tv.text?.toString()?.takeIf { it.isNotEmpty() } ?: return height
        return face(tv, height, width, boldness, text)?.height ?: height
    }

    /** Width and height of the digits [tv] draws in that box, or null when it has no text or face. */
    fun drawnSize(tv: TextView, height: Float, width: Float, boldness: Int): Pair<Float, Float>? {
        val text = tv.text?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        val face = face(tv, height, width, boldness, text) ?: return null
        return face.shape(text).bounds.width() to face.height
    }

    fun draw(tv: TextView, canvas: Canvas, glass: GlassStyle?, host: GlassTextHost, backdrop: () -> WallpaperBackdrop): Boolean {
        val spec = specs[tv] ?: return false
        val text = tv.text?.toString()?.takeIf { it.isNotEmpty() } ?: return false
        val face = face(tv, spec.height, spec.width, spec.boldness, text) ?: return false
        val shape = face.shape(text)
        // Digits shorter than the box (a font without a height axis) sit in its middle (user request 2026-11-24).
        val top = LockClock.TOP_INSET + maxOf(0f, (spec.height - face.height) / 2)
        // Where Nubia aligns the row: centred (the one-line clock), else from its left or right edge.
        val b = shape.bounds
        val x = when (android.view.Gravity.getAbsoluteGravity(spec.gravity, tv.layoutDirection) and android.view.Gravity.HORIZONTAL_GRAVITY_MASK) {
            android.view.Gravity.LEFT -> tv.totalPaddingLeft - b.left
            android.view.Gravity.RIGHT -> tv.width - tv.totalPaddingRight - b.right
            else -> tv.width / 2f
        }
        if (glass != null && host.drawShape(tv, canvas, backdrop(), glass, face, shape, x, top, neutral = true)) return true
        // No glass, or its distance field is still being built.
        val save = canvas.save()
        canvas.translate(tv.scrollX + x, tv.scrollY + top)
        solid.color = tv.currentTextColor
        canvas.drawPath(shape.path, solid)
        canvas.restoreToCount(save)
        return true
    }
}
