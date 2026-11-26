package dev.nebula.glass

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Non-destructive iOS-style lock-screen wallpaper colour filters (Natural, Black & White, Duotone,
 * Colour Wash). Expressed as exact 4×5 [ColorMatrixColorFilter]s so both the live lock screen, the
 * glass backdrop and Nubia's editor preview run on the GPU with zero bitmap copies.
 */
object WallpaperFilter {
    const val NATURAL = 0
    const val MONO = 1
    const val DUOTONE = 2
    const val WASH = 3
    const val COUNT = 4

    const val DEFAULT_COLOR = 0xFFE6C86E.toInt()

    /** Preset swatches for Duotone and Colour Wash, matching the iOS lock-screen editor palette. */
    val SWATCHES = intArrayOf(
        0xFFE6C86E.toInt(), // Warm Gold (video reference)
        0xFF7EC8FF.toInt(), // Sky Blue
        0xFFB49BFF.toInt(), // Lavender
        0xFFFF9EBB.toInt(), // Rose Pink
        0xFF7CE0B8.toInt(), // Mint Jade
        0xFFFFB076.toInt(), // Sunset Peach
    )

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun filter(mode: Int, color: Int): ColorMatrixColorFilter? = when (mode) {
        MONO -> {
            val c = 1.14f
            val off = -16f
            ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                LR * c, LG * c, LB * c, 0f, off,
                LR * c, LG * c, LB * c, 0f, off,
                LR * c, LG * c, LB * c, 0f, off,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        DUOTONE -> {
            // iOS Duotone: deep shifted-hue shadow (e.g. plum under gold) to bright pastel highlight.
            val hsv = FloatArray(3).also { Color.colorToHSV(color or 0xFF000000.toInt(), it) }
            val shadowHue = (hsv[0] + 245f) % 360f
            val dark = Color.HSVToColor(floatArrayOf(shadowHue, (hsv[1] * 0.85f).coerceIn(0.35f, 0.75f), 0.34f))
            val light = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.52f).coerceIn(0.18f, 0.55f), 0.98f))
            rampFilter(dark, light, contrast = 1.16f)
        }
        WASH -> {
            // iOS Colour Wash: monochrome wash in one hue from deep shadow to creamy highlight.
            val hsv = FloatArray(3).also { Color.colorToHSV(color or 0xFF000000.toInt(), it) }
            val dark = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.65f).coerceIn(0.20f, 0.60f), 0.18f))
            val light = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.42f).coerceIn(0.15f, 0.48f), 0.96f))
            rampFilter(dark, light, contrast = 1.06f)
        }
        else -> null
    }

    /** Clock/text accent colour harmonised with the active wallpaper filter, or null for Natural/Mono. */
    fun textTint(mode: Int, color: Int): Int? = when (mode) {
        DUOTONE, WASH -> {
            val hsv = FloatArray(3).also { Color.colorToHSV(color or 0xFF000000.toInt(), it) }
            Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.58f).coerceIn(0.20f, 0.62f), 0.98f))
        }
        else -> null
    }

    private fun rampFilter(dark: Int, light: Int, contrast: Float): ColorMatrixColorFilter {
        val dr = (Color.red(light) - Color.red(dark)) / 255f * contrast
        val dg = (Color.green(light) - Color.green(dark)) / 255f * contrast
        val db = (Color.blue(light) - Color.blue(dark)) / 255f * contrast
        val midShift = (1f - contrast) * 0.5f
        val or = (Color.red(dark) + (Color.red(light) - Color.red(dark)) * midShift).coerceIn(0f, 255f)
        val og = (Color.green(dark) + (Color.green(light) - Color.green(dark)) * midShift).coerceIn(0f, 255f)
        val ob = (Color.blue(dark) + (Color.blue(light) - Color.blue(dark)) * midShift).coerceIn(0f, 255f)
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            LR * dr, LG * dr, LB * dr, 0f, or,
            LR * dg, LG * dg, LB * dg, 0f, og,
            LR * db, LG * db, LB * db, 0f, ob,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
}
