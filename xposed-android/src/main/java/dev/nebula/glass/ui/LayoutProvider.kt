package dev.nebula.glass.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dev.nebula.glass.LockLayout

/**
 * Where SystemUI's lock-screen editor saves the clock, date and custom text boxes, so the module's prefs
 * stay the one store. Exported for that caller only: anyone but SystemUI gets a SecurityException.
 */
class LayoutProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (SYSTEMUI !in callers) throw SecurityException("only System UI may change the lock layout")
        if (method != LockLayout.CALL_SAVE_LAYOUT || extras == null) return null
        val json = extras.getString(LockLayout.KEY_LAYOUT) ?: return null
        val token = Binder.clearCallingIdentity()
        try {
            SettingsStore(ctx).saveEditorNow(LockLayout.fromJson(org.json.JSONObject(json)))
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object { const val SYSTEMUI = "com.android.systemui" }
}
