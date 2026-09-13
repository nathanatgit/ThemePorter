package com.nathanhanapps.nebulaThemePorter.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * An Android component as the launcher names it: dots become underscores, and package and activity are
 * joined with '-' ("com_tencent_mm-com_tencent_mm_ui_LauncherUI").
 */
data class AppComponent(val packageName: String, val className: String? = null) {
    val packageStem: String get() = sanitize(packageName)
    val classStem: String? get() = className?.let { sanitize(qualify(packageName, it)) }
    val stem: String get() = classStem?.let { "$packageStem-$it" } ?: packageStem

    companion object {
        private val unsafe = Regex("[^A-Za-z0-9_]")
        private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val stemPattern = Regex("^[A-Za-z0-9_]+(-[A-Za-z0-9_]+)?$")

        fun sanitize(value: String): String = value.trim().replace(unsafe, "_")

        fun qualify(packageName: String, className: String): String =
            if (className.startsWith('.')) packageName + className else className

        fun looksLikePackage(value: String): Boolean = packagePattern.matches(value)

        fun isSafeStem(stem: String): Boolean = stem.length <= 180 && stemPattern.matches(stem)
    }
}

/** One decodable image offered by a source (icon pack APK or MTZ). */
data class SourceIcon(
    val id: String,
    /** Package name for icon packs; the drawable name (package or MIUI alias) for MTZ themes. */
    val key: String,
    val component: AppComponent? = null,
)

/**
 * A [PlannedIcon.sourceId] can name either a real [SourceIcon] from the pack/theme, or, when neither has
 * artwork for an installed app, a temporary resource synthesized from that app's own launcher icon so it
 * still flows through the normal background and tint pipeline instead of being skipped.
 */
object DeviceIconId {
    private const val PREFIX = "device:"

    fun of(packageName: String, className: String?): String = PREFIX + packageName + (className?.let { "/$it" } ?: "")

    fun isDeviceIcon(sourceId: String): Boolean = sourceId.startsWith(PREFIX)

    fun packageName(sourceId: String): String = sourceId.removePrefix(PREFIX).substringBefore('/')

    fun className(sourceId: String): String? = sourceId.removePrefix(PREFIX).substringAfter('/', "").ifEmpty { null }
}

data class ThemeMetadata(
    val id: String,
    val labelEn: String,
    val labelZh: String,
    val introEn: String,
    val introZh: String,
    val author: String,
    val version: String = NebulaSpec.THEME_VERSION,
    /** 1 static wallpaper, 2 ZTE lock screen, 3 dynamic, 5 pressure-sensitive. */
    val lockScreenWallpaperType: Int = 1,
)

/** The draw order for a pre-rendered fixed icon. It never changes the system's icon-shape setting. */
enum class FixedIconComposition {
    /** Blend a translucent shaped plate behind the icon, so the imported artwork remains readable. */
    OVERLAY,
    /** Crop the icon so it fills the shaped background. */
    COVER,
    /** Keep the icon scale, but crop it to the shaped background's outline. */
    CLIP,
}

/**
 * How an icon's own grayscale shading combines with the selected tint color - the same choice Photoshop's layer
 * blend modes offer, applied per-pixel between the icon's luminance and the tint color's own RGB channels.
 */
enum class TintBlendMode {
    /** grayscale * tint / 255 - white becomes the tint color, black stays black. The long-standing default. */
    MULTIPLY,
    /** Darkens below 50% grey, lightens above it - punchier contrast than Multiply. */
    OVERLAY,
    /** Like Overlay but gentler, without Overlay's hard midpoint - a subtler recolor. */
    SOFT_LIGHT,
    /** Keeps whichever of the icon's shading or the tint color is brighter, per channel. */
    LIGHTEN,
    ;

