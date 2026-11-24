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
 * Switch: adb shell am broadcast -a com.nathanhanapps.nebulacolorize.KEYGUARDTEXT --es mode glass|stock
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
        if (installed) return   // SystemUI calls this twice, the second time with another classloader
        installed = true
        HookSettings.load()
        GlassSettings.addListener { for (t in live.toList()) t.postInvalidate() }
        try {
            XposedHelpers.findAndHookMethod(TextView::class.java, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val style = GlassSettings[Surface.LOCK_CLOCK]
                    val tv = p.thisObject as TextView
                    try {
                        val glassy = enabled && style.enabled && isTarget(tv)
                        // The resized clock draws with or without glass, and in the editor too.
                        if (LockClock.draw(tv, p.args[0] as Canvas, if (glassy) style else null, glass) { source(tv) }) {
                            p.result = null
                            return
                        }
                        if (!glassy) return
                        if (glass.draw(tv, p.args[0] as Canvas, source(tv), style, neutral = true)) p.result = null
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
        // Every text clock style's digits (default, incarnation, horizontal, vertical, clip), the
        // calendar lines, and the custom text under the clock ("owner_info").
        // Not the editor's style thumbnails: they stay stock ([GlassTextHost.inList]).
        val wanted = (id in IDS || tv is android.widget.TextClock) && !GlassTextHost.inList(tv)
        // AOD lives in its own DecorView window: leave it stock. Nubia's editor shows the lock
        // screen full size over the same wallpaper, so it gets glass too (user request 2026-11-24).
        val activity = activityOf(tv.context)
        val host = if (activity == null) tv.rootView.javaClass.simpleName.startsWith("NotificationShade")
            else activity.javaClass.name == LockEditor.ACTIVITY
        if (wanted && host) {
            var p: ViewParent? = tv.parent
            while (p != null) {
                if (p is View && (idName(p) in CONTAINERS ||
                        p.javaClass.name.contains("LockScreenClock") || p.javaClass.simpleName == "ClockStatusView")) { ok = true; break }
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
        private var installed = false
        private const val SYSTEMUI = "com.android.systemui"
        const val SWITCH = "com.nathanhanapps.nebulacolorize.KEYGUARDTEXT"
        private val IDS = setOf(
            "clock_view", "date_view", "lunar_date_widget_horiz", "lunar_date_widget", "owner_info",
            "hours", "minute", "month", "day", "clock_hour_view", "clock_minute_view",
        )
        private val CONTAINERS = setOf("keyguard_clock_container", "clock_status_view", "keyguard_status_view")
    }
}
