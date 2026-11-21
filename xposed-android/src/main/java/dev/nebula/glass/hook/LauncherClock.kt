package dev.nebula.glass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect
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
 * Glass digits and date on Nubia's home-screen clock widget, the same look as the lock-screen clock
 * ([KeyguardText]). The widget is RemoteViews inflated in the launcher: two TextClocks,
 * `appwidget_text_time_mfvclr` and `appwidget_text_day_week_mfvclr`, under an AppWidgetHostView.
 *
 * The widget moves with workspace pages while the wallpaper does not, so a pre-draw check
 * re-records a bound clock whenever its screen position changes (as the folder tiles do).
 * Drag shadows and previews draw on software canvases, where glass declines and stock is drawn.
 *
 * Switch: adb shell am broadcast -a com.nathanhanapps.nebulaColorize.CLOCKWIDGET --es mode glass|stock
 */
class LauncherClock : IXposedHookLoadPackage {
    private var enabled = true
    private val glass = GlassTextHost()
    private val checked = WeakHashMap<TextView, Boolean>()
    /** Bound clocks → screen position their backdrop was recorded at; packed x shl 32 | y. */
    private val live = WeakHashMap<TextView, Long>()
    private val roots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val loc = IntArray(2)
    private val visible = Rect()
    private var backdrop: WallpaperBackdrop? = null
    private var switchRegistered = false

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != LAUNCHER || app.processName != LAUNCHER) return
        HookSettings.load()
        GlassSettings.addListener { for (t in live.keys.toList()) t.postInvalidate() }
        try {
            XposedHelpers.findAndHookMethod(TextView::class.java, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val style = GlassSettings[Surface.HOME_CLOCK]
                    if (!enabled || !style.enabled) return
                    val tv = p.thisObject as TextView
                    try {
                        if (!isTarget(tv)) return
                        val canvas = p.args[0] as Canvas
                        if (glass.draw(tv, canvas, source(tv), style)) p.result = null
                        tv.getLocationOnScreen(loc)
                        live[tv] = pack(loc)
                    } catch (t: Throwable) { Log.e(TAG, "clock widget", t) }
                }
            })
            Log.i(TAG, "clock widget hook installed")
        } catch (t: Throwable) {
            Log.e(TAG, "clock widget hook failed", t)
        }
    }

    private fun isTarget(tv: TextView): Boolean {
        checked[tv]?.let { return it }
        if (!tv.isAttachedToWindow) return false
        val id = GlassTextHost.idName(tv)
        var ok = false
        if (id != null && (id in IDS || (id.startsWith("appwidget_text_") && id.contains("mfvclr")))) {
            var p: ViewParent? = tv.parent
            while (p != null) {
                if (p is android.appwidget.AppWidgetHostView) { ok = true; break }
                p = p.parent
            }
        }
        checked[tv] = ok
        if (ok) {
            live[tv] = Long.MIN_VALUE
            registerSwitch(tv.context.applicationContext)
            HookSettings.attach(tv.context)
            watch(tv.rootView)
            Log.i(TAG, "clock widget text bound: #$id")
        }
        return ok
    }

    private fun source(v: View): WallpaperBackdrop = backdrop ?: WallpaperBackdrop.shared(v.context).also { b ->
        backdrop = b
        b.addListener { for (t in live.keys.toList()) t.postInvalidate() }
    }

    /** Pages move by scroll or translation; re-record any visible clock whose screen position changed. */
    private fun watch(root: View) {
        if (!roots.add(root)) return
        root.viewTreeObserver.addOnPreDrawListener {
            if (enabled) try {
                for ((t, at) in live) {
                    if (!t.isAttachedToWindow || !t.getGlobalVisibleRect(visible)) continue
                    t.getLocationOnScreen(loc)
                    if (pack(loc) != at) t.invalidate()
                }
            } catch (t: Throwable) { Log.e(TAG, "clock widget watch", t) }
            true
        }
    }

    private fun pack(l: IntArray) = (l[0].toLong() shl 32) or (l[1].toLong() and 0xffffffffL)

    private fun registerSwitch(ctx: Context) {
        if (switchRegistered) return
        switchRegistered = true
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                enabled = when (i.getStringExtra("mode")) { "glass" -> true; "stock" -> false; else -> return }
                for (t in live.keys.toList()) t.postInvalidate()
                Log.i(TAG, "clock widget mode=${if (enabled) "glass" else "stock"}")
            }
        }, IntentFilter(SWITCH), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val LAUNCHER = "com.zte.mifavor.launcher"
        private val IDS = setOf("appwidget_text_time_mfvclr", "appwidget_text_day_week_mfvclr")
        const val SWITCH = "com.nathanhanapps.nebulaColorize.CLOCKWIDGET"
    }
}
