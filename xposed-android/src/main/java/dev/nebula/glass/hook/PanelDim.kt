package dev.nebula.glass.hook

import android.util.Log
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers
import dev.nebula.glass.Surface

/**
 * The dim behind the notification shade and control centre: a black colour layer just *beneath*
 * each panel window.
 *
 * It must not be the window's own blur colour (`setBackgroundBlurRadiusAndAlphaColor`'s argb):
 * SurfaceFlinger composites that colour into every blur region again, so cards and tiles came out
 * at (1 - dim)² of the backdrop, nearly black. From beneath, the window blur and the regions both
 * sample the dimmed image, so everything is dimmed once and the glass can be lighter than its surround.
 */
internal class PanelDim {
    private class Layer(val parentId: Int, val control: SurfaceControl)

    private val layers = HashMap<Surface, Layer>()

    /** False once the hidden API failed: callers then keep the scrim in the window and skip blur regions. */
    @Volatile var available = true
        private set

    /** Set the dim in [t], so it lands in the same frame as the blur. False when unavailable. */
    @Synchronized
    fun set(t: SurfaceControl.Transaction, window: SurfaceControl, surface: Surface, alpha: Float): Boolean {
        if (!available) return false
        try {
            val id = XposedHelpers.callMethod(window, "getLayerId") as Int
            var layer = layers[surface]
            // The window gets a new surface when it is re-shown; the old child went with it.
            if (layer == null || layer.parentId != id) {
                if (alpha <= 0f) return true
                layer?.control?.release()
                val builder = SurfaceControl.Builder().setName("nebulaColorize dim ${surface.key}").setParent(window)
                XposedHelpers.callMethod(builder, "setColorLayer")
                layer = Layer(id, builder.build())
                layers[surface] = layer
                XposedHelpers.callMethod(t, "setColor", layer.control, floatArrayOf(0f, 0f, 0f))
                t.setLayer(layer.control, -1).setVisibility(layer.control, true)
            }
            t.setAlpha(layer.control, alpha.coerceIn(0f, 1f))
            return true
        } catch (e: Throwable) {
            available = false
            Log.e("NebulaGlass", "panel dim layer unavailable, using the window scrim", e)
            return false
        }
    }
}
