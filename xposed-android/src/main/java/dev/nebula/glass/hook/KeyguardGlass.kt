package dev.nebula.glass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
 * Lock-screen widgets and bottom shortcuts (SystemUI `NotificationShade` window):
 *  - Widgets (`#widget_background` inside `KeyguardWidgetContainer`): bare on the wallpaper with
 *    their backdrop panel removed, matching iOS. Switching `LOCK_WIDGET` off restores Nubia's panel.
 *  - Bottom shortcuts (`KeyguardAffordanceView`): refractive Liquid Glass circles.
 */
class KeyguardGlass : IXposedHookLoadPackage {
    private var enabled = true
    private val originals = WeakHashMap<View, Drawable?>()
    private val styled: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val widgets: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val shortcuts: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private var switchRegistered = false
    private val glasses = WeakHashMap<View, GlassDrawable>()
    private val bareWidgetBg = ColorDrawable(Color.TRANSPARENT)

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        if (installed) return
        installed = true
        val widgetContainer = XposedHelpers.findClassIfExists("com.zte.feature.keyguardwidget.KeyguardWidgetContainer", app.classLoader)
        val affordance = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.KeyguardAffordanceView", app.classLoader)
        Log.i(TAG, "keyguard glass: widgetContainer=${widgetContainer != null} affordance=${affordance != null}")
        HookSettings.load()
        GlassSettings.addListener {
            for (v in widgets.toList()) guard { refreshWidget(v) }
            for ((v, g) in glasses.entries.toList()) {
                if (shortcuts.contains(v)) guard { fitShortcut(v, g) }
                g.invalidateSelf()
            }
        }
        refit = { for ((v, g) in glasses.entries.toList()) if (shortcuts.contains(v)) guard { fitShortcut(v, g) } }
        dev.nebula.glass.LightSourceController.addRedraw { for (g in glasses.values.toList()) g.invalidateSelf() }
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
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", Drawable::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    val d = p.args[0] as? Drawable
                    if (!widgets.contains(v) || d === bareWidgetBg || d is GlassDrawable) return
                    try {
                        originals[v] = d
                        if (enabled && GlassSettings[Surface.LOCK_WIDGET, v.context].enabled) {
                            styled.add(v)
                            p.args[0] = bareWidgetBg
                        }
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
        val cur = v.background
        if (cur !== bareWidgetBg && cur !is GlassDrawable) originals[v] = cur
        refreshWidget(v)
    }

    private fun refreshWidget(v: View) {
        if (!v.isAttachedToWindow) return
        val bare = enabled && GlassSettings[Surface.LOCK_WIDGET, v.context].enabled
        if (bare) {
            if (v.background !== bareWidgetBg) {
                if (!originals.containsKey(v)) originals[v] = v.background
                styled.add(v)
                v.background = bareWidgetBg
            }
        } else if (v.background === bareWidgetBg) {
            styled.remove(v)
            v.background = originals[v]
        }
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
        val glass = GlassDrawable(v, Looks.base(Surface.LOCK_SHORTCUT, v.context), null,
            prepare = {
                val night = GlassSettings.isNight(v.context)
                Looks.style(it, Surface.LOCK_SHORTCUT, dp, 999f, night = night, st = GlassSettings[Surface.LOCK_SHORTCUT, night])
            }) { source(v, Surface.LOCK_SHORTCUT) }
        glasses[v] = glass
        shortcuts.add(v)
        install(v, original, InsetDrawable(glass, shortcutInset(v)))
    }

    private fun shortcutInset(v: View): Int =
        ((minOf(v.width, v.height) - shortcutCircle(v)) / 2f).toInt().coerceAtLeast(0)

    private fun shortcutCircle(v: View): Float {
        val stock = originals[v]?.intrinsicWidth ?: -1
        val side = minOf(v.width, v.height).toFloat()
        if (shortcutSize > 0f && shortcutSize <= side) return shortcutSize.also { circles[v] = it }
        return (if (stock > 0 && stock <= side) stock.toFloat() else minOf(side, 52 * v.resources.displayMetrics.density))
            .also { circles[v] = it }
    }

    private fun fitShortcut(v: View, glass: GlassDrawable) {
        if (!enabled || !styled.contains(v) || v.width == 0) return
        val inset = shortcutInset(v)
        if (glass.bounds.left != inset) v.background = InsetDrawable(glass, inset)
    }

    private fun install(v: View, original: Drawable?, replacement: Drawable) {
        originals[v] = original
        styled.add(v)
        v.background = replacement
        registerSwitch(v.context.applicationContext)
        Log.i(TAG, "keyguard glass on ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} was=${original?.javaClass?.simpleName}")
    }

    private fun source(v: View, s: Surface): View? {
        if (!GlassSettings[s, v.context].enabled) return null
        val b = WallpaperBackdrop.sharedLock(v.context)
        if (b.isLive || b.bitmap == null) return null
        val root = v.rootView
        if (root.width > 0) b.layoutTo(root.width, root.height)
        return b
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
                        if (idName(v) == "widget_background") refreshWidget(v) else styleShortcut(v)
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
        private var installed = false
        private const val SYSTEMUI = "com.android.systemui"
        const val SWITCH = "com.nathanhanapps.nebulacolorize.KEYGUARD"

        private val circles = java.util.WeakHashMap<View, Float>()

        fun shortcutDiameter(v: View): Float? = circles[v]

        @Volatile private var shortcutSize = 0f
        private var refit: (() -> Unit)? = null

        fun setShortcutSize(px: Float) {
            if (kotlin.math.abs(px - shortcutSize) < 1f) return
            shortcutSize = px
            refit?.invoke()
        }

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.e(TAG, "keyguard", t) }
        }
    }
}
