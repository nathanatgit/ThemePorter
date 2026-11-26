package dev.nebula.glass

import android.content.Context

/**
 * Built-in lens values for each [Surface] (the "Original"/"Auto"/100 % look), shared by the hooks
 * and the settings preview so the preview matches the device. User settings are layered on top by
 * [style].
 */
object Looks {
    /** Dark-mode glass bodies (deep frost), paired with the Dark theme preset. */
    const val DARK_LOOKS = true

    /** Whether [night] picks the dark-mode body. */
    fun dark(night: Boolean) = night && DARK_LOOKS

    /** Folder tiles sample a wallpaper pre-blurred to about this radius (WallpaperBackdrop.sharedBlurred). */
    const val FOLDER_PREBLUR_PX = 40f

    /** Nubia animates the panel window blur up to this radius. */
    const val NUBIA_BLUR_PX = 150f

    /** Share of Nubia's window blur kept at 100 % intensity: strong, so icons behind don't compete with the tiles. */
    const val PANEL_BLUR_SCALE = 0.8f

    /** Blur behind each card and tile. */
    const val PANEL_CARD_BLUR_PX = 90f

    /** Saturation of that blur: the colour behind glows through, as in the ColorOS folders. */
    const val PANEL_CARD_SATURATION = 1.8f

    /** Card saturation scaled by the user's Vibrancy control. */
    fun panelSaturation(st: GlassStyle): Float = (1f + (PANEL_CARD_SATURATION - 1f) * st.vibrancy).coerceIn(0f, 3f)

    /** On-state control-centre tiles keep their hue at this alpha (0..255). */
    const val ACTIVE_TILE_ALPHA = 0xE0

    // Relative luminances for [toned].
    private const val DEEP_TONE = 0.07f
    private const val PALE_TONE = 0.5f
    private const val GLYPH_TONE = 0.18f
    private const val ON_TILE_TONE_MIN = 0.17f
    private const val ON_TILE_TONE_MAX = 0.22f

    /** Black dim over the lock wallpaper at 100 %: a light veil, under the clock, widgets and cards. */
    const val LOCK_DIM = 0.2f

    /** Black dim behind the widget board at 100 %, on the launcher's wallpaper blur. */
    const val BOARD_DIM = 0.15f

    /** [LOCK_DIM] or [BOARD_DIM] under the user's dim setting. */
    fun backdropDim(s: Surface, st: GlassStyle): Float =
        ((if (s == Surface.WIDGET_BOARD) BOARD_DIM else LOCK_DIM) * st.dim).coerceIn(0f, 0.9f)

    /**
     * Body of a card on the lock screen, over the blurred, undimmed wallpaper: pale frost under
     * Nubia's dark light-mode text, deep frost under its white dark-mode text.
     */
    fun lockCardBody(night: Boolean): Int = if (dark(night)) 0x66101014 else 0x73FFFFFF

    /**
     * Black dim behind a fully open panel, in light and dark mode alike.
     */
    fun panelDim(s: Surface): Float = if (s == Surface.NOTIFICATION) 0.58f else 0.48f

    /** [panelDim] under the user's dim setting. */
    fun panelDim(s: Surface, st: GlassStyle): Float = (panelDim(s) * st.dim).coerceIn(0f, 1f)

    /**
     * Body colour of a card or tile over the dimmed panel.
     */
    fun panelBody(s: Surface, st: GlassStyle, night: Boolean, active: Int?): Int {
        if (active == null) return body(s, st, night)
        val rgb = if (st.tinted) toned(st.color, ON_TILE_TONE_MIN, ON_TILE_TONE_MAX) else active
        return (rgb and 0xFFFFFF) or (ACTIVE_TILE_ALPHA shl 24)
    }

    /**
     * Glass body of [s]. Clear scales [builtIn] by [GlassStyle.frost]; Tinted takes the tone that
     * surface's text needs: deep under white text, pale under dark text.
     */
    fun body(s: Surface, st: GlassStyle, night: Boolean, builtIn: Int = tint(s, night), fullAlpha: Int = GlassStyle.TINT_ALPHA): Int {
        if (!st.tinted) return st.frosted(builtIn)
        val colour = when (s) {
            Surface.NOTIFICATION, Surface.CONTROL_CENTER -> toned(st.color, 0f, DEEP_TONE)
            Surface.POPUP, Surface.HOME_WIDGET, Surface.WIDGET_BOARD ->
                if (dark(night)) toned(st.color, 0f, DEEP_TONE) else toned(st.color, PALE_TONE, 1f)
            Surface.LOCK_WIDGET, Surface.LOCK_SHORTCUT -> toned(st.color, 0f, GLYPH_TONE)
            else -> st.color
        }
        return st.body(builtIn, colour, fullAlpha)
    }

    fun headsUpBody(st: GlassStyle, night: Boolean): Int = lockCardBody(st, night)

    /** Body of a card on the lock screen: Nubia's text there is dark in light mode, white in dark. */
    fun lockCardBody(st: GlassStyle, night: Boolean): Int {
        if (!st.tinted) return st.frosted(lockCardBody(night))
        val colour = if (dark(night)) toned(st.color, 0f, DEEP_TONE) else toned(st.color, PALE_TONE, 1f)
        return st.body(lockCardBody(night), colour)
    }

