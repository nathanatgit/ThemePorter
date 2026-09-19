package com.nathanhanapps.nebulaThemePorter.build

import android.graphics.Bitmap
import android.content.Context
import android.os.Build
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.MtzRecolor
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.render.Bitmaps
import com.nathanhanapps.nebulaThemePorter.render.FixedIconArt
import com.nathanhanapps.nebulaThemePorter.render.MiuiIconArt
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.source.MtzSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class RecolorSummary(
    val recolored: Int,
    val generated: Int,
    val dropped: Int,
    val copied: Int,
    val bytes: Long,
)

/**
 * Rewrites a MIUI/HyperOS .mtz in place: every other component, the entry order and each entry's compression
 * are kept exactly as they were found, and only the pixels inside the "icons" component change. A theme the
 * Themes app already accepts therefore still installs afterwards, which is the point of recoloring one rather
 * than porting it - the output stays a MIUI theme.
 *
 * Icons go through the same [FixedIconArt] render as a Nebula build, so a tint means the same thing in both
 * exports. With no fixed shape selected - the normal case here - that render is exactly the tint, and every
 * icon keeps its own artwork, its own outline and its own pixel size.
 */
class MtzRecolorBuilder(private val context: Context) {
    private class Rendered(val entry: ZipEntry, val bytes: ByteArray?)

