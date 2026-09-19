package com.nathanhanapps.nebulaThemePorter.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

object StorageAccess {
    const val THEMES_APP_PACKAGE = "com.zte.beautify"

    /** Where a recolored .mtz is applied from. Present on Xiaomi/HyperOS, absent on a ZTE phone. */
    const val MIUI_THEMES_APP_PACKAGE = "com.android.thememanager"

    val root: File get() = Environment.getExternalStorageDirectory()

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    /** Where the Themes app looks for imported themes. */
    val themeDir: File get() = File(root, "Theme")

    /** Start location for the system folder picker. */
    val themeDirUri: Uri get() = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Theme")

    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Opens this app's "All files access" switch. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))

    /** The global list, for ROMs that do not handle the per-app screen. */
    fun generalSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /**
     * The file behind a document from the system file picker, when it is on this phone's storage and all-files access
     * lets the app read it in place. Null means the app has to copy it through the content resolver.
     */
    fun fileFor(context: Context, uri: Uri): File? {
        if (!hasAllFilesAccess(context) || !DocumentsContract.isDocumentUri(context, uri)) return null
        return pathFor(uri.authority, DocumentsContract.getDocumentId(uri))?.takeIf { it.isFile && it.canRead() }
    }

    /** The directory behind a folder from the system folder picker, or null when the app cannot write to it directly. */
    fun directoryFor(context: Context, treeUri: Uri): File? {
        if (!hasAllFilesAccess(context)) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return pathFor(treeUri.authority, id)?.takeIf { it.isDirectory && it.canWrite() }
    }

    /** Maps ExternalStorageProvider ids ("primary:Theme", "1234-ABCD:x", "home:x") and raw download ids to paths. */
    private fun pathFor(authority: String?, documentId: String): File? = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> {
            val volume = documentId.substringBefore(':', "")
            val relative = documentId.substringAfter(':', "")
            when {
                volume.isEmpty() -> null
                volume.equals("primary", ignoreCase = true) -> File(root, relative)
                volume.equals("home", ignoreCase = true) -> File(File(root, Environment.DIRECTORY_DOCUMENTS), relative)
                else -> File("/storage/$volume", relative)
            }
        }
        DOWNLOADS_AUTHORITY -> documentId.takeIf { it.startsWith("raw:") }?.let { File(it.removePrefix("raw:")) }
        else -> null
    }?.takeUnless { ".." in it.path.split('/') }

    fun friendlyPath(path: String): String {
        val base = root.absolutePath
        return when {
            path == base -> "Internal storage"
            path.startsWith("$base/") -> "Internal storage/" + path.removePrefix("$base/")
            else -> path
        }
    }
}

class PorterPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("porter", Context.MODE_PRIVATE)

    var outputDir: File
        get() = prefs.getString(KEY_OUTPUT, null)?.let(::File) ?: StorageAccess.themeDir
        set(value) = prefs.edit().putString(KEY_OUTPUT, value.absolutePath).apply()

    private companion object {
        const val KEY_OUTPUT = "outputDir"
    }
}
