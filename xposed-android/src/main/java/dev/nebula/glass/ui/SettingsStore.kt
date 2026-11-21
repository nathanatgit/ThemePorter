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

    val global: GlobalStyle get() = config.global

    fun isOn(s: Surface) = config.isOn(s)

    fun setOn(s: Surface, on: Boolean) = commit(config.copy(on = config.on + (s to on)))

    fun setGlobal(g: GlobalStyle) = commit(config.copy(global = g))

    fun resetGlobal() = commit(config.copy(global = GlobalStyle(), own = emptyMap()))

    /** The look [s] currently uses (own override or the shared look). */
    fun look(s: Surface) = config.look(s)

    fun hasOwn(s: Surface) = s in config.own

    /** Give [s] its own look (starting from the shared one), or return it to the shared look. */
    fun setHasOwn(s: Surface, own: Boolean) =
        commit(config.copy(own = if (own) config.own + (s to config.global) else config.own - s))

    fun setOwn(s: Surface, g: GlobalStyle) = commit(config.copy(own = config.own + (s to g)))

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
