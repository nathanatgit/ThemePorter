package dev.nebula.glass.hook

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassDrawable
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.Looks
import dev.nebula.glass.Shapes
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import dev.nebula.glass.WidgetTweak
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Glass backgrounds, containers and per-widget settings for home-screen app widgets ([Surface.HOME_WIDGET]).
 *
 * Trace (docs/LIQUID_GLASS_PORT.md): widget hosts are `AppWidgetHostView` subclasses (obfuscated
 * `launcher3.widget.U`) whose host background is the launcher's press/focus StateListDrawable. The host
 * spans the widget's cells; its children are the RemoteViews root and Nubia's title
 * (`launcher_widget_title_text_view_mfvclr`, below the content, hidden for some widgets).
 * Widgets paint their card themselves: a flat root background, flat panel images, or a one-colour
 * bitmap. Those layers are hidden and a glass panel is drawn under the host's own background.
 *
 * Containers ([Shapes.widgetWidth] / [Shapes.widgetHeight]) are laid out from the cell span, the same way
 * as large folders ([LauncherFolders]), so equal values line them up. Each widget's [WidgetTweak]
 * (long-press menu, [WidgetMenu]) sets its title, a zoom (content laid out at container / zoom and scaled
 * up, like a per-widget dpi) and a margin; the content is clipped to the container's rounded shape.
 * Every provider is made resizable both ways down to one cell. Everything is restorable.
 */
