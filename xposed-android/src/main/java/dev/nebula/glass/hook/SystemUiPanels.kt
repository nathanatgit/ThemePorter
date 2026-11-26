package dev.nebula.glass.hook

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassAccessibility
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.LensGlass
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Liquid Glass for the notification shade and Nubia's control centre (SystemUI).
 *
 * Trace (docs/LIQUID_GLASS_PORT.md): both panels sit over a system window blur of whatever is behind
 * (another app or home), which cannot be sampled in-process, so they get a [GlassSkin]: translucent
 * body + specular rim + sheen, with the system blur providing the frost.
 *  - The dim is a layer beneath each window ([PanelDim]), never the window's blur colour.
 *  - Notification cards: the background drawable of `NotificationBackgroundView` (live corner radii;
 *    grouped cards round only some corners).
 *  - Control centre (`com.zte.controlcenter`): GradientDrawable fills of `QSTileViewWide`, the inner
 *    circle of `QSTileViewCircle` and `ControlCenterMediaPlayerView`. Translucent (off) tiles become
 *    glass in Nubia's own colour; solid (on) tiles keep their accent fill. Sliders are left alone.
 *
 * Nubia's own drawables are never replaced (its code may cast them, e.g. to GradientDrawable to
 * recolour a tile; a wrong type there would crash SystemUI). Instead `draw()` of their classes is
 * hooked and filtered to the exact instances tracked here. Any failure draws the original.
 */
class SystemUiPanels : IXposedHookLoadPackage {

    private class Target(host: View, val surface: Surface, val radii: () -> FloatArray?, val ownShape: Boolean = false) {
        val host = WeakReference(host)
    }

