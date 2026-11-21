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
 *
 * This is the parameter derivation of upstream `LiquidGlassView.tryDrawLensGlass`
 * (adaptive lens scale, material, quantisation, margin) without the View subclass,
 * XML attributes or resources, so it can paint onto a canvas inside SystemUI or the launcher.
 * Call [draw] from the glass view's draw path; it returns false when the caller should
 * draw its stock appearance instead.
 */
class LensGlass {
    var material = GlassMaterial.REGULAR
    /** Upstream `blurAmount` (0..1); radius = 4 + 32·amount (12 + 32·amount over light). */
    var blurAmount = 0.0625f
    /** When set, the exact blur radius in px (0 = none, e.g. over a pre-blurred backdrop). */
    var blurRadiusOverride: Float? = null
    var bevelWidth = 48f
    var refractionHeight = 160f
    var refractionFalloff = 2f
    var dispersion = 0.10f
    var specular = 1f
    var saturation = 140f
    /** Glass body colour, straight-alpha ARGB; alpha 0 disables. */
    var glassTint = 0
    var adaptiveTint = false
    /** Corner radius in px; clamped to half the short side (999 = capsule). */
    var cornerRadius = 999f
    var lightX = LightSourceController.DEFAULT_X
    var lightY = LightSourceController.DEFAULT_Y
    /** Set from a [BackdropLuminanceMeter]; switches the base tint like upstream. */
    var overLight = false

    private val renderer = if (Build.VERSION.SDK_INT >= 33) GlassLensRenderer() else null
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)

    val isAvailable: Boolean get() = renderer?.isAvailable == true

    /** The blur radius (px) used when [blurRadiusOverride] is null, ignoring the over-light lift. */
    val defaultBlurPx: Float get() = (4f + blurAmount * 32f) * material.blurScale

    /** Cached "high contrast text" state; call [refreshAccessibility] on attach. */
    var reducedTransparency = false
        private set

    fun refreshAccessibility(context: android.content.Context) {
        reducedTransparency = GlassAccessibility.prefersReducedTransparency(context)
    }

    /**
     * @param glass view whose bounds the glass occupies (skipped while recording)
     * @param backdrop view to sample; parent by default. Must not be [glass].
     * @param shape glass rect in [glass]-local px, or null to fill the view
     */
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
        val radius = cornerRadius.coerceIn(0f, min(hw, hh))

        // Adaptive lens scale (upstream defaults: 110 dp reference, 0.3 / 0.7 / 0.05 ratios, 6 px rim cap)
        val minDim = 2f * min(hw, hh)
        val refPx = 110f * glass.resources.displayMetrics.density
        val bevel = min(bevelWidth, minDim * 0.3f).coerceAtLeast(2f)
        val refract = min(refractionHeight, 0.7f * minDim * min(1f, minDim / refPx))
        val rimBand = min(6f, minDim * 0.05f).coerceAtLeast(2f)

        val blur = blurRadiusOverride?.let { (it * 2f).toInt() / 2f }
            ?: ((((if (overLight) 12f else 4f) + blurAmount * 32f) * material.blurScale * 2f).toInt() / 2f)
        val margin = ((max(max(blur * 3f, 32f), 0f).toInt() + 15) / 16 * 16).coerceAtLeast(32)
        val adaptive = material.adaptiveTint && adaptiveTint

        val params = GlassLensRenderer.LensParams(
            blurRadius = blur,
            shape1CX = cx, shape1CY = cy, shape1HW = hw, shape1HH = hh,
            radius1TL = radius, radius1TR = radius, radius1BR = radius, radius1BL = radius,
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
            tint = if (adaptive) 0 else if (overLight) 0x33000000 else material.baseTint,
            adaptiveTint = adaptive,
            glassTint = glassTint,
            dim = material.dimAmount,
            saturation = saturation,
            press = 0f, touchX = 0f, touchY = 0f, touchAmp = 0f
        )
        return r.draw(canvas, glass, source, params, margin)
    }

    /** Opaque stand-in for reduced transparency or shader failure (upstream FORCE_OPAQUE look). */
    fun drawOpaque(canvas: Canvas, glass: View, shape: RectF? = null) {
        val rect = shape ?: RectF(0f, 0f, glass.width.toFloat(), glass.height.toFloat())
        val radius = cornerRadius.coerceIn(0f, min(rect.width(), rect.height()) / 2f)
        fallback.style = Paint.Style.FILL
        fallback.color = if (overLight) Color.argb(235, 246, 246, 248) else Color.argb(235, 44, 44, 48)
        canvas.drawRoundRect(rect, radius, radius, fallback)
        fallback.style = Paint.Style.STROKE
        fallback.strokeWidth = glass.resources.displayMetrics.density
        fallback.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, fallback)
    }

    fun release() = renderer?.release()
}