    /**
     * [base] (the icon's own grayscale) and [color] (one tint channel) are 0..255; returns the blended 0..255
     * result. Overlay/Soft Light match the W3C compositing spec, which is also what Photoshop implements for
     * those modes. Pure math (no Android dependency) so it can be unit tested directly.
     */
    fun blend(base: Int, color: Int): Int {
        val a = base / 255f
        val b = color / 255f
        val result = when (this) {
            MULTIPLY -> a * b
            OVERLAY -> if (a <= 0.5f) 2 * a * b else 1f - 2f * (1f - a) * (1f - b)
            SOFT_LIGHT -> if (b <= 0.5f) {
                a - (1f - 2f * b) * a * (1f - a)
            } else {
                val d = if (a <= 0.25f) ((16f * a - 12f) * a + 4f) * a else kotlin.math.sqrt(a)
                a + (2f * b - 1f) * (d - a)
            }
            LIGHTEN -> maxOf(a, b)
        }
        return (result * 255f).let { kotlin.math.round(it).toInt() }.coerceIn(0, 255)
    }
}

/** How the generated wallpaper combines its selected colors. */
enum class GradientMix {
    LINEAR,
    RADIAL,
    BLOBS,
}

/**
 * Settings for an offline generated wallpaper. [blur] controls the softness of the colour boundaries; it does not
 * modify an imported or source-pack wallpaper.
 */
data class GeneratedWallpaper(
    val colorA: Long = 0xFF5B7CFA,
    val colorB: Long = 0xFF1B2A6B,
    val colorC: Long = 0xFFA78BFA,
    val blur: Float = 0.55f,
    val mix: GradientMix = GradientMix.BLOBS,
    /** Direction for Linear and Radial mixes, in degrees clockwise from the right. */
    val angle: Float = 45f,
    val seed: Int = 1,
)

data class CalendarTextStyle(
    val showWeekInfo: Boolean = false,
    val paddingTop: Int = 7,
    val textColor: Long = 0xE5000000,
    val textSize: Int = 9,
)

data class BuildOptions(
    /** Fixed icons can bake any [FixedIconShape] into their PNG; NONE preserves the imported outline. */
    val fixedShape: FixedIconShape = FixedIconShape.NONE,
    val fixedComposition: FixedIconComposition = FixedIconComposition.OVERLAY,
    /** When non-null, recolors the imported icon (and, when generated, the calendar/clock strips) while
     * preserving alpha silhouettes. */
    val fixedTintColor: Long? = null,
    /** Mix ratio between the imported RGB values and [fixedTintColor]. */
    val fixedTintStrength: Float = 1f,
    /** How the icon's own grayscale shading combines with [fixedTintColor]. */
    val fixedTintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
    /** Side of the foreground icon as a fraction of the final fixed PNG. */
    val fixedIconScale: Float = 0.66f,
    /** Strength of the icon alpha when mixing it with a Cutout or Overlay plate. */
    val fixedIconAlpha: Float = 1f,
    /** Side of the shaped background as a fraction of the final fixed PNG. */
    val fixedBackgroundScale: Float = 1f,
    val generatedWallpaper: GeneratedWallpaper = GeneratedWallpaper(),
    val padBackground: Long = 0xFFFFFFFF,
    val onlyInstalledApps: Boolean = true,
    val generateMissingDynamicIcons: Boolean = true,
    /** When an installed app has no artwork in the icon pack or ported theme, theme its own launcher icon instead. */
    val generateMissingAppIcons: Boolean = true,
    /**
     * A generated icon uses only the app's foreground glyph by default, tinted/plated like imported artwork. Some
     * glyphs are only legible against their own designed backdrop, so this keeps the app's own background layer
     * too instead of the theme's unified background color.
     */
    val generatedIconOwnBackground: Boolean = true,
)

object ThemeIds {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)

    /** Follows the stock comment in description.xml: online_theme_<vendor>_<date>_<n>. */
    fun create(now: LocalDateTime, salt: String): String {
        val suffix = (salt + now.toString()).hashCode().toUInt().toString(16).padStart(8, '0')
        return "online_theme_ntp_${now.format(dateFormat)}_$suffix"
    }
}

fun argbHex(color: Long): String = String.format(Locale.ROOT, "#%08X", color and 0xFFFFFFFFL)
