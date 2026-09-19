package com.nathanhanapps.nebulaThemePorter.source

import android.content.Context
import android.net.Uri
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.MtzParser
import com.nathanhanapps.nebulaThemePorter.core.SourceIcon
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A MIUI/HyperOS theme (.mtz). Image ids are "o:<entry>" for the outer archive and "i:<entry>" for the nested
 * icons archive.
 */
class MtzSource private constructor(
    /** Files deleted on close: the extracted icons archive, plus the outer copy when it came from a picker. */
    private val temporaryFiles: List<File>,
    private val outer: ZipFile,
    private val inner: ZipFile?,
    override val fileName: String,
    override val suggestedLabel: String,
    override val author: String,
    override val description: String,
    override val icons: List<SourceIcon>,
    override val extras: SourceExtras,
    /** Name of the outer entry holding the icons archive, null when the theme themes no icons. */
    internal val iconsEntry: String?,
    /** transform_config.xml's scale, needed to place a generated icon inside the theme's own mask. */
    internal val iconScale: Float?,
) : ThemeSource {
    override val kind = SourceKind.MTZ

    /** The open archives, so a recolor can rewrite the theme entry by entry without copying it again. */
    internal val outerArchive: ZipFile get() = outer
    internal val iconsArchive: ZipFile? get() = inner

    override fun readBytes(imageId: String): ByteArray? {
        val zip = when {
            imageId.startsWith(OUTER) -> outer
            imageId.startsWith(INNER) -> inner
            else -> null
        } ?: return null
        val entry = zip.getEntry(imageId.substring(2)) ?: return null
        return SourceFiles.read(zip, entry, SourceFiles.MAX_IMAGE_BYTES)
    }

    override fun close() {
        runCatching { inner?.close() }
        runCatching { outer.close() }
        temporaryFiles.forEach(File::delete)
    }

    companion object {
        private const val OUTER = "o:"
        private const val INNER = "i:"

        /**
         * The id [readBytes] takes for one entry of the icons archive - the same id the UI knows that icon by,
         * so a recolor can look up what the user decided about an entry while walking the archive.
         */
        internal fun imageId(entry: String): String = INNER + entry
        private val iconPreview = Regex("^preview/preview_icons_\\d+\\.(jpg|jpeg|png|webp)$")

        suspend fun open(context: Context, uri: Uri): MtzSource = withContext(Dispatchers.IO) {
            val name = SourceFiles.displayName(context, uri) ?: "theme.mtz"
            val copy = SourceFiles.copyToCache(context, uri, "theme.mtz")
            try {
                create(context, copy, name, deleteOuter = true)
            } catch (error: Throwable) {
                copy.delete()
                throw error
            }
        }

        /** Reads [file] in place; it is never deleted. */
        suspend fun open(context: Context, file: File): MtzSource = withContext(Dispatchers.IO) {
            create(context, file, file.name, deleteOuter = false)
        }

        private fun create(context: Context, outerFile: File, name: String, deleteOuter: Boolean): MtzSource {
            val innerFile = File(File(context.cacheDir, "sources").apply { mkdirs() }, "theme-icons.zip")
            val outer = SourceFiles.openZip(context, outerFile, context.getString(R.string.archive_miui_theme))
            var inner: ZipFile? = null
            try {
                val pkg = MtzParser.readPackage(SourceFiles.fileNames(outer)) { SourceFiles.readText(outer, it) }
                inner = pkg.iconsEntry?.let { entry ->
                    outer.getInputStream(outer.getEntry(entry)).use { SourceFiles.copyLimited(context, it, innerFile, SourceFiles.MAX_INPUT_BYTES) }
                    runCatching { ZipFile(innerFile) }.getOrNull()
                }
                val iconSet = inner?.let { zip -> MtzParser.readIcons(SourceFiles.fileNames(zip)) { SourceFiles.readText(zip, it) } }
                require(iconSet != null && iconSet.icons.isNotEmpty() || pkg.wallpaperEntry != null) {
                    context.getString(R.string.mtz_no_content, name)
                }

                val icons = iconSet?.icons?.map { (key, entry) -> SourceIcon(INNER + entry, key) }.orEmpty()
                val extras = SourceExtras(
                    iconBack = iconSet?.patternEntry?.let { INNER + it },
                    iconMask = iconSet?.maskEntry?.let { INNER + it },
                    iconUpon = iconSet?.borderEntry?.let { INNER + it },
                    folderIcon = iconSet?.folderEntry?.let { INNER + it },
                    calendar = iconSet?.calendar?.let { calendar ->
                        CalendarSource(
                            dayImages = calendar.dayEntries.mapValues { INNER + it.value },
                            background = calendar.backgroundEntry?.let { INNER + it },
                            textColor = calendar.textColor,
                            textSizeRatio = calendar.textSize?.let { it / calendar.canvasSize },
                            showWeek = calendar.showWeek,
                        )
                    },
                    clock = iconSet?.clock?.let { clock ->
                        if (clock.hourEntry == null || clock.minuteEntry == null) null
                        else ClockSource(clock.dialEntry?.let { INNER + it }, INNER + clock.hourEntry, INNER + clock.minuteEntry, clock.canvasSize)
                    },
                    wallpaper = pkg.wallpaperEntry?.let { OUTER + it },
                    lockWallpaper = pkg.lockWallpaperEntry?.let { OUTER + it },
                    wallpapers = pkg.wallpaperEntries.map { entry ->
                        SourceWallpaper(OUTER + entry, wallpaperLabel(context, entry))
                    },
                    previewImages = pkg.previewEntries.filter(iconPreview::matches).map { OUTER + it },
                )
                return MtzSource(
                    temporaryFiles = if (deleteOuter) listOf(outerFile, innerFile) else listOf(innerFile),
                    outer = outer,
                    inner = inner,
                    fileName = name,
                    suggestedLabel = pkg.metadata.title.ifBlank { name.substringBeforeLast('.') },
                    author = pkg.metadata.author.ifBlank { pkg.metadata.designer },
                    description = pkg.metadata.description,
                    icons = icons,
                    extras = extras,
                    iconsEntry = pkg.iconsEntry,
                    iconScale = iconSet?.iconScale,
                )
            } catch (error: Throwable) {
                runCatching { inner?.close() }
                outer.close()
                innerFile.delete()
                throw error
            }
        }

        private fun wallpaperLabel(context: Context, entry: String): String {
            val stem = entry.substringAfterLast('/').substringBeforeLast('.')
            return when (stem.lowercase()) {
                "default_wallpaper" -> context.getString(R.string.home_wallpaper)
                "default_lock_wallpaper" -> context.getString(R.string.lock_wallpaper)
                else -> stem.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar(Char::titlecase)
            }
        }
    }
}
