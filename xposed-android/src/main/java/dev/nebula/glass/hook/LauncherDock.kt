package dev.nebula.glass.hook

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import java.lang.ref.WeakReference

/**
 * Phase L1: a Liquid Glass panel behind the Nubia launcher dock.
 *
 * Trace facts (docs/LIQUID_GLASS_PORT.md §0): `Hotseat#hotseat` has no background and the
 * launcher window shows the wallpaper, so the panel is installed as the Hotseat's background
 * drawable (drawn before the icons) and samples [WallpaperBackdrop] (backdrop source 2).
 * Wallpaper parallax follows the launcher's own setWallpaperOffsets calls.
 *
 * Test switch, shell only (needs DUMP):
 *   adb shell am broadcast -a com.nathanhanapps.nebulacolorize.DOCK --es mode glass|raw|off
 * `raw` paints the unfiltered backdrop inside the panel: if the crop matches ImageWallpaper,
 * the panel area is indistinguishable from the real wallpaper.
 */
class LauncherDock : IXposedHookLoadPackage {
    private var backdrop: WallpaperBackdrop? = null
    private var dock: WeakReference<View>? = null
    private var drawable: DockDrawable? = null
    /** Shell override (glass|raw|off); null = follow the "Dock panel" toggle in the settings app. */
    private var forced: String? = null

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != LAUNCHER || app.processName != LAUNCHER) return
        HookSettings.load()
        GlassSettings.addListener { guard { apply() } }
        dev.nebula.glass.LightSourceController.addRedraw { drawable?.invalidateSelf() }
        val hotseat = XposedHelpers.findClassIfExists("com.android.launcher3.Hotseat", app.classLoader) ?: run {
            Log.w(TAG, "Hotseat not found"); return
        }
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    if (hotseat.isInstance(v)) guard { install(v) }
                }
            })
            XposedHelpers.findAndHookMethod(WallpaperManager::class.java, "setWallpaperOffsets",
                IBinder::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        guard { WallpaperBackdrop.setPageOffsets(p.args[1] as Float, p.args[2] as Float) }
                    }
                })
        } catch (t: Throwable) {
            Log.e(TAG, "dock hooks failed", t)
        }
    }

    private fun install(v: View) {
        if (v.background is DockDrawable) return
        val ctx = v.context
        if (backdrop == null) {
            backdrop = WallpaperBackdrop.shared(ctx)
            // A background drawable sits in its own RenderNode: only invalidateSelf() re-records it, so
            // the dock follows wallpaper scrolling and changes (View.invalidate() left it frozen).
            val redraw = { drawable?.invalidateSelf(); Unit }
            backdrop!!.addListener(redraw)
            WallpaperBackdrop.sharedBlurred(ctx).addListener(redraw)
            registerSwitch(ctx.applicationContext)
            HookSettings.attach(ctx)
        }
        val d = DockDrawable(v)
        drawable = d
        dock = WeakReference(v)
        apply()
        Log.i(TAG, "dock glass ready on ${v.javaClass.name} ${v.width}x${v.height} on=${isOn()}")
    }

    private fun isOn() = forced?.let { it != "off" } ?: GlassSettings[Surface.DOCK].enabled

    /** Show or remove the panel to match the settings toggle (or the shell override). */
    private fun apply() {
        val v = dock?.get() ?: return
        val d = drawable ?: return
        d.raw = forced == "raw"
        val want = if (isOn()) d else null
        if (v.background !== want) v.background = want
        d.invalidateSelf()
    }

    private fun registerSwitch(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val m = i.getStringExtra("mode") ?: return
                // "settings" hands control back to the app's toggle.
                forced = when (m) { "glass", "raw", "off" -> m; "settings" -> null; else -> return }
                apply()
                Log.i(TAG, "dock mode=${forced ?: "settings"} on=${isOn()}")
            }
        }, IntentFilter(SWITCH), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    /** Glass panel sized around the dock icons; drawn as the Hotseat background. */
    private class DockDrawable(private val host: View) : Drawable() {
        val glass = Looks.base(Surface.DOCK, host.context)
        var raw = false
        private val shape = RectF()
        private val icons = RectF()
        private val clip = Path()
        private val a = IntArray(2)
        private val b = IntArray(2)
        private val loc = IntArray(2)
        private var recX = Int.MIN_VALUE
        private var recY = Int.MIN_VALUE
        private var observer: android.view.ViewTreeObserver? = null

        // The panel is recorded into the drawable's own RenderNode with a sample offset taken from the
        // host's screen position. During unlock the Hotseat is transiently far off-screen; without this
        // the panel stays recorded against that position (samples outside the wallpaper = dark).
        private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
            try {
                if (host.isShown) {
                    host.getLocationOnScreen(loc)
                    if (loc[0] != recX || loc[1] != recY) invalidateSelf()
                }
            } catch (_: Throwable) {}
            true
        }

        init {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = watch()
                override fun onViewDetachedFromWindow(v: View) = unwatch()
            })
            if (host.isAttachedToWindow) watch()
        }

        private fun watch() {
            if (observer != null) return
            observer = host.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        }

        private fun unwatch() {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            observer = null
        }

        override fun draw(canvas: Canvas) {
            guard {
                host.getLocationOnScreen(loc)
                recX = loc[0]; recY = loc[1]
                if (!panel()) return@guard
                // Unified look: style, blur and glow from the settings app. Like folders and
                // widgets, 100 % blur samples the shared pre-blurred wallpaper (no GPU blur).
                val dp = host.resources.displayMetrics.density
                val night = GlassSettings.isNight(host.context)
                val st = GlassSettings[Surface.DOCK, night]
                val pre = st.blur == 100
                Looks.style(glass, Surface.DOCK, dp, minOf(30 * dp, shape.height() / 2f), night = night, preBlurred = pre, st = st)
                val source = if (pre) WallpaperBackdrop.sharedBlurred(host.context) else WallpaperBackdrop.shared(host.context)
                val root = host.rootView
                if (root.width > 0) source.layoutTo(root.width, root.height)
                if (raw) { drawRaw(canvas); return@guard }
                val ok = !source.isLive && source.bitmap != null && glass.draw(canvas, host, source, shape)
                // Wallpaper still loading (first unlock): draw nothing rather than a dark slab. The
                // backdrop listener redraws once it arrives. Opaque fallback only for live wallpapers.
                if (!ok && source.isLive) glass.drawOpaque(canvas, host, shape)
            }
        }

        /** Unfiltered backdrop clipped to the panel: alignment check against the real wallpaper. */
        private fun drawRaw(canvas: Canvas) {
            host.getLocationOnScreen(a)
            val r = glass.cornerRadius.coerceAtMost(minOf(shape.width(), shape.height()) / 2f)
            clip.reset(); clip.addRoundRect(shape, r, r, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.translate(-a[0].toFloat(), -a[1].toFloat())
            WallpaperBackdrop.shared(host.context).draw(canvas)
            canvas.restoreToCount(save)
        }

        /** Panel = full width minus margins, vertically hugging the icon row. */
        private fun panel(): Boolean {
            val w = host.width.toFloat()
            val h = host.height.toFloat()
            if (w <= 0f || h <= 0f) return false
            val dp = host.resources.displayMetrics.density
            icons.setEmpty()
            host.getLocationOnScreen(a)
            union(host)
            val top: Float
            val bottom: Float
            // Auto-fit to the icons' real bounds (so any DPI), then the user's width and padding.
            val shapes = GlassSettings.config.shapes
            val pad = shapes.dockPad * dp
            if (icons.isEmpty) {
                top = h * 0.12f; bottom = h * 0.78f
            } else {
                top = (icons.top - pad).coerceAtLeast(0f)
                bottom = (icons.bottom + pad).coerceAtMost(h)
            }
            val full = w - 24 * dp
            val width = full * shapes.dockWidth / 100f
            shape.set((w - width) / 2f, top, (w + width) / 2f, bottom)
            glass.cornerRadius = minOf(30 * dp, shape.height() / 2f)
            return shape.width() > 0f && shape.height() > 0f
        }

        /** Union of visible icon views (BubbleTextView) in host coordinates. */
        private fun union(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v !== host && v.javaClass.name.endsWith("BubbleTextView") && v.width > 0) {
                v.getLocationOnScreen(b)
                var l = (b[0] - a[0]).toFloat()
                var t = (b[1] - a[1]).toFloat()
                var r = l + v.width
                var btm = t + v.height
                // Dock icons hide their label; the view still reserves it. Use the icon drawable.
                val icon = (v as? android.widget.TextView)?.compoundDrawables?.get(1)
                if (icon != null && icon.bounds.width() > 0) {
                    val iw = icon.bounds.width()
                    l += (v.width - iw) / 2f
                    t += v.paddingTop
                    r = l + iw
                    btm = t + icon.bounds.height()
                }
                icons.union(l, t, r, btm)
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) union(v.getChildAt(i))
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val LAUNCHER = "com.zte.mifavor.launcher"
        const val SWITCH = "com.nathanhanapps.nebulacolorize.DOCK"

        /** A throw inside the launcher's draw or attach path must never escape. */
        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.e(TAG, "dock", t) }
        }
    }
}
