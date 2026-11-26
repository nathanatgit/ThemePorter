package dev.nebula.glass

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Every place the module draws glass. Keys are stored in the settings JSON: do not rename. */
enum class Surface(
    val key: String,
    val title: String,
    val where: String,
    /** Rounded-rect glass whose corner radius can be set (text surfaces have none). */
    val hasCorner: Boolean,
    /** What "Original" corner means for this surface, shown in the UI. */
    val originalCorner: String,
    val defaultEnabled: Boolean = true,
) {
    FOLDER("folder", "Folder tiles", "Home screen", true, "16 dp"),
    POPUP("popup", "Long-press menu", "Home screen", true, "Nubia's shape"),
    DOCK("dock", "Dock panel", "Home screen", true, "30 dp", defaultEnabled = false),
    HOME_CLOCK("homeClock", "Clock widget", "Home screen", false, ""),
    HOME_WIDGET("homeWidget", "App widgets", "Home screen", true, "widget's own corners"),
    WIDGET_BOARD("widgetBoard", "Widget board cards", "Home screen", true, "Nubia's shape"),
    LOCK_CLOCK("lockClock", "Clock, date & lunar date", "Lock screen", false, ""),
    LOCK_WIDGET("lockWidget", "Widgets", "Lock screen", true, "Nubia's shape"),
    LOCK_SHORTCUT("lockShortcut", "Bottom shortcuts", "Lock screen", true, "circle"),
    LOCK_CARD("lockCard", "Notifications & player", "Lock screen", true, "Nubia's shape"),
    NOTIFICATION("notification", "Notification cards", "Notifications & control centre", true, "Nubia's shape"),
    CONTROL_CENTER("controlCenter", "Control centre tiles", "Notifications & control centre", true, "Nubia's shape");

    val isText get() = !hasCorner

    /** Every look has a blur: the widget board's scales the launcher's wallpaper blur behind it. */
    val hasBlur get() = true

    /** The surface whose own look this one uses: the lock screen is edited as one. */
    val lookKey: Surface get() = when (this) {
        LOCK_CLOCK, LOCK_SHORTCUT, LOCK_CARD -> LOCK_WIDGET
        else -> this
    }

    /** These darken what is behind them: the panels' windows, the lock wallpaper, the board's backdrop. */
    val hasDim get() = this == NOTIFICATION || this == CONTROL_CENTER || this == LOCK_WIDGET || this == WIDGET_BOARD

    /** Surfaces rendered through the refractive AGSL lens (rather than system-blur GlassSkin). */
    val hasLensOptics get() = this != NOTIFICATION && this != CONTROL_CENTER && this != WIDGET_BOARD

    companion object {
        /** Surfaces that can have their own separated look and UI scope. */
        val PANELS by lazy {
            listOf(FOLDER, HOME_WIDGET, DOCK, NOTIFICATION, CONTROL_CENTER, LOCK_WIDGET, WIDGET_BOARD)
        }
    }
}

/**
 * The glass look: style (Clear / Tinted accent), Edge & Edge Glow colours, core amounts (Blur,
 * Glow, Dim), and iOS Liquid Glass optical controls (Refraction, Dispersion, Vibrancy, Frost).
 * Separate Light and Dark presets are stored in [GlassConfig] and selected automatically from the
 * system UI night mode.
 */
