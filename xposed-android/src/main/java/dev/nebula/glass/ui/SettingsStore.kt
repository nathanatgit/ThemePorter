package dev.nebula.glass.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.nebula.glass.GlassConfig
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlobalStyle
import dev.nebula.glass.LockLayout
import dev.nebula.glass.Shapes
import dev.nebula.glass.Surface
import dev.nebula.glass.ThemePreset

import org.json.JSONObject

/**
 * Settings-app side of [GlassSettings]: keeps the editable map (with separate Light and Dark theme
 * presets), persists it where Vector can serve it to the hooks and pushes every change live.
 */
class SettingsStore(private val ctx: Context) {
    /** False when the prefs could not be made world-readable (module not active in Vector). */
    var hooksCanRead = true
        private set

    private val prefs: SharedPreferences = openPrefs()
    var config: GlassConfig = GlassConfig.parse(prefs.getString(GlassSettings.KEY, null))
        private set

    private val main = Handler(Looper.getMainLooper())
    private val push = Runnable { persistAndBroadcast() }

    fun isOn(s: Surface) = config.isOn(s)

    fun setOn(surfaces: List<Surface>, on: Boolean) = commit(config.copy(on = config.on + surfaces.associateWith { on }))

    fun resetGlobal(night: Boolean) = commit(config.withPreset(night, ThemePreset(GlobalStyle())))

    /** Copy one theme preset's parameters into the other (e.g. start Dark from Light). */
    fun copyPreset(fromNight: Boolean, toNight: Boolean) =
        commit(config.withPreset(toNight, config.preset(fromNight)))

    /** The look being edited in [night] mode: [scope]'s own, or [scope] == null for All surfaces. */
    fun look(scope: Surface?, night: Boolean) = if (scope == null) config.preset(night).global else config.look(scope, night)

    /** Panel [s] uses the shared look ("Follow all surfaces") in [night] mode. */
    fun follows(s: Surface, night: Boolean) = config.follows(s, night)

    /** Edit a look in [night] mode: the shared one, or a panel's own (it stops following). */
    fun setLook(scope: Surface?, night: Boolean, g: GlobalStyle) {
        val p = config.preset(night)
        val next = if (scope == null) p.copy(global = g)
        else p.copy(own = p.own + (scope to g), follow = p.follow - scope)
        commit(config.withPreset(night, next))
    }

    /** Follow the shared look, or stop, in [night] mode. */
    fun setFollow(s: Surface, night: Boolean, follow: Boolean) {
        val p = config.preset(night)
        val next = if (follow) p.copy(follow = p.follow + s)
        else p.copy(own = p.own + (s to (p.own[s] ?: p.global)), follow = p.follow - s)
        commit(config.withPreset(night, next))
    }

    val shapes get() = config.shapes

    fun setShapes(sh: Shapes) = commit(config.copy(shapes = sh))

    val lock get() = config.lock

    fun setLock(layout: LockLayout) = commit(config.copy(lock = layout))

    /** From the lock-screen editor ([LayoutProvider]): no debounce, the process may not live long. */
    fun saveEditorNow(edited: LockLayout) {
        config = config.copy(lock = config.lock.withEditorFields(edited))
        persistAndBroadcast()
    }

    /** From the launcher's widget menu ([LayoutProvider]). */
    fun saveWidgetsNow(widgets: Map<Int, dev.nebula.glass.WidgetTweak>) {
        config = config.copy(widgets = widgets)
        persistAndBroadcast()
    }

    /** Pick up what the editor saved meanwhile, unless a change of our own is still waiting to be written. */
    fun reload() {
        if (!main.hasCallbacks(push)) config = GlassConfig.parse(prefs.getString(GlassSettings.KEY, null))
    }

    /**
     * Exports ALL configuration (Light & Dark presets, all per-surface looks & follow flags, all
     * surface switches, Shapes, and Lock screen layout including clock/date/text boxes, weights,
     * and wallpaper filters) as formatted JSON.
     */
    fun exportJson(): String = JSONObject(config.toJson())
        .put("exportApp", GlassSettings.PKG)
        .put("exportVersion", 1)
        .toString(2)

    /**
     * Validates and imports a full configuration JSON string, persisting and broadcasting live to
     * SystemUI, Launcher, and MiBoard immediately.
     */
    fun importJson(raw: String): Boolean = try {
        val obj = JSONObject(raw.trim())
        if (!obj.has("global") && !obj.has("lockLayout") && !obj.has("shapes") && !obj.has("widgets")) {
            false
        } else {
            main.removeCallbacks(push)
            config = GlassConfig.parse(obj.toString())
            persistAndBroadcast()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "settings import failed", e)
        false
    }

    /** Saving and broadcasting are coalesced so slider drags stay cheap. */
    private fun commit(c: GlassConfig) {
        config = c
        main.removeCallbacks(push)
        main.postDelayed(push, 120)
    }

    @SuppressLint("WorldReadableFiles")
    private fun openPrefs(): SharedPreferences = try {
        @Suppress("DEPRECATION")
        ctx.getSharedPreferences(GlassSettings.PREFS, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        Log.w(TAG, "world-readable prefs unavailable; is the module enabled in Vector?", e)
        hooksCanRead = false
        ctx.getSharedPreferences(GlassSettings.PREFS, Context.MODE_PRIVATE)
    }

    private fun persistAndBroadcast() {
        val json = config.toJson()
        prefs.edit().putString(GlassSettings.KEY, json).apply()
        for (pkg in GlassSettings.TARGETS) {
            ctx.sendBroadcast(Intent(GlassSettings.ACTION)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(GlassSettings.EXTRA, json))
        }
    }

    companion object { private const val TAG = "NebulaGlass" }
}