    private val tracked = WeakHashMap<Drawable, Target>()
    private val hostsByDrawable get() = tracked.values.mapNotNull { it.host.get() }
    private val hookedDraws = HashSet<Method>()
    private val skin = GlassSkin()
    private val dim = PanelDim()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }   // no main Looper yet when the hook class is created
    private val rect = RectF()
    private var bgField: Field? = null
    private var radiiField: Field? = null
    private var clipTopField: Field? = null
    private var clipBottomField: Field? = null
    private var settingsAttached = false
    private var reducedAt = 0L
    private var reduced = false

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        // This is called twice in SystemUI (the second time with another classloader). Hooking twice
        // applies every scale twice: the blur radius was squared down and the dim squared up.
        if (installed) return
        installed = true
        HookSettings.load()
        GlassSettings.addListener { redrawPanels() }
        dev.nebula.glass.LightSourceController.addRedraw { redrawCards() }
        val notif = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.row.NotificationBackgroundView", app.classLoader)
        if (notif != null) {
            bgField = XposedHelpers.findFieldIfExists(notif, "mBackground")
            radiiField = XposedHelpers.findFieldIfExists(notif, "mCornerRadii")
            clipTopField = XposedHelpers.findFieldIfExists(notif, "mClipTopAmount")
            clipBottomField = XposedHelpers.findFieldIfExists(notif, "mClipBottomAmount")
            Log.i(TAG, "notification bg field=${bgField?.name} radii=${radiiField?.name}")
        } else Log.w(TAG, "NotificationBackgroundView not found")
        hookPanelBlur()   // first: the panel windows set their blur at creation, before the slower view hooks finish
        try {
            val refresh = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try { refresh(p.thisObject as View, notif) } catch (t: Throwable) { Log.e(TAG, "panel track", t) }
                }
            }
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", refresh)
            // Hide signals for the live blur regions (see updateBlursUnder).
            val hide = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { hideBlursLater(p.thisObject as View) }
            }
            XposedHelpers.findAndHookMethod(View::class.java, "onDetachedFromWindow", hide)
            XposedHelpers.findAndHookMethod(View::class.java, "onVisibilityAggregated", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.args[0] == false) hideBlursLater(p.thisObject as View)
                    if (p.args[0] == true && p.thisObject.javaClass.simpleName == "NotificationShadeWindowView") refreshShadeText(p.thisObject as View)
                }
            })
            XposedHelpers.findAndHookMethod(View::class.java, "setAlpha", Float::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.thisObject is android.view.ViewGroup) try { updateBlursUnder(p.thisObject as View, false) } catch (t: Throwable) { Log.e(TAG, "blur alpha", t) }
                }
            })
            // Same for the control centre's tile and media-button icons: drawn into a layer that is composited
            // as white (keeps each icon's shape and alpha). Sliders and album art are not tile / small icons.
            XposedHelpers.findAndHookMethod(android.widget.ImageView::class.java, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                private val white = android.graphics.Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) }
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val iv = p.thisObject as android.widget.ImageView
                        if (night(iv) || !(isPanelIcon(iv) || isStatusIcon(iv) || isPanelButton(iv)) || !glassPanel(iv)) return
                        val c = p.args[0] as Canvas
                        p.setObjectExtra("layer", c.saveLayer(0f, 0f, iv.width.toFloat(), iv.height.toFloat(), white))
                    } catch (_: Throwable) {}
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    val n = p.getObjectExtra("layer") as? Int ?: return
                    (p.args[0] as Canvas).restoreToCount(n)
                }
            })
            // The control centre is dimmed now, so its dark (light-mode) text is drawn white. Done at draw time on
            // the text's colour field, so Nubia's own colours and states are untouched.
            XposedHelpers.findAndHookMethod(android.widget.TextView::class.java, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val tv = p.thisObject as android.widget.TextView
                        if (night(tv) || !(inControlCenter(tv) || inCCHeader(tv) || inShade(tv)) || !glassPanel(tv)) return
                        val cur = XposedHelpers.getIntField(tv, "mCurTextColor")
                        if (Color.alpha(cur) == 0) return
                        p.setObjectExtra("textColor", cur)
                        XposedHelpers.setIntField(tv, "mCurTextColor", (cur and 0xFF000000.toInt()) or 0xFFFFFF)
                    } catch (_: Throwable) {}
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    val cur = p.getObjectExtra("textColor") as? Int ?: return
                    XposedHelpers.setIntField(p.thisObject, "mCurTextColor", cur)
                }
            })
            // The shade media player's seek bar is black on black, in both modes. The control centre's
            // brightness and volume sliders get a glass track under Nubia's own fill.
            // Because those SeekBars have rotation=270, HWUI's BlurRegion drops corner radii to 0 on them
            // (leaving a rectangular ghost outside pill corners); host their liveBlur on the unrotated parent!
            XposedHelpers.findAndHookMethod(android.view.ViewGroup::class.java, "dispatchDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val vg = p.thisObject as View
                    val spec = sliderParents[vg] ?: return
                    try {
                        drawSliderParentBlur(vg, p.args[0] as Canvas, spec)
                    } catch (t: Throwable) { Log.e(TAG, "slider blur", t) }
                }
            })
            XposedHelpers.findAndHookMethod(android.widget.ProgressBar::class.java, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                private val white = android.graphics.Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) }
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val bar = p.thisObject as View
                        if (bar is android.widget.SeekBar && isControlCenterSlider(bar)) {
                            drawSliderTrack(bar, p.args[0] as Canvas)?.let { p.setObjectExtra("track", it) }
                            return
                        }
                        if (idName(bar) != "media_seek_bar" || !whiteSeekBar(bar)) return
                        p.setObjectExtra("layer", (p.args[0] as Canvas).saveLayer(0f, 0f, bar.width.toFloat(), bar.height.toFloat(), white))
                    } catch (t: Throwable) { Log.e(TAG, "slider", t) }
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    (p.getObjectExtra("track") as? HiddenTrack)?.restore()
                    val n = p.getObjectExtra("layer") as? Int ?: return
                    (p.args[0] as Canvas).restoreToCount(n)
                }
            })
            // Tiles swap or recolour their background on state changes; notifications set custom ones.
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", Drawable::class.java, refresh)
            if (notif != null) XposedBridge.hookAllMethods(notif, "setCustomBackground", refresh)
            if (notif != null) hookNotificationBackground(notif, app.classLoader)
            hookGroupHeader()
            Log.i(TAG, "notification / control centre glass hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "panel hooks failed", t)
        }
    }

    /** NotificationBackgroundView's geometry fields (see its getActualHeight / draw(Canvas, Drawable)). */
    private class NotifFields(private val cls: Class<*>) {
        private fun f(n: String) = XposedHelpers.findField(cls, n)
        val actualH = f("mActualHeight"); val actualW = f("mActualWidth")
        val expanding = f("mExpandAnimationRunning"); val expandH = f("mExpandAnimationHeight"); val expandW = f("mExpandAnimationWidth")
        val bottomRounded = f("mBottomIsRounded"); val bottomClips = f("mBottomAmountClips")
        val clipTop = f("mClipTopAmount"); val clipBottom = f("mClipBottomAmount")
    }

    /**
     * Notification cards: replace `NotificationBackgroundView.onDraw` itself rather than one of its
     * drawables. Nubia swaps `mBackground` on refreshes (a new RippleDrawable, the heads-up
     * `mBlurBgNormal` via setCustomBackgroundForBlur 50 ms later, the lock screen's async blurred
     * LayerDrawable 40 ms later): any frame drawn with an untracked drawable showed the stock opaque
     * card, which is the flicker seen when an ongoing notification updates every second. Drawing
     * from the view's own geometry makes the glass independent of whatever drawable is current, and
     * Nubia's competing CPU blurs are skipped while glass is on.
     */
    private fun hookNotificationBackground(notif: Class<*>, cl: ClassLoader) {
        val fields = try { NotifFields(notif) } catch (t: Throwable) { Log.w(TAG, "notification bg fields", t); null }
        if (fields != null) {
            XposedHelpers.findAndHookMethod(notif, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try { if (drawNotificationBackground(p.thisObject as View, p.args[0] as Canvas, fields)) p.result = null }
                    catch (t: Throwable) { Log.e(TAG, "notification bg", t) }
                }
            })
            Log.i(TAG, "notification background onDraw hooked")
        }
        val gauss = XposedHelpers.findClassIfExists("com.zte.utils.GaussBlur", cl) ?: return
        fun glassOn(row: Any?, s: Surface): Boolean {
            val v = row as? View ?: return false
            return GlassSettings[cardSurface(s, v), night(v)].enabled && !reducedTransparency(v)
        }
        try {
            XposedBridge.hookAllMethods(gauss, "blurRowNormal", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try { if (glassOn(p.args.getOrNull(0), Surface.NOTIFICATION)) p.result = null } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(gauss, "blurKeyguardNotificationView", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val v = p.args.getOrNull(0) as? View ?: return
                        if (GlassSettings[Surface.LOCK_CARD, night(v)].enabled && !reducedTransparency(v)) p.result = null
                    } catch (_: Throwable) {}
                }
            })
            Log.i(TAG, "GaussBlur row blurs skipped while glass is on")
        } catch (t: Throwable) { Log.w(TAG, "GaussBlur hooks", t) }
    }

    /**
     * An expanded notification group gives its header an opaque fill
     * (`NotificationChildrenContainer.updateHeaderForExpansion` -> `setHeaderBackgroundDrawable(ColorDrawable)`,
     * `notification_children_container_header_bg_color`). `NotificationHeaderView` paints it in its own
     * onDraw from a private field, so it is not the view's background: it was the light strip covering the
     * group title. With card glass on, the header sits on the summary's glass and gets no fill.
     */
    private fun hookGroupHeader() {
        try {
            val cls = Class.forName("android.view.NotificationHeaderView")
            XposedHelpers.findAndHookMethod(cls, "setHeaderBackgroundDrawable", Drawable::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (p.args[0] == null) return
                    try {
                        val v = p.thisObject as View
                        val st = GlassSettings[cardSurface(Surface.NOTIFICATION, v), night(v)]
                        if (st.enabled && !reducedTransparency(v)) p.args[0] = null
                    } catch (t: Throwable) { Log.e(TAG, "group header", t) }
                }
            })
        } catch (t: Throwable) { Log.w(TAG, "group header hook", t) }
    }

    private fun drawNotificationBackground(v: View, c: Canvas, f: NotifFields): Boolean {
        if (!PANEL_SKIN_DRAW) return false
        val surface = cardSurface(Surface.NOTIFICATION, v)
        val st = withCardCorner(GlassSettings[surface, night(v)], surface)
        if (!st.enabled || reducedTransparency(v)) { dropBlur(v); return false }
        if (!settingsAttached) { settingsAttached = true; HookSettings.attach(v.context); followLockState(v.context) }
        // A row has two background views (normal and dimmed); the invisible one must not paint a second glass.
        if (v.alpha < 0.02f || v.visibility != View.VISIBLE) { traceLog(v) { "skip hidden a=${v.alpha} vis=${v.visibility}" }; return true }
        // Children of a notification group sit inside the summary's glass: they must not paint panes of their own.
        if ((v.parent as? View)?.parent?.javaClass?.simpleName == "NotificationChildrenContainer") { dropBlur(v); return true }
        // Normal and dimmed backgrounds are both visible and stacked: the upper one paints, the lower must not.
        (v.parent as? android.view.ViewGroup)?.let { g ->
            for (i in g.indexOfChild(v) + 1 until g.childCount) {
                val o = g.getChildAt(i)
                if (o.javaClass == v.javaClass && o.visibility == View.VISIBLE && o.alpha >= 0.02f) { traceLog(v) { "skip covered" }; dropBlur(v); return true }
            }
        }
        val expand = f.expanding.getBoolean(v)
        val h = f.actualH.getInt(v).let { a -> f.expandH.getInt(v).takeIf { expand && it > -1 } ?: if (a > -1) a else rowHeight(v) }
        val w = f.actualW.getInt(v).let { a -> f.expandW.getInt(v).takeIf { expand && it > -1 } ?: if (a > -1) a else v.width }
        val clipT = f.clipTop.getInt(v)
        val clipB = f.clipBottom.getInt(v)
        if (clipT + clipB >= h && !expand) { dropBlur(v); return true }
        val bgH = if (f.bottomRounded.getBoolean(v) && f.bottomClips.getBoolean(v) && !expand) h - clipB else h
        val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
        var l = if (rtl) v.width - w else 0
        var r = if (rtl) v.width else w
        if (expand) { l = ((v.width - w) / 2f).toInt(); r = l + w }
        if (bgH <= 0 || r <= l) { dropBlur(v); return true }
        val dp = v.resources.displayMetrics.density
        val save = c.save()
        try {
            if (!expand) c.clipRect(0, clipT, v.width, h)
            rect.set(l.toFloat(), 0f, r.toFloat(), bgH.toFloat())
            val radii = if (st.cornerDp != GlassStyle.ORIGINAL) cornerOverride(st, rect, dp)
                else cardRadii(v, dp)
            drawGlass(v, c, rect, radii, surface, st, null, dp)
        } finally {
            c.restoreToCount(save)
        }
        return true
    }

    /** Height the row really shows: its own getActualHeight, which a background view reports as -1 while collapsed. */
    private fun rowHeight(v: View): Int {
        val row = v.parent as? View ?: return v.height
        return try { (XposedHelpers.callMethod(row, "getActualHeight") as? Int)?.takeIf { it > 0 }?.coerceAtMost(v.height) ?: v.height }
        catch (_: Throwable) { v.height }
    }

    /** Stock radii of a card: the view's own field, else its current background drawable, else a default. */
    private fun cardRadii(v: View, dp: Float): FloatArray {
        fun ok(a: FloatArray?) = a?.takeIf { it.size == 8 && it.any { x -> x > 0.5f } }
        val base = ok(radiiField?.get(v) as? FloatArray) ?: ok((bgField?.get(v) as? Drawable)?.let { radiiOf(it) })
        base?.max()?.let { stockCardRadius = it }
        // Cards are separate glass panes with gaps between them: round all four corners.
        return GlassSkin.uniform(base?.max() ?: (20 * dp))
    }

    /** Nubia's corner of a full notification card, as last seen on one: the media card uses it too. */
    @Volatile private var stockCardRadius = 0f

    /**
     * The corner setting a card follows: Notification corner in the shade, Lock card corner on the lock
     * screen (its own surface), Heads-up corner for a card shown over an app (the shade window closed).
     */
    private fun withCardCorner(st: GlassStyle, surface: Surface): GlassStyle =
        if (surface == Surface.NOTIFICATION && !shadeOpen()) st.copy(cornerDp = GlassSettings.config.shapes.headsUpCorner) else st

    private val blurSeen = HashSet<String>()

    /** Scale the window blur and wash behind the shade and control centre. */
    private fun hookPanelBlur() {
        val int = Int::class.javaPrimitiveType!!
        // Nubia's framework adds Transaction.setBackgroundBlurRadiusAndAlphaColor(sc, radius, argb) and calls
        // it by reflection for the control_center / NotificationShade windows: that is the blur *and* the
        // grey-white wash of both panels. Scale both here.
        try {
            XposedHelpers.findAndHookMethod(android.view.SurfaceControl.Transaction::class.java, "setBackgroundBlurRadiusAndAlphaColor",
                android.view.SurfaceControl::class.java, int, int, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val name = p.args[0].toString()
                            val surface = when {
                                "control_center" in name -> Surface.CONTROL_CENTER
                                "NotificationShade" in name -> Surface.NOTIFICATION
                                else -> return
                            }
                            val st = GlassSettings[surface]
                            val radius = p.args[1] as Int
                            val argb = p.args[2] as Int
                            val key = surface.key
                            if (blurSeen.add(key)) Log.i(TAG, "panel blur ${surface.key} radius=$radius argb=#${Integer.toHexString(argb)}")
                            val t = p.thisObject as android.view.SurfaceControl.Transaction
                            val window = p.args[0] as android.view.SurfaceControl
                            setWindowOpen(if (surface == Surface.CONTROL_CENTER) CC_WINDOW else SHADE_WINDOW, radius > 0 || (argb ushr 24) > 0)
                            if (!st.enabled) {
                                dim.set(t, window, surface, 0f)
                                return
                            }
                            p.args[1] = (radius * Looks.PANEL_BLUR_SCALE * st.blur / 100f).toInt()
                            // The radius is the open progress in both themes; the scrim's alpha is not.
                            val scrim = Looks.panelDim(surface, st) * (radius / Looks.NUBIA_BLUR_PX).coerceIn(0f, 1f)
                            p.args[2] = if (dim.set(t, window, surface, scrim)) 0 else (scrim * 255).toInt() shl 24
                        } catch (t: Throwable) { Log.e(TAG, "panel blur", t) }
                    }
                })
        } catch (t: Throwable) { Log.w(TAG, "panel blur hook", t) }
    }

    /**
     * The shade's media player background (`NotificationMediaPanelNoBlurBg`) is 561 px tall inside a
     * 395 px container that clips it, so Nubia's own card shows square, cut-off edges (seen with no
     * hooks at all). Draw a glass card fitted to the visible container instead.
     */
    private val mediaHooked = HashSet<Class<*>>()

    /**
     * The lock screen draws its media card with `NotificationMediaPanelBackground`, the same 561 px
     * panel in a 395 px container, so it needs the same fit (its square, cut-off edges were visible).
     */
    private val mediaClassNames = setOf(
        "com.zte.feature.dynamicisland.NotificationMediaPanelNoBlurBg",
        "com.zte.feature.dynamicisland.NotificationMediaPanelBackground",
    )

    /** The classes live in Nubia's feature classloader, so each is hooked from its first live instance. */
    private fun hookMediaPanel(cls: Class<*>) {
        if (!mediaHooked.add(cls)) return
        val m = XposedHelpers.findMethodExactIfExists(cls, "onDraw", Canvas::class.java)
            ?: XposedHelpers.findMethodExactIfExists(cls, "draw", Canvas::class.java)
            ?: XposedHelpers.findMethodExactIfExists(View::class.java, "draw", Canvas::class.java)
        Log.i(TAG, "media panel hook on ${m?.declaringClass?.name}.${m?.name}")
        m ?: return
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as? View ?: return
                if (!cls.isInstance(v)) return
                try { if (drawMediaPanel(v, p.args[0] as Canvas)) p.result = null } catch (t: Throwable) { Log.e(TAG, "media panel", t) }
            }
        })
    }

    private val mediaRect = RectF()
    /** Media cards seen so far: they have no tracked drawable to redraw them by. */
    private val mediaViews = WeakHashMap<View, Boolean>()
    /** Media containers re-shaped for the glass: their stock outline provider and clip, restored when glass is off. */
    private val stockOutlines = WeakHashMap<View, Pair<android.view.ViewOutlineProvider?, Boolean>>()
    private val stockOutlineRadius = WeakHashMap<View, Float?>()
    private val glassOutlineRadius = WeakHashMap<View, Float>()

    /**
     * The media panel is taller than its container, which hides the overflow with a rounded outline clip.
     * Turning that clip off showed the overflow under the card; keeping Nubia's outline cut the glass to
     * Nubia's radius. So the container clips to the glass's own rounded box instead.
     */
    private fun clipContainerTo(container: View, radius: Float, stockRadius: Float?) {
        if (container !in stockOutlines) {
            stockOutlines[container] = container.outlineProvider to container.clipToOutline
            stockOutlineRadius[container] = stockRadius
        }
        val ours = container.outlineProvider === glassOutline
        if (ours && glassOutlineRadius[container] == radius && container.clipToOutline) return
        glassOutlineRadius[container] = radius
        if (!ours) container.outlineProvider = glassOutline
        container.clipToOutline = true
        container.invalidateOutline()
    }

    private val glassOutline = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            val r = glassOutlineRadius[view] ?: 0f
            outline.setRoundRect(0, 0, view.width, view.height, r.coerceAtMost(minOf(view.width, view.height) / 2f))
        }
    }
    private var mediaLogged = false

    /** Corner radius of the container's outline, when it clips to a rounded shape. */
    private fun containerRadius(container: View): Float? = try {
        val o = android.graphics.Outline()
        container.outlineProvider?.getOutline(container, o)
        o.radius.takeIf { it > 0f }
            ?: (container.background as? android.graphics.drawable.GradientDrawable)?.let { d ->
                (d.cornerRadii?.maxOrNull() ?: d.cornerRadius).takeIf { it > 0f }
            }
    } catch (_: Throwable) { null }

    private fun drawMediaPanel(v: View, c: Canvas): Boolean {
        mediaViews[v] = true
        val surf = cardSurface(Surface.NOTIFICATION, v)
        val st = withCardCorner(GlassSettings[surf, night(v)], surf)
        val container = v.parent as? View ?: return false
        if (!st.enabled || reducedTransparency(v)) {
            dropBlur(v)
            stockOutlines.remove(container)?.let { (provider, clip) ->
                container.outlineProvider = provider; container.clipToOutline = clip
            }
            return false
        }
        val dp = v.resources.displayMetrics.density
        // Visible part = the container's box, in this view's coordinates, on both axes (the panel is
        // wider and taller than the container that clips it, so square corners were being cut off).
        val left = (-v.left).toFloat().coerceAtLeast(0f)
        val top = (-v.top).toFloat().coerceAtLeast(0f)
        mediaRect.set(left, top,
            (left + container.width).coerceAtMost(v.width.toFloat()),
            (top + container.height).coerceAtMost(v.height.toFloat()))
        // Corners: exactly those of the notification cards around it. The user's corner setting for this
        // place (shade, lock screen, heads-up), else Nubia's card radius; the container's own rounded
        // clip only when no card has been seen yet.
        val own = if (container in stockOutlines) stockOutlineRadius[container] else containerRadius(container)
        val radius = stockCardRadius.takeIf { it > 0f } ?: own ?: (20 * dp)
        val radii = if (st.cornerDp != GlassStyle.ORIGINAL) cornerOverride(st, mediaRect, dp)
            else GlassSkin.uniform(radius)
        clipContainerTo(container, radii[0], own)
        if (!mediaLogged) {
            mediaLogged = true
            Log.i(TAG, "media panel rect=$mediaRect view=${v.left},${v.top} ${v.width}x${v.height} container=${container.width}x${container.height} " +
                "bg=${container.background?.javaClass?.name} outlineR=$own cornerR=$radius")
        }
        drawGlass(v, c, mediaRect, radii, cardSurface(Surface.NOTIFICATION, v), st, null, dp)
        return true
    }

    /** Track the current background drawable of a notification card or control-centre panel. */
    private fun refresh(v: View, notif: Class<*>?) {
        if (v.javaClass.simpleName == "ChargeIndicator" && v.javaClass !in statusDrawHooked) hookStatusDraw(v.javaClass)
        if (MEDIA_CARD_GLASS && v.javaClass.name in mediaClassNames && v.javaClass !in mediaHooked) {
            hookMediaPanel(v.javaClass); v.invalidate()
        }
        when {
            notif != null && notif.isInstance(v) -> {
                val d = bgField?.get(v) as? Drawable ?: return
                track(d, v, Surface.NOTIFICATION) { (radiiField?.get(v) as? FloatArray)?.takeIf { it.size == 8 } ?: radiiOf(d) }
            }
            isControlCenterPanel(v) -> {
                val d = v.background ?: return
                // Circle buttons stay circles; the tile corner applies to the card tiles and the player.
                val circle = v.parent?.javaClass?.name?.startsWith("com.zte.qs.tileimpl.QSTileViewCircle") == true
                if (d is GradientDrawable) track(d, v, Surface.CONTROL_CENTER, ownShape = circle) { radiiOf(d) }
            }
            // Long-press tile sheet (Bluetooth / Wi-Fi device list): QSDetail with QsDetailModule$RoundCornerDrawable.
            v.javaClass.name == "com.zte.mifavor.qs.QSDetail" -> {
                val d = v.background ?: return
                val dp = v.resources.displayMetrics.density
                track(d, v, Surface.CONTROL_CENTER, ownShape = true) { radiiOf(d) ?: GlassSkin.uniform(24 * dp) }
            }
        }
    }

    private fun track(d: Drawable, host: View, surface: Surface, ownShape: Boolean = false, radii: () -> FloatArray?) {
        if (tracked[d]?.host?.get() === host) return
        if (!settingsAttached) { settingsAttached = true; HookSettings.attach(host.context); followLockState(host.context) }
        tracked[d] = Target(host, surface, radii, ownShape)
        val draw = d.javaClass.getMethod("draw", Canvas::class.java)
        if (hookedDraws.add(draw)) {
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val t = tracked[p.thisObject] ?: return
                    try {
                        if (drawSkin(p.thisObject as Drawable, p.args[0] as Canvas, t)) p.result = null
                    } catch (e: Throwable) {
                        Log.e(TAG, "skin draw", e)   // original draw continues
                    }
                }
            })
            Log.i(TAG, "skin hook on ${draw.declaringClass.name}.draw for ${surface.key}")
        }
        host.invalidate()
    }

    /** True when the skin replaced the original draw. */
    private fun drawSkin(d: Drawable, c: Canvas, t: Target): Boolean {
        if (!PANEL_SKIN_DRAW) return false
        val host = t.host.get() ?: return false
        val surface = cardSurface(t.surface, host)
        val st = GlassSettings[surface, night(host)]
        if (!st.enabled || reducedTransparency(host)) { dropBlur(host); return false }
        val color = colorOf(d, host)
        val dp = host.resources.displayMetrics.density
        rect.set(d.bounds)
        val radii = if (st.cornerDp != GlassStyle.ORIGINAL && !t.ownShape) cornerOverride(st, rect, dp)
            else t.radii() ?: GlassSkin.uniform(16 * dp)
        // On-state tiles are a solid accent fill: keep them as a strong tint.
        val active = color?.takeIf { t.surface == Surface.CONTROL_CENTER && isSolid(it) }
        drawGlass(host, c, rect, radii, surface, st, active, dp)   // keyed by the view: Nubia swaps a row's drawable on every content refresh
        return true
    }

    /** A notification card on the lock screen is the lock screen's: its own switch, look and material. */
    private fun cardSurface(s: Surface, host: View): Surface =
        if (s == Surface.NOTIFICATION && isLocked(host)) Surface.LOCK_CARD else s

    /**
     * One card or tile. Notification cards appear over three backdrops:
     *  - the open shade (dimmed): light panel glass, white text;
     *  - the lock screen (undimmed): frosted glass in the tone of Nubia's text;
     *  - an app, as a heads-up (undimmed): a dark body, white text.
     */
    private fun drawGlass(host: View, c: Canvas, r: RectF, radii: FloatArray, surface: Surface, st: GlassStyle, active: Int?, dp: Float, withBlur: Boolean = true) {
        val night = night(host)
        val body = when {
            surface == Surface.LOCK_CARD -> Looks.lockCardBody(st, night)
            // A heads-up over an app: frosted glass in the tone of Nubia's own text, as on the lock screen.
            surface == Surface.NOTIFICATION && !shadeOpen() -> Looks.headsUpBody(st, night)
            else -> Looks.panelBody(surface, st, night, active)
        }
        if (surface == Surface.LOCK_CARD && withBlur && drawLockCardLens(host, c, r, radii, st, body, dp)) {
            return
        }
        // Without the dim layer the scrim is in the window, and a blur region would be dimmed twice.
        if (withBlur && dim.available) liveBlur(host, c, r, radii, st.blurPx(Looks.PANEL_CARD_BLUR_PX), Looks.panelSaturation(st))
        val rot = host.rotation
        if (kotlin.math.abs(rot) > 1f) {
            val rad = Math.toRadians((-rot).toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            val lx = st.lightX * cos - st.lightY * sin
            val ly = st.lightX * sin + st.lightY * cos
            skin.drawPanel(c, r, radii, body, Looks.specular(surface) * st.glow, dp, st.edgeRgb, st.glowRgb, lx, ly)
        } else {
            skin.drawPanel(c, r, radii, body, Looks.specular(surface) * st.glow, dp, st)
        }
    }

    /** Guard preventing nested lock-card lens capture when Card #0 records peeking Card #1 underneath it. */
    private var inLockCardCapture = false
    private val lockCardLenses = WeakHashMap<View, LensGlass>()
    private val lockCardAt = WeakHashMap<View, Long>()
    private val lockCardClipRect = RectF()
    private val lockCardRadii4 = FloatArray(4)
    private var lockCardBackdrop: LockSceneBackdrop? = null

    @android.annotation.SuppressLint("ViewConstructor")
    private inner class LockSceneBackdrop(ctx: android.content.Context) : View(ctx) {
        var shadeRoot: android.view.ViewGroup? = null
        var cardRow: View? = null
        val wantScreen = RectF()
        private val loc = IntArray(2)
        private val veil = android.graphics.Paint()

        fun layoutTo(w: Int, h: Int) {
            if (w == width && h == height) return
            measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }

        private fun overlaps(v: View): Boolean {
            if (!v.isShown || v.alpha <= 0.02f || v.width <= 0 || v.height <= 0) return false
            v.getLocationOnScreen(loc)
            val l = loc[0].toFloat()
            val t = loc[1].toFloat()
            val r = l + v.width * v.scaleX.coerceAtLeast(0.5f)
            val b = t + v.height * v.scaleY.coerceAtLeast(0.5f)
            return r > wantScreen.left && l < wantScreen.right && b > wantScreen.top && t < wantScreen.bottom
        }

        private fun drawBranch(c: Canvas, v: View) {
            if (!overlaps(v)) return
            v.getLocationOnScreen(loc)
            val save = c.save()
            val a = v.alpha
            if (a < 0.99f) {
                c.saveLayerAlpha(loc[0].toFloat(), loc[1].toFloat(), loc[0] + v.width.toFloat(), loc[1] + v.height.toFloat(), (a * 255).toInt())
            }
            c.translate(loc[0].toFloat(), loc[1].toFloat())
            if (v.scaleX != 1f || v.scaleY != 1f) c.scale(v.scaleX, v.scaleY, 0f, 0f)
            v.draw(c)
            c.restoreToCount(save)
        }

        override fun onDraw(c: Canvas) {
            val root = shadeRoot ?: return
            val wp = WallpaperBackdrop.sharedLock(context)
            wp.layoutTo(width, height)
            wp.draw(c)
            val lw = GlassSettings[Surface.LOCK_WIDGET, context]
            if (lw.enabled) {
                val dimA = Looks.backdropDim(Surface.LOCK_WIDGET, lw)
                if (dimA > 0f) {
                    veil.color = Color.argb((dimA * 255).toInt(), 0, 0, 0)
                    c.drawPaint(veil)
                }
            }
            fun named(n: String): View? {
                val id = root.resources.getIdentifier(n, "id", SYSTEMUI)
                return if (id != 0) root.findViewById(id) else null
            }
            val statusRoot = named("keyguard_status_view") ?: named("clock_status_view") ?: named("keyguard_clock_container")
            if (statusRoot != null) drawBranch(c, statusRoot)
            val widgetBox = named("keyguard_widget_container_id")
            if (widgetBox != null && (statusRoot == null || !isDescendant(widgetBox, statusRoot))) {
                drawBranch(c, widgetBox)
            }

            val row = cardRow
            val stack = row?.parent as? android.view.ViewGroup
            if (stack != null && !inLockCardCapture) {
                inLockCardCapture = true
                try {
                    val myZ = row.translationZ + row.elevation
                    val myIdx = stack.indexOfChild(row)
                    for (i in 0 until stack.childCount) {
                        val sib = stack.getChildAt(i)
                        if (sib === row || !sib.isShown || sib.alpha <= 0.02f) continue
                        val sibZ = sib.translationZ + sib.elevation
                        val behind = sibZ < myZ - 0.05f || (kotlin.math.abs(sibZ - myZ) <= 0.05f && i < myIdx)
                        if (behind) drawBranch(c, sib)
                    }
                } finally {
                    inLockCardCapture = false
                }
            }
        }

        private fun isDescendant(child: View, ancestor: View): Boolean {
            var p = child.parent
            while (p is View) { if (p === ancestor) return true; p = p.parent }
            return false
        }
    }

    private fun stackRowOf(v: View): View? {
        var cur: View? = v
        while (cur != null) {
            val p = cur.parent as? View
            if (p?.javaClass?.simpleName == "NotificationStackScrollLayout") return cur
            cur = p
        }
        return null
    }

    private fun drawLockCardLens(host: View, c: Canvas, r: RectF, radii: FloatArray, st: GlassStyle, body: Int, dp: Float): Boolean {
        if (!c.isHardwareAccelerated) return false
        if (inLockCardCapture) {
            skin.drawPanel(c, r, radii, body, Looks.specular(Surface.LOCK_CARD) * st.glow, dp, st)
            return true
        }
        val shadeRoot = host.rootView as? android.view.ViewGroup ?: return false
        if (shadeRoot.width <= 0 || shadeRoot.height <= 0) return false
        watchLockCards(shadeRoot)

        val wp = WallpaperBackdrop.sharedLock(host.context)
        val cutTopPx = maxOf(r.top, clipAmount(clipTopField, host).toFloat())
        val cutBotPx = r.bottom - clipAmount(clipBottomField, host)
        if (cutTopPx >= cutBotPx) { dropBlur(host); return true }

        host.getLocationOnScreen(hostAt)
        lockCardAt[host] = (hostAt[0].toLong() shl 32) or (hostAt[1].toLong() and 0xffffffffL)

        val lens = lockCardLenses.getOrPut(host) { Looks.base(Surface.LOCK_CARD, host.context, night(host)) }
        if (!wp.isLive && wp.bitmap != null && lens.isAvailable) {
            dropBlur(host)
            val back = lockCardBackdrop ?: LockSceneBackdrop(host.context.applicationContext).also {
                lockCardBackdrop = it
                wp.addListener { redrawCards() }
            }
            back.layoutTo(shadeRoot.width, shadeRoot.height)
            back.shadeRoot = shadeRoot
            back.cardRow = stackRowOf(host)
            val pad = 96f
            back.wantScreen.set(
                hostAt[0] + r.left - pad,
                hostAt[1] + cutTopPx - pad,
                hostAt[0] + r.right + pad,
                hostAt[1] + cutBotPx + pad,
            )

            lockCardClipRect.set(r.left, cutTopPx, r.right, cutBotPx)
            val cutAbove = cutTopPx > r.top + 0.5f
            val cutBelow = cutBotPx < r.bottom - 0.5f
            lockCardRadii4[0] = if (cutAbove) 0f else radii[0]
            lockCardRadii4[1] = if (cutAbove) 0f else radii[2]
            lockCardRadii4[2] = if (cutBelow) 0f else radii[4]
            lockCardRadii4[3] = if (cutBelow) 0f else radii[6]

            Looks.style(lens, Surface.LOCK_CARD, dp, radii[0], night = night(host), st = st)
            lens.glassTint = body
            lens.frostCoat = !st.tinted
            lens.cornerRadii4 = lockCardRadii4
            lens.blurRadiusOverride = st.blurPx(14f * dp).coerceIn(0f, 80f)
            if (lens.draw(c, host, back, lockCardClipRect)) return true
        }
        return false
    }

    private val watchedLockRoots = WeakHashMap<View, Boolean>()
    private val lockVisRect = android.graphics.Rect()

    private fun watchLockCards(root: View) {
        if (watchedLockRoots.put(root, true) != null) return
        root.viewTreeObserver.addOnPreDrawListener {
            if (isLocked(root)) {
                try {
                    for ((h, prev) in lockCardAt.entries.toList()) {
                        if (!h.isAttachedToWindow || !h.isShown || !h.getGlobalVisibleRect(lockVisRect)) continue
                        h.getLocationOnScreen(hostAt)
                        val now = (hostAt[0].toLong() shl 32) or (hostAt[1].toLong() and 0xffffffffL)
                        if (now != prev) h.invalidate()
                    }
                } catch (_: Throwable) {}
            }
            true
        }
    }

    private class LiveBlur(val root: Any, val drawable: Drawable, host: View) {
        val host = WeakReference(host)
        var scroller: WeakReference<View>? = null
        var rectTop = 0f
        var cutTop = 0
        var lastAlpha = -1
        var windowName = ""
        // Last values handed to the BackgroundBlurDrawable: every setter call marks the blur region dirty
        // and schedules another compositor update, which blinked cards refreshed once a second.
        var appliedAlpha = -1
        var lastRadius = -1
        var lastSat = Float.NaN
        val lastCorners = FloatArray(4) { -1f }
        val lastBounds = android.graphics.Rect()
        var colorCleared = false
    }

    private var traceAt = 0L
    private var traceOn = false
    private var traceStacks = 0

    /** `adb shell setprop debug.nebula.blurtrace 1` logs every blur-region change (re-read every 2 s). */
    private fun trace(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - traceAt > 2000) {
            traceAt = now
            val was = traceOn
            traceOn = try { XposedHelpers.callStaticMethod(Class.forName("android.os.SystemProperties"), "getBoolean", "debug.nebula.blurtrace", false) == true } catch (_: Throwable) { false }
            if (traceOn && !was) traceStacks = 0
        }
        return traceOn
    }

    private inline fun traceLog(host: View, what: () -> String) {
        if (trace()) Log.i(TAG, "blurtrace ${SystemClock.uptimeMillis()} ${host.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(host))} ${what()}")
    }

    /** Keyed by the drawable (cards, tiles) or the view (media cards). Guarded by itself: draws come from several threads. */
    private val liveBlurs = WeakHashMap<Any, LiveBlur>()
    private var liveBlurBroken = false

    /**
     * A real cross-window blur of whatever is behind the card or tile (another app, home): the same
     * `ViewRootImpl.createBackgroundBlurDrawable()` Nubia's lock-screen widgets use, drawn under the
     * skin. The compositor does the blur, so nothing is sampled in-process. A radius of 0 draws no blur.
     *
     * The compositor ignores the canvas clip, so a region inside a scroll view is cut to it here;
     * otherwise a scrolled tile's blur shows over the header.
     */
    private fun liveBlur(host: View, c: Canvas, rect: RectF, radii: FloatArray, radiusPx: Float, saturation: Float) {
        if (liveBlurBroken) return
        if (radiusPx < 1f) { dropBlur(host); return }
        try {
            val root = XposedHelpers.callMethod(host, "getViewRootImpl") ?: return
            val lb = synchronized(liveBlurs) {
                var x = liveBlurs[host]
                if (x == null || x.root !== root) {
                    traceLog(host) { "new region (replacing=${x != null})" }
                    x = LiveBlur(root, XposedHelpers.callMethod(root, "createBackgroundBlurDrawable") as Drawable, host).also {
                        it.windowName = topName(host)
                        scrollerOf(host)?.let { s -> it.scroller = WeakReference(s); followScroll(host.rootView) }
                    }
                    liveBlurs[host] = x
                }
                x
            }
            val b = lb.drawable
            lb.rectTop = rect.top
            lb.cutTop = cutTop(lb, host)
            // Rows sliding under each other or the shelf clip their canvas, which the compositor ignores too.
            val top = maxOf(rect.top + lb.cutTop, clipAmount(clipTopField, host).toFloat())
            val bottom = rect.bottom - clipAmount(clipBottomField, host)
            lb.lastAlpha = regionAlpha(lb, host)
            val wantAlpha = if (top >= bottom) 0 else lb.lastAlpha
            if (wantAlpha != lb.appliedAlpha) { traceLog(host) { "alpha ${lb.appliedAlpha}->$wantAlpha rect=$rect top=$top bottom=$bottom" }; b.alpha = wantAlpha; lb.appliedAlpha = wantAlpha }
            if (top >= bottom) return
            if (radiusPx.toInt() != lb.lastRadius) { XposedHelpers.callMethod(b, "setBlurRadius", radiusPx.toInt()); lb.lastRadius = radiusPx.toInt() }
            // Per corner (grouped cards round only some); square where the scroll view cuts.
            val cut = top > rect.top
            val cutBelow = bottom < rect.bottom
            // A radius of half the side or more is dropped by the compositor (square corners showed
            // behind pill sliders), so stay just under it.
            val most = (minOf(rect.width(), bottom - top) / 2f - 1f).coerceAtLeast(0f)
            fun r(i: Int) = radii[i].coerceAtMost(most)
            val cTL = if (cut) 0f else r(0); val cTR = if (cut) 0f else r(2)
            val cBL = if (cutBelow) 0f else r(6); val cBR = if (cutBelow) 0f else r(4)
            val lc = lb.lastCorners
            if (lc[0] != cTL || lc[1] != cTR || lc[2] != cBL || lc[3] != cBR) {
                traceLog(host) { "corners $cTL $cTR $cBL $cBR" }
                lc[0] = cTL; lc[1] = cTR; lc[2] = cBL; lc[3] = cBR
                XposedHelpers.callMethod(b, "setCornerRadius", cTL, cTR, cBL, cBR)
            }
            if (!lb.colorCleared) { lb.colorCleared = true; try { XposedHelpers.callMethod(b, "setColor", 0) } catch (_: Throwable) {} }   // no tint of its own: the skin supplies the body
            if (saturation != lb.lastSat) { lb.lastSat = saturation; try { XposedHelpers.callMethod(b, "setSaturation", saturation) } catch (_: Throwable) {} }   // Nubia's addition to the AOSP drawable
            val bl = rect.left.toInt(); val bt = top.toInt(); val br = rect.right.toInt(); val bb = bottom.toInt()
            if (lb.lastBounds.left != bl || lb.lastBounds.top != bt || lb.lastBounds.right != br || lb.lastBounds.bottom != bb) {
                traceLog(host) { "bounds ${lb.lastBounds} -> $bl,$bt,$br,$bb" }
                lb.lastBounds.set(bl, bt, br, bb)
                b.setBounds(bl, bt, br, bb)
            }
            b.draw(c)
        } catch (t: Throwable) {
            liveBlurBroken = true
            Log.e(TAG, "live blur unavailable", t)
        }
    }

    /**
     * Forget a view's region when it stops drawing one (glass off, blur 0). A stale entry would keep
     * an outdated scroll cut, and [followScroll] would redraw that view on every frame.
     */
    private fun dropBlur(host: View) {
        val lb = synchronized(liveBlurs) { liveBlurs.remove(host) } ?: return
        traceLog(host) { "drop" }
        try { lb.drawable.alpha = 0 } catch (_: Throwable) {}
    }

    private fun clipAmount(f: Field?, host: View): Int =
        if (f != null && f.declaringClass.isInstance(host)) f.getInt(host).coerceAtLeast(0) else 0

    private fun scrollerOf(v: View): View? {
        var p: Any? = v.parent
        while (p is View) {
            if (p.javaClass.simpleName.endsWith("ScrollView")) return p
            p = p.parent
        }
        return null
    }

    private val hostAt = IntArray(2)
    private val scrollerAt = IntArray(2)

    /** Pixels of the glass hidden above its scroll view. */
    private fun cutTop(lb: LiveBlur, host: View): Int {
        val s = lb.scroller?.get() ?: return 0
        host.getLocationInWindow(hostAt)
        s.getLocationInWindow(scrollerAt)
        return (scrollerAt[1] - hostAt[1] - lb.rectTop).toInt().coerceAtLeast(0)
    }

    private val followedRoots = WeakHashMap<View, Boolean>()

    /** Scrolling moves tiles without redrawing them, so redraw the ones whose cut changed. */
    private fun followScroll(root: View) {
        if (followedRoots.put(root, true) != null) return
        root.viewTreeObserver.addOnPreDrawListener {
            try {
                synchronized(liveBlurs) {
                    for (lb in liveBlurs.values) {
                        if (lb.scroller == null) continue
                        val host = lb.host.get()?.takeIf { it.isAttachedToWindow } ?: continue
                        if (cutTop(lb, host) != lb.cutTop) host.invalidate()
                    }
                }
            } catch (t: Throwable) { Log.e(TAG, "blur clip", t) }
            true
        }
    }

    /**
     * Nubia drives each panel window's scrim (blur radius + wash) with the panel's open progress and ends it at
     * radius 0 / alpha 0 when the panel is closed. That is the one signal that is always in step with the
     * animation, so the card and tile blurs of a window follow it: closed means every blur region is faded out,
     * whatever happened to the views (hidden, clipped, re-parented, a gesture cancelled halfway).
     * The exception is the lock screen, which is the shade window with no scrim: see [regionAlpha].
     */
    private val windowsOpen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun windowOpen(name: String) = windowsOpen[name] ?: true

    /** The shade is pulled down (dimmed). Closed, its window shows only heads-ups. */
    private fun shadeOpen() = windowOpen(SHADE_WINDOW)

    private val headsUpMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, Array<Method>>()

    /** A card of a heads-up notification: its row says it is pinned or heads-up. */
    private fun isHeadsUp(host: View): Boolean {
        var p: Any? = host
        repeat(4) {
            val v = p as? View ?: return false
            val ms = headsUpMethods.getOrPut(v.javaClass) {
                listOfNotNull(
                    XposedHelpers.findMethodExactIfExists(v.javaClass, "isPinned"),
                    XposedHelpers.findMethodExactIfExists(v.javaClass, "isHeadsUp"),
                ).toTypedArray()
            }
            if (ms.isNotEmpty()) return try { ms.any { it.invoke(v) == true } } catch (_: Throwable) { false }
            p = v.parent
        }
        return false
    }

    private fun setWindowOpen(name: String, open: Boolean) {
        if (windowsOpen[name] == open) return
        windowsOpen[name] = open
        // A card's material depends on whether the shade is dimmed behind it.
        if (name == SHADE_WINDOW) main.post { redrawPanels() }
        synchronized(liveBlurs) {
            for (lb in liveBlurs.values) {
                if (lb.windowName != name) continue
                val host = lb.host.get() ?: continue
                val a = regionAlpha(lb, host)
                if (a == lb.lastAlpha) continue
                lb.lastAlpha = a
                traceLog(host) { "window $name alpha $a" }
                try { lb.drawable.alpha = a; lb.appliedAlpha = a } catch (_: Throwable) {}
            }
        }
    }

    private fun topName(v: View): String {
        var p: Any? = v
        var name = ""
        while (p is View) { name = p.javaClass.simpleName; p = p.parent }
        return name
    }

    private fun followLockState(ctx: android.content.Context) {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        try {
            ctx.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    keyguardAt = 0
                    redrawPanels()
                }
            }, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } catch (t: Throwable) { Log.e(TAG, "lock state receiver", t) }
    }

    private fun redrawCards() {
        val blurred = synchronized(liveBlurs) { liveBlurs.values.mapNotNull { it.host.get() } }
        for (h in hostsByDrawable + blurred + lockCardLenses.keys + mediaViews.keys + sliders.keys + sliderParents.keys) h.invalidate()
    }

    /** After a settings change: cards and tiles, plus the text and icons whose colour follows the on/off. */
    private fun redrawPanels() {
        redrawCards()
        for (root in hostsByDrawable.map { it.rootView }.toSet()) redrawContent(root)
    }

    /** A region's strength: its view's fade, while its window is open or is showing the lock screen. */
    private fun regionAlpha(lb: LiveBlur, host: View): Int {
        val showing = windowOpen(lb.windowName) ||
            (lb.windowName == SHADE_WINDOW && (isLocked(host) || isHeadsUp(host)))
        if (!showing) return 0
        return (effectiveAlpha(host) * 255).toInt().coerceIn(0, 255).let { if (it < 4) 0 else it }
    }

    /** Product of the alphas from [v] up to the window: what the user actually sees of a card or tile. */
    private fun effectiveAlpha(v: View): Float {
        var a = 1f
        var p: Any? = v
        while (p is View) { a *= p.alpha; p = p.parent }
        return a
    }

    /**
     * A blur region does not inherit its view's fade. Nubia fades the panels by setting an ancestor's
     * alpha (the cards are not redrawn), so without this the compositor keeps blurring the full-strength
     * shapes where the tiles have already faded: ghosts while sliding, and after the panel hides.
     * Called when a view under [root] changes alpha (blur follows it) or hides / detaches ([hide]: alpha 0).
     */
    private fun updateBlursUnder(root: View, hide: Boolean) {
        synchronized(liveBlurs) {
            if (liveBlurs.isEmpty()) return
            for (lb in liveBlurs.values) {
                val host = lb.host.get() ?: continue
                var p: Any? = host
                while (p != null && p !== root) p = (p as? View)?.parent
                if (p == null) continue
                val a = if (hide) 0 else regionAlpha(lb, host)
                // Small steps are skipped: every change invalidates the card, so a fade is followed in coarse steps.
                if (a == lb.lastAlpha || (a != 0 && lb.lastAlpha != 0 && kotlin.math.abs(a - lb.lastAlpha) < 8)) continue
                lb.lastAlpha = a
                traceLog(host) { "under ${root.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(root))} hide=$hide alpha $a" }
                if (hide && trace() && traceStacks++ < 4) Log.i(TAG, "blurtrace stack", Throwable())
                try { lb.drawable.alpha = a; lb.appliedAlpha = a } catch (_: Throwable) {}
            }
        }
    }
    private val pendingHides = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * A detach or hide is acted on only if it is still true a moment later. Every list rebuild (any
     * notification update: a speed meter, a media position) reorders rows with
     * `NotificationStackScrollLayout.changeViewPosition`, a `removeView` + `addView` of the row in one
     * call. Zeroing the blur regions on that transient detach left every card without its blur for one
     * frame: the dim flash on each update.
     */
    private fun hideBlursLater(v: View) {
        if (synchronized(liveBlurs) { liveBlurs.isEmpty() }) return
        if (!pendingHides.add(v)) return
        main.post {
            pendingHides.remove(v)
            if (v.isAttachedToWindow && v.isShown) return@post
            try { updateBlursUnder(v, true) } catch (t: Throwable) { Log.e(TAG, "blur drop", t) }
        }
    }

    private val inControlCenterCache = WeakHashMap<View, Boolean>()

    private fun inControlCenter(v: View): Boolean {
        inControlCenterCache[v]?.let { return it }
        var p: Any? = v
        var hit = false
        var attached = false
        while (p is View) {
            if (p.javaClass.name == "com.zte.controlcenter.ControlPanelWindowView") { hit = true; break }
            attached = p.parent == null && p.isAttachedToWindow
            p = p.parent
        }
        if (attached || hit) inControlCenterCache[v] = hit
        return hit
    }

    private val panelIconCache = WeakHashMap<View, Boolean>()

    /** A small icon inside a control-centre tile or the media player (not the sliders, not album art). */
    private fun isPanelIcon(v: View): Boolean {
        panelIconCache[v]?.let { return it }
        if (v.width == 0) return false
        val dp = v.resources.displayMetrics.density
        // Icons are at most about 76 x 44 dp (tile icon views are wider than tall); album art and its badge are excluded.
        var ok = v.width <= 76 * dp && v.height <= 44 * dp && v.javaClass.simpleName != "MediaHeaderIconView" && inControlCenter(v)
        if (ok) { val par = v.parent as? android.view.ViewGroup; if (par != null) for (i in 0 until par.childCount) if (par.getChildAt(i).javaClass.simpleName == "MediaHeaderIconView") ok = false }
        if (ok) {
            ok = false
            var p: Any? = v.parent
            while (p is View) {
                val n = p.javaClass.name
                if (n.startsWith("com.zte.qs.tileimpl.QSTileView") || n == "com.zte.controlcenter.view.ControlCenterMediaPlayerView") { ok = true; break }
                p = p.parent
            }
        }
        if (v.isAttachedToWindow) panelIconCache[v] = ok
        return ok
    }

    private val statusIconCache = WeakHashMap<View, Boolean>()

    /**
     * A status-bar style icon (signal, NFC, Bluetooth, charging) in the header of the control centre or the
     * notification shade. Those headers sit on the dimmed background now, so the icons are drawn white.
     */
    private fun isStatusIcon(v: View): Boolean {
        statusIconCache[v]?.let { return it }
        if (v.width == 0 || !v.isAttachedToWindow) return false
        val names = ArrayList<String>()
        var p: Any? = v.parent
        while (p is View) { names.add(p.javaClass.simpleName); p = p.parent }
        val inStatus = names.any { it == "StatusIconContainer" || it == "MFVBatteryViewLayout" || it.startsWith("ModernStatusBar") }

        val ok = inStatus && v.javaClass.simpleName != "MFVBatteryMeterView" && names.any { it == "CCHeaderView" || it.contains("QuickStatusBarHeader") || it.contains("QSHeader") }
        statusIconCache[v] = ok
        return ok
    }

    /** White on the shade's glass, and on the lock screen's only in dark mode (its light-mode card is pale). */
    private fun whiteSeekBar(bar: View): Boolean =
        if (isLocked(bar)) night(bar) && GlassSettings[Surface.LOCK_CARD, night(bar)].enabled && !reducedTransparency(bar)
        else inShade(bar) && glassPanel(bar)

    private val panelButtonCache = WeakHashMap<View, Boolean>()

    /** Dark controls outside the tiles: the shade's media buttons and chevrons, the headers' buttons. */
    private fun isPanelButton(v: View): Boolean {
        val named = panelButtonCache[v] ?: (idName(v) in PANEL_BUTTONS).also { panelButtonCache[v] = it }
        return named && (inControlCenter(v) || inShade(v))
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { null }

    /** Whether the panel holding [v] is glass, so dimmed and in need of light text and icons. */
    private fun glassPanel(v: View): Boolean {
        val surface = if (inControlCenter(v)) Surface.CONTROL_CENTER else Surface.NOTIFICATION
        return GlassSettings[surface, night(v)].enabled && !reducedTransparency(v)
    }

    private val ccHeaderCache = WeakHashMap<View, Boolean>()

    /** Inside the header (clock, date, status icons) shared by the control centre and the notification shade. */
    private fun inCCHeader(v: View): Boolean {
        ccHeaderCache[v]?.let { return it }
        var p: Any? = v
        var hit = false
        while (p is View) { if (p.javaClass.simpleName == "CCHeaderView") { hit = true; break }; p = p.parent }
        if (v.isAttachedToWindow) ccHeaderCache[v] = hit
        return hit
    }

    private val statusDrawHooked = HashSet<Class<*>>()

    /** Custom status views (the charging bolt) draw themselves, so hook their own draw, from the first live instance. */
    private fun hookStatusDraw(cls: Class<*>) {
        if (!statusDrawHooked.add(cls)) return
        val m = XposedHelpers.findMethodExactIfExists(cls, "onDraw", Canvas::class.java) ?: return
        val white = android.graphics.Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) }
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val v = p.thisObject as View
                    if (night(v) || !isStatusIcon(v) || !glassPanel(v)) return
                    p.setObjectExtra("layer", (p.args[0] as Canvas).saveLayer(0f, 0f, v.width.toFloat(), v.height.toFloat(), white))
                } catch (_: Throwable) {}
            }
            override fun afterHookedMethod(p: MethodHookParam) {
                val n = p.getObjectExtra("layer") as? Int ?: return
                (p.args[0] as Canvas).restoreToCount(n)
            }
        })
        Log.i(TAG, "status draw hook on ${cls.name}")
    }

    private val inShadeCache = WeakHashMap<View, Boolean>()
    private var keyguardAt = 0L
    private var keyguardLocked = true

    /**
     * A view in the notification shade (cards, media card, "manage"), on an unlocked phone. The shade is dimmed
     * there, so its dark text is drawn white. On the lock screen the wallpaper decides the text colour, so it is
     * left to Nubia; the lock state is re-read at most once a second.
     */
    private fun inShade(v: View): Boolean {
        if (isLocked(v) || !shadeOpen()) return false
        inShadeCache[v]?.let { return it }
        var p: Any? = v
        var hit = false
        while (p is View) { if (p.javaClass.simpleName == "NotificationShadeWindowView") { hit = true; break }; p = p.parent }
        if (v.isAttachedToWindow) inShadeCache[v] = hit
        return hit
    }

    private fun isLocked(v: View): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - keyguardAt > 1000) {
            keyguardAt = now
            val was = keyguardLocked
            keyguardLocked = try {
                (v.context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
            } catch (_: Throwable) { true }
            if (was != keyguardLocked) main.post { redrawPanels() }
        }
        return keyguardLocked
    }

    /**
     * Notification rows are drawn once and cached, so text that was drawn while the phone was locked stays dark
     * after unlocking. When the shade window becomes visible, re-read the lock state and redraw every text and
     * icon under it (twice: the lock state settles a moment after the window shows).
     */
    private fun refreshShadeText(root: View) {
        keyguardAt = 0
        root.post { redrawCards(); redrawContent(root) }
        root.postDelayed({ keyguardAt = 0; redrawCards(); redrawContent(root) }, 350)
    }

    private fun redrawContent(v: View) {
        if (v is android.widget.TextView || v is android.widget.ImageView || v is android.widget.ProgressBar) v.invalidate()
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) redrawContent(v.getChildAt(i))
    }

    /** A drawable hidden for one draw, without invalidating its view (that would redraw forever). */
    private class HiddenTrack(val d: Drawable, val alpha: Int, val callback: Drawable.Callback?) {
        /** Canvas save count of the clip that shapes Nubia's fill to the user's corner, or 0. */
        var clip = 0
        var canvas: Canvas? = null

        fun restore() {
            canvas?.takeIf { clip > 0 }?.restoreToCount(clip)
            d.callback = null
            d.alpha = alpha
            d.callback = callback
        }
    }

    private val sliderCache = WeakHashMap<View, Boolean>()
    private var sliderLogged = false

    /** A brightness or volume slider inside the control centre (not a tile's or the media player's bar). */
    private fun isControlCenterSlider(bar: View): Boolean {
        sliderCache[bar]?.let { return it }
        if (!bar.isAttachedToWindow) return false
        val ok = inControlCenter(bar) && idName(bar) != "media_seek_bar"
        sliderCache[bar] = ok
        return ok
    }

    private class SliderParentSpec(val bar: WeakReference<android.widget.SeekBar>)
    private val sliderParents = WeakHashMap<View, SliderParentSpec>()
    private val sliderParentRect = RectF()

    /** Draws the slider's cross-window blur on its unrotated FrameLayout wrapper so BlurRegion keeps pill corners. */
    private fun drawSliderParentBlur(vg: View, c: Canvas, spec: SliderParentSpec) {
        val bar = spec.bar.get() ?: run { dropBlur(vg); return }
        val st = GlassSettings[Surface.CONTROL_CENTER, night(vg)]
        if (!st.enabled || reducedTransparency(vg) || !dim.available || vg.width <= 0 || vg.height <= 0) {
            dropBlur(vg); return
        }
        val d = bar.progressDrawable ?: return
        val track = (d as? LayerDrawable)?.let { it.findDrawableByLayerId(android.R.id.background) ?: it.getDrawable(0) } ?: return
        if (track.bounds.isEmpty) return
        val dp = vg.resources.displayMetrics.density
        // SeekBar is rotated 270 deg and centred in vg: swap width/height into unrotated wrapper space.
        val uw = if (kotlin.math.abs(bar.rotation % 180f) > 45f) track.bounds.height().toFloat() else track.bounds.width().toFloat()
        val uh = if (kotlin.math.abs(bar.rotation % 180f) > 45f) track.bounds.width().toFloat() else track.bounds.height().toFloat()
        val l = (vg.width - uw) / 2f
        val t = (vg.height - uh) / 2f
        sliderParentRect.set(l, t, l + uw, t + uh)
        val radii = if (st.cornerDp != GlassStyle.ORIGINAL) cornerOverride(st, sliderParentRect, dp)
            else radiiOf(track) ?: GlassSkin.uniform(minOf(uw, uh) / 2f)
        liveBlur(vg, c, sliderParentRect, radii, st.blurPx(Looks.PANEL_CARD_BLUR_PX), Looks.panelSaturation(st))
    }

    /**
     * Draws glass where the slider's track is and hides Nubia's track for this draw, keeping its
     * progress fill and thumb. Null when nothing was drawn (the original track draws).
     */
    private fun drawSliderTrack(bar: android.widget.SeekBar, c: Canvas): HiddenTrack? {
        val st = GlassSettings[Surface.CONTROL_CENTER, night(bar)]
        if (!st.enabled || reducedTransparency(bar)) {
            dropBlur(bar)
            (bar.parent as? View)?.let { dropBlur(it) }
            return null
        }
        val d = bar.progressDrawable ?: return null
        val track = (d as? LayerDrawable)?.let { it.findDrawableByLayerId(android.R.id.background) ?: it.getDrawable(0) } ?: return null
        if (!sliderLogged) {
            sliderLogged = true
            Log.i(TAG, "cc slider ${bar.javaClass.name} ${bar.width}x${bar.height} rot=${bar.rotation} progress=${d.javaClass.simpleName}" +
                " layers=${(d as? LayerDrawable)?.numberOfLayers} track=${track.javaClass.simpleName} ${track.bounds}" +
                " parentBg=${(bar.parent as? View)?.background?.javaClass?.simpleName}")
        }
        val b = track.bounds
        if (b.isEmpty) return null
        if (!settingsAttached) { settingsAttached = true; HookSettings.attach(bar.context); followLockState(bar.context) }
        sliders[bar] = true
        val unrotatedParent = (bar.parent as? View)?.takeIf { kotlin.math.abs(bar.rotation) > 1f && kotlin.math.abs(it.rotation) < 1f }
        if (unrotatedParent != null) {
            dropBlur(bar)
            if (sliderParents[unrotatedParent]?.bar?.get() !== bar) {
                sliderParents[unrotatedParent] = SliderParentSpec(WeakReference(bar))
                unrotatedParent.setWillNotDraw(false)
                unrotatedParent.invalidate()
            }
        }
        val dp = bar.resources.displayMetrics.density
        val left = (bar.paddingLeft - bar.scrollX).toFloat()
        val top = (bar.paddingTop - bar.scrollY).toFloat()
        rect.set(b.left + left, b.top + top, b.right + left, b.bottom + top)
        val radii = if (st.cornerDp != GlassStyle.ORIGINAL) cornerOverride(st, rect, dp)
            else radiiOf(track) ?: GlassSkin.uniform(minOf(rect.width(), rect.height()) / 2)
        // Clip both skin and Nubia's progress fill strictly to the pill/rounded shape so no square ears leak.
        val hidden = HiddenTrack(track, track.alpha, track.callback)
        hidden.canvas = c
        hidden.clip = c.save()
        sliderClip.reset()
        sliderClip.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
        c.clipPath(sliderClip)
        drawGlass(bar, c, rect, radii, Surface.CONTROL_CENTER, st, null, dp, withBlur = unrotatedParent == null)
        track.callback = null
        track.alpha = 0
        track.callback = hidden.callback
        return hidden
    }

    private val sliderClip = android.graphics.Path()

    /** Control-centre sliders seen so far, redrawn with the cards. */
    private val sliders = WeakHashMap<View, Boolean>()

    /** The user's tile corner: large values make 2x1 / 1x2 tiles and sliders pills (see [GlassSkin.tileCorner]). */
    private fun cornerOverride(st: GlassStyle, r: RectF, dp: Float): FloatArray =
        GlassSkin.uniform(GlassSkin.tileCorner(st.cornerDp * dp, r.width(), r.height()))

    private fun isControlCenterPanel(v: View): Boolean {
        val n = v.javaClass.name
        if (n.startsWith("com.zte.qs.tileimpl.QSTileViewWide") || n == "com.zte.controlcenter.view.ControlCenterMediaPlayerView") return true
        val p = v.parent ?: return false
        return v is android.widget.LinearLayout && p.javaClass.name.startsWith("com.zte.qs.tileimpl.QSTileViewCircle")
    }

    private fun radiiOf(d: Drawable): FloatArray? {
        val g = (d as? GradientDrawable)
            ?: ((d as? LayerDrawable)?.takeIf { it.numberOfLayers > 0 }?.getDrawable(0) as? GradientDrawable) ?: return null
        g.cornerRadii?.takeIf { it.size == 8 }?.let { return it }
        return g.cornerRadius.takeIf { it > 0f }?.let { GlassSkin.uniform(it) }
    }

    private val loggedSources = HashSet<String>()

    /**
     * The fill colour the user currently sees. Nubia colours tile states several ways, so check in
     * order: a colour filter on the drawable, the drawable's tint (GradientDrawable state `mTint`),
     * the view's background tint list, then the drawable's own colour.
     */
    private fun colorOf(d: Drawable, host: View): Int? {
        val state = host.drawableState
        val (src, c) = run {
            when (val f = d.colorFilter) {
                is android.graphics.BlendModeColorFilter -> return@run "filter" to f.color
                is android.graphics.PorterDuffColorFilter -> return@run "filter" to (XposedHelpers.callMethod(f, "getColor") as Int)
            }
            try {
                val gs = XposedHelpers.getObjectField(d, "mGradientState")
                (XposedHelpers.getObjectField(gs, "mTint") as? android.content.res.ColorStateList)?.let {
                    return@run "tint" to it.getColorForState(state, it.defaultColor)
                }
            } catch (_: Throwable) {}
            if (host.background === d) host.backgroundTintList?.let { return@run "viewTint" to it.getColorForState(state, it.defaultColor) }
            if (d !is GradientDrawable) {
                val f = XposedHelpers.findFieldIfExists(d.javaClass, "mColors")
                (f?.get(d) as? IntArray)?.firstOrNull()?.let { return@run "mColors" to it }
                return null
            }
            val g = d
            g.color?.let { return@run "color" to it.getColorForState(state, it.defaultColor) }
            // Nubia's tiles fill with a gradient colour array (setColors), not a solid colour.
            g.colors?.takeIf { it.isNotEmpty() }?.let { cs ->
                return@run "colors" to (if (cs.size == 1) cs[0] else cs.maxByOrNull { Color.alpha(it) }!!)
            }
            return null
        }
        if (loggedSources.add("$src ${host.javaClass.simpleName}")) Log.i(TAG, "tile colour from $src: #${Integer.toHexString(c)} on ${host.javaClass.simpleName}")
        return c
    }

    /** On-state control-centre tiles are solid accent fills; keep them. */
    private fun isSolid(c: Int) = Color.alpha(c) >= 0xE0 && !(Color.red(c) > 0xF0 && Color.green(c) > 0xF0 && Color.blue(c) > 0xF0)

    private fun night(v: View) = (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** "High contrast text" means opaque panels; re-read at most every 5 s (it is a settings read). */
    private fun reducedTransparency(v: View): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - reducedAt > 5000) { reducedAt = now; reduced = GlassAccessibility.prefersReducedTransparency(v.context) }
        return reduced
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SYSTEMUI = "com.android.systemui"
        private var installed = false
        private const val SHADE_WINDOW = "NotificationShadeWindowView"
        private const val CC_WINDOW = "ControlPanelWindowView"
        private val PANEL_BUTTONS = setOf(
            "settings_button", "control_center_menu_button", "search_button",
            "actionPrev", "actionPlayPause", "actionNext", "expand_button_icon",
        )

        /** Glass on the shade's centre media card and the lock screen's media card. */
        private const val MEDIA_CARD_GLASS = true

        /**
         * Glass skin on notification cards and control-centre tiles/panels (white tint, body and
         * sheen). Off while their own background blur is examined: Nubia's original drawables draw.
         * Tracking and hooks stay installed, so this only gates the draw.
         */
        private const val PANEL_SKIN_DRAW = true
    }
}
