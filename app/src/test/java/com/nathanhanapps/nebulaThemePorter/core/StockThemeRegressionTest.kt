package com.nathanhanapps.nebulaThemePorter.core

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Compares the mapped layout with real stock themes. Set NEBULA_STOCK_THEMES to the folder holding
 * default_theme_04/13/49/60.zmtp; the tests are skipped otherwise.
 */
class StockThemeRegressionTest {
    private class StockTheme(val description: Map<String, String>, val outerEntries: List<String>, val iconFiles: Set<String>)

    private fun load(name: String): StockTheme {
        val dir = System.getenv("NEBULA_STOCK_THEMES")?.let(::File)
        val file = dir?.resolve("$name.zmtp")
        assumeTrue("NEBULA_STOCK_THEMES not set or $name.zmtp missing", file != null && file.isFile)
        ZipFile(file!!).use { zip ->
            val description = SafeXml.parse(zip.getInputStream(zip.getEntry(NebulaSpec.DESCRIPTION)).readBytes().toString(Charsets.UTF_8))
            val items = description.getElementsByTagName("item")
            val keys = (0 until items.length).mapNotNull { items.item(it) as? Element }
                .associate { it.getAttribute("key") to it.getAttribute("value") }
            val iconFiles = mutableSetOf<String>()
            ZipInputStream(zip.getInputStream(zip.getEntry(NebulaSpec.ICONS_CUR))).use { icons ->
                while (true) {
                    val entry = icons.nextEntry ?: break
                    if (!entry.isDirectory) iconFiles += entry.name
                }
            }
            val outer = zip.entries().asSequence().map { it.name }.toList()
            return StockTheme(keys, outer, iconFiles)
        }
    }

    private fun specialFiles(theme: StockTheme): Set<String> = theme.iconFiles
        .filter { it.startsWith(NebulaSpec.ICON_DIR) }
        .map { it.removePrefix(NebulaSpec.ICON_DIR) }
        .filter { it.startsWith("theme_") || it.startsWith("config") }
        .toSet()

    private fun assertOuterLayout(theme: StockTheme) {
        val known = setOf(NebulaSpec.DESCRIPTION, NebulaSpec.WALLPAPER, NebulaSpec.LOCKSCREEN_WALLPAPER, NebulaSpec.ICONS_CUR) +
            NebulaSpec.OVERLAY_APKS
        known.forEach { assertTrue("missing $it", it in theme.outerEntries) }
        assertEquals(theme.outerEntries.sorted(), theme.outerEntries)
    }

    @Test
    fun theme13IsAdaptiveWithEveryAsset() {
        val theme = load("default_theme_13")
        assertOuterLayout(theme)
        assertEquals("config_1", theme.description["defaultIconShape"])
        assertEquals("true", theme.description["isSupportIconShapeChange"])
        val expected = ThemeLayout.specialFiles(ThemeStyle.ADAPTIVE, IconShape.SQUIRCLE, ShapeAsset.entries.toSet(), includeIconInfo = true)
        assertEquals(expected.toSet(), specialFiles(theme))
    }

    @Test
    fun theme49SupportsShapesWithoutIconInfoOrFolderPlate() {
        val theme = load("default_theme_49")
        assertOuterLayout(theme)
        assertEquals("true", theme.description["isSupportIconShapeChange"])
        val expected = ThemeLayout.specialFiles(
            ThemeStyle.ADAPTIVE,
            IconShape.SQUIRCLE,
            ShapeAsset.entries.toSet() - ShapeAsset.FOLDER_ICON,
            includeIconInfo = false,
        )
        assertEquals(expected.toSet(), specialFiles(theme))
    }

    @Test
    fun theme04DefaultsToCircle() {
        val theme = load("default_theme_04")
        assertEquals("config", theme.description["defaultIconShape"])
        val expected = ThemeLayout.specialFiles(
            ThemeStyle.ADAPTIVE,
            IconShape.CIRCLE,
            ShapeAsset.entries.toSet(),
            includeIconInfo = true,
            includeBackgroundIcons = true,
        )
        assertEquals(expected.toSet(), specialFiles(theme))
    }

    @Test
    fun theme60IsFixedShape() {
        val theme = load("default_theme_60")
        assertOuterLayout(theme)
        assertNull(theme.description["defaultIconShape"])
        assertNull(theme.description["isSupportIconShapeChange"])
        val expected = ThemeLayout.specialFiles(
            ThemeStyle.FIXED,
            IconShape.SQUIRCLE,
            setOf(ShapeAsset.MASK, ShapeAsset.FOLDER_ADD, ShapeAsset.DYNAMIC_CALENDAR, ShapeAsset.DYNAMIC_CLOCK),
            includeIconInfo = false,
        )
        assertEquals(expected.toSet(), specialFiles(theme))
    }

    @Test
    fun stockShapeConfigsMatchIconShapePaths() {
        val theme = load("default_theme_13")
        val dir = System.getenv("NEBULA_STOCK_THEMES")!!.let(::File)
        ZipFile(dir.resolve("default_theme_13.zmtp")).use { zip ->
            ZipInputStream(zip.getInputStream(zip.getEntry(NebulaSpec.ICONS_CUR))).use { icons ->
                val configs = mutableMapOf<String, String>()
                while (true) {
                    val entry = icons.nextEntry ?: break
                    if (entry.name.startsWith("icon/config")) configs[entry.name.removePrefix("icon/")] = icons.readBytes().toString(Charsets.UTF_8)
                }
                IconShape.entries.forEach { shape ->
                    val stock = configs.getValue("${shape.configName}.xml")
                    assertTrue(shape.name, stock.contains("\"${shape.svgPath}\""))
                }
            }
        }
        assertTrue(theme.iconFiles.isNotEmpty())
    }
}
