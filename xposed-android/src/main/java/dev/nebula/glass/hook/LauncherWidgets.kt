package dev.nebula.glass.hook

import android.appwidget.AppWidgetHostView
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassDrawable
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import java.util.WeakHashMap

/**
 * Glass backgrounds for home-screen app widgets ([Surface.HOME_WIDGET]).
 *
 * Trace (docs/LIQUID_GLASS_PORT.md): widget hosts are `AppWidgetHostView` subclasses (obfuscated
 * `launcher3.widget.U`) whose host background is the launcher's press/focus StateListDrawable.
 * Widgets paint their card themselves: a flat root background (calendar: ColorDrawable) or flat
 * panel images (Microsoft To Do: ImageView + GradientDrawable). Image-only widgets (a single bitmap
 * with tiles baked in) have no separable card and are left alone, so no shape ever changes.
 *
 * For each top-level widget host: a glass panel is layered under the host's own background, inset
 * to the widget's content and using the widget's corner radius; the widget's flat card layers are
 * hidden so the glass shows. RemoteViews updates re-apply the widget, so styling is re-run after
 * every update and layout. Everything is restorable (settings off restores the originals).
 */
class LauncherWidgets : IXposedHookLoadPackage {
    private class Styled(
        val hostBackground: Drawable?,
        var glass: GlassDrawable? = null,
        var insets: IntArray? = null,
        val hiddenBackgrounds: WeakHashMap<View, Drawable> = WeakHashMap(),
        val hiddenImages: WeakHashMap<ImageView, Int> = WeakHashMap(),
    )

    private val hosts = WeakHashMap<AppWidgetHostView, Styled>()

