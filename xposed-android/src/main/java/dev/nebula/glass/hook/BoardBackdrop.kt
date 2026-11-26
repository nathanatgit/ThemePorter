package dev.nebula.glass.hook

import android.util.Log
import android.view.SurfaceControl
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Blur and dim behind the widget board (user request 2026-11-24), like the panels'.
 *
 * The board (MiBoard) has no backdrop of its own: when the launcher scrolls to it, the launcher's
 * depth controller blurs the wallpaper behind the launcher window
 * (`Transaction.setBackgroundBlurRadius` on its base surface, `doWallpaperBlurForMiBoard`). So here,
 * in the launcher, while the board shows:
 *  - that radius is scaled by the board look's Blur;
 *  - a black layer beneath the launcher window ([PanelDim]) dims what the blur samples, scaled by
 *    the board look's Dim and by how far the board has slid in.
 *
 * How far it has slid in comes from the workspace's overlay-scroll callback (Launcher3's
 * `onOverlayScrollChanged(float)`, obfuscated in Nubia's build, so found by its shape).
 */
class BoardBackdrop : IXposedHookLoadPackage {
    @Volatile private var progress = 0f
    private var workspace: WeakReference<View>? = null
    private val dim = PanelDim()

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != LAUNCHER || app.processName != LAUNCHER) return
        HookSettings.load()
        GlassSettings.addListener { applyDim() }
        val ws = XposedHelpers.findClassIfExists("com.android.launcher3.Workspace", app.classLoader) ?: run {
            Log.w(TAG, "board backdrop: Workspace not found"); return
        }
        val scroll = overlayScroll(ws) ?: run { Log.w(TAG, "board backdrop: overlay scroll callback not found"); return }
        Log.i(TAG, "board backdrop: overlay scroll = ${scroll.declaringClass.name}.${scroll.name}")
        try {
            XposedBridge.hookMethod(scroll, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        val v = p.thisObject as View
                        if (workspace?.get() !== v) { workspace = WeakReference(v); HookSettings.attach(v.context) }
                        progress = (p.args[0] as Float).coerceIn(0f, 1f)
                        applyDim()
                    } catch (t: Throwable) { Log.e(TAG, "board scroll", t) }
                }
            })
            XposedHelpers.findAndHookMethod(SurfaceControl.Transaction::class.java, "setBackgroundBlurRadius",
                SurfaceControl::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (progress <= 0f) return
                        val st = GlassSettings[Surface.WIDGET_BOARD, workspace?.get()?.context]
                        if (!st.enabled || st.blur == 100) return
                        if (p.args[0] !== baseSurface()) return
                        p.args[1] = ((p.args[1] as Int) * st.blur / 100f).toInt()
                    }
                })
            Log.i(TAG, "board backdrop hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "board backdrop hooks failed", t)
        }
    }

    /** The Workspace method implementing a one-float overlay callback interface. */
    private fun overlayScroll(ws: Class<*>): Method? {
        val float = Float::class.javaPrimitiveType
        for (iface in ws.interfaces) {
            val ms = iface.declaredMethods.filter { !it.isSynthetic }
            if (ms.size != 1) continue
            val m = ms[0]
            if (m.returnType != Void.TYPE || m.parameterTypes.size != 1 || m.parameterTypes[0] != float) continue
            return XposedHelpers.findMethodExactIfExists(ws, m.name, float) ?: continue
        }
        return XposedHelpers.findMethodExactIfExists(ws, "onOverlayScrollChanged", float)
    }

    private fun baseSurface(): SurfaceControl? = try {
        val root = XposedHelpers.callMethod(workspace?.get() ?: return null, "getViewRootImpl") ?: return null
        XposedHelpers.callMethod(root, "getSurfaceControl") as? SurfaceControl
    } catch (_: Throwable) { null }

    private fun applyDim() {
        val sc = baseSurface()?.takeIf { it.isValid } ?: return
        val st = GlassSettings[Surface.WIDGET_BOARD, workspace?.get()?.context]
        val a = if (st.enabled) Looks.backdropDim(Surface.WIDGET_BOARD, st) * progress else 0f
        val t = SurfaceControl.Transaction()
        try {
            if (dim.set(t, sc, Surface.WIDGET_BOARD, a)) t.apply()
        } finally { t.close() }
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val LAUNCHER = "com.zte.mifavor.launcher"
    }
}
