package dev.nebula.glass

import android.content.Context

/**
 * Built-in lens values for each [Surface] (the "Original"/"Auto"/100 % look), shared by the hooks
 * and the settings preview so the preview matches the device. User settings are layered on top by
 * [style].
 */
object Looks {
    /** Folder tiles sample a wallpaper pre-blurred to about this radius (WallpaperBackdrop.sharedBlurred). */
    const val FOLDER_PREBLUR_PX = 40f

    /** A lens with the surface's built-in material. Corner radius is set later by [style]. */
    fun base(s: Surface, ctx: Context, night: Boolean = false): LensGlass = LensGlass().apply {
        val dp = ctx.resources.displayMetrics.density
        material = GlassMaterial.REGULAR
        when (s) {
            Surface.FOLDER -> {
                saturation = 190f; bevelWidth = 10 * dp; refractionHeight = 18 * dp; dispersion = 0.05f
            }
            Surface.POPUP -> {
                blurAmount = 0.8f; saturation = 170f; bevelWidth = 8 * dp; refractionHeight = 14 * dp; dispersion = 0.04f
            }
            Surface.HOME_WIDGET -> {
                saturation = 175f; bevelWidth = 10 * dp; refractionHeight = 16 * dp; dispersion = 0.04f
            }
            Surface.DOCK -> {
                blurAmount = 0.5f; bevelWidth = 40f; refractionHeight = 120f; dispersion = 0.08f
            }
            else -> {   // lock widgets and shortcuts
                blurAmount = 0.9f; saturation = 180f; bevelWidth = 10 * dp; refractionHeight = 18 * dp; dispersion = 0.05f
            }
        }
        glassTint = tint(s, night)
        specular = specular(s)
        refreshAccessibility(ctx)
    }

    fun tint(s: Surface, night: Boolean): Int = when (s) {
        Surface.FOLDER -> 0x26FFFFFF
        Surface.POPUP -> if (night) 0xB82A2A2E.toInt() else 0xB8F7F7F9.toInt()
        Surface.DOCK -> 0
        // Widgets were designed for an opaque card: a stronger frosted body keeps their text legible.
        Surface.HOME_WIDGET -> if (night) 0x9E1C1C1E.toInt() else 0x9EF4F5F7.toInt()
        // Skins over the system blur (GlassSkin): a frosted body that keeps notification text legible.
        Surface.NOTIFICATION -> if (night) 0xA61C1C1E.toInt() else 0xB3FAFAFC.toInt()
        // Control centre: 0 = keep Nubia's own tile colour (on/off states), only the glass edge is added.
        Surface.CONTROL_CENTER -> 0
        // Widget board cards float over the system blur; frosted enough for their text.
        Surface.WIDGET_BOARD -> if (night) 0x9E1C1C1E.toInt() else 0xA6FAFAFC.toInt()
        else -> 0x30FFFFFF
    }

    fun specular(s: Surface): Float = when (s) {
        Surface.FOLDER -> 0.8f
        Surface.POPUP -> 0.7f
        Surface.DOCK -> 1f
        Surface.HOME_WIDGET -> 0.7f
        Surface.NOTIFICATION -> 0.8f
        Surface.CONTROL_CENTER -> 1f
        Surface.WIDGET_BOARD -> 0.9f
        else -> 0.9f
    }

    /** Built-in blur override in px, or null when the lens uses its blurAmount default. */
    fun blurOverride(s: Surface): Float? = if (s == Surface.FOLDER || s == Surface.HOME_WIDGET || s == Surface.DOCK) FOLDER_PREBLUR_PX else null

    /**
     * Apply the current user settings for [s] to [lens].
     * @param originalCornerPx the surface's own shape (Nubia's radius, the 16 dp tile, a circle = 999)
     * @param preBlurred the backdrop is already blurred to [FOLDER_PREBLUR_PX]: no GPU blur at 100 %
     */
    fun style(lens: LensGlass, s: Surface, density: Float, originalCornerPx: Float, night: Boolean = false,
              preBlurred: Boolean = false, st: GlassStyle = GlassSettings[s]) {
        lens.applyStyle(st, density, originalCornerPx, tint(s, night), specular(s), blurOverride(s))
        if (preBlurred) lens.blurRadiusOverride = 0f
    }
}
