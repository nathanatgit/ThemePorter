package dev.nebula.glass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import de.robv.android.xposed.XSharedPreferences
import dev.nebula.glass.GlassSettings

/**
 * Hook-process side of [GlassSettings]: loads the saved settings at hook time through Vector's
 * XSharedPreferences, then follows live changes broadcast by the settings app.
 * Kept out of [GlassSettings] because the settings app has no Xposed classes at runtime.
 */
object HookSettings {
    private const val TAG = "NebulaGlass"
    private var loaded = false
    private var attached = false

    /** Load saved settings once per process. Safe to call from every hook's handleLoadPackage. */
    fun load() {
        if (loaded) return
        loaded = true
        try {
            val p = XSharedPreferences(GlassSettings.PKG, GlassSettings.PREFS)
            p.reload()
            val json = p.getString(GlassSettings.KEY, null)
            GlassSettings.update(json)
            Log.i(TAG, "settings loaded: readable=${p.file.canRead()} saved=${json != null}")
        } catch (t: Throwable) {
            Log.w(TAG, "settings load failed, using defaults", t)
        }
    }

    /** Start following live changes. Needs a Context, so hooks call it on first use. Main thread. */
    fun attach(ctx: Context) {
        if (attached) return
        attached = true
        try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    GlassSettings.update(i.getStringExtra(GlassSettings.EXTRA))
                    Log.i(TAG, "settings updated")
                }
            }, IntentFilter(GlassSettings.ACTION), GlassSettings.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            attached = false
            Log.e(TAG, "settings receiver failed", t)
        }
    }
}
