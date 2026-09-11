package com.nathanhanapps.nebulaThemePorter.source

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.AppComponent
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.SourceIcon
import com.nathanhanapps.nebulaThemePorter.render.Bitmaps
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class SourceKind { ICON_PACK, MTZ }

data class CalendarSource(
    /** Complete per-day icons, 1..31. */
    val dayImages: Map<Int, String>,
    val background: String?,
    val textColor: Long?,
    /** Date text size as a fraction of the icon side. */
    val textSizeRatio: Float?,
    val showWeek: Boolean,
)

data class ClockSource(val dial: String?, val hour: String, val minute: String, val canvasSize: Int)

/** A wallpaper embedded in the selected APK or MTZ. [id] is read lazily through [ThemeSource]. */
data class SourceWallpaper(val id: String, val label: String)

/** Optional theme-wide artwork. Every value is an image id for [ThemeSource.readBytes]. */
data class SourceExtras(
    val iconBack: String? = null,
    val iconMask: String? = null,
    val iconUpon: String? = null,
    val folderIcon: String? = null,
    val calendar: CalendarSource? = null,
    val clock: ClockSource? = null,
    val wallpaper: String? = null,
    val lockWallpaper: String? = null,
    val wallpapers: List<SourceWallpaper> = emptyList(),
    val previewImages: List<String> = emptyList(),
)

/** A parsed input. Images are read on demand because icon packs contain thousands of them. */
interface ThemeSource : Closeable {
    val kind: SourceKind
    val fileName: String
    val suggestedLabel: String
    val author: String
    val description: String
    val icons: List<SourceIcon>
    val extras: SourceExtras

    fun readBytes(imageId: String): ByteArray?

    fun decode(imageId: String, maxSize: Int): Bitmap? = readBytes(imageId)?.let { Bitmaps.decode(it, maxSize) }
}

internal object SourceFiles {
    const val MAX_INPUT_BYTES = 1024L * 1024L * 1024L
    const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    const val MAX_TEXT_BYTES = 4L * 1024L * 1024L

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun copyToCache(context: Context, uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "sources").apply { mkdirs() }
        val target = File(dir, name)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.cannot_read_selected_file))
        input.use { copyLimited(context, it, target, MAX_INPUT_BYTES) }
        return target
    }

    fun copyLimited(context: Context, input: InputStream, target: File, limit: Long) {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { context.getString(R.string.input_too_large, limit / (1024 * 1024)) }
                output.write(buffer, 0, read)
            }
        }
    }

    /**
     * Opens [file] as a ZIP, turning a wrong file type into a readable error before any parsing starts.
     * [what] includes its article, e.g. "an icon pack APK".
     */
    fun openZip(context: Context, file: File, what: String): ZipFile {
        val header = ByteArray(4)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (error: Exception) {
            throw IllegalArgumentException(context.getString(R.string.cannot_read_named_file, file.name, error.message), error)
        }
        require(read == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() && header[2].toInt() == 3 && header[3].toInt() == 4) {
            context.getString(R.string.not_zip_archive, file.name, what)
        }
        return try {
            ZipFile(file)
        } catch (error: Exception) {
            throw IllegalArgumentException(context.getString(R.string.damaged_archive, file.name, what, error.message), error)
        }
    }

    fun read(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        require(entry.size < 0 || entry.size <= limit) { "Entry too large: ${entry.name}" }
        zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Entry too large after decompression: ${entry.name}" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    fun readText(zip: ZipFile, name: String): String? =
        zip.getEntry(name)?.takeUnless(ZipEntry::isDirectory)?.let { read(zip, it, MAX_TEXT_BYTES).toString(Charsets.UTF_8) }

    fun fileNames(zip: ZipFile): List<String> =
        zip.entries().asSequence().filterNot(ZipEntry::isDirectory).map(ZipEntry::getName).toList()
}

object InstalledApps {
    data class IconPack(
        val packageName: String,
        val label: String,
        val apkPath: String,
    )

    data class LauncherApp(
        val packageName: String,
        val activityName: String,
        val label: String,
        val isSystem: Boolean,
    ) {
        val stem: String get() = AppComponent(packageName, activityName).stem
    }

    /** Every currently installed activity that is visible in the launcher. */
    fun launcherApps(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        return queryLauncherActivities(pm).map { info ->
            val flags = info.activityInfo.applicationInfo.flags
            LauncherApp(
                packageName = info.activityInfo.packageName,
                activityName = info.activityInfo.name,
                label = info.loadLabel(pm).toString().ifBlank { info.activityInfo.packageName },
                isSystem = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }.distinctBy { it.stem }
    }

    /**
     * Installed, launcher-visible APKs that contain an ADW/Nova appfilter. The ZIP probe is intentionally
     * deferred until the user opens the installed-icon-pack chooser.
     */
    fun iconPacks(context: Context): List<IconPack> {
        val pm = context.packageManager
        return queryLauncherActivities(pm)
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                val file = File(info.activityInfo.applicationInfo.sourceDir ?: return@mapNotNull null)
                val hasAppFilter = runCatching {
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().any { entry ->
                            !entry.isDirectory && (
                                entry.name.endsWith("assets/appfilter.xml", ignoreCase = true) ||
                                    entry.name.endsWith("res/xml/appfilter.xml", ignoreCase = true)
                                )
                        }
                    }
                }.getOrDefault(false)
                if (!hasAppFilter) null else IconPack(
                    packageName = packageName,
                    label = info.loadLabel(pm).toString().ifBlank { packageName },
                    apkPath = file.absolutePath,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** Package name to launcher activity class names. */
    fun launcherActivities(context: Context): Map<String, List<String>> {
        val pm = context.packageManager
        return queryLauncherActivities(pm)
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.distinct() }
    }

    /**
     * Decodes a [DeviceIconId] as a temp resource. Reads the icon resource straight out of the app's own APK via
     * its own [Resources][android.content.res.Resources], not [PackageManager.getActivityIcon] /
     * [PackageManager.getApplicationIcon] - on this OEM those can return an icon the launcher/system has already
     * re-rendered under its own live icon theme, which defeats generating a "raw" fallback for our own shape/tint
     * pipeline to process. Falls back to the live-rendered icon only if the app declares no icon resource at all.
     */
    fun decodeIcon(context: Context, sourceId: String, maxSize: Int, includeBackground: Boolean = false): Bitmap? {
        val packageName = DeviceIconId.packageName(sourceId)
        val className = DeviceIconId.className(sourceId)
        val pm = context.packageManager
        val drawable = runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val iconRes = className
                ?.let { runCatching { pm.getActivityInfo(ComponentName(packageName, it), 0).getIconResource() }.getOrNull() }
                ?.takeIf { it != 0 }
                ?: appInfo.icon.takeIf { it != 0 }
            iconRes?.let { pm.getResourcesForApplication(appInfo).getDrawable(it, null) }
        }.getOrNull() ?: runCatching {
            if (className != null) pm.getActivityIcon(ComponentName(packageName, className)) else pm.getApplicationIcon(packageName)
        }.getOrElse { return null }
        return Bitmaps.fromDrawable(drawable, maxSize, includeBackground)
    }

    private fun queryLauncherActivities(pm: PackageManager) =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).let { intent ->
            if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }
}
