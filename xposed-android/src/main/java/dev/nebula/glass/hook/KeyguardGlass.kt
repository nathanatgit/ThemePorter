package dev.nebula.glass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.util.Log
import android.view.View
import android.view.ViewParent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassDrawable
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import java.util.Collections
import java.util.WeakHashMap

/**
 * Phase K1: ColorOS-style glass on the live lock screen (SystemUI `NotificationShade` window).
 *
 * Trace facts (docs/LIQUID_GLASS_PORT.md §0): no view draws the wallpaper, so glass samples the
 * lock wallpaper bitmap ([WallpaperBackdrop.sharedLock]). Targets:
 *  - widget band: `#widget_background` inside `com.zte.feature.keyguardwidget.KeyguardWidgetContainer`
 *    (Nubia uses BackgroundBlurDrawable / ColorDrawable there; replaced, never stacked)
 *  - bottom shortcuts: `KeyguardAffordanceView#left_button` / `#camera_button` get a glass circle.
 * Editor previews (`MyDepthLockScreenEditActivity`) are left alone: different window and backdrop.
 *
 * Everything runs inside SystemUI: every hook body is guarded, and the original background is the
 * fallback whenever glass cannot draw.
 *
 * Switch: adb shell am broadcast -a com.nathanhanapps.nebulaColorize.KEYGUARD --es mode glass|stock
 * (stock restores the saved original backgrounds immediately).
 */
