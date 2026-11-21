package dev.nebula.glass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.LensGlass
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import java.util.WeakHashMap

/**
 * ColorOS-style glass on Nubia's large folder tiles.
 *
 * Trace facts: each `TransformFolderIcon` has background `X1.a`, a wrapper around a static
 * NinePatchDrawable whose bounds are the visible tile (e.g. 376×146 at 39,68 in a 454×327 cell).
 * ColorOS 17's large folders are a saturated, lightened blur of the wallpaper
 * (docs/reference/coloros17/launcher). We replace `X1.a.draw` with a lens in the same bounds,
 * sampling [WallpaperBackdrop]. Because the wallpaper does not scroll with the workspace,
 * visible tiles are re-recorded on workspace scroll.
 *
 * Switch: adb shell am broadcast -a com.nathanhanapps.nebulaColorize.FOLDER --es mode glass|stock
 */
class LauncherFolders : IXposedHookLoadPackage {
    private var sharp: WallpaperBackdrop? = null
    private var blurred: WallpaperBackdrop? = null
    private var attached = false
    private var enabled = true
    private val lenses = WeakHashMap<View, LensGlass>()
    /** Tile host → its X1.a. A background drawable is only re-recorded when the drawable itself is invalidated. */
    private val tiles = WeakHashMap<View, Drawable>()
    private val rect = RectF()
    /** Screen position each tile's backdrop was recorded at; packed x shl 32 | y. */
    private val recordedAt = WeakHashMap<View, Long>()
    private val roots: MutableSet<View> = java.util.Collections.newSetFromMap(WeakHashMap())
    private val loc = IntArray(2)
    private val visible = android.graphics.Rect()

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != LAUNCHER || app.processName != LAUNCHER) return
        val tileDrawable = XposedHelpers.findClassIfExists("X1.a", app.classLoader)
        val workspace = XposedHelpers.findClassIfExists("com.android.launcher3.Workspace", app.classLoader)
        if (tileDrawable == null || workspace == null || !Drawable::class.java.isAssignableFrom(tileDrawable)) {
            Log.w(TAG, "folder tile classes not found: X1.a=$tileDrawable"); return
        }
        HookSettings.load()
        GlassSettings.addListener { for (d in tiles.values) d.invalidateSelf() }
        try {
            // X1.a inherits draw(); hook the declaring method and filter to X1.a instances.
            val draw = tileDrawable.getMethod("draw", Canvas::class.java)
            Log.i(TAG, "X1.a draw declared by ${draw.declaringClass.name}, super=${tileDrawable.superclass?.name}")
            de.robv.android.xposed.XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!enabled || !tileDrawable.isInstance(p.thisObject)) return
                    try {
                        if (drawGlass(p.thisObject as Drawable, p.args[0] as Canvas)) p.result = null
                    } catch (t: Throwable) {
                        Log.e(TAG, "folder tile", t)
                    }
                }
            })
            XposedHelpers.findAndHookMethod(View::class.java, "onScrollChanged",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (enabled && workspace.isInstance(p.thisObject)) invalidateVisibleTiles()
                    }
                })
            Log.i(TAG, "folder glass hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "folder hooks failed", t)
        }
    }

    private fun drawGlass(d: Drawable, canvas: Canvas): Boolean {
        val host = d.callback as? View ?: return false
        if (d.bounds.isEmpty) return false
        val unified = GlassSettings[Surface.FOLDER]
        if (!unified.enabled) return false
        // A folder the user coloured in Nubia keeps its own colour over the unified accent.
        val own = folderColor(d)
        val style = if (own != null) unified.copy(accent = own) else unified
        // At 100 % blur the CPU pre-blurred copy is used as is; any other intensity blurs the sharp copy on the GPU.
        val preBlurred = style.blur == 100
        val source = backdrop(host, preBlurred)
        if (source.isLive || source.bitmap == null) return false
        val root = host.rootView
        if (root.width > 0) source.layoutTo(root.width, root.height)
        tiles[host] = d
        host.getLocationOnScreen(loc)
        recordedAt[host] = (loc[0].toLong() shl 32) or (loc[1].toLong() and 0xffffffffL)
        watch(root)
        val lens = lenses.getOrPut(host) { Looks.base(Surface.FOLDER, host.context) }
        val dp = host.resources.displayMetrics.density
        Looks.style(lens, Surface.FOLDER, dp, 16 * dp, preBlurred = preBlurred, st = style)
        rect.set(d.bounds)
        return lens.draw(canvas, host, source, rect)
    }

    private var loggedState = false
    private val seenColors = HashSet<String>()

    /**
     * The folder's own Nubia colour, if the user set one: a colour filter or tint on the tile drawable
     * (`X1.a`) or on the nine-patch it wraps. Null for the default frosted tile.
     */
    private fun folderColor(d: Drawable): Int? {
        if (!loggedState) {
            loggedState = true
            try {
                val st = XposedHelpers.getObjectField(d, "h")
                Log.i(TAG, "folder tile state ${st?.javaClass?.name}: " + st?.javaClass?.declaredFields?.joinToString { f ->
                    f.isAccessible = true; "${f.name}=${try { f.get(st) } catch (_: Throwable) { "?" }}" }?.take(400))
            } catch (_: Throwable) {}
        }
        fun of(x: Drawable?): Int? {
            x ?: return null
            when (val f = x.colorFilter) {
                is android.graphics.BlendModeColorFilter -> return f.color
                is android.graphics.PorterDuffColorFilter -> return XposedHelpers.callMethod(f, "getColor") as Int
            }
            for (stateField in listOf("mNinePatchState", "mState", "mDrawableState")) {
                try {
                    val st = XposedHelpers.getObjectField(x, stateField) ?: continue
                    val tint = XposedHelpers.getObjectField(st, "mTint") as? android.content.res.ColorStateList ?: continue
                    return tint.getColorForState(x.state, tint.defaultColor)
                } catch (_: Throwable) {}
            }
            return null
        }
        val outer = of(d)
        val inner = of((d as? android.graphics.drawable.DrawableWrapper)?.drawable)
        val key = "${outer?.let { Integer.toHexString(it) }}/${inner?.let { Integer.toHexString(it) }}"
        if (seenColors.add(key)) Log.i(TAG, "folder tile colour outer=${outer?.let { Integer.toHexString(it) }} inner=${inner?.let { Integer.toHexString(it) }}")
        val c = outer ?: inner ?: return null
        // Nubia's default folder tint is a neutral grey (#80F1F1F1 in light mode): that follows the unified
        // accent. Only a coloured tint (a folder colour the user picked) overrides it.
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
        if (hsv[1] < 0.15f) return null
        // Keep the user's hue; make sure it reads as a tint on glass.
        return if ((c ushr 24) < 0x40) (c and 0xFFFFFF) or (0x59 shl 24) else c
    }

    /**
     * Pages may move by translation rather than scroll, so check every frame: a tile whose
     * screen position differs from where its backdrop was recorded is re-recorded.
     */
    private fun watch(root: View) {
        if (!roots.add(root)) return
        root.viewTreeObserver.addOnPreDrawListener {
            if (enabled) try {
                for ((t, d) in tiles) {
                    if (!t.isAttachedToWindow || !t.getGlobalVisibleRect(visible)) continue
                    t.getLocationOnScreen(loc)
                    val now = (loc[0].toLong() shl 32) or (loc[1].toLong() and 0xffffffffL)
                    if (recordedAt[t] != now) d.invalidateSelf()
                }
            } catch (t: Throwable) { Log.e(TAG, "folder watch", t) }
            true
        }
    }

    private fun invalidateVisibleTiles() {
        for ((t, d) in tiles) if (t.isAttachedToWindow && t.getGlobalVisibleRect(visible)) d.invalidateSelf()
    }

    private fun backdrop(host: View, preBlurred: Boolean): WallpaperBackdrop {
        if (!attached) {
            attached = true
            registerSwitch(host.context.applicationContext)
            HookSettings.attach(host.context)
        }
        val existing = if (preBlurred) blurred else sharp
        return existing ?: (if (preBlurred) WallpaperBackdrop.sharedBlurred(host.context) else WallpaperBackdrop.shared(host.context)).also { b ->
            if (preBlurred) blurred = b else sharp = b
            b.addListener { for (d in tiles.values) d.invalidateSelf() }
        }
    }

    private fun registerSwitch(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                enabled = when (i.getStringExtra("mode")) { "glass" -> true; "stock" -> false; else -> return }
                for (d in tiles.values) d.invalidateSelf()
                Log.i(TAG, "folder mode=${if (enabled) "glass" else "stock"}")
            }
        }, IntentFilter(SWITCH), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val LAUNCHER = "com.zte.mifavor.launcher"
        const val SWITCH = "com.nathanhanapps.nebulaColorize.FOLDER"
    }
}
