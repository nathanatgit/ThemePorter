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
    NOTIFICATION("notification", "Notification cards", "Notifications & control centre", true, "Nubia's shape"),
    CONTROL_CENTER("controlCenter", "Control centre tiles", "Notifications & control centre", true, "Nubia's shape");

    val isText get() = !hasCorner

    companion object {
        /** Panel surfaces that can have their own intensity, edge glow and accent. */
        val PANELS = listOf(NOTIFICATION, CONTROL_CENTER, WIDGET_BOARD)
    }
}

/**
 * Per-surface glass style.
 * @param cornerDp corner radius in dp; [ORIGINAL] keeps the surface's built-in shape
 * @param accent glass body colour, ARGB where alpha is the strength; [AUTO] keeps the built-in tint
 *               (for clock text, Auto follows the text colour)
 * @param blur blur intensity, percent of the built-in default (0..200)
 * @param edge light-edge (specular rim) intensity, percent of the built-in default (0..300)
 */
data class GlassStyle(
    val enabled: Boolean,
    val cornerDp: Int = ORIGINAL,
    val accent: Int = AUTO,
    val blur: Int = 100,
    val edge: Int = 100,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("on", enabled).put("corner", cornerDp).put("accent", accent).put("blur", blur).put("edge", edge)

    /** Blur radius in px for a surface whose built-in radius is [defaultPx]. */
    fun blurPx(defaultPx: Float) = defaultPx * blur / 100f

    companion object {
        const val ORIGINAL = -1
        const val AUTO = 0
        const val MAX_CORNER_DP = 48
        const val MAX_BLUR = 200
        const val MAX_EDGE = 300

        fun default(s: Surface) = GlassStyle(enabled = s.defaultEnabled)

        fun fromJson(s: Surface, o: JSONObject?): GlassStyle {
            val d = default(s)
            o ?: return d
            return GlassStyle(
                enabled = o.optBoolean("on", d.enabled),
                cornerDp = o.optInt("corner", d.cornerDp).coerceIn(ORIGINAL, MAX_CORNER_DP),
                accent = o.optInt("accent", d.accent),
                blur = o.optInt("blur", d.blur).coerceIn(0, MAX_BLUR),
                edge = o.optInt("edge", d.edge).coerceIn(0, MAX_EDGE),
            )
        }
    }
}

/**
 * The glass look shared by every surface (user decision 2026-11-21: one accent, one intensity,
 * one edge glow for all Liquid Glass; surfaces only switch on/off).
 * @param accent ARGB, alpha = strength; [GlassStyle.AUTO] keeps each surface's built-in tint
 * @param intensity percent of each surface's built-in glass (blur for lenses, body opacity for skins), 0..200
 * @param edge edge-glow (specular rim) percent of the built-in value, 0..300
 */
data class GlobalStyle(val accent: Int = GlassStyle.AUTO, val intensity: Int = 100, val edge: Int = 100) {
    fun toJson(): JSONObject = JSONObject().put("accent", accent).put("intensity", intensity).put("edge", edge)

    companion object {
        fun fromJson(o: JSONObject?): GlobalStyle {
            o ?: return GlobalStyle()
            return GlobalStyle(
                accent = o.optInt("accent", GlassStyle.AUTO),
                intensity = o.optInt("intensity", 100).coerceIn(0, GlassStyle.MAX_BLUR),
                edge = o.optInt("edge", 100).coerceIn(0, GlassStyle.MAX_EDGE),
            )
        }
    }
}

/**
 * Everything the user can set: the shared [global] look, an on/off per surface, and an optional own
 * look ([own]) for the panel surfaces in [Surface.PANELS] (notifications, control centre, widget board).
 */
data class GlassConfig(
    val global: GlobalStyle,
    val on: Map<Surface, Boolean>,
    val own: Map<Surface, GlobalStyle> = emptyMap(),
) {
    fun isOn(s: Surface) = on[s] ?: s.defaultEnabled

    /** The look [s] uses: its own override if set, else the shared one. */
    fun look(s: Surface) = own[s] ?: global

    /** The effective per-surface style the hooks consume (shape always the surface's own). */
    fun style(s: Surface): GlassStyle {
        val g = look(s)
        return GlassStyle(enabled = isOn(s), cornerDp = GlassStyle.ORIGINAL, accent = g.accent, blur = g.intensity, edge = g.edge)
    }

    fun toJson(): String {
        val o = JSONObject().put("global", global.toJson())
        for (s in Surface.values()) {
            val e = JSONObject().put("on", isOn(s))
            own[s]?.let { e.put("look", it.toJson()) }
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
                val on = Surface.values().associateWith { s -> o.optJSONObject(s.key)?.optBoolean("on", s.defaultEnabled) ?: s.defaultEnabled }
                val own = HashMap<Surface, GlobalStyle>()
                for (s in Surface.PANELS) o.optJSONObject(s.key)?.optJSONObject("look")?.let { own[s] = GlobalStyle.fromJson(it) }
                GlassConfig(GlobalStyle.fromJson(o.optJSONObject("global")), on, own)
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
    const val PKG = "com.nathanhanapps.nebulaColorize"
    const val PREFS = "glass"
    const val KEY = "json"
    const val ACTION = "$PKG.SETTINGS"
    const val PERMISSION = "$PKG.permission.SETTINGS"
    const val EXTRA = "json"
    val TARGETS = listOf("com.android.systemui", "com.zte.mifavor.launcher", "com.zte.mifavor.miboard")

    @Volatile var config: GlassConfig = GlassConfig.defaults()
        private set
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Effective style for [s]: its on/off plus the shared accent, intensity and edge glow. */
    operator fun get(s: Surface): GlassStyle = config.style(s)

    /** Replace the current settings and notify listeners on the main thread. */
    fun update(json: String?) {
        config = GlassConfig.parse(json)
        main.post { for (l in listeners.toList()) try { l() } catch (t: Throwable) { android.util.Log.e("NebulaGlass", "settings listener", t) } }
    }

    /** Main thread. Called after every settings change. */
    fun addListener(l: () -> Unit) { listeners.add(l) }

    /** Accent presets offered in the UI (RGB; strength is chosen separately). */
    val PALETTE = intArrayOf(
        Color.rgb(255, 255, 255), Color.rgb(28, 28, 30), Color.rgb(10, 132, 255), Color.rgb(94, 92, 230),
        Color.rgb(191, 90, 242), Color.rgb(255, 55, 95), Color.rgb(255, 69, 58), Color.rgb(255, 159, 10),
        Color.rgb(255, 214, 10), Color.rgb(215, 226, 52), Color.rgb(48, 209, 88), Color.rgb(100, 210, 255),
    )
}

/**
 * Applies a [GlassStyle] on top of a surface's built-in lens values.
 * Call before each draw; the setters are plain field writes.
 */
fun LensGlass.applyStyle(
    st: GlassStyle,
    density: Float,
    originalCornerPx: Float,
    originalTint: Int,
    originalSpecular: Float,
    /** Built-in blur: an explicit radius, or null for the [LensGlass.blurAmount] default. */
    originalBlurOverride: Float?,
) {
    cornerRadius = if (st.cornerDp >= 0) st.cornerDp * density else originalCornerPx
    glassTint = if (st.accent != GlassStyle.AUTO) st.accent else originalTint
    specular = originalSpecular * st.edge / 100f
    blurRadiusOverride = if (st.blur == 100) originalBlurOverride
        else st.blurPx(originalBlurOverride ?: defaultBlurPx)
}
