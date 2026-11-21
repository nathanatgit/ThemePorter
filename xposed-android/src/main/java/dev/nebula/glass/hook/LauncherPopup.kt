package dev.nebula.glass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.CompositeBackdrop
import dev.nebula.glass.GlassDrawable
import dev.nebula.glass.GlassMaterial
import dev.nebula.glass.LensGlass
import dev.nebula.glass.WallpaperBackdrop

/**
 * ColorOS-style glass for the launcher long-press menu.
 *
 * Trace (docs/reference/nebulaos/popup): `PopupContainerWithArrow#popup_container` holds
 * `#deep_shortcuts_container` (rounded GradientDrawable, rows `DeepShortcutView` with white
 * GradientDrawable fills) and `#system_shortcuts_container` whose `DeepShortcutView#system_shortcut`
 * row is its own rounded panel. Each panel becomes a lens over wallpaper + launcher content
 * ([CompositeBackdrop]); row fills are cleared so the glass shows, ripples and text stay stock.
 * Light/dark follows the system night mode, keeping Nubia's own text colours legible.
 *
 * Switch: adb shell am broadcast -a com.nathanhanapps.nebulaColorize.POPUP --es mode glass|stock --es style native|pill
 */
class LauncherPopup : IXposedHookLoadPackage {
    private var enabled = true
    private var composite: CompositeBackdrop? = null
    /**
     * Panel shape. `native` (default) keeps Nubia's shapes exactly and only swaps the fill for glass.
     * `pill` turns the horizontal icon row into one capsule: an alternate style for a future setting.
     */
    private var style = "native"

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != LAUNCHER || app.processName != LAUNCHER) return
        val popup = XposedHelpers.findClassIfExists("com.android.launcher3.popup.PopupContainerWithArrow", app.classLoader)
            ?: run { Log.w(TAG, "popup class not found"); return }
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    if (enabled && popup.isInstance(v)) v.post { guard { style(v as ViewGroup) } }
                }
            })
            Log.i(TAG, "popup glass hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "popup hooks failed", t)
        }
    }

    private fun style(popup: ViewGroup) {
        val ctx = popup.context
        val back = composite ?: CompositeBackdrop(ctx.applicationContext, WallpaperBackdrop.shared(ctx)).also {
            composite = it
            registerSwitch(ctx.applicationContext)
        }
        val root = popup.rootView
        if (root.width > 0) back.layoutTo(root.width, root.height)
        back.content = popup.parent as? View   // DragLayer: workspace, hotseat, popup
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val panels = ArrayList<View>()
        find(popup, "deep_shortcuts_container")?.let { group ->
            panels.add(group)
            if (group is ViewGroup) for (i in 0 until group.childCount) clearFill(group.getChildAt(i))
        }
        (find(popup, "system_shortcuts_container") as? ViewGroup)?.let { group ->
            val horizontal = (group as? android.widget.LinearLayout)?.orientation == android.widget.LinearLayout.HORIZONTAL
            if (horizontal) {
                // Icon-row variant (uninstall / share / info): one panel for the whole row,
                // shaped by Nubia's outline (native) or as a capsule (pill style).
                panels.add(group)
                for (i in 0 until group.childCount) clearFill(group.getChildAt(i))
            } else {
                for (i in 0 until group.childCount) panels.add(group.getChildAt(i))
                group.background = null
            }
        }
        for (panel in panels) {
            val original = panel.background
            if (original is GlassDrawable) continue
            val lens = LensGlass().apply {
                val dp = panel.resources.displayMetrics.density
                material = GlassMaterial.REGULAR
                blurAmount = 0.8f                 // ≈ 30 px: icons behind read as colour, not shapes
                saturation = 170f
                glassTint = if (night) 0xB82A2A2E.toInt() else 0xB8F7F7F9.toInt()
                bevelWidth = 8 * dp
                refractionHeight = 14 * dp
                dispersion = 0.04f
                specular = 0.7f
                val horizontalRow = panel is android.widget.LinearLayout &&
                    panel.orientation == android.widget.LinearLayout.HORIZONTAL
                cornerRadius = when {
                    horizontalRow && style == "pill" -> 999f
                    else -> corner(original) ?: outlineRadius(panel) ?: outlineRadius(popup) ?: (16 * dp)
                }
                refreshAccessibility(ctx)
            }
            panel.background = GlassDrawable(panel, lens, original) {
                back.glass = panel
                back
            }
        }
        tintArrow(popup, night)
        Log.i(TAG, "popup glass on ${panels.size} panels style=$style night=$night")
    }

    /**
     * The arrow is a small plain View child of the popup (stock paints it white). A layer paint with a
     * SRC_IN colour filter recolours whatever it draws and keeps its alpha, so the arrow's own
     * drawable class does not matter. Uses the glass body tint so arrow and panel read as one.
     */
    private fun tintArrow(popup: ViewGroup, night: Boolean) {
        val dp = popup.resources.displayMetrics.density
        val tint = if (night) 0xFF2A2A2E.toInt() else 0xFFF7F7F9.toInt()
        for (i in 0 until popup.childCount) {
            val c = popup.getChildAt(i)
            if (c is ViewGroup || c.background == null || c.width == 0) continue
            if (c.width > 40 * dp || c.height > 40 * dp) continue
            c.setLayerType(View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            })
            Log.i(TAG, "popup arrow tinted ${c.javaClass.name} ${c.width}x${c.height}")
        }
    }

    /** Rows inside a panel lose their opaque GradientDrawable fill; RippleDrawables are kept. */
    private fun clearFill(v: View) {
        if (v.background is GradientDrawable) v.background = null
    }

    private fun corner(d: android.graphics.drawable.Drawable?): Float? {
        val g = d as? GradientDrawable ?: return null
        g.cornerRadii?.let { r -> return r.maxOrNull() }
        return g.cornerRadius.takeIf { it > 0f }
    }

    /** Nubia rounds some panels through the view outline rather than the drawable. */
    private fun outlineRadius(v: View): Float? = try {
        val o = android.graphics.Outline()
        v.outlineProvider?.getOutline(v, o)
        o.radius.takeIf { it > 0f && v.clipToOutline }
    } catch (_: Throwable) { null }

    private fun find(v: View, idName: String): View? {
        try {
            if (v.id != View.NO_ID && v.resources.getResourceEntryName(v.id) == idName) return v
        } catch (_: Exception) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), idName)?.let { return it }
        return null
    }

    private fun registerSwitch(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getStringExtra("mode")) { "glass" -> enabled = true; "stock" -> enabled = false }
                i.getStringExtra("style")?.takeIf { it == "native" || it == "pill" }?.let { style = it }
                Log.i(TAG, "popup mode=${if (enabled) "glass" else "stock"} style=$style (applies to the next popup)")
            }
        }, IntentFilter(SWITCH), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val LAUNCHER = "com.zte.mifavor.launcher"
        const val SWITCH = "com.nathanhanapps.nebulaColorize.POPUP"

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.e(TAG, "popup", t) }
        }
    }
}
