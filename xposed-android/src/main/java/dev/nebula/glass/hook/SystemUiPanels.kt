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
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
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

    private class Target(host: View, val surface: Surface, val radii: () -> FloatArray?) {
        val host = WeakReference(host)
    }

    private val tracked = WeakHashMap<Drawable, Target>()
    private val hostsByDrawable get() = tracked.values.mapNotNull { it.host.get() }
    private val hookedDraws = HashSet<Method>()
    private val skin = GlassSkin()
    private val rect = RectF()
    private var bgField: Field? = null
    private var radiiField: Field? = null
    private var settingsAttached = false
    private var reducedAt = 0L
    private var reduced = false

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        HookSettings.load()
        GlassSettings.addListener { for (h in hostsByDrawable) h.invalidate() }
        val notif = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.row.NotificationBackgroundView", app.classLoader)
        if (notif != null) {
            bgField = XposedHelpers.findFieldIfExists(notif, "mBackground")
            radiiField = XposedHelpers.findFieldIfExists(notif, "mCornerRadii")
            Log.i(TAG, "notification bg field=${bgField?.name} radii=${radiiField?.name}")
        } else Log.w(TAG, "NotificationBackgroundView not found")
        try {
            val refresh = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try { refresh(p.thisObject as View, notif) } catch (t: Throwable) { Log.e(TAG, "panel track", t) }
                }
            }
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", refresh)
            // Tiles swap or recolour their background on state changes; notifications set custom ones.
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", Drawable::class.java, refresh)
            if (notif != null) XposedBridge.hookAllMethods(notif, "setCustomBackground", refresh)
            Log.i(TAG, "notification / control centre glass hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "panel hooks failed", t)
        }
    }

    /**
     * The shade's media player background (`NotificationMediaPanelNoBlurBg`) is 561 px tall inside a
     * 395 px container that clips it, so Nubia's own card shows square, cut-off edges (seen with no
     * hooks at all). Draw a glass card fitted to the visible container instead.
     */
    private var mediaHooked = false

    /** The class lives in Nubia's feature classloader, so it is hooked from the first live instance. */
    private fun hookMediaPanel(cls: Class<*>) {
        if (mediaHooked) return
        mediaHooked = true
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
        val st = GlassSettings[Surface.NOTIFICATION]
        if (!st.enabled || reducedTransparency(v)) return false
        val container = v.parent as? View ?: return false
        val dp = v.resources.displayMetrics.density
        // Visible part = the container's box, in this view's coordinates, on both axes (the panel is
        // wider and taller than the container that clips it, so square corners were being cut off).
        val left = (-v.left).toFloat().coerceAtLeast(0f)
        val top = (-v.top).toFloat().coerceAtLeast(0f)
        mediaRect.set(left, top,
            (left + container.width).coerceAtMost(v.width.toFloat()),
            (top + container.height).coerceAtMost(v.height.toFloat()))
        // Corners: the container's own blur shape if it has one (so glass fills the rounded blur),
        // else the largest radius a notification card uses (middle cards of a group use 0).
        val own = containerRadius(container)
        val radius = own ?: tracked.values.filter { it.surface == Surface.NOTIFICATION }
            .mapNotNull { it.radii()?.maxOrNull() }.maxOrNull()?.takeIf { it > 0f } ?: (20 * dp)
        val radii = if (st.cornerDp != GlassStyle.ORIGINAL) GlassSkin.uniform(st.cornerDp * dp) else GlassSkin.uniform(radius)
        if (!mediaLogged) {
            mediaLogged = true
            Log.i(TAG, "media panel rect=$mediaRect view=${v.left},${v.top} ${v.width}x${v.height} container=${container.width}x${container.height} " +
                "bg=${container.background?.javaClass?.name} outlineR=$own cornerR=$radius")
        }
        val tint = if (st.accent != GlassStyle.AUTO) st.accent else Looks.tint(Surface.NOTIFICATION, night(v))
        skin.draw(c, mediaRect, radii, scaleAlpha(tint, st.blur), Looks.specular(Surface.NOTIFICATION) * st.edge / 100f, dp)
        return true
    }

    /** Track the current background drawable of a notification card or control-centre panel. */
    private fun refresh(v: View, notif: Class<*>?) {
        if (!mediaHooked && v.javaClass.name == "com.zte.feature.dynamicisland.NotificationMediaPanelNoBlurBg") {
            hookMediaPanel(v.javaClass); v.invalidate()
        }
        when {
            notif != null && notif.isInstance(v) -> {
                val d = bgField?.get(v) as? Drawable ?: return
                track(d, v, Surface.NOTIFICATION) { (radiiField?.get(v) as? FloatArray)?.takeIf { it.size == 8 } ?: radiiOf(d) }
            }
            isControlCenterPanel(v) -> {
                val d = v.background ?: return
                if (d is GradientDrawable) track(d, v, Surface.CONTROL_CENTER) { radiiOf(d) }
            }
            // Long-press tile sheet (Bluetooth / Wi-Fi device list): QSDetail with QsDetailModule$RoundCornerDrawable.
            v.javaClass.name == "com.zte.mifavor.qs.QSDetail" -> {
                val d = v.background ?: return
                val dp = v.resources.displayMetrics.density
                track(d, v, Surface.CONTROL_CENTER) { radiiOf(d) ?: GlassSkin.uniform(24 * dp) }
            }
        }
    }

    private fun track(d: Drawable, host: View, surface: Surface, radii: () -> FloatArray?) {
        if (tracked[d]?.host?.get() === host) return
        if (!settingsAttached) { settingsAttached = true; HookSettings.attach(host.context) }
        tracked[d] = Target(host, surface, radii)
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
        val host = t.host.get() ?: return false
        val st = GlassSettings[t.surface]
        if (!st.enabled || reducedTransparency(host)) return false
        val color = colorOf(d, host)
        if (t.surface == Surface.CONTROL_CENTER && color != null && isSolid(color)) return false
        val dp = host.resources.displayMetrics.density
        rect.set(d.bounds)
        val radii = if (st.cornerDp != GlassStyle.ORIGINAL) GlassSkin.uniform(st.cornerDp * dp)
            else t.radii() ?: GlassSkin.uniform(16 * dp)
        val tint = when {
            st.accent != GlassStyle.AUTO -> st.accent
            Looks.tint(t.surface, night(host)) != 0 -> Looks.tint(t.surface, night(host))
            else -> color ?: 0x40FFFFFF
        }
        skin.draw(c, rect, radii, scaleAlpha(tint, st.blur), Looks.specular(t.surface) * st.edge / 100f, dp)
        return true
    }

    /** Glass intensity (0..200 %) scales a skin's body opacity. */
    private fun scaleAlpha(argb: Int, percent: Int): Int {
        val a = ((argb ushr 24) * percent / 100).coerceIn(0, 255)
        return (argb and 0xFFFFFF) or (a shl 24)
    }

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
    }
}
