package dev.nebula.glass.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassTextHost
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import java.util.Collections
import java.util.WeakHashMap

/**
 * Glass clock digits and date/lunar texts on the live lock screen (SystemUI `NotificationShade`).
 *
 * The host TextView keeps its layout, time updates and accessibility. Only `TextView.onDraw` is
 * intercepted, and only for bound instances: the glass glyphs are drawn by [GlassText] and the
 * stock draw is skipped. Whenever glass cannot draw (SDF still building, API < 33, shader failure,
 * live wallpaper) the stock text is drawn, so the clock is never blank.
 *
 * Targets, by id under `keyguard_clock_container` / `LockScreenClock*` (docs/reference/nebulaos/lockscreen-trace):
 * `clock_view` (mifavor TextClock), `date_view` (DateView), `lunar_date_widget_horiz`.
 * The editor activity is left alone (different window and backdrop).
 *
 * Switch: adb shell am broadcast -a com.nathanhanapps.nebulaColorize.KEYGUARDTEXT --es mode glass|stock
 */
class KeyguardText : IXposedHookLoadPackage {
    private var enabled = true
    private val glass = GlassTextHost()
    private val checked = WeakHashMap<TextView, Boolean>()
    private val live: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())
    private var switchRegistered = false
    private var listening = false

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        HookSettings.load()
        GlassSettings.addListener { for (t in live.toList()) t.postInvalidate() }
        try {
            XposedHelpers.findAndHookMethod(TextView::class.java, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val style = GlassSettings[Surface.LOCK_CLOCK]
                    if (!enabled || !style.enabled) return
                    val tv = p.thisObject as TextView
                    try {
                        if (!isTarget(tv)) return
                        if (glass.draw(tv, p.args[0] as Canvas, source(tv), style)) p.result = null
                    } catch (t: Throwable) { Log.e(TAG, "keyguard text", t) }
                }
            })
            Log.i(TAG, "keyguard text hook installed")
        } catch (t: Throwable) {
            Log.e(TAG, "keyguard text hook failed", t)
        }
    }

    private fun isTarget(tv: TextView): Boolean {
        checked[tv]?.let { return it }
        if (!tv.isAttachedToWindow) return false
        var ok = false
        val id = idName(tv)
        val wanted = id == "clock_view" || id == "date_view" || id == "lunar_date_widget_horiz" ||
            tv.javaClass.simpleName == "TextClock"
        // AOD lives in its own DecorView window: leave it stock.
        if (wanted && activityOf(tv.context) == null && tv.rootView.javaClass.simpleName.startsWith("NotificationShade")) {
            var p: ViewParent? = tv.parent
            while (p != null) {
                if (p is View && (idName(p) == "keyguard_clock_container" ||
                        p.javaClass.name.contains("LockScreenClock"))) { ok = true; break }
                p = p.parent
            }
        }
        checked[tv] = ok
        if (ok) {
            live.add(tv)
            registerSwitch(tv.context.applicationContext)
            HookSettings.attach(tv.context)
            Log.i(TAG, "keyguard text bound: ${tv.javaClass.simpleName}#$id")
        }
        return ok
    }

    private fun source(v: View): WallpaperBackdrop {
        val b = WallpaperBackdrop.sharedLock(v.context)
        if (!listening) {
            listening = true
            b.addListener { for (t in live.toList()) t.postInvalidate() }
        }
        return b
    }

    private fun registerSwitch(ctx: Context) {
        if (switchRegistered) return
        switchRegistered = true
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                enabled = when (i.getStringExtra("mode")) { "glass" -> true; "stock" -> false; else -> return }
                for (t in live.toList()) t.postInvalidate()
                Log.i(TAG, "keyguard text mode=${if (enabled) "glass" else "stock"}")
            }
        }, IntentFilter(SWITCH), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    private fun activityOf(c0: Context): Activity? {
        var c: Context? = c0
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    private fun idName(v: View): String? = GlassTextHost.idName(v)

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SYSTEMUI = "com.android.systemui"
        const val SWITCH = "com.nathanhanapps.nebulaColorize.KEYGUARDTEXT"
    }
}
