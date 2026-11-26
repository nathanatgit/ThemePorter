package dev.nebula.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Rounded-rect Liquid Glass for any view, including host views inside hooked processes.
 */
class LensGlass {
    var material = GlassMaterial.REGULAR
    var blurAmount = 0.0625f
    var blurRadiusOverride: Float? = null
    var bevelWidth = 48f
    var refractionHeight = 160f
    var refractionFalloff = 2f
    var dispersion = 0.10f
    var specular = 1f
    var saturation = 140f
    /** Glass body colour, straight-alpha ARGB; alpha 0 disables. */
    var glassTint = 0
    /** Multiplier on material frost (1 = 100%, 0 = crystal clear, 2 = 200% dense frost). */
    var frostScale = 1f
    /** True when [glassTint] is a Clear frosted coat rather than a saturated coloured medium. */
    var frostCoat = false
    /** Specular rim hairline colour (opaque RGB). */
    var edgeColor = Color.WHITE
    /** Inward directional rim glow colour (opaque RGB). */
    var glowColor = Color.WHITE
    var adaptiveTint = false
    /** Per-corner radii [TL, TR, BR, BL] in px, or null to use [cornerRadius] uniformly. */
    var cornerRadii4: FloatArray? = null
    /** Corner radius in px; clamped to half the short side (999 = capsule). */
    var cornerRadius = 999f
    var lightX = LightSourceController.DEFAULT_X
    var lightY = LightSourceController.DEFAULT_Y
    var overLight = false

    private val renderer = if (Build.VERSION.SDK_INT >= 33) GlassLensRenderer() else null
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)

    val isAvailable: Boolean get() = renderer?.isAvailable == true

    val defaultBlurPx: Float get() = (4f + blurAmount * 32f) * material.blurScale

    var reducedTransparency = false
        private set

    fun refreshAccessibility(context: android.content.Context) {
        reducedTransparency = GlassAccessibility.prefersReducedTransparency(context)
    }

    fun draw(canvas: Canvas, glass: View, backdrop: View?, shape: RectF? = null): Boolean {
        val r = renderer ?: return false
        val source = backdrop ?: glass.parent as? View ?: return false
        if (GlassRegistry.isCapturing(glass) || !r.isAvailable) return false
        if (reducedTransparency) return false
        val w = glass.width.toFloat()
        val h = glass.height.toFloat()
        if (w <= 0f || h <= 0f) return false

        val cx = shape?.centerX() ?: (w / 2f)
        val cy = shape?.centerY() ?: (h / 2f)
        val hw = ((shape?.width() ?: w) / 2f).coerceAtLeast(1f)
        val hh = ((shape?.height() ?: h) / 2f).coerceAtLeast(1f)
        val maxR = min(hw, hh)
        val r4 = cornerRadii4
        val rTL = (r4?.getOrNull(0) ?: cornerRadius).coerceIn(0f, maxR)
        val rTR = (r4?.getOrNull(1) ?: cornerRadius).coerceIn(0f, maxR)
        val rBR = (r4?.getOrNull(2) ?: cornerRadius).coerceIn(0f, maxR)
        val rBL = (r4?.getOrNull(3) ?: cornerRadius).coerceIn(0f, maxR)

        val minDim = 2f * maxR
        val dp = glass.resources.displayMetrics.density
        val refPx = 110f * dp
        val bevel = min(bevelWidth, minDim * 0.45f).coerceAtLeast(2f)
        val refract = min(refractionHeight, 1.1f * minDim * min(1.25f, minDim / refPx))
        val rimBand = min(14f * dp, minDim * 0.22f).coerceAtLeast(3f)

        val blur = blurRadiusOverride?.let { (it * 2f).toInt() / 2f }
            ?: ((((if (overLight) 12f else 4f) + blurAmount * 32f) * material.blurScale * 2f).toInt() / 2f)
        val margin = ((max(max(blur * 3f, 32f), 0f).toInt() + 15) / 16 * 16).coerceAtLeast(32)
        val adaptive = material.adaptiveTint && adaptiveTint

        // Scale the material's built-in frosted base tint by frostScale so 0% Frost is genuinely clear
        // and 200% Frost matches dense frosted panels across Folders, Dock, Widgets, and Lock Cards!
        val rawBaseTint = if (adaptive) 0 else if (overLight) 0x33000000 else material.baseTint
        val scaledBaseTint = if (rawBaseTint == 0) 0 else {
            val a = Math.round((rawBaseTint ushr 24) * frostScale.coerceIn(0f, 2f)).coerceIn(0, 255)
            (rawBaseTint and 0x00FFFFFF) or (a shl 24)
        }

        val params = GlassLensRenderer.LensParams(
            blurRadius = blur,
            shape1CX = cx, shape1CY = cy, shape1HW = hw, shape1HH = hh,
            radius1TL = rTL, radius1TR = rTR, radius1BR = rBR, radius1BL = rBL,
            lens1CX = cx, lens1CY = cy, lens1HW = hw, lens1HH = hh,
            rimSoft = 0f,
            shape2CX = 0f, shape2CY = 0f, shape2HW = 0f, shape2HH = 0f, radius2 = 0f,
            blendK = 0f,
            bevel = bevel,
            refract = refract,
            falloff = (refractionFalloff * 100f).toInt() / 100f,
            outward = false,
            rimBandMax = rimBand,
            dispersion = dispersion.coerceIn(0f, 0.9f),
            lightX = (lightX * 200f).toInt() / 200f, lightY = (lightY * 200f).toInt() / 200f,
            spec = specular * material.specBoost,
            edgeColor = edgeColor,
            glowColor = glowColor,
            tint = scaledBaseTint,
            adaptiveTint = adaptive,
            glassTint = glassTint,
            frostCoat = frostCoat,
            dim = material.dimAmount,
            saturation = saturation,
            press = 0f, touchX = 0f, touchY = 0f, touchAmp = 0f
        )
        return r.draw(canvas, glass, source, params, margin)
    }

    fun drawOpaque(canvas: Canvas, glass: View, shape: RectF? = null) {
        val rect = shape ?: RectF(0f, 0f, glass.width.toFloat(), glass.height.toFloat())
        val radius = cornerRadius.coerceIn(0f, min(rect.width(), rect.height()) / 2f)
        fallback.style = Paint.Style.FILL
        fallback.color = if (overLight) Color.argb(235, 246, 246, 248) else Color.argb(235, 44, 44, 48)
        canvas.drawRoundRect(rect, radius, radius, fallback)
        fallback.style = Paint.Style.STROKE
        fallback.strokeWidth = glass.resources.displayMetrics.density
        fallback.color = Color.argb(60, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
        canvas.drawRoundRect(rect, radius, radius, fallback)
    }

    fun release() = renderer?.release()
}
