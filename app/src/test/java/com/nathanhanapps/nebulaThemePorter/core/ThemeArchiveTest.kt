package com.nathanhanapps.nebulaThemePorter.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeArchiveTest {
    private fun entries(bytes: ByteArray): List<ZipEntry> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.toList()
    }

    @Test
    fun iconsZipStartsWithDirectoryAndStoresPngs() {
        val output = ByteArrayOutputStream()
        IconsZipWriter(output).use { zip ->
            assertTrue(zip.putPng("com_tencent_mm-com_tencent_mm_ui_LauncherUI_back.png", byteArrayOf(1, 2, 3)))
            assertFalse(zip.putPng("com_tencent_mm-com_tencent_mm_ui_LauncherUI_back.png", byteArrayOf(9)))
            assertTrue(zip.putText("config.xml", "<resources/>"))
            assertTrue("config.xml" in zip)
            assertEquals(2, zip.fileCount)
        }
        val list = entries(output.toByteArray())
        assertEquals(listOf("icon/", "icon/com_tencent_mm-com_tencent_mm_ui_LauncherUI_back.png", "icon/config.xml"), list.map { it.name })
        assertTrue(list[0].isDirectory)
        assertEquals(ZipEntry.STORED, list[1].method)
        assertEquals(ZipEntry.DEFLATED, list[2].method)
    }

    @Test(expected = IllegalArgumentException::class)
    fun iconNamesCannotEscapeTheIconFolder() {
        IconsZipWriter(ByteArrayOutputStream()).use { it.putPng("../description.xml", byteArrayOf(0)) }
    }

    @Test
    fun themeArchiveUsesStockEntryOrder() {
        val icons = File.createTempFile("icons_cur", ".zip")
        try {
            icons.outputStream().use { raw -> IconsZipWriter(raw).use { it.putText("config.xml", "<resources/>") } }
            val output = ByteArrayOutputStream()
            ThemeArchiveWriter.write(
                output,
                ThemeArchiveParts(
                    descriptionXml = "<root/>",
                    wallpaperJpeg = byteArrayOf(1),
                    lockscreenJpeg = byteArrayOf(2),
                    overlayFiles = NebulaSpec.OVERLAY_APKS.associateWith { byteArrayOf(3) },
                    androidzteShapeConfig = ThemeXml.shapeConfig(FixedIconShape.SQUARE),
                    iconsCurZip = icons,
                    previewJpegs = List(8) { byteArrayOf(4) },
                ),
            )
            val names = entries(output.toByteArray()).map { it.name }
            assertEquals(
                listOf(
                    "description.xml",
                    "lockscreen/wallpaper1.jpg",
                    "overlays/androidzte/res/values/config.xml",
                    "overlays/androidzte/resources.apk",
                    "overlays/com.android.settings/resources.apk",
                    "overlays/com.android.systemui/resources.apk",
                    "overlays/com.zte.mifavor.launcher.resource/raw/icons_cur.zip",
                    "preview/preview00.jpg",
                    "preview/preview01.jpg",
                    "preview/preview02.jpg",
                    "preview/preview03.jpg",
                    "preview/preview04.jpg",
                    "preview/preview05.jpg",
                    "wallpaper/wallpaper1.jpg",
                ),
                names,
            )
            assertEquals(names.sorted(), names)
        } finally {
            icons.delete()
        }
    }
}
