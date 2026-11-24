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

/**
 * Settings-app side of [GlassSettings]: keeps the editable map, persists it where Vector can serve it
 * to the hooks (world-readable prefs, enabled by the `xposedsharedprefs` manifest flag) and pushes
 * every change live to SystemUI and the launcher.
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

    fun resetGlobal() = commit(config.copy(global = GlobalStyle(), own = emptyMap(), follow = emptySet()))

    /** The look being edited: [scope]'s own (or, until it has one, the shared look); null = the shared look. */
    fun look(scope: Surface?) = if (scope == null) config.global else config.look(scope)

    /** Panel [s] uses the shared look ("Follow all surfaces"). */
    fun follows(s: Surface) = config.follows(s)

    /** Edit a look: the shared one, or a panel's own (it stops following). */
    fun setLook(scope: Surface?, g: GlobalStyle) =
        commit(if (scope == null) config.copy(global = g) else config.copy(own = config.own + (scope to g), follow = config.follow - scope))

    /** Follow the shared look, or stop: a panel without a look of its own starts from the shared one. */
    fun setFollow(s: Surface, follow: Boolean) = commit(
        if (follow) config.copy(follow = config.follow + s)
        else config.copy(own = config.own + (s to (config.own[s] ?: config.global)), follow = config.follow - s))

    val shapes get() = config.shapes

    fun setShapes(sh: Shapes) = commit(config.copy(shapes = sh))

    val lock get() = config.lock

    fun setLock(layout: LockLayout) = commit(config.copy(lock = layout))

    /** From the lock-screen editor ([LayoutProvider]): no debounce, the process may not live long. */
    fun saveEditorNow(edited: LockLayout) {
        config = config.copy(lock = config.lock.withEditorFields(edited))
        persistAndBroadcast()
    }

    /** Pick up what the editor saved meanwhile, unless a change of our own is still waiting to be written. */
    fun reload() {
        if (!main.hasCallbacks(push)) config = GlassConfig.parse(prefs.getString(GlassSettings.KEY, null))
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
