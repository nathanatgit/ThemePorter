package dev.nebula.glass

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

    companion object {
        /** Surfaces that can have their own look. */
        val PANELS by lazy { listOf(NOTIFICATION, CONTROL_CENTER, LOCK_WIDGET, WIDGET_BOARD) }
    }
}

/**
 * The glass look (user decisions 2026-11-23): a style, and three amounts that each do one thing.
 * Light and dark are not a setting: glass follows the system theme, like the hosts' own text.
 * @param tinted false = Clear (each surface's built-in body), true = Tinted
 * @param color opaque RGB for Tinted, kept while Clear; [GlassStyle.AUTO] = none picked yet
 * @param intensity 0..100, how far Tinted goes from Clear (0) to the full colour (100)
 * @param blur percent of the built-in blur: how frosted what's behind is
 * @param glow percent of the built-in light edge
 * @param dim percent of the built-in dim behind notifications and the control centre
 */
data class GlobalStyle(
    val tinted: Boolean = false,
    val color: Int = GlassStyle.AUTO,
    val intensity: Int = DEFAULT_INTENSITY,
    val blur: Int = DEFAULT,
    val glow: Int = DEFAULT,
    val dim: Int = DEFAULT,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tinted", tinted).put("color", color).put("intensity", intensity)
        .put("blur", blur).put("glow", glow).put("dim", dim)

    companion object {
        const val DEFAULT = 100
        const val DEFAULT_INTENSITY = 50
        const val MAX_INTENSITY = 100
        const val MAX_BLUR = 200
        const val MAX_GLOW = 300
        const val MAX_DIM = 150

        fun fromJson(o: JSONObject?): GlobalStyle {
            o ?: return GlobalStyle()
            if (!o.has("tinted")) return fromAccentFormat(o)
            val color = opaque(o.optInt("color", GlassStyle.AUTO))
            return GlobalStyle(
                tinted = o.optBoolean("tinted") && color != GlassStyle.AUTO,
                color = color,
                intensity = o.optInt("intensity", DEFAULT_INTENSITY).coerceIn(0, MAX_INTENSITY),
                blur = o.optInt("blur", DEFAULT).coerceIn(0, MAX_BLUR),
                glow = o.optInt("glow", DEFAULT).coerceIn(0, MAX_GLOW),
                dim = o.optInt("dim", DEFAULT).coerceIn(0, MAX_DIM),
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

    /** Multiplier on a built-in light-edge strength. */
    val glow get() = look.glow / 100f

    /** Multiplier on a built-in panel dim. */
    val dim get() = look.dim / 100f

    /** Blur radius in px for a surface whose built-in radius is [defaultPx]. */
    fun blurPx(defaultPx: Float) = defaultPx * blur / 100f

    /**
     * A glass body. Clear is [builtIn]; Tinted moves from it towards [colour] at [fullAlpha] as the
     * intensity rises, so intensity 0 and Clear are the same glass. ([Looks.body] also fits the
     * colour's tone to the surface's text.)
     */
    fun body(builtIn: Int, colour: Int = color, fullAlpha: Int = TINT_ALPHA): Int {
        if (!tinted || look.intensity == 0) return builtIn
        val t = look.intensity / 100f
        // Mixed premultiplied, so a faint built-in colour doesn't wash the tint out.
        val from = (builtIn ushr 24) * (1 - t)
        val to = fullAlpha * t
        val a = from + to
        if (a <= 0f) return builtIn
        fun channel(shift: Int) = Math.round((((builtIn shr shift) and 0xFF) * from + ((colour shr shift) and 0xFF) * to) / a)
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
 * The lock screen's arrangement (user requests 2026-11-23 and 11-24, after iOS 27).
 *
 * The clock, the date line and the custom text under the clock (Nubia's signature) each have a box,
 * sized by dragging in Nubia's editor, both ways; the text fits the box, the tighter side deciding.
 * Box sizes are thousandths of the screen width ([..W]) and height ([..H]); 0 = Nubia's own.
 * @param clockHeight the clock box's height (digit height); 0 = Nubia's own clock and font
 * @param clockWidth the clock box's width; 0 = [DEFAULT_CLOCK_WIDTH]
 * @param clockBoldness 0..100 across the weights that fit at that height
 * @param bottomWidgets widgets and shortcuts where iOS puts them
 * @param bottomNotifications cards stack up from just above those; opt-in (user request 2026-11-24),
 *   stored as `notificationsAtBottom` so the old always-written default doesn't turn it on
 * @param dateW the date line's box (date and lunar date together)
 * @param dateWeight their font weight (100..900)
 * @param noteW the custom text's box
 * @param noteWeight its weight; 0 = Nubia's
 * @param styleBoxes the clock box of each other clock style (by its class's simple name), whose
 *   digits are text: (width, height) in thousandths of the screen
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
) {
    val hasCustomClock get() = clockHeight > 0

    fun clockHeightPx(screenHeight: Int): Float =
        screenHeight * (if (hasCustomClock) clockHeight / 1000f else NUBIA_CLOCK)

    fun clockWidthPx(screenWidth: Int): Float =
        screenWidth * (if (clockWidth > 0) clockWidth else DEFAULT_CLOCK_WIDTH) / 1000f

    /** The fields Nubia's editor sets: clock, date and custom text boxes and weights. */
    fun editorFields() = listOf(clockHeight, clockWidth, clockBoldness, dateW, dateH, dateWeight, noteW, noteH, noteWeight, styleBoxes)

    /** This layout with [from]'s editor fields; the app's switches stay. */
    fun withEditorFields(from: LockLayout) = copy(
        clockHeight = from.clockHeight, clockWidth = from.clockWidth, clockBoldness = from.clockBoldness,
        dateW = from.dateW, dateH = from.dateH, dateWeight = from.dateWeight,
        noteW = from.noteW, noteH = from.noteH, noteWeight = from.noteWeight, styleBoxes = from.styleBoxes,
    )

    fun toJson(): JSONObject = JSONObject().put(KEY_HEIGHT, clockHeight).put(KEY_BOLDNESS, clockBoldness)
        .put("bottomWidgets", bottomWidgets).put("notificationsAtBottom", bottomNotifications)
        .put("clockWidth", clockWidth).put("dateW", dateW).put("dateH", dateH).put("dateWeight", dateWeight)
        .put("noteW", noteW).put("noteH", noteH).put("noteWeight", noteWeight)
        .put("styleBoxes", JSONObject().also { o -> for ((k, v) in styleBoxes) o.put(k, org.json.JSONArray().put(v.first).put(v.second)) })

    companion object {
        /** Shares of the screen height: the range iOS allows, and Nubia's own digits. */
        const val MIN_CLOCK = 0.085f
        const val MAX_CLOCK = 0.40f
        const val NUBIA_CLOCK = 0.094f
        const val DEFAULT_BOLDNESS = 80
        /** The clock box's width when not set, and its range; thousandths of the screen width. */
        const val DEFAULT_CLOCK_WIDTH = 860
        const val MIN_WIDTH = 200
        /** Keeps a centred box's right corner, where its handle is, out of the 82 px back-gesture strip. */
        const val MAX_WIDTH = 860
        /** A text box's height range, thousandths of the screen height. */
        const val MIN_TEXT_H = 8
        const val MAX_TEXT_H = 120
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
 * Corner and size overrides (user request 2026-11-24). Corners: [GlassStyle.ORIGINAL] keeps the
 * surface's own shape, [PILL] rounds to half the shorter side.
 * @param folderCorner home-screen folder tiles, dp
 * @param tileCorner control-centre tiles and players, dp (2x1 and 1x2 tiles become pills when large)
 * @param dockWidth the dock panel's width, percent of the auto-fitted width (the icons' row, edge to edge)
 * @param dockPad space between the dock icons and the panel's top and bottom, dp
 * @param dockCorner dp; [GlassStyle.ORIGINAL] = 30 dp
 */
data class Shapes(
    val folderCorner: Int = GlassStyle.ORIGINAL,
    val tileCorner: Int = GlassStyle.ORIGINAL,
    val dockWidth: Int = 100,
    val dockPad: Int = DOCK_PAD,
    val dockCorner: Int = GlassStyle.ORIGINAL,
) {
    fun corner(s: Surface): Int = when (s) {
        Surface.FOLDER -> folderCorner
        Surface.CONTROL_CENTER -> tileCorner
        Surface.DOCK -> dockCorner
        else -> GlassStyle.ORIGINAL
    }

    fun toJson(): JSONObject = JSONObject().put("folderCorner", folderCorner).put("tileCorner", tileCorner)
        .put("dockWidth", dockWidth).put("dockPad", dockPad).put("dockCorner", dockCorner)

    companion object {
        const val PILL = 999
        const val MAX_CORNER = 60
        const val MIN_DOCK_WIDTH = 50
        const val MAX_DOCK_PAD = 40
        const val DOCK_PAD = 12

        fun corner(v: Int) = if (v < 0) GlassStyle.ORIGINAL else if (v >= PILL) PILL else v.coerceAtMost(MAX_CORNER)

        fun fromJson(o: JSONObject?): Shapes {
            o ?: return Shapes()
            return Shapes(
                folderCorner = corner(o.optInt("folderCorner", -1)),
                tileCorner = corner(o.optInt("tileCorner", -1)),
                dockWidth = o.optInt("dockWidth", 100).coerceIn(MIN_DOCK_WIDTH, 100),
                dockPad = o.optInt("dockPad", DOCK_PAD).coerceIn(0, MAX_DOCK_PAD),
                dockCorner = corner(o.optInt("dockCorner", -1)),
            )
        }
    }
}

/**
 * Everything the user can set: the shared [global] look, an on/off per surface, and an own look
 * ([own]) for the panel surfaces in [Surface.PANELS] (notifications, control centre, lock screen,
 * widget board). A panel in [follow] uses the shared look; its own one is kept for when it stops.
 */
data class GlassConfig(
    val global: GlobalStyle,
    val on: Map<Surface, Boolean>,
    val own: Map<Surface, GlobalStyle> = emptyMap(),
    val lock: LockLayout = LockLayout(),
    val follow: Set<Surface> = emptySet(),
    val shapes: Shapes = Shapes(),
) {
    fun isOn(s: Surface) = on[s] ?: s.defaultEnabled

    /** Whether panel [s] follows the shared look ("Follow all surfaces"). */
    fun follows(s: Surface) = s.lookKey in follow || s.lookKey !in own

    /** The look [s] uses: its own unless it follows the shared one. */
    fun look(s: Surface) = if (follows(s)) global else own[s.lookKey] ?: global

    /** The effective per-surface style the hooks consume. */
    fun style(s: Surface) = GlassStyle(enabled = isOn(s), look = look(s), cornerDp = shapes.corner(s))

    fun toJson(): String {
        val o = JSONObject().put("global", global.toJson()).put("lockLayout", lock.toJson()).put("shapes", shapes.toJson())
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

        /** Also reads the earlier per-surface format: only each surface's on/off is kept. */
        fun parse(json: String?): GlassConfig {
            if (json.isNullOrBlank()) return defaults()
            return try {
                val o = JSONObject(json)
                fun saved(s: Surface) = o.optJSONObject(s.key)?.optBoolean("on", s.defaultEnabled)
                val on = Surface.values().associateWith { s ->
                    saved(s)
                        // Lock-screen cards followed the notifications switch until they got their own.
                        ?: (if (s == Surface.LOCK_CARD) saved(Surface.NOTIFICATION) else null)
                        ?: s.defaultEnabled
                }
                val own = HashMap<Surface, GlobalStyle>()
                val follow = HashSet<Surface>()
                for (s in Surface.PANELS) o.optJSONObject(s.key)?.let { e ->
                    e.optJSONObject("look")?.let { own[s] = GlobalStyle.fromJson(it) }
                    if (e.optBoolean("follow", false)) follow.add(s)
                }
                GlassConfig(GlobalStyle.fromJson(o.optJSONObject("global")), on, own,
                    LockLayout.fromJson(o.optJSONObject("lockLayout")), follow, Shapes.fromJson(o.optJSONObject("shapes")))
            } catch (_: Exception) { defaults() }
        }
    }
}

/**
 * The module's settings, shared by the settings app and the hooked processes (launcher, SystemUI).
 *
 * Flow: the app writes [GlassConfig.toJson] to world-readable prefs ([PREFS]/[KEY]; Vector serves them to
 * hooks as XSharedPreferences, see `hook/HookSettings`) and broadcasts [ACTION] with the same JSON to
 * SystemUI and the launcher, guarded by the signature permission [PERMISSION], so changes apply live.
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

    /** Effective style for [s]: its on/off plus its own or the shared look. */
    operator fun get(s: Surface): GlassStyle = config.style(s)

    /** Replace the current settings and notify listeners on the main thread. */
    fun update(json: String?) {
        config = GlassConfig.parse(json)
        main.post { for (l in listeners.toList()) try { l() } catch (t: Throwable) { android.util.Log.e("NebulaGlass", "settings listener", t) } }
    }

    /** Main thread. Called after every settings change. */
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
) {
    cornerRadius = if (st.cornerDp >= 0) st.cornerDp * density else originalCornerPx
    glassTint = originalTint
    specular = originalSpecular * st.glow
    blurRadiusOverride = if (st.blur == 100) originalBlurOverride
        else st.blurPx(originalBlurOverride ?: defaultBlurPx)
}
