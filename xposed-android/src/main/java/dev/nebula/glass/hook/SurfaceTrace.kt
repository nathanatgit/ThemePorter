package dev.nebula.glass.hook

import android.app.Application
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * Phase L0 diagnostic: logs, once per instance, where each candidate glass surface lives in
 * the Nubia launcher, and who applies blur. Read-only: no drawing or layout is changed.
 *
 * Answers the open questions in docs/LIQUID_GLASS_PORT.md:
 *  - which Hotseat/Folder/drawer/popup instances are visible, their bounds, parents, backgrounds
 *  - whether the window shows the wallpaper (FLAG_SHOW_WALLPAPER) → backdrop source 2
 *  - whether `launcher3.blur.*` / OneShotBlurLayout drive window blur or view RenderEffects
 *
 * Capture: `adb shell setprop log.tag I`, `adb logcat -s NebulaGlass`, then `setprop log.tag S`.
 */
open class SurfaceTrace(
    private val packages: Set<String>,
    private val surfaces: List<String>,
    private val activities: List<String> = emptyList(),
) : IXposedHookLoadPackage {
    private val seen: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val blurCallers = HashSet<String>()

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName !in packages || app.processName != app.packageName) return
        log("loaded in ${Application.getProcessName()}")
        val classes = surfaces.mapNotNull { name ->
            XposedHelpers.findClassIfExists(name, app.classLoader).also { if (it == null) log("missing $name") }
        }
        hook(classes, "onAttachedToWindow") { v -> v.post { describe(v, "attach") } }
        hook(classes, "onVisibilityAggregated", Boolean::class.javaPrimitiveType!!) { v ->
            if (v.isShown) v.post { describe(v, "shown") }
        }
        traceBlur(app.classLoader)
        if (activities.isNotEmpty()) try {
            // Activity.onResume, filtered: subclasses may not override it.
            XposedHelpers.findAndHookMethod(android.app.Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val a = p.thisObject as android.app.Activity
                    val name = a.javaClass.name
                    if (name !in activities) return
                    a.window.decorView.postDelayed({ dumpTree(a.window.decorView, 0, name.substringAfterLast('.')) }, 800)
                }
            })
        } catch (t: Throwable) { Log.w(TAG, "hook onResume", t) }
        // On demand: `am broadcast -a com.nathanhanapps.nebulaColorize.DUMP` (shell holds DUMP) logs every window tree.
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val ctx = p.thisObject as Application
                    val filter = android.content.IntentFilter(DUMP_ACTION)
                    ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                            for (root in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                                val lp = root.layoutParams as? WindowManager.LayoutParams
                                if (!root.isShown) continue
                                val owner = (lp?.title ?: root.javaClass.simpleName).toString().substringAfterLast('/')
                                dumpTree(root, 0, owner)
                            }
                            log("dump done in ${Application.getProcessName()}")
                        }
                    }, filter, android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
                }
            })
        } catch (t: Throwable) { Log.w(TAG, "hook onCreate", t) }
    }

    /** One line per visible view: class, id, screen position, size, background, notable state. */
    private fun dumpTree(v: View, depth: Int, owner: String) {
        if (depth > 16 || !v.isShown || v.width == 0 || v.height == 0) return
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val extra = buildString {
            v.background?.let { append(" bg=").append(it.javaClass.name.substringAfterLast('.')) }
            if (v is android.widget.ImageView) v.drawable?.let { append(" img=").append(it.javaClass.name.substringAfterLast('.')) }
            if (v is android.widget.TextView && v.text.isNotEmpty()) append(" text=\"").append(v.text.take(16)).append('"')
            if (hasRenderEffect(v)) append(" renderEffect")
            if (v.alpha < 1f) append(" a=").append(v.alpha)
        }
        log("tree $owner ${". ".repeat(depth)}${v.javaClass.name}${idName(v)} ${loc[0]},${loc[1]} ${v.width}x${v.height}$extra")
        v.background?.let { describeDrawable(it, v) }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, owner)
    }

    private val describedDrawables = HashSet<String>()

    /** Once per non-framework drawable class: bounds, padding, and every field up the hierarchy. */
    private fun describeDrawable(d: android.graphics.drawable.Drawable, host: View) {
        val name = d.javaClass.name
        if (name.startsWith("android.") || !describedDrawables.add(name)) return
        log("drawable $name on ${host.javaClass.simpleName} ${host.width}x${host.height} pad=${host.paddingLeft},${host.paddingTop},${host.paddingRight},${host.paddingBottom} bounds=${d.bounds} alpha=${d.alpha}")
        var c: Class<*>? = d.javaClass
        while (c != null && !c.name.startsWith("android.") && !c.name.startsWith("java.")) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val value = try {
                    f.isAccessible = true
                    when (val x = f.get(d)) {
                        null -> "null"
                        is FloatArray -> x.joinToString(",", "[", "]")
                        is IntArray -> x.joinToString(",", "[", "]")
                        is android.graphics.Bitmap -> "Bitmap ${x.width}x${x.height} ${x.config}"
                        is android.graphics.Paint -> "Paint color=#${Integer.toHexString(x.color)} alpha=${x.alpha} shader=${x.shader?.javaClass?.simpleName} filter=${x.colorFilter?.javaClass?.simpleName}"
                        is Number, is Boolean, is String, is android.graphics.Rect, is android.graphics.RectF -> x.toString()
                        else -> x.javaClass.name
                    }
                } catch (t: Throwable) { "?" }
                log("drawable $name.${f.name}: ${f.type.simpleName} = ${value.take(160)}")
            }
            c = c.superclass
        }
    }

    private fun hook(classes: List<Class<*>>, method: String, vararg types: Class<*>, after: (View) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(View::class.java, method, *types, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as? View ?: return
                    if (classes.any { it.isInstance(v) }) try { after(v) } catch (t: Throwable) { Log.w(TAG, "trace", t) }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "hook $method", t)
        }
    }

    private fun describe(v: View, why: String) {
        if (!v.isShown || !seen.add(System.identityHashCode(v).toString() + why)) return
        val r = Rect().also { v.getGlobalVisibleRect(it) }
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val chain = buildString {
            var p = v.parent
            var n = 0
            while (p is View && n < 6) { append(" < ").append(p.javaClass.name.substringAfterLast('.')).append(idName(p)); p = p.parent; n++ }
        }
        val lp = v.rootView.layoutParams as? WindowManager.LayoutParams
        val showWallpaper = lp != null && lp.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0
        log("$why ${v.javaClass.name}${idName(v)} screen=${loc[0]},${loc[1]} size=${v.width}x${v.height} " +
            "visible=$r bg=${v.background?.javaClass?.name} renderEffect=${hasRenderEffect(v)} " +
            "window=${lp?.title} showWallpaper=$showWallpaper blurBehind=${lp?.blurBehindRadius ?: -1}$chain")
    }

    /** Logs each distinct launcher caller of the blur APIs once, with the calling frames. */
    private fun traceBlur(loader: ClassLoader) {
        val sites = listOf(
            Triple(android.view.Window::class.java, "setBackgroundBlurRadius", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)),
            Triple(android.view.SurfaceControl.Transaction::class.java, "setBackgroundBlurRadius",
                arrayOf<Class<*>>(android.view.SurfaceControl::class.java, Int::class.javaPrimitiveType!!)),
            Triple(View::class.java, "setRenderEffect", arrayOf<Class<*>>(android.graphics.RenderEffect::class.java)),
            Triple(WindowManager.LayoutParams::class.java, "setBlurBehindRadius", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)),
        )
        for ((cls, name, types) in sites) {
            try {
                XposedHelpers.findAndHookMethod(cls, name, *types, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val frames = Throwable().stackTrace
                            .map { "${it.className}.${it.methodName}" }
                            .filter { it.startsWith("com.android.launcher3") || it.startsWith("com.zte") }
                            .take(6)
                        val site = "$name<-" + frames.firstOrNull()
                        if (frames.isEmpty() || !blurCallers.add(site)) return
                        log("blur $name(${p.args.drop(if (name == "setBackgroundBlurRadius" && p.args.size == 2) 1 else 0).joinToString()}) " +
                            "target=${p.thisObject?.javaClass?.name} via ${frames.joinToString(" < ")}")
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "hook $name", t)
            }
        }
        // Constructors of the obfuscated blur package tell us which helpers exist at runtime.
        for (suffix in listOf("a", "b", "c", "d", "e", "f")) {
            val name = "com.android.launcher3.blur.$suffix"
            try {
                val cls = XposedHelpers.findClassIfExists(name, loader) ?: continue
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) { log("new $name") }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun hasRenderEffect(v: View): Boolean = try {
        XposedHelpers.callMethod(XposedHelpers.getObjectField(v, "mRenderNode"), "hasRenderEffect") as? Boolean ?: false
    } catch (_: Throwable) { false }

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "" else "#" + v.resources.getResourceEntryName(v.id)
    } catch (_: Exception) { "" }

    private fun log(msg: String) = Log.i(TAG, msg)

    companion object {
        internal const val TAG = "NebulaGlass"
        internal const val DUMP_ACTION = "com.nathanhanapps.nebulaColorize.DUMP"
    }
}