class LauncherWidgets : IXposedHookLoadPackage {
    private class Styled(val hostBackground: Drawable?) {
        var glass: GlassDrawable? = null
        var insets: IntArray? = null
        val hiddenBackgrounds = WeakHashMap<View, Drawable>()
        val hiddenImages = WeakHashMap<ImageView, Int>()
        val hiddenCards: MutableSet<Drawable> = java.util.Collections.newSetFromMap(WeakHashMap())
        /** Nubia's blur views behind its own widgets, and their alpha. */
        val hiddenViews = WeakHashMap<View, Float>()
        /** The glass container in host coordinates; empty until laid out. */
        val container = Rect()
        /** Where Nubia put the content, from the last layout pass that used Nubia's measurement. */
        val nubia = Rect()
        /** Content transformed by [arrange]: its measured size, and what to restore. */
        var transformed = false
        var ourW = -1
        var ourH = -1
        var outline: android.view.ViewOutlineProvider? = null
        var clipped = false
        var hostClipped = false
        /** The clip in content coordinates, and its radius. */
        val clip = android.graphics.RectF()
        var clipRadius = 0f
        /** The content's scale: the user's zoom times the fit into a smaller box. */
        var scale = 1f
        /** Corner of the glass, px. */
        var corner = 0f
        /** Nubia's title as it was before a tweak changed it. */
        var titleText: CharSequence? = null
        var titleVisibility: Int? = null
        /** A title view added for a widget Nubia shows none for. */
        var ownTitle: TextView? = null
    }

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
        instance = this
        freeResize()
        GlassSettings.addListener {
            for (h in hosts.keys.toList()) guard { applyTitle(h); h.requestLayout(); style(h) }
            for (t in inner.values.toList()) t.view.get()?.invalidate()   // their skins are cached draws
        }
        dev.nebula.glass.LightSourceController.addRedraw {
            for (s in hosts.values.toList()) s.glass?.invalidateSelf()
            for (t in inner.values.toList()) t.view.get()?.invalidate()
        }
        val popup = XposedHelpers.findClassIfExists("com.android.launcher3.popup.PopupContainerWithArrow", app.classLoader)
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    // The long-press menu, before Nubia measures and places it.
                    if (popup != null && popup.isInstance(p.thisObject)) { guard { WidgetMenu.extend(p.thisObject as ViewGroup) }; return }
                    val v = p.thisObject as? AppWidgetHostView ?: return
                    if (isTopLevel(v)) v.post { guard { track(v) } }
                    else v.post { guard { topHost(v)?.let { h -> if (hosts[h]?.glass != null) styleInner(h, v) } } }
                }
            })
            XposedHelpers.findAndHookMethod(AppWidgetHostView::class.java, "updateAppWidget", RemoteViews::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as AppWidgetHostView
                        if (hosts.containsKey(v)) v.post { guard { applyTitle(v); style(v) } }
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
        hookLayout(host.javaClass)
        host.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> guard { style(v as AppWidgetHostView) } }
        WallpaperBackdrop.sharedBlurred(host.context).addListener { guard { style(host) } }
        applyTitle(host)
        host.requestLayout()
        style(host)
    }

    /** (Re)apply or remove the glass for one widget host to match the current settings. */
    private fun style(host: AppWidgetHostView) {
        val state = hosts[host] ?: return
        val st = GlassSettings[Surface.HOME_WIDGET, night(host)]
        val content = contentOf(host)
        val backdrop = WallpaperBackdrop.sharedBlurred(host.context)
        val tweak = tweakOf(host)
        val usable = st.enabled && tweak.glass && content != null && content.width > 0 && !backdrop.isLive && backdrop.bitmap != null
        val panels = if (usable) findPanels(content!!, state) else emptyList()
        // Picture-only widgets (one bitmap, e.g. KWGT) have no separable card: left stock (user decision),
        // unless containers are normalised, where every widget gets the same glass container.
        if (!usable || (panels.isEmpty() && !normalised(host))) { restore(host, state); return }

        // Nubia's own widgets (sleep, steps) sit on a blur view of Nubia's beside the content: the glass
        // replaces it, so its frosted card fades out (it would also stay put when the content moves).
        val blurViews = (0 until host.childCount).map { host.getChildAt(it) }.filter(::isBlurView)
        for (v in blurViews) {
            // It paints its own wallpaper copy over the glass: hidden while the glass is on.
            if (!state.hiddenViews.containsKey(v)) state.hiddenViews[v] = v.alpha
            if (v.alpha != 0f) v.alpha = 0f
        }
        // Hide the widget's own flat card layers (re-applied RemoteViews bring them back each update).
        for (v in panels + blurViews) {
            val bg = v.background
            if (bg != null && isBlurCard(bg)) {
                // Nubia rebuilds a removed blur card every frame: keep it, faded out. Its blur is a
                // compositor region, so the alpha (not skipping the draw) is what removes it.
                if (hiddenCards.add(bg)) state.hiddenCards.add(bg)
                (bg as InsetDrawable).drawable?.let { fadeBlur(it, true); bg.invalidateSelf() }
            } else if (v is ImageView && isFlat(v.drawable)) {
                if (!state.hiddenImages.containsKey(v)) state.hiddenImages[v] = v.imageAlpha
                v.imageAlpha = 0
            } else if (isFlat(v.background) && v.background !== state.hiddenBackgrounds[v]) {
                state.hiddenBackgrounds[v] = v.background
                v.background = null
            }
        }

        // Glass under the host's own press/focus background, inset to the container.
        val c = if (state.container.isEmpty) Rect(content!!.left, content.top, content.right, content.bottom) else Rect(state.container)
        val insets = intArrayOf(c.left, c.top, host.width - c.right, host.height - c.bottom)
        val dp = host.resources.displayMetrics.density
        val own = cornerOf(panels) ?: outlineRadius(content!!) ?: outlineRadius(host) ?: systemWidgetRadius(host) ?: (16 * dp)
        val corner = if (st.cornerDp >= 0) minOf(st.cornerDp * dp, minOf(c.width(), c.height()) / 2f) else own
        if (corner != state.corner) {
            state.corner = corner
            if (state.transformed) { state.clipRadius = corner / state.scale; content!!.invalidateOutline() }
        }
        if (state.glass == null || !insets.contentEquals(state.insets)) {
            val glass = GlassDrawable(host, Looks.base(Surface.HOME_WIDGET, host.context, night(host)), null,
                prepare = { l ->
                    val n = night(host)
                    val s = GlassSettings[Surface.HOME_WIDGET, n]
                    Looks.style(l, Surface.HOME_WIDGET, dp, own, n, preBlurred = s.blur == 100, st = s)
                }) {
                val s = GlassSettings[Surface.HOME_WIDGET, night(host)]
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
            Log.i(TAG, "widget glass on ${host.appWidgetId} ${c.width()}x${c.height()} panels=${panels.size} corner=$corner")
        } else {
            state.glass?.invalidateSelf()
        }
        styleInner(host, content!!)
    }

    /**
     * Inner elements: views smaller than the card (task rows, calendar day cells, chips, buttons)
     * whose fill is flat (a shape or a plain colour, optionally under a ripple) become glass skins,
     * keeping their own shape. Solid saturated marks (today's dot, an accent button) stay solid.
     * Nothing is replaced: draw() of the drawable's class is hooked and filtered by identity.
     */
    private fun styleInner(host: AppWidgetHostView, root: View) {
        val dp = host.resources.displayMetrics.density
        val content = contentOf(host) ?: return
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
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
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
        val st = GlassSettings[Surface.HOME_WIDGET, night(v)]
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
        skin.draw(c, skinRect, r, tint, 0.6f * st.glow, dp, st)
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

    // ---- Containers, title, zoom and margin ----

    /** Nubia's titled widget layout, px: content left, top, right insets and the title band. 0 = not seen yet. */
    private val titled = IntArray(4)

    /** Containers are set (all widgets, or this one): every such widget gets the glass container. */
    private fun normalised(host: AppWidgetHostView): Boolean {
        val sh = GlassSettings.config.shapes
        val tw = tweakOf(host)
        return sh.widgetWidth >= 0 || sh.widgetHeight >= 0 || tw.width >= 0 || tw.height >= 0
    }

    fun tweakOf(host: AppWidgetHostView): WidgetTweak = GlassSettings.config.widgets[host.appWidgetId] ?: WidgetTweak()

    private var titleId = 0

    private fun isTitle(v: View): Boolean {
        if (v !is TextView) return false
        if (v.tag == OWN_TITLE) return true
        if (titleId == 0) titleId = v.resources.getIdentifier(TITLE_ID, "id", LAUNCHER)
        return titleId != 0 && v.id == titleId
    }

    /** The title shown now: ours, or Nubia's when visible. */
    private fun titleOf(host: ViewGroup): TextView? {
        var nubia: TextView? = null
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (!isTitle(c)) continue
            if (c.tag == OWN_TITLE) return c as TextView
            nubia = c as TextView
        }
        return nubia
    }

    private fun nubiaTitleOf(host: ViewGroup): TextView? {
        for (i in 0 until host.childCount) { val c = host.getChildAt(i); if (isTitle(c) && c.tag != OWN_TITLE) return c as TextView }
        return null
    }

    /** The widget's RemoteViews root: re-added on updates, so not always child 0. */
    private fun contentOf(host: ViewGroup): View? {
        for (i in 0 until host.childCount) { val c = host.getChildAt(i); if (!isTitle(c) && !isBlurView(c)) return c }
        return null
    }

    /** Nubia's blur backing view behind its own widgets (`launcher3.blur.a`, the host's getBgBlurImageView). */
    private fun isBlurView(v: View) = v.javaClass.name.startsWith("com.android.launcher3.blur.")

    /** A Nubia title, kept to style the titles added for widgets without one. */
    private var sampleTitle: java.lang.ref.WeakReference<TextView>? = null

    /** Show, hide or rename the title per the widget's tweak. Not during layout: it changes visibility. */
    fun applyTitle(host: AppWidgetHostView) {
        val state = hosts[host] ?: return
        val tw = tweakOf(host)
        val enabled = GlassSettings[Surface.HOME_WIDGET, night(host)].enabled
        val show = if (!enabled) null else when (tw.title) { WidgetTweak.TITLE_SHOW -> true; WidgetTweak.TITLE_HIDE -> false; else -> null }
        val label = if (enabled) tw.label else ""
        val nubia = nubiaTitleOf(host)
        if (nubia != null && nubia.visibility == View.VISIBLE && nubia.text.isNotEmpty()) sampleTitle = java.lang.ref.WeakReference(nubia)
        // Does Nubia show a title for this widget by itself? Un-hiding one Nubia hides changes how its host
        // draws the widget (a KWGT picture vanished), so such widgets get a title view of our own instead.
        val nubiaShows = nubia != null && (state.titleVisibility ?: nubia.visibility) == View.VISIBLE
        val own = show == true && !nubiaShows
        if (!own && state.ownTitle != null) { host.removeView(state.ownTitle); state.ownTitle = null }
        if (own) {
            val t = state.ownTitle ?: newTitle(host).also { t ->
                state.ownTitle = t
                val dp = host.resources.displayMetrics.density
                host.addView(t, FrameLayout.LayoutParams(-1, (Shapes.LABEL_BAND * dp).roundToInt(), android.view.Gravity.BOTTOM))
            }
            val text = label.ifEmpty { nubia?.text?.toString().orEmpty().ifEmpty { providerLabel(host) } }
            if (t.text.toString() != text) t.text = text
        }
        val t = nubia ?: return
        val managed = !own && (show != null || label.isNotEmpty())
        if (!managed) {
            // Back to Nubia's own title.
            state.titleText?.let { if (t.text.toString() != it.toString()) t.text = it }
            state.titleVisibility?.let { if (t.visibility != it) t.visibility = it }
            state.titleText = null; state.titleVisibility = null
            return
        }
        if (state.titleText == null) { state.titleText = t.text; state.titleVisibility = t.visibility }
        val text = label.ifEmpty { state.titleText?.toString().orEmpty().ifEmpty { providerLabel(host) } }
        if (t.text.toString() != text) t.text = text
        val vis = when (show) { true -> View.VISIBLE; false -> View.GONE; null -> state.titleVisibility ?: t.visibility }
        if (t.visibility != vis) t.visibility = vis
        if (vis == View.VISIBLE && t.alpha == 0f) t.alpha = 1f
    }

    private fun newTitle(host: AppWidgetHostView): TextView = TextView(host.context).apply {
        tag = OWN_TITLE
        gravity = android.view.Gravity.CENTER
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        val sample = sampleTitle?.get()
        if (sample != null) {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sample.textSize)
            setTextColor(sample.textColors)
            typeface = sample.typeface
            setShadowLayer(sample.shadowRadius, sample.shadowDx, sample.shadowDy, sample.shadowColor)
        } else {
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(4f, 0f, 1f, 0x66000000)
        }
    }

    fun providerLabel(host: AppWidgetHostView): String = try {
        host.appWidgetInfo?.loadLabel(host.context.packageManager)?.toString().orEmpty()
    } catch (_: Throwable) { "" }

    /** The title the widget shows now (for the menu's hint). */
    fun titleText(host: AppWidgetHostView): String =
        hosts[host]?.titleText?.toString()?.takeIf { it.isNotEmpty() }
            ?: titleOf(host)?.text?.toString()?.takeIf { it.isNotEmpty() && titleOf(host)?.tag != OWN_TITLE }
            ?: providerLabel(host)

    private val layoutHooked = HashSet<Class<*>>()

    /** After the host's own onLayout (the most derived one), re-place the content in its container. */
    private fun hookLayout(cls: Class<*>) {
        var k: Class<*>? = cls
        val int = Int::class.javaPrimitiveType
        while (k != null && k != View::class.java) {
            val m = try { k.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType, int, int, int, int) } catch (_: NoSuchMethodException) { null }
            if (m != null) {
                if (layoutHooked.add(k)) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            val h = p.thisObject as? AppWidgetHostView ?: return
                            if (hosts.containsKey(h)) guard { arrange(h) }
                        }
                    })
                    Log.i(TAG, "widget layout hook on ${k.name}")
                }
                return
            }
            k = k.superclass
        }
    }


    private fun arrange(host: AppWidgetHostView) {
        val state = hosts[host] ?: return
        val content = contentOf(host) ?: return
        val enabled = GlassSettings[Surface.HOME_WIDGET, night(host)].enabled
        val sh = GlassSettings.config.shapes
        val tw = tweakOf(host)
        // Nubia's placement, unless the content still carries our measurement (a layout pass without a measure).
        val ours = state.transformed && content.measuredWidth == state.ourW && content.measuredHeight == state.ourH
        if (!ours || state.nubia.isEmpty) state.nubia.set(content.left, content.top, content.right, content.bottom)
        val title = titleOf(host)?.takeIf { it.visibility == View.VISIBLE }
        // Learn Nubia's titled layout (content box and title band) from a widget Nubia titles itself.
        val nubiaTitled = title != null && title.tag != OWN_TITLE && (state.titleVisibility ?: View.VISIBLE) == View.VISIBLE
        if (nubiaTitled && !ours && !state.transformed && title!!.height > 0 && title.top >= state.nubia.bottom - 1) {
            val seen = intArrayOf(state.nubia.left, state.nubia.top, host.width - state.nubia.right, host.height - title.top)
            if (!seen.contentEquals(titled)) {
                seen.copyInto(titled)
                // Widgets whose title was switched on wait for these numbers.
                host.post { for ((h, s) in hosts) if (s.transformed && h !== host) h.requestLayout() }
            }
        }
        val trimW = if (tw.width >= 0) tw.width else sh.widgetWidth
        val trimH = if (tw.height >= 0) tw.height else sh.widgetHeight
        val normW = enabled && trimW >= 0
        val normH = enabled && trimH >= 0
        val active = enabled && (normW || normH || tw.scale != 100 || tw.marginX != 0 || tw.marginY != 0 ||
            tw.title != WidgetTweak.TITLE_AUTO)
        if (!active) {
            if (state.transformed) undo(host, content, state)
            state.container.set(state.nubia)
            return
        }
        val dp = host.resources.displayMetrics.density
        val w = host.width
        val h = host.height
        // With set heights every title gets the same band (folders use it too); otherwise Nubia's band.
        val band = when {
            title == null -> 0
            normH || titled[3] <= 0 -> (Shapes.LABEL_BAND * dp).roundToInt()
            else -> titled[3]
        }
        val areaBottom = h - band
        val c = Rect(state.nubia)
        if (title == null) {
            // A hidden title frees its band.
            c.bottom = maxOf(c.bottom, h - (c.top - host.paddingTop).coerceAtLeast(0) - host.paddingBottom)
        } else if (!nubiaTitled) {
            // A title switched on for a widget Nubia shows without one: Nubia's titled box, above the band.
            if (titled[3] > 0) { c.left = titled[0]; c.top = titled[1]; c.right = w - titled[2] }
            c.bottom = minOf(c.bottom, areaBottom)
        }
        if (normW) {
            val cw = (w - trimW * dp).roundToInt().coerceIn(1, w)
            c.left = (w - cw) / 2; c.right = c.left + cw
        }
        if (normH) {
            val ch = (areaBottom - trimH * dp).roundToInt().coerceIn(1, maxOf(1, areaBottom))
            c.top = (areaBottom - ch) / 2; c.bottom = c.top + ch
        }
        if (title != null && (normH || !nubiaTitled)) {
            title.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(band, View.MeasureSpec.EXACTLY))
            title.layout(0, areaBottom, w, h)
        }
        state.container.set(c)

        val r = Rect(c.left + tw.marginX, c.top + tw.marginY, c.right - tw.marginX, c.bottom - tw.marginY)
        if (r.width() < 8 || r.height() < 8) r.set(c)
        // Never lay the content out smaller than Nubia does: pictures (KWGT) and fixed layouts don't
        // reflow. A smaller box scales the content down to fit; the user's zoom multiplies on top.
        val n = state.nubia
        val fit = if (n.width() > 0 && n.height() > 0)
            minOf(1f, r.width().toFloat() / n.width(), r.height().toFloat() / n.height()).coerceAtLeast(0.2f) else 1f
        val s = tw.scale / 100f * fit
        state.scale = s
        val cw = maxOf(1, (r.width() / s).roundToInt())
        val ch = maxOf(1, (r.height() / s).roundToInt())
        content.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY))
        content.layout(r.left, r.top, r.left + cw, r.top + ch)
        state.ourW = cw; state.ourH = ch
        content.pivotX = 0f; content.pivotY = 0f
        content.scaleX = s; content.scaleY = s
        if (!state.transformed) {
            state.transformed = true
            state.outline = content.outlineProvider
            state.clipped = content.clipToOutline
            state.hostClipped = host.clipToOutline
        }
        // Nubia clips the host to an outline from its own layout (for a KWGT widget with a title it was
        // only the bottom 124 px): the content carries our clip instead.
        if (host.clipToOutline) host.clipToOutline = false
        // Clip to the container's rounded shape, in the content's own (unscaled) coordinates.
        state.clip.set((c.left - r.left) / s, (c.top - r.top) / s, (c.right - r.left) / s, (c.bottom - r.top) / s)
        val corner = if (state.corner > 0f) state.corner else (systemWidgetRadius(host) ?: 16 * dp)
        state.clipRadius = corner / s
        if (content.outlineProvider !is ClipOutline) content.outlineProvider = ClipOutline(state)
        content.clipToOutline = true
        content.invalidateOutline()
        if (DEBUG) Log.i(TAG, "arrange ${host.appWidgetId} host=${w}x$h nubia=${state.nubia} c=$c r=$r s=$s measured=${cw}x$ch")
    }

    private class ClipOutline(val state: Styled) : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) {
            val r = state.clip
            o.setRoundRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), state.clipRadius)
        }
    }

    private fun undo(host: AppWidgetHostView, content: View, state: Styled) {
        state.transformed = false
        state.ourW = -1; state.ourH = -1
        content.scaleX = 1f; content.scaleY = 1f
        content.outlineProvider = state.outline
        content.clipToOutline = state.clipped
        host.clipToOutline = state.hostClipped
        host.post { host.requestLayout() }
    }

    /** Called by [WidgetMenu] for a live preview: re-runs title, layout and glass. */
    fun refresh(host: AppWidgetHostView) {
        applyTitle(host)
        host.requestLayout()
        host.post { guard { style(host) } }
    }

    /** The widget a long-press menu was opened for, if it is one. */
    fun hostFor(popup: View): AppWidgetHostView? {
        try {
            (XposedHelpers.callMethod(popup, "getOriginalIcon") as? AppWidgetHostView)?.let { if (hosts.containsKey(it)) return it }
        } catch (_: Throwable) {}
        for (field in listOf("mOriginalIcon", "mIcon")) try {
            (XposedHelpers.getObjectField(popup, field) as? AppWidgetHostView)?.let { if (hosts.containsKey(it)) return it }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Every provider resizes both ways down to one cell. The launcher copies these fields from the
     * framework's AppWidgetProviderInfo, so they are changed where the launcher fetches them.
     */
    private fun freeResize() {
        val free = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                when (val r = p.result) {
                    is AppWidgetProviderInfo -> loosen(r)
                    is List<*> -> for (i in r) (i as? AppWidgetProviderInfo)?.let(::loosen)
                }
            }
        }
        for (name in listOf("getAppWidgetInfo", "getInstalledProviders", "getInstalledProvidersForProfile", "getInstalledProvidersForPackage")) {
            try { XposedBridge.hookAllMethods(AppWidgetManager::class.java, name, free) }
            catch (t: Throwable) { Log.w(TAG, "free resize: $name", t) }
        }
    }

    private fun loosen(i: AppWidgetProviderInfo) {
        i.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
        i.minResizeWidth = 1
        i.minResizeHeight = 1
        i.maxResizeWidth = 0
        i.maxResizeHeight = 0
    }

    private fun restore(host: AppWidgetHostView, state: Styled) {
        if (state.glass == null && state.hiddenBackgrounds.isEmpty() && state.hiddenImages.isEmpty() &&
            state.hiddenCards.isEmpty() && state.hiddenViews.isEmpty()) return
        for ((v, d) in state.hiddenBackgrounds) if (v.background == null) v.background = d
        for ((v, a) in state.hiddenImages) v.imageAlpha = a
        for (d in state.hiddenCards) { hiddenCards.remove(d); (d as? InsetDrawable)?.drawable?.let { fadeBlur(it, false) }; d.invalidateSelf() }
        for ((v, a) in state.hiddenViews) v.alpha = a
        state.hiddenBackgrounds.clear(); state.hiddenImages.clear(); state.hiddenCards.clear(); state.hiddenViews.clear()
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
            val hiddenImage = v is ImageView && state.hiddenImages.containsKey(v)
            // A blur card is the widget's card only when it is (nearly) the whole widget, not a tile inside it.
            val blur = v.background?.let(::isBlurCard) == true
            val whole = v.width.toLong() * v.height * 4 >= area * 3
            if (blur) { if (whole) out.add(v) }
            else if (big && (hidden || hiddenImage || isFlat(v.background) || (v is ImageView && isFlat(v.drawable)))) out.add(v)
            if (v is ViewGroup && v !is android.widget.AdapterView<*>) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(content)
        return out
    }

    /**
     * Nubia's frosted card (sleep, steps, dashboard tiles): a BackgroundBlurDrawable in X1.a, whose draw
     * [LauncherFolders] hooks, so it can be skipped there.
     */
    private fun isBlurCard(d: Drawable): Boolean = d is InsetDrawable && d.drawable?.javaClass?.name == BLUR_CARD

    /** Original colour and blur radius of each faded BackgroundBlurDrawable. */
    private val blurOriginals = WeakHashMap<Drawable, IntArray>()

    /**
     * Fade a BackgroundBlurDrawable out (or back). Its blur is a compositor region whose colour, radius
     * and alpha come from these setters, so all three go to zero.
     */
    private fun fadeBlur(d: Drawable, hide: Boolean) {
        try {
            if (hide) {
                if (!blurOriginals.containsKey(d)) {
                    val paint = XposedHelpers.getObjectField(d, "mPaint") as? android.graphics.Paint
                    blurOriginals[d] = intArrayOf(paint?.color ?: 0, XposedHelpers.getIntField(d, "mBlurRadius"), d.alpha)
                }
                if (d.alpha != 0) d.alpha = 0
                XposedHelpers.callMethod(d, "setColor", 0)
                XposedHelpers.callMethod(d, "setBlurRadius", 0)
            } else {
                val o = blurOriginals.remove(d) ?: return
                XposedHelpers.callMethod(d, "setColor", o[0])
                XposedHelpers.callMethod(d, "setBlurRadius", o[1])
                d.alpha = o[2]
            }
        } catch (t: Throwable) {
            if (hide) d.alpha = 0 else d.alpha = 255
            Log.w(TAG, "blur card fade", t)
        }
    }

    private fun isFlat(d: Drawable?): Boolean = when (d) {
        null -> false
        is ColorDrawable -> d.alpha > 0 && (d.color ushr 24) > 0
        is GradientDrawable, is NinePatchDrawable -> true
        // A card shipped as a picture: a bitmap that is one opaque colour across its inner area.
        is BitmapDrawable -> flatBitmap(d.bitmap)
        // Wrapped cards: a flat fill under an inset, a ripple or a layer list is still a flat card.
        // Nubia's own widgets (sleep, steps) wrap a system frosted card, BackgroundBlurDrawable, in X1.a.
        is InsetDrawable -> isFlat(d.drawable) || flatPixels(d)
        is LayerDrawable -> d.numberOfLayers in 1..3 && (0 until d.numberOfLayers).all { i ->
            d.getDrawable(i).let { it == null || isFlat(it) || it is android.graphics.drawable.ShapeDrawable }
        } && (0 until d.numberOfLayers).any { isFlat(d.getDrawable(it)) }
        // Press feedback, state lists and vectors are not cards.
        is android.graphics.drawable.RippleDrawable, is android.graphics.drawable.StateListDrawable,
        is android.graphics.drawable.VectorDrawable, is android.graphics.drawable.AnimatedVectorDrawable -> false
        // Nubia's own widget views paint their card with custom drawable classes: look at what they draw.
        else -> flatPixels(d)
    }

    private val flatDrawables = WeakHashMap<Drawable, Boolean>()

    /** A drawable that renders one opaque colour across its inner area (a card), sampled small. */
    private fun flatPixels(d: Drawable): Boolean {
        val b = d.bounds
        if (b.width() < 32 || b.height() < 32) return false
        return flatDrawables.getOrPut(d) {
            try {
                val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.scale(48f / b.width(), 48f / b.height())
                c.translate(-b.left.toFloat(), -b.top.toFloat())
                d.draw(c)
                val flat = flatBitmap(bmp)
                if (DEBUG) Log.i(TAG, "flatPixels ${d.javaClass.name} flat=$flat " + (1..3).joinToString(" ") { i ->
                    (1..3).joinToString(",") { j -> Integer.toHexString(bmp.getPixel(48 * (i * 2 + 1) / 8, 48 * (j * 2 + 1) / 8)) } })
                bmp.recycle()
                flat
            } catch (t: Throwable) { if (DEBUG) Log.w(TAG, "flatPixels ${d.javaClass.name}", t); false }
        }
    }

    private val flatBitmaps = WeakHashMap<android.graphics.Bitmap, Boolean>()

    private fun flatBitmap(b: android.graphics.Bitmap?): Boolean {
        if (b == null || b.isRecycled || b.width < 32 || b.height < 32) return false
        return flatBitmaps.getOrPut(b) {
            try {
                if (b.config == android.graphics.Bitmap.Config.HARDWARE) return@getOrPut false
                // A 5 × 5 grid from 10 % to 90 %: a card is one colour almost to its edges, while a
                // picture (a KWGT dashboard of white tiles) has gaps or detail somewhere.
                val first = b.getPixel(b.width / 2, b.height / 2)
                if ((first ushr 24) < 0xC0) return@getOrPut false
                for (i in 0..4) for (j in 0..4) {
                    val px = b.getPixel(b.width * (1 + 2 * i) / 10, b.height * (1 + 2 * j) / 10)
                    if ((px ushr 24) < 0xC0 || colourDistance(px, first) > 12) return@getOrPut false
                }
                true
            } catch (_: Throwable) { false }
        }
    }

    private fun colourDistance(a: Int, b: Int): Int = maxOf(
        Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        Math.abs((a and 0xFF) - (b and 0xFF)))

    private fun cornerOf(panels: List<View>): Float? {
        for (v in panels) {
            val d = (if (v is ImageView && isFlat(v.drawable)) v.drawable else v.background) as? GradientDrawable ?: continue
            (d.cornerRadii?.maxOrNull() ?: d.cornerRadius).takeIf { it > 0f }?.let { return it }
        }
        return null
    }

    private fun outlineRadius(v: View): Float? = try {
        if (v.outlineProvider is ClipOutline) null else {
            val o = Outline()
            v.outlineProvider?.getOutline(v, o)
            o.radius.takeIf { it > 0f && v.clipToOutline }
        }
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
        private const val TITLE_ID = "launcher_widget_title_text_view_mfvclr"
        private const val OWN_TITLE = "nebula.widgetTitle"
        private const val BLUR_CARD = "com.android.internal.graphics.drawable.BackgroundBlurDrawable"
        private val DEBUG = try { (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.nebula.widgettrace") as String) == "1" } catch (_: Throwable) { false }

        /** Widget cards to skip drawing (blur cards kept in place); [LauncherFolders] checks it in X1.a.draw. */
        val hiddenCards: MutableSet<Drawable> = java.util.Collections.newSetFromMap(WeakHashMap())

        /** The live hook, for [LauncherPopup] and [WidgetMenu]. */
        @Volatile var instance: LauncherWidgets? = null
            private set

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.e(TAG, "widget", t) }
        }
    }
}
