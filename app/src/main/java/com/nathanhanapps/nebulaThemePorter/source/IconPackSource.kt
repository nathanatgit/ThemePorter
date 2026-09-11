package com.nathanhanapps.nebulaThemePorter.source

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.AppFilter
import com.nathanhanapps.nebulaThemePorter.core.AppFilterParser
import com.nathanhanapps.nebulaThemePorter.core.SourceIcon
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.parser.ResourceTableParser

/** An icon pack APK (ADW/Nova appfilter.xml format). Image ids are APK entry paths. */
class IconPackSource private constructor(
    private val apk: File,
    private val deleteOnClose: Boolean,
    private val zip: ZipFile,
    override val fileName: String,
    override val suggestedLabel: String,
    override val icons: List<SourceIcon>,
    override val extras: SourceExtras,
) : ThemeSource {
    override val kind = SourceKind.ICON_PACK
    override val author: String = ""
    override val description: String = ""

    override fun readBytes(imageId: String): ByteArray? =
        zip.getEntry(imageId)?.let { SourceFiles.read(zip, it, SourceFiles.MAX_IMAGE_BYTES) }

    override fun close() {
        runCatching { zip.close() }
        if (deleteOnClose) apk.delete()
    }

    companion object {
        private val rasterExtensions = setOf("png", "webp", "jpg", "jpeg")

        suspend fun open(context: Context, uri: Uri): IconPackSource = withContext(Dispatchers.IO) {
            val name = SourceFiles.displayName(context, uri) ?: "icon-pack.apk"
            val file = SourceFiles.copyToCache(context, uri, "icon-pack.apk")
            try {
                create(context, file, name, deleteOnClose = true)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }

        /** Reads [file] in place; it is never deleted. */
        suspend fun open(context: Context, file: File): IconPackSource = withContext(Dispatchers.IO) {
            create(context, file, file.name, deleteOnClose = false)
        }

        private fun create(context: Context, file: File, name: String, deleteOnClose: Boolean): IconPackSource {
            val zip = SourceFiles.openZip(context, file, context.getString(R.string.archive_icon_pack))
            try {
                val entries = SourceFiles.fileNames(zip)
                require(entries.size <= 200_000) { context.getString(R.string.apk_too_many_files) }
                val appFilterEntry = entries.firstOrNull { it.endsWith("assets/appfilter.xml") }
                    ?: entries.firstOrNull { it.endsWith("res/xml/appfilter.xml") }
                    ?: throw IllegalArgumentException(context.getString(R.string.no_appfilter))
                val raw = SourceFiles.read(zip, zip.getEntry(appFilterEntry), SourceFiles.MAX_TEXT_BYTES)
                val xml = if (looksLikeText(raw)) {
                    raw.toString(Charsets.UTF_8)
                } else {
                    ApkFile(file).use { it.transBinaryXml(appFilterEntry) }
                        ?: throw IllegalArgumentException(context.getString(R.string.cannot_decode_appfilter))
                }
                val filter = AppFilterParser.parse(xml)
                require(filter.items.isNotEmpty()) { context.getString(R.string.empty_appfilter) }

                val requested = filter.requestedDrawables.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
                val resolved = ResourceTableResolver.resolve(zip, requested)
                val raster = indexRasterEntries(entries)
                fun entryFor(drawable: String): String? {
                    val key = drawable.lowercase(Locale.ROOT)
                    return resolved[key] ?: raster[key]
                }

                val icons = filter.items.mapNotNull { item ->
                    entryFor(item.drawable)?.let { SourceIcon(it, item.component.packageName, item.component) }
                }.distinct()
                require(icons.isNotEmpty()) { context.getString(R.string.no_raster_icons) }

                val label = runCatching { ApkFile(file).use { it.apkMeta.label } }.getOrNull()
                    ?.takeIf(String::isNotBlank) ?: name.substringBeforeLast('.')
                return IconPackSource(file, deleteOnClose, zip, name, label, icons, extras(context, filter, ::entryFor, entries, zip))
            } catch (error: Throwable) {
                zip.close()
                throw error
            }
        }

        private fun extras(context: Context, filter: AppFilter, entryFor: (String) -> String?, entries: List<String>, zip: ZipFile): SourceExtras {
            val calendar = filter.calendars.firstNotNullOfOrNull { calendar ->
                val days = (1..31).mapNotNull { day -> entryFor(calendar.prefix + day)?.let { day to it } }.toMap()
                days.takeIf { it.size >= 28 }
            }
            val wallpapers = discoverWallpapers(entries).filter { isWallpaperArtwork(zip, it) }.map { entry ->
                val stem = entry.substringAfterLast('/').substringBeforeLast('.')
                val label = when (stem.lowercase(Locale.ROOT)) {
                    "default_wallpaper", "wallpaper", "wallpaper0" -> context.getString(R.string.home_wallpaper)
                    "default_lock_wallpaper", "lock_wallpaper" -> context.getString(R.string.lock_wallpaper)
                    else -> stem.replace(Regex("[_-]+"), " ").trim().replaceFirstChar(Char::titlecase)
                }
                SourceWallpaper(entry, label)
            }
            return SourceExtras(
                iconBack = filter.iconBacks.firstNotNullOfOrNull(entryFor),
                iconMask = filter.iconMasks.firstNotNullOfOrNull(entryFor),
                iconUpon = filter.iconUpons.firstNotNullOfOrNull(entryFor),
                calendar = calendar?.let { CalendarSource(it, null, null, null, showWeek = false) },
                wallpaper = wallpapers.firstOrNull()?.id,
                wallpapers = wallpapers,
            )
        }

        /** Finds full wallpaper artwork without treating previews, thumbnails or arbitrary drawables as choices. */
        internal fun discoverWallpapers(entries: List<String>): List<String> = entries
            .asSequence()
            .filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in rasterExtensions }
            .filter { entry ->
                val path = entry.lowercase(Locale.ROOT)
                val stem = path.substringAfterLast('/').substringBeforeLast('.')
                val wallpaperFolder = path.startsWith("wallpaper/") || path.startsWith("wallpapers/") ||
                    "/wallpaper/" in path || "/wallpapers/" in path
                val namedWallpaper = (path.startsWith("res/drawable") || path.startsWith("res/raw") || path.startsWith("assets/")) &&
                    (stem == "wallpaper" || stem.startsWith("wallpaper_") || stem.startsWith("wallpaper-") ||
                        stem.startsWith("wall_") || stem.startsWith("bg_wallpaper"))
                val preview = listOf("preview", "thumb", "thumbnail", "screenshot").any { it in path }
                (wallpaperFolder || namedWallpaper) && !preview
            }
            .groupBy { it.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT) }
            .values
            .map { paths -> paths.maxWith(compareBy({ densityScore(it) }, { it })) }
            .sortedBy { it.lowercase(Locale.ROOT) }
            .take(24)
            .toList()

        /** Rejects tiny placeholders and buttons that merely happen to use a wallpaper-like name. */
        private fun isWallpaperArtwork(zip: ZipFile, entry: String): Boolean {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { zip.getInputStream(zip.getEntry(entry)).use { BitmapFactory.decodeStream(it, null, options) } }
            return options.outWidth >= 480 && options.outHeight >= 480
        }

        private fun looksLikeText(bytes: ByteArray): Boolean {
            val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() && it != 0xEF.toByte() && it != 0xBB.toByte() && it != 0xBF.toByte() }
            return first == '<'.code.toByte()
        }

        private fun indexRasterEntries(entries: List<String>): Map<String, String> = entries
            .filter { it.startsWith("res/") && it.substringAfterLast('.', "").lowercase(Locale.ROOT) in rasterExtensions }
            .groupBy { it.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT) }
            .mapValues { (_, paths) -> paths.maxWith(compareBy({ densityScore(it) }, { it })) }

        private fun densityScore(path: String): Int = when {
            "xxxhdpi" in path -> 800
            "xxhdpi" in path -> 700
            "nodpi" in path -> 650
            "xhdpi" in path -> 600
            "hdpi" in path -> 500
            "mdpi" in path -> 400
            else -> 200
        }
    }
}