data class GlobalStyle(
    val tinted: Boolean = false,
    val color: Int = GlassStyle.AUTO,
    val intensity: Int = DEFAULT_INTENSITY,
    val edgeColor: Int = GlassStyle.AUTO,
    val glowColor: Int = GlassStyle.AUTO,
    val glowAngle: Int = DEFAULT_GLOW_ANGLE,
    val blur: Int = DEFAULT,
    val glow: Int = DEFAULT,
    val dim: Int = DEFAULT,
    val refraction: Int = DEFAULT,
    val dispersion: Int = DEFAULT,
    val vibrancy: Int = DEFAULT,
    val frost: Int = DEFAULT,
    /** Glow direction follows device tilt / rotation (the static [glowAngle] is the upright angle). */
    val glowSensor: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tinted", tinted).put("color", color).put("intensity", intensity)
        .put("edgeColor", edgeColor).put("glowColor", glowColor).put("glowAngle", glowAngle)
        .put("glowSensor", glowSensor)
        .put("blur", blur).put("glow", glow).put("dim", dim)
        .put("refraction", refraction).put("dispersion", dispersion)
        .put("vibrancy", vibrancy).put("frost", frost)

    companion object {
        const val DEFAULT = 100
        const val DEFAULT_INTENSITY = 50
        /** Compass angle of the primary lit rim: 0° = Top, 90° = Right, 300° = iOS Upper-Left, 315° = 45° Top-Left. */
        const val DEFAULT_GLOW_ANGLE = 300
        const val MAX_GLOW_ANGLE = 359
        const val MAX_INTENSITY = 100
        const val MAX_BLUR = 200
        const val MAX_GLOW = 300
        const val MAX_DIM = 150
        const val MAX_REFRACTION = 250
        const val MAX_DISPERSION = 300
        const val MAX_VIBRANCY = 200
        const val MAX_FROST = 200

        fun fromJson(o: JSONObject?): GlobalStyle {
            o ?: return GlobalStyle()
            if (!o.has("tinted")) return fromAccentFormat(o)
            val color = opaque(o.optInt("color", GlassStyle.AUTO))
            val edgeColor = opaque(o.optInt("edgeColor", GlassStyle.AUTO))
            val glowColor = opaque(o.optInt("glowColor", GlassStyle.AUTO))
            val rawAngle = o.optInt("glowAngle", DEFAULT_GLOW_ANGLE)
            return GlobalStyle(
                tinted = o.optBoolean("tinted") && color != GlassStyle.AUTO,
                color = color,
                intensity = o.optInt("intensity", DEFAULT_INTENSITY).coerceIn(0, MAX_INTENSITY),
                edgeColor = edgeColor,
                glowColor = glowColor,
                glowAngle = ((rawAngle % 360) + 360) % 360,
                glowSensor = o.optBoolean("glowSensor", false),
                blur = o.optInt("blur", DEFAULT).coerceIn(0, MAX_BLUR),
                glow = o.optInt("glow", DEFAULT).coerceIn(0, MAX_GLOW),
                dim = o.optInt("dim", DEFAULT).coerceIn(0, MAX_DIM),
                refraction = o.optInt("refraction", DEFAULT).coerceIn(0, MAX_REFRACTION),
                dispersion = o.optInt("dispersion", DEFAULT).coerceIn(0, MAX_DISPERSION),
                vibrancy = o.optInt("vibrancy", DEFAULT).coerceIn(0, MAX_VIBRANCY),
                frost = o.optInt("frost", DEFAULT).coerceIn(0, MAX_FROST),
            )
        }

        /** The format before 2026-11-23: "intensity" (the blur), "edge", and an accent whose alpha was its strength. */
        private fun fromAccentFormat(o: JSONObject): GlobalStyle {
            val accent = o.optInt("accent", GlassStyle.AUTO)
            val tinted = accent != GlassStyle.AUTO
            return GlobalStyle(
                tinted = tinted,
                color = opaque(accent),
                intensity = if (!tinted) DEFAULT_INTENSITY
                    else Math.round((accent ushr 24) * 100f / GlassStyle.TINT_ALPHA).coerceIn(0, MAX_INTENSITY),
                blur = o.optInt("intensity", DEFAULT).coerceIn(0, MAX_BLUR),
                glow = o.optInt("edge", DEFAULT).coerceIn(0, MAX_GLOW),
            )
        }

        private fun opaque(c: Int) = if (c == GlassStyle.AUTO) c else c or 0xFF000000.toInt()
    }
}

/** A complete look preset (All surfaces + per-panel overrides) for either Light or Dark system theme. */
data class ThemePreset(
    val global: GlobalStyle = GlobalStyle(),
    val own: Map<Surface, GlobalStyle> = emptyMap(),
    val follow: Set<Surface> = emptySet(),
) {
    fun follows(s: Surface) = s.lookKey in follow || s.lookKey !in own
    fun look(s: Surface) = if (follows(s)) global else own[s.lookKey] ?: global

    fun toJson(): JSONObject {
        val o = JSONObject().put("global", global.toJson())
        for (s in Surface.PANELS) {
            val hasOwn = own.containsKey(s)
            val hasFollow = s in follow
            if (!hasOwn && !hasFollow) continue
            val e = JSONObject()
            own[s]?.let { e.put("look", it.toJson()) }
            if (hasFollow) e.put("follow", true)
            o.put(s.key, e)
        }
        return o
    }

    companion object {
        fun defaultDark() = ThemePreset(GlobalStyle())

        fun fromJson(o: JSONObject?): ThemePreset {
            o ?: return defaultDark()
            val own = HashMap<Surface, GlobalStyle>()
            val follow = HashSet<Surface>()
            for (s in Surface.PANELS) o.optJSONObject(s.key)?.let { e ->
                e.optJSONObject("look")?.let { own[s] = GlobalStyle.fromJson(it) }
                if (e.optBoolean("follow", false)) follow.add(s)
            }
            return ThemePreset(GlobalStyle.fromJson(o.optJSONObject("global")), own, follow)
        }
    }
}