/** Launcher: dock, folders, drawer, popups, widget sheets, and who calls blur. */
class LauncherTrace : SurfaceTrace(
    setOf("com.zte.mifavor.launcher"),
    listOf(
        "com.android.launcher3.Hotseat",
        "com.android.launcher3.hotseat.HotseatRecentCellLayout",
        "com.android.launcher3.Workspace",
        "com.android.launcher3.folder.Folder",
        "com.android.launcher3.folder.FolderIcon",
        "com.android.launcher3.allapps.LauncherAllAppsContainerView",
        "com.android.launcher3.popup.PopupContainerWithArrow",
        "com.android.launcher3.views.OptionsPopupView",
        "com.android.launcher3.widget.WidgetsBottomSheet",
        "com.android.launcher3.pageindicators.PageIndicatorDots",
        "com.android.launcher3.views.ScrimView",
        "com.android.launcher3.oneshot.OneShotBlurLayout",
    )
)

/**
 * Lock screen: which keyguard views are live, whether one draws the wallpaper in-tree
 * (backdrop source 1) and the editor/gallery activities' view trees. Read-only.
 */
class KeyguardTrace : SurfaceTrace(
    setOf("com.android.systemui", "com.zte.mfvkeyguard"),
    listOf(
        "com.zte.lockscreen.base.lockView.BaseLockView",
        "com.zte.lockscreen.base.lockView.ImageLockView",
        "com.zte.lockscreen.base.lockView.CustomImageView",
        "com.zte.lockscreen.base.clockView.ClockView",
        "com.zte.lockscreen.base.clockView.SingleClockView",
        "com.zte.lockscreen.base.clockView.DoubleClockView",
        "com.zte.lockscreen.base.clockView.ClockStatusView",
        "com.zte.lockscreen.base.clockView.single.DateView",
    ),
    listOf(
        "com.zte.mifavor.keyguard.personalclock.MyLockScreenSettingsActivity",
        "com.zte.mifavor.keyguard.personalclock.MyDepthLockScreenEditActivity",
    )
)

/** Nubia's widget board (the page left of home): its own process and overlay window. Dump only. */
class BoardTrace : SurfaceTrace(setOf("com.zte.mifavor.miboard"), emptyList())