class KeyguardGlass : IXposedHookLoadPackage {
    private var enabled = true
    private val originals = WeakHashMap<View, Drawable?>()
    private val styled: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    /** Lock-screen widget background views we watch, glassed or not yet. */
    private val widgets: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private var switchRegistered = false
    /** Every glass drawable we installed, for live invalidation when settings change. */
    private val glasses = WeakHashMap<View, GlassDrawable>()

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        val widgetContainer = XposedHelpers.findClassIfExists("com.zte.feature.keyguardwidget.KeyguardWidgetContainer", app.classLoader)
        val affordance = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.KeyguardAffordanceView", app.classLoader)
        Log.i(TAG, "keyguard glass: widgetContainer=${widgetContainer != null} affordance=${affordance != null}")
        HookSettings.load()
        GlassSettings.addListener { for (g in glasses.values.toList()) g.invalidateSelf() }
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    try {
                        when {
                            widgetContainer != null && idName(v) == "widget_background" && hasAncestor(v, widgetContainer) ->
                                v.post { guard { styleWidget(v) } }
                            affordance != null && affordance.isInstance(v) ->
                                v.post { guard { styleShortcut(v) } }
                        }
                    } catch (t: Throwable) { Log.e(TAG, "keyguard attach", t) }
                }
            })
            // Nubia assigns the widget BackgroundBlurDrawable after attach (and again on updates).
            // Keep whatever it sets as the fallback and keep glass installed.
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", Drawable::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    val d = p.args[0] as? Drawable
                    if (!enabled || d is GlassDrawable || !widgets.contains(v)) return
                    if (!isPanel(d)) {
                        // Not a panel (e.g. the transparent screen-time background): keep Nubia's shape.
                        styled.remove(v); originals.remove(v); return
                    }
                    try {
                        originals[v] = d
                        styled.add(v)
                        p.args[0] = widgetGlass(v, d)
                    } catch (t: Throwable) { Log.e(TAG, "keyguard setBackground", t) }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "keyguard hooks failed", t)
        }
    }

    private fun styleWidget(v: View) {
        widgets.add(v)
        registerSwitch(v.context.applicationContext)
        if (!enabled || v.background is GlassDrawable) return
        val original = v.background
        // Only real panels become glass; null/transparent backgrounds wait for Nubia to set one.
        if (!isPanel(original)) return
        install(v, original, widgetGlass(v, original))
    }

    /** Widget panel glass in Nubia's own shape; user settings (LOCK_WIDGET) are applied per draw. */
    private fun widgetGlass(v: View, original: Drawable?): GlassDrawable {
        val dp = v.resources.displayMetrics.density
        val corner = cornerOf(original) ?: (20 * dp)
        return GlassDrawable(v, Looks.base(Surface.LOCK_WIDGET, v.context), original,
            prepare = { Looks.style(it, Surface.LOCK_WIDGET, dp, corner) }) { source(v, Surface.LOCK_WIDGET) }
            .also { glasses[v] = it }
    }

    private fun styleShortcut(v: View) {
        if (!enabled || styled.contains(v)) return
        if (v.width == 0 || v.height == 0) {
            v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(x: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (r - l <= 0) return
                    x.removeOnLayoutChangeListener(this)
                    guard { styleShortcut(x) }
                }
            })
            return
        }
        val dp = v.resources.displayMetrics.density
        val original = v.background
        // 202 px touch target; ColorOS-like ~52 dp glass circle centred in it ("Original" = circle).
        val inset = ((minOf(v.width, v.height) - 52 * dp) / 2f).toInt().coerceAtLeast(0)
        val glass = GlassDrawable(v, Looks.base(Surface.LOCK_SHORTCUT, v.context), null,
            prepare = { Looks.style(it, Surface.LOCK_SHORTCUT, dp, 999f) }) { source(v, Surface.LOCK_SHORTCUT) }
        glasses[v] = glass
        install(v, original, InsetDrawable(glass, inset))
    }

    private fun install(v: View, original: Drawable?, replacement: Drawable) {
        originals[v] = original
        styled.add(v)
        v.background = replacement
        registerSwitch(v.context.applicationContext)
        Log.i(TAG, "keyguard glass on ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} was=${original?.javaClass?.simpleName}")
    }

    /** Backdrop, or null (draw the original) when the user switched this surface off. */
    private fun source(v: View, s: Surface): View? {
        if (!GlassSettings[s].enabled) return null
        val b = WallpaperBackdrop.sharedLock(v.context)
        if (b.isLive || b.bitmap == null) return null
        val root = v.rootView
        if (root.width > 0) b.layoutTo(root.width, root.height)
        return b
    }

    /** A background that draws a panel: Nubia's blur panel or a rounded/filled shape. */
    private fun isPanel(d: Drawable?): Boolean = when {
        d == null -> false
        d.javaClass.name.endsWith("BackgroundBlurDrawable") -> true
        d is android.graphics.drawable.GradientDrawable -> true
        else -> false
    }

    /** BackgroundBlurDrawable keeps its radii in private fields; read them if present. */
    private fun cornerOf(d: Drawable?): Float? {
        d ?: return null
        (d as? android.graphics.drawable.GradientDrawable)?.let { g ->
            return g.cornerRadii?.maxOrNull() ?: g.cornerRadius.takeIf { it > 0f }
        }
        for (name in listOf("mCornerRadiusTL", "mCornerRadius")) {
            try {
                val f = XposedHelpers.findFieldIfExists(d.javaClass, name) ?: continue
                val r = (f.get(d) as? Number)?.toFloat() ?: continue
                if (r > 0f) return r
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun registerSwitch(ctx: Context) {
        if (switchRegistered) return
        switchRegistered = true
        HookSettings.attach(ctx)
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val glass = when (i.getStringExtra("mode")) { "glass" -> true; "stock" -> false; else -> return }
                enabled = glass
                if (!glass) {
                    for ((v, d) in originals.entries.toList()) { v.background = d }
                    styled.clear()
                } else {
                    for (v in originals.keys.toList()) {
                        if (idName(v) == "widget_background") styleWidget(v) else styleShortcut(v)
                    }
                }
                Log.i(TAG, "keyguard mode=${if (glass) "glass" else "stock"}")
            }
        }, IntentFilter(SWITCH), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    private fun hasAncestor(v: View, cls: Class<*>): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) { if (cls.isInstance(p)) return true; p = p.parent }
        return false
    }

    private fun idName(v: View): String? = try {
        if (v.id == View.NO_ID) null else v.resources.getResourceEntryName(v.id)
    } catch (_: Exception) { null }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SYSTEMUI = "com.android.systemui"
        const val SWITCH = "com.nathanhanapps.nebulaColorize.KEYGUARD"

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.e(TAG, "keyguard", t) }
        }
    }
}
