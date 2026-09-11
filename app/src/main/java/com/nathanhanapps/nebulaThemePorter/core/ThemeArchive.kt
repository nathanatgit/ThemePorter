package com.nathanhanapps.nebulaThemePorter.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes icons_cur.zip the way stock 13/49/60 do: an "icon/" directory entry first, PNGs stored (they are
 * already compressed), XML deflated.
 */
class IconsZipWriter(output: OutputStream) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(output)).apply { setLevel(Deflater.BEST_SPEED) }
    private val names = HashSet<String>()

    init {
        stored(NebulaSpec.ICON_DIR, ByteArray(0))
    }

    val fileCount: Int get() = names.size - 1

    operator fun contains(fileName: String): Boolean = NebulaSpec.iconEntry(fileName) in names

    fun putPng(fileName: String, bytes: ByteArray): Boolean = stored(NebulaSpec.iconEntry(checkName(fileName)), bytes)

    fun putText(fileName: String, text: String): Boolean {
        val name = NebulaSpec.iconEntry(checkName(fileName))
        if (!names.add(name)) return false
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return true
    }

    private fun stored(name: String, bytes: ByteArray): Boolean {
        if (!names.add(name)) return false
        val crc = CRC32().apply { update(bytes) }
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
        return true
    }

    private fun checkName(fileName: String): String {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && fileName != "." && fileName != "..") {
            "Unsafe icon file name: $fileName"
        }
        return fileName
    }

    override fun close() = zip.close()
}

class ThemeArchiveParts(
    val descriptionXml: String,
    val wallpaperJpeg: ByteArray,
    val lockscreenJpeg: ByteArray,
    /** Path under overlays/ to bytes, e.g. NebulaSpec.OVERLAY_APKS. */
    val overlayFiles: Map<String, ByteArray>,
    val androidzteShapeConfig: String?,
    val iconsCurZip: File,
    val previewJpegs: List<ByteArray>,
)

/** Writes the outer .zmtp with the entry order and DEFLATE compression every stock theme uses. */
object ThemeArchiveWriter {
    fun write(output: OutputStream, parts: ThemeArchiveParts) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(NebulaSpec.DESCRIPTION, parts.descriptionXml.toByteArray(Charsets.UTF_8))
            put(NebulaSpec.LOCKSCREEN_WALLPAPER, parts.lockscreenJpeg)
            parts.androidzteShapeConfig?.let { put(NebulaSpec.ANDROIDZTE_SHAPE_CONFIG, it.toByteArray(Charsets.UTF_8)) }
            NebulaSpec.OVERLAY_APKS.forEach { path -> parts.overlayFiles[path]?.let { put(path, it) } }
            zip.putNextEntry(ZipEntry(NebulaSpec.ICONS_CUR))
            parts.iconsCurZip.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            parts.previewJpegs.take(NebulaSpec.MAX_PREVIEWS).forEachIndexed { index, bytes ->
                put(NebulaSpec.previewPath(index), bytes)
            }
            put(NebulaSpec.WALLPAPER, parts.wallpaperJpeg)
        }
    }
}
