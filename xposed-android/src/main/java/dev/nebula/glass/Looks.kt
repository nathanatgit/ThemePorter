package dev.nebula.glass

import android.content.Context

/**
 * Built-in lens values for each [Surface] (the "Original"/"Auto"/100 % look), shared by the hooks
 * and the settings preview so the preview matches the device. User settings are layered on top by
 * [style].
 */
object Looks {
    /**
     * Dark-mode glass bodies (deep frost). Off by the user's decision (2026-11-24): dark mode uses
     * the light-mode looks, one unified set, until per-mode parameter sets exist.
     */
    const val DARK_LOOKS = false

    /** Whether [night] picks the dark-mode body; see [DARK_LOOKS]. */
    fun dark(night: Boolean) = night && DARK_LOOKS

    /** Folder tiles sample a wallpaper pre-blurred to about this radius (WallpaperBackdrop.sharedBlurred). */
    const val FOLDER_PREBLUR_PX = 40f

    // Shade and control centre, tuned against the ColorOS 17 frames (docs/LIQUID_GLASS_PORT.md).
    // Shared by SystemUiPanels and the settings preview.

    /** Nubia animates the panel window blur up to this radius. */
    const val NUBIA_BLUR_PX = 150f

    /** Share of Nubia's window blur kept at 100 % intensity: strong, so icons behind don't compete with the tiles. */
    const val PANEL_BLUR_SCALE = 0.8f

    /** Blur behind each card and tile. */
    const val PANEL_CARD_BLUR_PX = 90f

    /** Saturation of that blur: the colour behind glows through, as in the ColorOS folders. */
    const val PANEL_CARD_SATURATION = 1.8f

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
     * Black dim behind a fully open panel, in light and dark mode alike. The shade is darker, for
     * its small text. Not a multiple of Nubia's scrim alpha: that peaks at 138 in light mode but 255
     * in dark mode (with a marker colour), which turned the panel solid black.
     */
    fun panelDim(s: Surface): Float = if (s == Surface.NOTIFICATION) 0.58f else 0.48f

    /** [panelDim] under the user's dim setting. */
    fun panelDim(s: Surface, st: GlassStyle): Float = (panelDim(s) * st.dim).coerceIn(0f, 1f)

    /**
     * Body colour of a card or tile over the dimmed panel.
     * @param active an on-state tile's own fill, else null. Tinted, it takes a lighter tone of the
     *               colour than the off tiles, whatever the intensity, so the two stay distinguishable.
     */
    fun panelBody(s: Surface, st: GlassStyle, night: Boolean, active: Int?): Int {
        if (active == null) return body(s, st, night)
        val rgb = if (st.tinted) toned(st.color, ON_TILE_TONE_MIN, ON_TILE_TONE_MAX) else active
        return (rgb and 0xFFFFFF) or (ACTIVE_TILE_ALPHA shl 24)
    }

    /**
     * Glass body of [s]. A tint takes the tone that surface's text needs, as in iOS and Material:
     * deep under white text, pale under dark text, the colour as picked where there is no text.
     */
    fun body(s: Surface, st: GlassStyle, night: Boolean, builtIn: Int = tint(s, night), fullAlpha: Int = GlassStyle.TINT_ALPHA): Int {
        if (!st.tinted) return builtIn
        val colour = when (s) {
            Surface.NOTIFICATION, Surface.CONTROL_CENTER -> toned(st.color, 0f, DEEP_TONE)
            Surface.POPUP, Surface.HOME_WIDGET, Surface.WIDGET_BOARD ->
                if (dark(night)) toned(st.color, 0f, DEEP_TONE) else toned(st.color, PALE_TONE, 1f)
            // Their glyphs are white in both themes.
            Surface.LOCK_WIDGET, Surface.LOCK_SHORTCUT -> toned(st.color, 0f, GLYPH_TONE)
            else -> st.color
        }
        return st.body(builtIn, colour, fullAlpha)
    }

    /**
     * Body of a heads-up card over an app: blurred like the shade's cards but undimmed, so frosted in
     * the tone of Nubia's text there, as on the lock screen.
     */
    fun headsUpBody(st: GlassStyle, night: Boolean): Int = lockCardBody(st, night)

    /** Body of a card on the lock screen: Nubia's text there is dark in light mode, white in dark. */
    fun lockCardBody(st: GlassStyle, night: Boolean): Int {
        if (!st.tinted) return lockCardBody(night)
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
            // Darker scales towards black, lighter mixes towards white; both are exact in linear light.
            linear = if (l > max) linear * max / l else linear + (1 - linear) * (min - l) / (1 - l)
            val out = if (linear <= 0.0031308) linear * 12.92 else 1.055 * Math.pow(linear, 1 / 2.4) - 0.055
            return Math.round(out * 255).toInt().coerceIn(0, 255)
        }
        return android.graphics.Color.rgb(channel(16), channel(8), channel(0))
    }

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
        Surface.POPUP -> if (dark(night)) 0xB82A2A2E.toInt() else 0xB8F7F7F9.toInt()
        Surface.DOCK -> 0
        // Widgets were designed for an opaque card: a stronger frosted body keeps their text legible.
        Surface.HOME_WIDGET -> if (dark(night)) 0x9E1C1C1E.toInt() else 0x9EF4F5F7.toInt()
        // Panel glass is lighter than the dimmed, blurred backdrop, as in ColorOS.
        Surface.NOTIFICATION -> if (dark(night)) 0x1AFFFFFF else 0x1EFFFFFF
        Surface.CONTROL_CENTER -> if (dark(night)) 0x20FFFFFF else 0x28FFFFFF
        // Widget board cards float over the system blur; frosted enough for their text.
        Surface.WIDGET_BOARD -> if (dark(night)) 0x9E1C1C1E.toInt() else 0xA6FAFAFC.toInt()
        else -> 0x30FFFFFF
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
     * @param originalCornerPx the surface's own shape (Nubia's radius, the 16 dp tile, a circle = 999)
     * @param builtInTint the Clear body, where it isn't the surface's usual one
     * @param preBlurred the backdrop is already blurred to [FOLDER_PREBLUR_PX]: no GPU blur at 100 %
     */
    fun style(lens: LensGlass, s: Surface, density: Float, originalCornerPx: Float, night: Boolean = false,
              preBlurred: Boolean = false, st: GlassStyle = GlassSettings[s], builtInTint: Int = tint(s, night)) {
        lens.applyStyle(st, density, originalCornerPx, body(s, st, night, builtInTint), specular(s), blurOverride(s))
        if (preBlurred) lens.blurRadiusOverride = 0f
    }
}