    /** [rgb] moved to a relative luminance within [min]..[max], keeping its hue. */
    fun toned(rgb: Int, min: Float, max: Float): Int {
        val l = android.graphics.Color.luminance(rgb)
        if (l in min..max) return rgb or 0xFF000000.toInt()
        fun channel(shift: Int): Int {
            val v = ((rgb shr shift) and 0xFF) / 255.0
            var linear = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            linear = if (l > max) linear * max / l else linear + (1 - linear) * (min - l) / (1 - l)
            val out = if (linear <= 0.0031308) linear * 12.92 else 1.055 * Math.pow(linear, 1 / 2.4) - 0.055
            return Math.round(out * 255).toInt().coerceIn(0, 255)
        }
        return android.graphics.Color.rgb(channel(16), channel(8), channel(0))
    }

    fun bevelPx(s: Surface, dp: Float): Float = when (s) {
        Surface.FOLDER -> 10 * dp
        Surface.POPUP -> 8 * dp
        Surface.HOME_WIDGET -> 10 * dp
        Surface.DOCK -> 40f
        else -> 10 * dp
    }

    fun refractionPx(s: Surface, dp: Float): Float = when (s) {
        Surface.FOLDER -> 18 * dp
        Surface.POPUP -> 14 * dp
        Surface.HOME_WIDGET -> 16 * dp
        Surface.DOCK -> 120f
        else -> 18 * dp
    }

    fun dispersionOf(s: Surface): Float = when (s) {
        Surface.FOLDER -> 0.05f
        Surface.POPUP -> 0.04f
        Surface.HOME_WIDGET -> 0.04f
        Surface.DOCK -> 0.08f
        else -> 0.05f
    }

    fun saturationOf(s: Surface): Float = when (s) {
        Surface.FOLDER -> 190f
        Surface.POPUP -> 170f
        Surface.HOME_WIDGET -> 175f
        Surface.DOCK -> 140f
        else -> 180f
    }

    /** A lens with the surface's built-in material. Corner radius is set later by [style]. */
    fun base(s: Surface, ctx: Context, night: Boolean = false): LensGlass = LensGlass().apply {
        val dp = ctx.resources.displayMetrics.density
        material = GlassMaterial.REGULAR
        blurAmount = when (s) {
            Surface.POPUP -> 0.8f
            Surface.DOCK -> 0.5f
            Surface.FOLDER, Surface.HOME_WIDGET -> 0.0625f
            else -> 0.9f
        }
        bevelWidth = bevelPx(s, dp)
        refractionHeight = refractionPx(s, dp)
        dispersion = dispersionOf(s)
        saturation = saturationOf(s)
        glassTint = tint(s, night)
        specular = specular(s)
        refreshAccessibility(ctx)
    }

    fun tint(s: Surface, night: Boolean): Int = when (s) {
        Surface.FOLDER -> if (dark(night)) 0x3618181C else 0x38FFFFFF
        Surface.POPUP -> if (dark(night)) 0xB82A2A2E.toInt() else 0xB8F7F7F9.toInt()
        Surface.DOCK -> if (dark(night)) 0x26101014 else 0x22FFFFFF
        Surface.HOME_WIDGET -> if (dark(night)) 0x7A1C1C1E else 0x7AF4F5F7
        Surface.NOTIFICATION -> if (dark(night)) 0x1AFFFFFF else 0x1EFFFFFF
        Surface.CONTROL_CENTER -> if (dark(night)) 0x20FFFFFF else 0x28FFFFFF
        Surface.WIDGET_BOARD -> if (dark(night)) 0x9E1C1C1E.toInt() else 0xA6FAFAFC.toInt()
        else -> if (dark(night)) 0x28101014 else 0x30FFFFFF
    }

    fun specular(s: Surface): Float = when (s) {
        Surface.FOLDER -> 0.8f
        Surface.POPUP -> 0.7f
        Surface.DOCK -> 1f
        Surface.HOME_WIDGET -> 0.7f
        Surface.NOTIFICATION -> 0.9f
        Surface.CONTROL_CENTER -> 1f
        Surface.WIDGET_BOARD -> 0.9f
        Surface.LOCK_CARD -> 0.9f
        else -> 0.9f
    }

    /** Built-in blur override in px, or null when the lens uses its blurAmount default. */
    fun blurOverride(s: Surface): Float? = if (s == Surface.FOLDER || s == Surface.HOME_WIDGET || s == Surface.DOCK) FOLDER_PREBLUR_PX else null

    /**
     * Apply the current user settings for [s] to [lens].
     */
    fun style(lens: LensGlass, s: Surface, density: Float, originalCornerPx: Float, night: Boolean = false,
              preBlurred: Boolean = false, st: GlassStyle = GlassSettings[s, night], builtInTint: Int = tint(s, night)) {
        lens.applyStyle(
            st, density, originalCornerPx, body(s, st, night, builtInTint), specular(s), blurOverride(s),
            originalBevel = bevelPx(s, density),
            originalRefraction = refractionPx(s, density),
            originalDispersion = dispersionOf(s),
            originalSaturation = saturationOf(s),
        )
        if (preBlurred) lens.blurRadiusOverride = 0f
    }
}