    suspend fun recolor(
        source: MtzSource,
        options: BuildOptions,
        /**
         * Launcher-visible packages on this phone: what [BuildOptions.onlyInstalledApps] filters against, and
         * what [BuildOptions.generateMissingAppIcons] generates an icon for.
         */
        installedPackages: Set<String>,
        openOutput: () -> OutputStream,
        onProgress: (BuildProgress) -> Unit,
    ): RecolorSummary = withContext(Dispatchers.Default) {
        val icons = source.iconsArchive
        val iconsEntry = source.iconsEntry
        require(icons != null && iconsEntry != null) { context.getString(R.string.mtz_has_no_icons, source.fileName) }

        val workDir = File(context.cacheDir, "recolor").apply {
            deleteRecursively()
            mkdirs()
        }
        val rebuilt = File(workDir, "icons")
        val entries = icons.entries().toList()
        val names = entries.map { it.name }
        val tint = options.fixedTintColor?.toInt()

        // The theme's own shared artwork, tinted once: the plate a shaped icon is drawn on, and - for an icon
        // generated for an app the theme skipped - the mask, plate and border MIUI would have composited live.
        // The mask is never tinted; only its alpha is ever read.
        val mask = decode(source, source.extras.iconMask)
        val pattern = tinted(decode(source, source.extras.iconBack), options)
        val border = tinted(decode(source, source.extras.iconUpon), options)

        val missing = if (options.generateMissingAppIcons) {
            MtzRecolor.missingPackages(names.mapNotNull(MtzRecolor::stemOf), installedPackages)
        } else {
            emptyList()
        }
        val drawableDir = MtzRecolor.drawableDir(names)
        val size = iconSize(mask, pattern)
        var recolored = 0
        var dropped = 0
        var copied = 0
        var generated = 0
        var done = 0
        val total = entries.size + missing.size + 2

        rebuilt.outputStream().buffered().use { raw ->
            ZipOutputStream(raw).use { zip ->
                entries.chunked(CHUNK).forEach { chunk ->
                    ensureActive()
                    val rendered = coroutineScope {
                        chunk.map { entry ->
                            async { Rendered(entry, render(icons, entry, options, installedPackages, pattern)) }
                        }.awaitAll()
                    }
                    rendered.forEach { result ->
                        val use = MtzRecolor.use(result.entry.name)
                        when {
                            // Left out entirely: an app icon for something this phone has no app for, with
                            // "only apps installed on this phone" on.
                            result.bytes == null && use == MtzRecolor.Use.APP_ICON && options.onlyInstalledApps &&
                                !covered(result.entry.name, installedPackages) -> dropped++
                            result.bytes != null -> {
                                put(zip, result.entry, result.bytes)
                                recolored++
                            }
                            else -> {
                                copy(zip, icons, result.entry)
                                copied++
                            }
                        }
                    }
                    done += chunk.size
                    onProgress(BuildProgress(done, total, context.getString(R.string.progress_recolor, done, entries.size)))
                }

                missing.forEach { packageName ->
                    ensureActive()
                    val bytes = generate(packageName, options, mask, pattern, border, source.iconScale ?: 1f, size)
                    done++
                    if (bytes != null) {
                        put(zip, ZipEntry(MtzRecolor.entryFor(drawableDir, packageName)).apply { method = ZipEntry.DEFLATED }, bytes)
                        generated++
                    }
                    onProgress(BuildProgress(done, total, context.getString(R.string.progress_generate, generated, missing.size)))
                }
            }
        }
        listOfNotNull(mask, pattern, border).forEach(Bitmap::recycle)
        onProgress(BuildProgress(++done, total, context.getString(R.string.progress_repacking)))

        val outer = source.outerArchive
        var written = 0L
        withContext(Dispatchers.IO) {
            // Counted as it goes: the stream may be a document the app cannot stat afterwards, and the size
            // worth reporting is the finished theme's, not the icons archive inside it.
            val counting = CountingStream(openOutput())
            counting.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    outer.entries().asSequence().forEach { entry ->
                        if (entry.name == iconsEntry) {
                            val crc = if (entry.method == ZipEntry.STORED) crcOf(rebuilt) else null
                            zip.putNextEntry(clone(entry, rebuilt.length(), crc))
                            rebuilt.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        } else {
                            copy(zip, outer, entry)
                        }
                    }
                }
            }
            written = counting.count
        }
        onProgress(BuildProgress(++done, total, context.getString(R.string.progress_saved)))
        workDir.deleteRecursively()
        RecolorSummary(recolored, generated, dropped, copied, written)
    }

    /**
     * New bytes for one entry, or null to leave it as it was - which, for an app icon
     * [BuildOptions.onlyInstalledApps] excludes, means leaving it out of the archive. The caller tells those
     * two cases apart by the entry's own [MtzRecolor.Use], so that this stays a pure render.
     */
    private fun render(
        icons: ZipFile,
        entry: ZipEntry,
        options: BuildOptions,
        installedPackages: Set<String>,
        pattern: Bitmap?,
    ): ByteArray? {
        val use = MtzRecolor.use(entry.name)
        if (use == MtzRecolor.Use.COPY) return null
        if (use == MtzRecolor.Use.APP_ICON && options.onlyInstalledApps && !covered(entry.name, installedPackages)) return null
        val tint = options.fixedTintColor?.toInt()
        val plain = use == MtzRecolor.Use.ASSET || options.fixedShape.isOriginal
        // Nothing to do: no tint, and no shape to bake in. Copying the original is then both faster and exact.
        if (tint == null && plain) return null

        val bytes = runCatching { icons.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: return null
        val bitmap = Bitmaps.decode(bytes, FULL_SIZE) ?: return null
        val out = if (plain) {
            // Theme-wide artwork, and every icon in a plain recolor, keeps its own pixel size and outline:
            // running those through the shape pipeline would square off artwork the theme drew deliberately.
            tint?.let { Bitmaps.tint(bitmap, it, options.fixedTintStrength, options.fixedTintBlendMode) }
        } else {
            FixedIconArt.render(
                source = bitmap,
                shape = options.fixedShape,
                composition = options.fixedComposition,
                iconScale = options.fixedIconScale,
                iconAlpha = options.fixedIconAlpha,
                tintColor = tint,
                tintStrength = options.fixedTintStrength,
                tintBlendMode = options.fixedTintBlendMode,
                backgroundScale = options.fixedBackgroundScale,
                padColor = options.padBackground.toInt(),
                iconBack = pattern,
                size = maxOf(bitmap.width, bitmap.height),
            )
        }
        bitmap.recycle()
        return out?.let { encode(it, entry.name).also { _ -> it.recycle() } }
    }

    /** An icon for an app the theme carries nothing for, drawn the way the rest of this output looks. */
    private fun generate(
        packageName: String,
        options: BuildOptions,
        mask: Bitmap?,
        pattern: Bitmap?,
        border: Bitmap?,
        scale: Float,
        size: Int,
    ): ByteArray? {
        val glyph = InstalledApps.decodeIcon(
            context, DeviceIconId.of(packageName, null), size * 2, options.generatedIconOwnBackground,
        ) ?: return null
        val out = if (options.fixedShape.isOriginal) {
            MiuiIconArt.render(
                glyph, size, mask, pattern, border, scale,
                options.fixedTintColor?.toInt(), options.fixedTintStrength, options.fixedTintBlendMode,
            )
        } else {
            FixedIconArt.render(
                source = glyph,
                shape = options.fixedShape,
                composition = options.fixedComposition,
                iconScale = options.fixedIconScale,
                iconAlpha = options.fixedIconAlpha,
                tintColor = options.fixedTintColor?.toInt(),
                tintStrength = options.fixedTintStrength,
                tintBlendMode = options.fixedTintBlendMode,
                backgroundScale = options.fixedBackgroundScale,
                padColor = options.padBackground.toInt(),
                iconBack = pattern,
                size = size,
            )
        }
        glyph.recycle()
        return Bitmaps.png(out).also { out.recycle() }
    }

    private fun covered(entry: String, installedPackages: Set<String>): Boolean {
        val stem = MtzRecolor.stemOf(entry) ?: return false
        return MtzRecolor.packageFor(stem, installedPackages) != null
    }

    private fun tinted(bitmap: Bitmap?, options: BuildOptions): Bitmap? {
        val tint = options.fixedTintColor?.toInt() ?: return bitmap
        val source = bitmap ?: return null
        return Bitmaps.tint(source, tint, options.fixedTintStrength, options.fixedTintBlendMode).also { source.recycle() }
    }

    private fun decode(source: MtzSource, imageId: String?): Bitmap? =
        imageId?.let { runCatching { source.decode(it, FULL_SIZE) }.getOrNull() }
            ?.takeIf { Bitmaps.alphaBounds(it, threshold = 8) != null }

    /** The theme's own icon canvas, so a generated icon lines up with the ones beside it. */
    private fun iconSize(mask: Bitmap?, pattern: Bitmap?): Int =
        listOfNotNull(mask, pattern).maxOfOrNull { maxOf(it.width, it.height) } ?: NebulaSpec.LAYER_SIZE

    /** Re-encodes in the format the entry's own name promises; MIUI reads these by extension. */
    private fun encode(bitmap: Bitmap, entryName: String): ByteArray =
        when (entryName.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> Bitmaps.jpeg(bitmap, 95)
            "webp" -> webp(bitmap)
            else -> Bitmaps.png(bitmap)
        }

    private fun webp(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, 100, out)
        out.toByteArray()
    }

    private fun copy(zip: ZipOutputStream, from: ZipFile, entry: ZipEntry) {
        zip.putNextEntry(clone(entry, entry.size, entry.crc.takeIf { entry.method == ZipEntry.STORED }))
        if (!entry.isDirectory) from.getInputStream(entry).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun put(zip: ZipOutputStream, entry: ZipEntry, bytes: ByteArray) {
        val crc = if (entry.method == ZipEntry.STORED) CRC32().apply { update(bytes) }.value else null
        zip.putNextEntry(clone(entry, bytes.size.toLong(), crc))
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * A fresh entry carrying the original's name, compression and timestamp. Keeping the method is what makes a
     * recolored archive still look like the one MIUI packed; a stored entry has to declare its own size and CRC,
     * which the caller recomputes whenever the bytes changed.
     */
    private fun clone(entry: ZipEntry, size: Long, crc: Long?): ZipEntry = ZipEntry(entry.name).apply {
        method = entry.method
        if (entry.time >= 0) time = entry.time
        if (entry.method == ZipEntry.STORED) {
            this.size = size
            compressedSize = size
            this.crc = crc ?: 0L
        }
    }

    private fun crcOf(file: File): Long = CRC32().also { crc ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
    }.value

    /** Counts what reaches [target], so the summary can report the theme's finished size. */
    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()

        override fun close() = target.close()
    }

    private companion object {
        /** Icons are small; this only stops a malformed entry from being decoded at an absurd size. */
        const val FULL_SIZE = 4096

        /** Matches [ThemeBuilder]: enough parallel decodes to use the cores, few enough to bound memory. */
        const val CHUNK = 8
    }
}