/**
 * What the hooks consume: a surface's on/off and shape, plus the [look] it uses.
 * @param cornerDp corner radius in dp; [ORIGINAL] keeps the surface's built-in shape
 */
data class GlassStyle(
    val enabled: Boolean,
    val look: GlobalStyle = GlobalStyle(),
    val cornerDp: Int = ORIGINAL,
) {
    val blur get() = look.blur
    val color get() = look.color
    val tinted get() = look.tinted && look.color != AUTO

    /** Specular rim hairline colour (opaque RGB; defaults to white when AUTO). */
    val edgeRgb: Int get() = if (look.edgeColor != AUTO) look.edgeColor or 0xFF000000.toInt() else Color.WHITE

    /** Directional inward rim glow / sheen colour (opaque RGB; defaults to white when AUTO). */
    val glowRgb: Int get() = if (look.glowColor != AUTO) look.glowColor or 0xFF000000.toInt() else Color.WHITE

    private val baseX: Float get() = -kotlin.math.sin(Math.toRadians(look.glowAngle.toDouble())).toFloat()
    private val baseY: Float get() = kotlin.math.cos(Math.toRadians(look.glowAngle.toDouble())).toFloat()

    /**
     * Light travel vector X from compass [GlobalStyle.glowAngle] (0° = Top, 90° = Right, 300° = Upper-Left);
     * with [GlobalStyle.glowSensor] it is rotated by the device tilt.
     */
    val lightX: Float get() = if (look.glowSensor) LightSourceController.rotateX(baseX, baseY) else baseX

    /** Light travel vector Y; see [lightX]. */
    val lightY: Float get() = if (look.glowSensor) LightSourceController.rotateY(baseX, baseY) else baseY

    /** Multiplier on a built-in light-edge strength. */
    val glow get() = look.glow / 100f

    /** Multiplier on a built-in panel dim. */
    val dim get() = look.dim / 100f

    /** Multiplier on lens refraction / bevel depth. */
    val refraction get() = look.refraction / 100f

    /** Multiplier on RGB chromatic dispersion. */
    val dispersion get() = look.dispersion / 100f

    /** Multiplier on backdrop vibrancy / saturation boost. */
    val vibrancy get() = look.vibrancy / 100f

    /** Multiplier on the Clear frosted body opacity. */
    val frost get() = look.frost / 100f

    /** Blur radius in px for a surface whose built-in radius is [defaultPx]. */
    fun blurPx(defaultPx: Float) = defaultPx * blur / 100f

    /** Scales a saturation percentage (100 = neutral) by [vibrancy]. */
    fun satPercent(baseSat: Float): Float = (100f + (baseSat - 100f) * vibrancy).coerceIn(0f, 300f)

    /** Scales the alpha of a built-in Clear body by [frost]. */
    fun frosted(builtIn: Int): Int {
        if (look.frost == 100 || builtIn == 0) return builtIn
        val a = Math.round((builtIn ushr 24) * frost).coerceIn(0, 255)
        return (builtIn and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * A glass body. Clear is [builtIn] scaled by [frost]; Tinted moves from it towards [colour] at
     * [fullAlpha] as the intensity rises, so intensity 0 and Clear are the same glass.
     */
    fun body(builtIn: Int, colour: Int = color, fullAlpha: Int = TINT_ALPHA): Int {
        val clear = frosted(builtIn)
        if (!tinted || look.intensity == 0) return clear
        val t = look.intensity / 100f
        // Mixed premultiplied, so a faint built-in colour doesn't wash the tint out.
        val from = (clear ushr 24) * (1 - t)
        val to = fullAlpha * t
        val a = from + to
        if (a <= 0f) return clear
        fun channel(shift: Int) = Math.round((((clear shr shift) and 0xFF) * from + ((colour shr shift) and 0xFF) * to) / a)
        return Color.argb(Math.round(a).coerceIn(0, 255), channel(16), channel(8), channel(0))
    }

    companion object {
        const val ORIGINAL = -1
        const val AUTO = 0

        /** Opacity of the colour at full intensity (70 %): still glass, not paint. */
        const val TINT_ALPHA = 0xB3
    }
}

/**
 * The lock screen's arrangement (after iOS 27).
 *
 * Box sizes are thousandths of the screen width ([..W]) and height ([..H]); 0 = Nubia's own.
 */
data class LockLayout(
    val clockHeight: Int = 0,
    val clockBoldness: Int = DEFAULT_BOLDNESS,
    val bottomWidgets: Boolean = true,
    val bottomNotifications: Boolean = false,
    val clockWidth: Int = 0,
    val dateW: Int = 0,
    val dateH: Int = 0,
    val dateWeight: Int = DEFAULT_DATE_WEIGHT,
    val noteW: Int = 0,
    val noteH: Int = 0,
    val noteWeight: Int = 0,
    val styleBoxes: Map<String, Pair<Int, Int>> = emptyMap(),
    val filterMode: Int = WallpaperFilter.NATURAL,
    val filterColor: Int = WallpaperFilter.DEFAULT_COLOR,
) {
    val hasCustomClock get() = clockHeight > 0

    fun clockHeightPx(screenHeight: Int): Float =
        screenHeight * (if (hasCustomClock) clockHeight / 1000f else NUBIA_CLOCK)

    fun clockWidthPx(screenWidth: Int): Float =
        screenWidth * (if (clockWidth > 0) clockWidth else DEFAULT_CLOCK_WIDTH) / 1000f

    /** The fields Nubia's editor sets: clock, date and custom text boxes, weights, and wallpaper filter. */
    fun editorFields() = listOf(
        clockHeight, clockWidth, clockBoldness, dateW, dateH, dateWeight,
        noteW, noteH, noteWeight, styleBoxes, filterMode, filterColor,
    )

    /** This layout with [from]'s editor fields; the app's switches stay. */
    fun withEditorFields(from: LockLayout) = copy(
        clockHeight = from.clockHeight, clockWidth = from.clockWidth, clockBoldness = from.clockBoldness,
        dateW = from.dateW, dateH = from.dateH, dateWeight = from.dateWeight,
        noteW = from.noteW, noteH = from.noteH, noteWeight = from.noteWeight, styleBoxes = from.styleBoxes,
        filterMode = from.filterMode, filterColor = from.filterColor,
    )

    fun toJson(): JSONObject = JSONObject().put(KEY_HEIGHT, clockHeight).put(KEY_BOLDNESS, clockBoldness)
        .put("bottomWidgets", bottomWidgets).put("notificationsAtBottom", bottomNotifications)
        .put("clockWidth", clockWidth).put("dateW", dateW).put("dateH", dateH).put("dateWeight", dateWeight)
        .put("noteW", noteW).put("noteH", noteH).put("noteWeight", noteWeight)
        .put("filterMode", filterMode).put("filterColor", filterColor)
        .put("styleBoxes", JSONObject().also { o -> for ((k, v) in styleBoxes) o.put(k, org.json.JSONArray().put(v.first).put(v.second)) })

    companion object {
        /** Shares of the screen height: the range iOS allows, and Nubia's own digits. */
        const val MIN_CLOCK = 0.085f
        const val MAX_CLOCK = 0.40f
        const val NUBIA_CLOCK = 0.094f
        const val DEFAULT_BOLDNESS = 80
        /** The clock box's width when not set, and its range; thousandths of the screen width. */
        const val DEFAULT_CLOCK_WIDTH = 860
        const val MIN_WIDTH = 160
        /** Keeps a centred box's right corner, where its handle is, out of the 82 px back-gesture strip. */
        const val MAX_WIDTH = 860
        /** A text box's height range, thousandths of the screen height. */
        const val MIN_TEXT_H = 8
        const val MAX_TEXT_H = 260
        const val KEY_HEIGHT = "clockHeight"
        const val KEY_BOLDNESS = "clockBoldness"
        const val KEY_LAYOUT = "lockLayout"
        const val CALL_SAVE_CLOCK = "saveClock"
        const val CALL_SAVE_LAYOUT = "saveLockLayout"
        const val DEFAULT_DATE_WEIGHT = 700

        fun clock(height: Int) = if (height <= 0) 0 else height.coerceIn((MIN_CLOCK * 1000).toInt(), (MAX_CLOCK * 1000).toInt())
        fun width(w: Int) = if (w <= 0) 0 else w.coerceIn(MIN_WIDTH, MAX_WIDTH)
        fun textHeight(h: Int) = if (h <= 0) 0 else h.coerceIn(MIN_TEXT_H, MAX_TEXT_H)
        private fun weight(w: Int) = if (w <= 0) 0 else w.coerceIn(100, 900)

        fun fromJson(o: JSONObject?): LockLayout {
            o ?: return LockLayout()
            return LockLayout(
                clockHeight = clock(o.optInt(KEY_HEIGHT, 0)),
                clockBoldness = o.optInt(KEY_BOLDNESS, DEFAULT_BOLDNESS).coerceIn(0, 100),
                bottomWidgets = o.optBoolean("bottomWidgets", true),
                bottomNotifications = o.optBoolean("notificationsAtBottom", false),
                clockWidth = width(o.optInt("clockWidth", 0)),
                dateW = width(o.optInt("dateW", 0)),
                dateH = textHeight(o.optInt("dateH", 0)),
                dateWeight = weight(o.optInt("dateWeight", DEFAULT_DATE_WEIGHT)).takeIf { it > 0 } ?: DEFAULT_DATE_WEIGHT,
                noteW = width(o.optInt("noteW", 0)),
                noteH = textHeight(o.optInt("noteH", 0)),
                noteWeight = weight(o.optInt("noteWeight", 0)),
                filterMode = o.optInt("filterMode", WallpaperFilter.NATURAL).coerceIn(0, WallpaperFilter.COUNT - 1),
                filterColor = o.optInt("filterColor", WallpaperFilter.DEFAULT_COLOR) or 0xFF000000.toInt(),
                styleBoxes = o.optJSONObject("styleBoxes")?.let { b ->
                    b.keys().asSequence().mapNotNull { k ->
                        val a = b.optJSONArray(k) ?: return@mapNotNull null
                        val w = width(a.optInt(0)); val h = textHeight(a.optInt(1)).let { if (it == 0) 0 else a.optInt(1).coerceIn(MIN_TEXT_H, (MAX_CLOCK * 1000).toInt()) }
                        if (w > 0 && h > 0) k to (w to h) else null
                    }.toMap()
                } ?: emptyMap(),
            )
        }
    }
}

/**
 * Corner and size overrides. Corners: [GlassStyle.ORIGINAL] keeps the surface's own shape,
 * [PILL] rounds to half the shorter side.
 */
data class Shapes(
    val folderCorner: Int = GlassStyle.ORIGINAL,
    val tileCorner: Int = GlassStyle.ORIGINAL,
    val dockWidth: Int = 100,
    val dockPad: Int = DOCK_PAD,
    val dockCorner: Int = GlassStyle.ORIGINAL,
    /** Notification cards in the shade, and the shade's media card. */
    val cardCorner: Int = GlassStyle.ORIGINAL,
    /** Heads-up cards over an app (and a heads-up media card). */
    val headsUpCorner: Int = GlassStyle.ORIGINAL,
    /** Lock-screen notification cards and the lock-screen media card. */
    val lockCardCorner: Int = GlassStyle.ORIGINAL,
    /**
     * Home widget containers: dp taken off the cell span's width / height, the container centred in it
     * (above the title band when a title shows). [GlassStyle.ORIGINAL] keeps each widget's own size.
     */
    val widgetWidth: Int = GlassStyle.ORIGINAL,
    val widgetHeight: Int = GlassStyle.ORIGINAL,
    val widgetCorner: Int = GlassStyle.ORIGINAL,
    /** Large folder tiles, measured the same way as [widgetWidth] so equal values line up. */
    val folderWidth: Int = GlassStyle.ORIGINAL,
    val folderHeight: Int = GlassStyle.ORIGINAL,
) {
    fun corner(s: Surface): Int = when (s) {
        Surface.FOLDER -> folderCorner
        Surface.CONTROL_CENTER -> tileCorner
        Surface.DOCK -> dockCorner
        Surface.NOTIFICATION -> cardCorner
        Surface.LOCK_CARD -> lockCardCorner
        Surface.HOME_WIDGET -> widgetCorner
        else -> GlassStyle.ORIGINAL
    }

    fun toJson(): JSONObject = JSONObject().put("folderCorner", folderCorner).put("tileCorner", tileCorner)
        .put("dockWidth", dockWidth).put("dockPad", dockPad).put("dockCorner", dockCorner)
        .put("cardCorner", cardCorner).put("headsUpCorner", headsUpCorner).put("lockCardCorner", lockCardCorner)
        .put("widgetWidth", widgetWidth).put("widgetHeight", widgetHeight).put("widgetCorner", widgetCorner)
        .put("folderWidth", folderWidth).put("folderHeight", folderHeight)

    companion object {
        const val PILL = 999
        const val MAX_CORNER = 60
        const val MIN_DOCK_WIDTH = 50
        const val MAX_DOCK_PAD = 40
        const val DOCK_PAD = 12
        /** Largest trim off a cell span, dp. */
        const val MAX_TRIM = 120
        /** The band under a container kept for a widget title or folder name, dp. */
        const val LABEL_BAND = 38

        fun trim(v: Int) = if (v < 0) GlassStyle.ORIGINAL else v.coerceAtMost(MAX_TRIM)

        fun corner(v: Int) = if (v < 0) GlassStyle.ORIGINAL else if (v >= PILL) PILL else v.coerceAtMost(MAX_CORNER)

        fun fromJson(o: JSONObject?): Shapes {
            o ?: return Shapes()
            return Shapes(
                folderCorner = corner(o.optInt("folderCorner", -1)),
                tileCorner = corner(o.optInt("tileCorner", -1)),
                dockWidth = o.optInt("dockWidth", 100).coerceIn(MIN_DOCK_WIDTH, 100),
                dockPad = o.optInt("dockPad", DOCK_PAD).coerceIn(0, MAX_DOCK_PAD),
                dockCorner = corner(o.optInt("dockCorner", -1)),
                cardCorner = corner(o.optInt("cardCorner", -1)),
                headsUpCorner = corner(o.optInt("headsUpCorner", -1)),
                lockCardCorner = corner(o.optInt("lockCardCorner", -1)),
                widgetWidth = trim(o.optInt("widgetWidth", -1)),
                widgetHeight = trim(o.optInt("widgetHeight", -1)),
                widgetCorner = corner(o.optInt("widgetCorner", -1)),
                folderWidth = trim(o.optInt("folderWidth", -1)),
                folderHeight = trim(o.optInt("folderHeight", -1)),
            )
        }
    }
}

/**
 * One home-screen widget's own settings, set from its long-press menu. Keyed by app widget id.
 * @param title [TITLE_AUTO] keeps Nubia's choice
 * @param label replaces the title text when not empty
 * @param scale content zoom in percent, like a per-widget dpi
 * @param marginX px between the container's left / right edges and the content; negative pushes the
 *   content past them (cropped)
 * @param marginY the same for the top and bottom edges
 * @param width this widget's container width, as [Shapes.widgetWidth]; [GlassStyle.ORIGINAL] uses that
 * @param height this widget's container height, as [Shapes.widgetHeight]; [GlassStyle.ORIGINAL] uses that
 */
data class WidgetTweak(
    val title: Int = TITLE_AUTO,
    val label: String = "",
    val scale: Int = 100,
    val marginX: Int = 0,
    val marginY: Int = 0,
    val width: Int = GlassStyle.ORIGINAL,
    val height: Int = GlassStyle.ORIGINAL,
    val glass: Boolean = true,
) {
    val isDefault get() = this == WidgetTweak()

    fun toJson(): JSONObject = JSONObject().put("title", title).put("label", label)
        .put("scale", scale).put("marginX", marginX).put("marginY", marginY)
        .put("width", width).put("height", height).put("glass", glass)

    companion object {
        const val TITLE_AUTO = 0
        const val TITLE_SHOW = 1
        const val TITLE_HIDE = 2
        const val MIN_SCALE = 40
        const val MAX_SCALE = 140
        const val MAX_MARGIN = 120
        const val KEY = "widgets"
        const val CALL_SAVE = "saveWidgets"

        fun fromJson(o: JSONObject?): WidgetTweak {
            o ?: return WidgetTweak()
            return WidgetTweak(
                title = o.optInt("title", TITLE_AUTO).coerceIn(TITLE_AUTO, TITLE_HIDE),
                label = o.optString("label", "").take(40),
                scale = o.optInt("scale", 100).coerceIn(MIN_SCALE, MAX_SCALE),
                // "margin" was one value for all sides (first build of this menu).
                marginX = o.optInt("marginX", o.optInt("margin", 0)).coerceIn(-MAX_MARGIN, MAX_MARGIN),
                marginY = o.optInt("marginY", o.optInt("margin", 0)).coerceIn(-MAX_MARGIN, MAX_MARGIN),
                width = Shapes.trim(o.optInt("width", -1)),
                height = Shapes.trim(o.optInt("height", -1)),
                glass = o.optBoolean("glass", true),
            )
        }

        fun mapToJson(m: Map<Int, WidgetTweak>): JSONObject =
            JSONObject().also { o -> for ((id, t) in m) if (!t.isDefault) o.put(id.toString(), t.toJson()) }

        fun mapFromJson(o: JSONObject?): Map<Int, WidgetTweak> {
            o ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val id = k.toIntOrNull() ?: return@mapNotNull null
                id to fromJson(o.optJSONObject(k))
            }.filter { !it.second.isDefault }.toMap()
        }
    }
}

/**
 * Everything the user can set:
 * - Light theme preset stored in [global], [own], [follow] (preserving pre-existing saved settings as Light theme!)
 * - Dark theme preset stored in [dark]
 * - Per-surface switches [on], lock layout [lock], and [shapes].
 */
data class GlassConfig(
    val global: GlobalStyle,
    val on: Map<Surface, Boolean>,
    val own: Map<Surface, GlobalStyle> = emptyMap(),
    val lock: LockLayout = LockLayout(),
    val follow: Set<Surface> = emptySet(),
    val shapes: Shapes = Shapes(),
    val dark: ThemePreset = ThemePreset.defaultDark(),
    /** Per-widget settings from the launcher's long-press menu. */
    val widgets: Map<Int, WidgetTweak> = emptyMap(),
) {
    val light: ThemePreset get() = ThemePreset(global, own, follow)

    fun preset(night: Boolean): ThemePreset = if (night) dark else light

    fun withPreset(night: Boolean, p: ThemePreset): GlassConfig =
        if (night) copy(dark = p) else copy(global = p.global, own = p.own, follow = p.follow)

    fun isOn(s: Surface) = on[s] ?: s.defaultEnabled

    /** Any look in either preset follows device tilt: the tilt sensor is needed. */
    fun anySensor(): Boolean = listOf(light, dark).any { p -> p.global.glowSensor || p.own.values.any { it.glowSensor } }

    /** Whether panel [s] follows the shared look ("Follow all surfaces") in the given theme. */
    fun follows(s: Surface, night: Boolean = false) = preset(night).follows(s)

    /** The look [s] uses in the given theme: its own unless it follows the shared one. */
    fun look(s: Surface, night: Boolean = false) = preset(night).look(s)

    /** The effective per-surface style the hooks consume. */
    fun style(s: Surface, night: Boolean = false) = GlassStyle(enabled = isOn(s), look = look(s, night), cornerDp = shapes.corner(s))

    fun toJson(): String {
        val o = JSONObject()
            .put("global", global.toJson())
            .put("dark", dark.toJson())
            .put("lockLayout", lock.toJson())
            .put("shapes", shapes.toJson())
            .put(WidgetTweak.KEY, WidgetTweak.mapToJson(widgets))
        for (s in Surface.values()) {
            val e = JSONObject().put("on", isOn(s))
            own[s]?.let { e.put("look", it.toJson()) }
            if (s in follow) e.put("follow", true)
            o.put(s.key, e)
        }
        return o.toString()
    }

    companion object {
        fun defaults() = GlassConfig(GlobalStyle(), emptyMap())

        /** Root keys store the Light preset, so existing user settings migrate automatically as Light theme. */
        fun parse(json: String?): GlassConfig {
            if (json.isNullOrBlank()) return defaults()
            return try {
                val o = JSONObject(json)
                fun saved(s: Surface) = o.optJSONObject(s.key)?.optBoolean("on", s.defaultEnabled)
                val on = Surface.values().associateWith { s ->
                    saved(s)
                        ?: (if (s == Surface.LOCK_CARD) saved(Surface.NOTIFICATION) else null)
                        ?: s.defaultEnabled
                }
                val own = HashMap<Surface, GlobalStyle>()
                val follow = HashSet<Surface>()
                for (s in Surface.PANELS) o.optJSONObject(s.key)?.let { e ->
                    e.optJSONObject("look")?.let { own[s] = GlobalStyle.fromJson(it) }
                    if (e.optBoolean("follow", false)) follow.add(s)
                }
                val dark = ThemePreset.fromJson(o.optJSONObject("dark"))
                GlassConfig(
                    GlobalStyle.fromJson(o.optJSONObject("global")),
                    on,
                    own,
                    LockLayout.fromJson(o.optJSONObject("lockLayout")),
                    follow,
                    Shapes.fromJson(o.optJSONObject("shapes")),
                    dark,
                    WidgetTweak.mapFromJson(o.optJSONObject(WidgetTweak.KEY)),
                )
            } catch (_: Exception) { defaults() }
        }
    }
}

/**
 * The module's settings, shared by the settings app and the hooked processes (launcher, SystemUI).
 */
object GlassSettings {
    const val PKG = "com.nathanhanapps.nebulacolorize"
    const val PREFS = "glass"
    const val KEY = "json"
    const val ACTION = "$PKG.SETTINGS"
    const val PERMISSION = "$PKG.permission.SETTINGS"
    const val EXTRA = "json"
    const val LAYOUT_AUTHORITY = "$PKG.layout"
    val TARGETS = listOf("com.android.systemui", "com.zte.mifavor.launcher", "com.zte.mifavor.miboard")

    @Volatile var config: GlassConfig = GlassConfig.defaults()
        private set
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun systemNight(): Boolean = try {
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) { false }

    fun isNight(ctx: Context?): Boolean = if (ctx == null) systemNight() else
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Effective style for [s] in the active Light or Dark system preset. */
    operator fun get(s: Surface, night: Boolean = systemNight()): GlassStyle = config.style(s, night)

    operator fun get(s: Surface, ctx: Context?): GlassStyle = config.style(s, isNight(ctx))

    /** Change the settings in this process only (a hook previewing an edit before it is saved). */
    fun updateLocal(c: GlassConfig) {
        config = c
        notifyListeners()
    }

    /** Replace the current settings and notify listeners on the main thread. */
    fun update(json: String?) {
        config = GlassConfig.parse(json)
        notifyListeners()
    }

    /** Trigger a live refresh (e.g. when the system night mode toggles). */
    fun notifyListeners() {
        main.post { for (l in listeners.toList()) try { l() } catch (t: Throwable) { android.util.Log.e("NebulaGlass", "settings listener", t) } }
    }

    /** Main thread. Called after every settings or theme change. */
    fun addListener(l: () -> Unit) { listeners.add(l) }
}

/**
 * Applies a [GlassStyle] on top of a surface's built-in lens values.
 * Call before each draw; the setters are plain field writes.
 */
fun LensGlass.applyStyle(
    st: GlassStyle,
    density: Float,
    originalCornerPx: Float,
    /** The resolved body colour (see [Looks.style]). */
    originalTint: Int,
    originalSpecular: Float,
    /** Built-in blur: an explicit radius, or null for the [LensGlass.blurAmount] default. */
    originalBlurOverride: Float?,
    originalBevel: Float = bevelWidth,
    originalRefraction: Float = refractionHeight,
    originalDispersion: Float = dispersion,
    originalSaturation: Float = saturation,
) {
    cornerRadius = if (st.cornerDp >= 0) st.cornerDp * density else originalCornerPx
    glassTint = originalTint
    frostScale = st.frost
    frostCoat = !st.tinted
    edgeColor = st.edgeRgb
    glowColor = st.glowRgb
    lightX = st.lightX
    lightY = st.lightY
    specular = originalSpecular * st.glow
    bevelWidth = (originalBevel * (0.5f + 0.5f * st.refraction)).coerceAtLeast(2f)
    refractionHeight = originalRefraction * st.refraction
    dispersion = (originalDispersion * st.dispersion).coerceIn(0f, 0.85f)
    saturation = st.satPercent(originalSaturation)
    blurRadiusOverride = if (st.blur == 100) originalBlurOverride
        else st.blurPx(originalBlurOverride ?: defaultBlurPx)
}