/** Resolves drawable names through resources.arsc, which also covers resource-obfuscated APK paths. */
internal object ResourceTableResolver {
    private const val MAX_TABLE_BYTES = 128L * 1024L * 1024L

    fun resolve(zip: ZipFile, requested: Set<String>): Map<String, String> {
        if (requested.isEmpty()) return emptyMap()
        val tableEntry = zip.getEntry("resources.arsc") ?: return emptyMap()
        val table = runCatching {
            ResourceTableParser(ByteBuffer.wrap(SourceFiles.read(zip, tableEntry, MAX_TABLE_BYTES))).apply { parse() }.resourceTable
        }.getOrNull() ?: return emptyMap()
        val best = HashMap<String, Pair<Long, String>>()
        for (packageId in 1..255) {
            val resourcePackage = table.getPackage(packageId.toShort()) ?: continue
            resourcePackage.typeSpecMap.values.forEach { typeSpec ->
                if (typeSpec.name != "drawable" && typeSpec.name != "mipmap") return@forEach
                resourcePackage.typesMap[typeSpec.id].orEmpty().forEach { type ->
                    val density = if (type.density == 0xFFFF) 650 else type.density
                    type.offsets.forEachIndexed { index, offset ->
                        if (offset < 0) return@forEachIndexed
                        val resourceEntry = runCatching { type.getResourceEntry(index) }.getOrNull() ?: return@forEachIndexed
                        val key = resourceEntry.key?.lowercase(Locale.ROOT) ?: return@forEachIndexed
                        if (key !in requested) return@forEachIndexed
                        val path = runCatching { resourceEntry.toStringValue(table, Locale.US) }.getOrNull() ?: return@forEachIndexed
                        val zipEntry = zip.getEntry(path) ?: return@forEachIndexed
                        if (!isRaster(zip, zipEntry)) return@forEachIndexed
                        val score = density * 100_000_000L + zipEntry.size.coerceIn(0, 99_999_999)
                        val previous = best[key]
                        if (previous == null || score > previous.first) best[key] = score to zipEntry.name
                    }
                }
            }
        }
        return best.mapValues { it.value.second }
    }

    private fun isRaster(zip: ZipFile, entry: ZipEntry): Boolean {
        val header = ByteArray(12)
        val read = zip.getInputStream(entry).use { it.read(header) }
        if (read >= 8 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte()) return true
        if (read >= 12 && header.copyOfRange(0, 4).decodeToString() == "RIFF" && header.copyOfRange(8, 12).decodeToString() == "WEBP") return true
        return read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    }
}