    /** Inner flat elements (task rows, day cells, chips) drawn as glass skins, by drawable identity. */
    private class Inner(view: View, host: AppWidgetHostView) {
        val view = java.lang.ref.WeakReference(view)
        val host = java.lang.ref.WeakReference(host)
    }
    private val inner = WeakHashMap<Drawable, Inner>()
    private val innerDraws = HashSet<java.lang.reflect.Method>()
    private val skin = GlassSkin()
    private val skinRect = android.graphics.RectF()
    private var attached = false

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != LAUNCHER || app.processName != LAUNCHER) return
        HookSettings.load()
        GlassSettings.addListener {
            for (h in hosts.keys.toList()) guard { style(h) }
            for (t in inner.values.toList()) t.view.get()?.invalidate()   // their skins are cached draws
        }
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as? AppWidgetHostView ?: return
                    if (isTopLevel(v)) v.post { guard { track(v) } }
                    else v.post { guard { topHost(v)?.let { h -> if (hosts[h]?.glass != null) styleInner(h, v) } } }
                }
            })
            XposedHelpers.findAndHookMethod(AppWidgetHostView::class.java, "updateAppWidget", RemoteViews::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as AppWidgetHostView
                        if (hosts.containsKey(v)) v.post { guard { style(v) } }
                    }
                })
            Log.i(TAG, "widget glass hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "widget hooks failed", t)
        }
    }

    private fun track(host: AppWidgetHostView) {
        if (hosts.containsKey(host)) return
        hosts[host] = Styled(host.background)
        if (!attached) { attached = true; HookSettings.attach(host.context) }
        host.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> guard { style(v as AppWidgetHostView) } }
        WallpaperBackdrop.sharedBlurred(host.context).addListener { guard { style(host) } }
        style(host)
    }

    /** (Re)apply or remove the glass for one widget host to match the current settings. */
    private fun style(host: AppWidgetHostView) {
        val state = hosts[host] ?: return
        val st = GlassSettings[Surface.HOME_WIDGET]
        val content = host.getChildAt(0)
        val backdrop = WallpaperBackdrop.sharedBlurred(host.context)
        val usable = st.enabled && content != null && content.width > 0 && !backdrop.isLive && backdrop.bitmap != null
        val panels = if (usable) findPanels(content!!, state) else emptyList()
        // Picture-only widgets (one bitmap, e.g. KWGT) have no separable card: left stock (user decision).
        if (!usable || panels.isEmpty()) { restore(host, state); return }

        // Hide the widget's own flat card layers (re-applied RemoteViews bring them back each update).
        for (v in panels) {
            if (v is ImageView && isFlat(v.drawable)) {
                if (!state.hiddenImages.containsKey(v)) state.hiddenImages[v] = v.imageAlpha
                v.imageAlpha = 0
            } else if (isFlat(v.background) && v.background !== state.hiddenBackgrounds[v]) {
                state.hiddenBackgrounds[v] = v.background
                v.background = null
            }
        }

        // Glass under the host's own press/focus background, inset to the widget content.
        val insets = intArrayOf(content!!.left, content.top, host.width - content.right, host.height - content.bottom)
        if (state.glass == null || !insets.contentEquals(state.insets)) {
            val dp = host.resources.displayMetrics.density
            val corner = cornerOf(panels) ?: outlineRadius(content) ?: outlineRadius(host) ?: systemWidgetRadius(host) ?: (16 * dp)
            val glass = GlassDrawable(host, Looks.base(Surface.HOME_WIDGET, host.context, night(host)), null,
                prepare = { l ->
                    val s = GlassSettings[Surface.HOME_WIDGET]
                    Looks.style(l, Surface.HOME_WIDGET, dp, corner, night(host), preBlurred = s.blur == 100, st = s)
                }) {
                val s = GlassSettings[Surface.HOME_WIDGET]
                if (!s.enabled) null
                else (if (s.blur == 100) WallpaperBackdrop.sharedBlurred(host.context) else WallpaperBackdrop.shared(host.context))
                    .also { b -> host.rootView.takeIf { it.width > 0 }?.let { b.layoutTo(it.width, it.height) } }
            }
            val layers = listOfNotNull(InsetDrawable(glass, insets[0], insets[1], insets[2], insets[3]), state.hostBackground)
            state.glass = glass
            state.insets = insets
            // A background with insets would change the host's padding and shift the widget: keep it.
            val pad = intArrayOf(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom)
            host.background = LayerDrawable(layers.toTypedArray())
            host.setPadding(pad[0], pad[1], pad[2], pad[3])
            Log.i(TAG, "widget glass on ${host.javaClass.name} ${content.width}x${content.height} panels=${panels.size} corner=$corner")
        } else {
            state.glass?.invalidateSelf()
        }
        styleInner(host, content)
    }

    /**
     * Inner elements: views smaller than the card (task rows, calendar day cells, chips, buttons)
     * whose fill is flat (a shape or a plain colour, optionally under a ripple) become glass skins,
     * keeping their own shape. Solid saturated marks (today's dot, an accent button) stay solid.
     * Nothing is replaced: draw() of the drawable's class is hooked and filtered by identity.
     */
    private fun styleInner(host: AppWidgetHostView, root: View) {
        val dp = host.resources.displayMetrics.density
        val content = host.getChildAt(0) ?: return
        val cardArea = content.width.toLong() * content.height
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            val small = v.width >= 16 * dp && v.height >= 16 * dp && v.width.toLong() * v.height * 4 < cardArea
            if (small) {
                val d = (v as? ImageView)?.drawable?.takeIf { innerFill(it) != null } ?: v.background?.takeIf { innerFill(it) != null }
                if (d != null) trackInner(d, v, host)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** The flat shape behind a drawable: itself, or the first layer of a ripple / layer list. */
    private fun innerFill(d: Drawable): Drawable? = when (d) {
        is GradientDrawable -> d
        is ColorDrawable -> d.takeIf { (it.color ushr 24) > 0 }
        is LayerDrawable -> (0 until d.numberOfLayers).asSequence().map { d.getDrawable(it) }
            .firstOrNull { it is GradientDrawable || (it is ColorDrawable && (it.color ushr 24) > 0) }
        else -> null
    }

    private fun trackInner(d: Drawable, v: View, host: AppWidgetHostView) {
        if (inner[d]?.view?.get() === v) return
        inner[d] = Inner(v, host)
        val draw = d.javaClass.getMethod("draw", android.graphics.Canvas::class.java)
        if (innerDraws.add(draw)) {
            de.robv.android.xposed.XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val t = inner[p.thisObject] ?: return
                    try {
                        if (drawInner(p.thisObject as Drawable, p.args[0] as android.graphics.Canvas, t)) p.result = null
                    } catch (e: Throwable) { Log.e(TAG, "widget inner", e) }
                }
            })
            Log.i(TAG, "widget inner glass on ${draw.declaringClass.name}.draw")
        }
        v.invalidate()
    }

    private fun drawInner(d: Drawable, c: android.graphics.Canvas, t: Inner): Boolean {
        val v = t.view.get() ?: return false
        val host = t.host.get() ?: return false
        val st = GlassSettings[Surface.HOME_WIDGET]
        if (!st.enabled || hosts[host]?.glass == null) return false
        val fill = innerFill(d) ?: return false
        val color = when (fill) {
            is ColorDrawable -> fill.color
            is GradientDrawable -> fill.color?.defaultColor ?: fill.colors?.firstOrNull()
            else -> null
        }
        if (color != null && isSolidAccent(color)) return false
        val dp = v.resources.displayMetrics.density
        skinRect.set(d.bounds)
        if (skinRect.isEmpty) return false
        val r = when {
            fill is GradientDrawable && fill.shape == GradientDrawable.OVAL -> GlassSkin.uniform(minOf(skinRect.width(), skinRect.height()) / 2f)
            fill is GradientDrawable -> fill.cornerRadii?.takeIf { it.size == 8 } ?: GlassSkin.uniform(fill.cornerRadius)
            else -> GlassSkin.uniform(outlineRadius(v) ?: (10 * dp))
        }
        val base = if (Looks.dark(night(v))) 0x38FFFFFF else 0x73FFFFFF
        // Inner elements are lighter than the card, whatever the colour.
        val tint = Looks.body(Surface.HOME_WIDGET, st, night(v), base, INNER_TINT_ALPHA)
        skin.draw(c, skinRect, r, tint, 0.6f * st.glow, dp)
        return true
    }

    /** A saturated opaque fill is a deliberate mark (today, an accent button): keep it. */
    private fun isSolidAccent(c: Int): Boolean {
        if ((c ushr 24) < 0xE0) return false
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
        return hsv[1] > 0.25f && hsv[2] > 0.2f
    }

    private fun topHost(v: View): AppWidgetHostView? {
        var p = v.parent
        while (p is View) { if (p is AppWidgetHostView && hosts.containsKey(p)) return p; p = p.parent }
        return null
    }

    private fun restore(host: AppWidgetHostView, state: Styled) {
        if (state.glass == null && state.hiddenBackgrounds.isEmpty() && state.hiddenImages.isEmpty()) return
        for ((v, d) in state.hiddenBackgrounds) if (v.background == null) v.background = d
        for ((v, a) in state.hiddenImages) v.imageAlpha = a
        state.hiddenBackgrounds.clear(); state.hiddenImages.clear()
        state.glass = null; state.insets = null
        val pad = intArrayOf(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom)
        host.background = state.hostBackground
        host.setPadding(pad[0], pad[1], pad[2], pad[3])
    }

    /**
     * The widget's card: views inside the widget, covering at least a quarter of it, whose background
     * (or ImageView image) is a flat fill. Photos, icons and text are never touched.
     */
    private fun findPanels(content: View, state: Styled): List<View> {
        val area = content.width.toLong() * content.height
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            val big = v.width.toLong() * v.height * 4 >= area
            val hidden = state.hiddenBackgrounds.containsKey(v) && v.background == null
            if (big && (hidden || isFlat(v.background) || (v is ImageView && isFlat(v.drawable)))) out.add(v)
            if (v is ViewGroup && v !is android.widget.AdapterView<*>) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(content)
        return out
    }

    private fun isFlat(d: Drawable?): Boolean = when (d) {
        null -> false
        is ColorDrawable -> d.alpha > 0 && (d.color ushr 24) > 0
        is GradientDrawable, is NinePatchDrawable -> true
        // Wrapped cards: a flat fill under an inset, a ripple or a layer list is still a flat card.
        is InsetDrawable -> isFlat(d.drawable)
        is LayerDrawable -> d.numberOfLayers in 1..3 && (0 until d.numberOfLayers).all { i ->
            d.getDrawable(i).let { it == null || isFlat(it) || it is android.graphics.drawable.ShapeDrawable }
        } && (0 until d.numberOfLayers).any { isFlat(d.getDrawable(it)) }
        else -> false
    }

    private fun cornerOf(panels: List<View>): Float? {
        for (v in panels) {
            val d = (if (v is ImageView && isFlat(v.drawable)) v.drawable else v.background) as? GradientDrawable ?: continue
            (d.cornerRadii?.maxOrNull() ?: d.cornerRadius).takeIf { it > 0f }?.let { return it }
        }
        return null
    }

    private fun outlineRadius(v: View): Float? = try {
        val o = Outline()
        v.outlineProvider?.getOutline(v, o)
        o.radius.takeIf { it > 0f && v.clipToOutline }
    } catch (_: Throwable) { null }

    /** Android 12+ widget corner radius (system_app_widget_background_radius). */
    private fun systemWidgetRadius(v: View): Float? = try {
        val id = v.resources.getIdentifier("system_app_widget_background_radius", "dimen", "android")
        if (id != 0) v.resources.getDimension(id) else null
    } catch (_: Throwable) { null }

    /** A widget host directly in the launcher, not a list-item host inside another widget. */
    private fun isTopLevel(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is AppWidgetHostView) return false; p = p.parent }
        return v.javaClass.name.startsWith("com.android.launcher3")
    }

    private fun night(v: View) = (v.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    companion object {
        /** The colour's opacity on an inner element at full intensity. */
        private const val INNER_TINT_ALPHA = 0x6B
        private const val TAG = "NebulaGlass"
        private const val LAUNCHER = "com.zte.mifavor.launcher"
        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.e(TAG, "widget", t) }
        }
    }
}
