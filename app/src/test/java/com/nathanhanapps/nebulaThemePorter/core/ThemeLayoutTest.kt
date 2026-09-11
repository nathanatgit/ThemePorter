package com.nathanhanapps.nebulaThemePorter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeLayoutTest {
    private fun variants(base: String) = listOf("", "_1", "_2", "_3", "_4").map { "${base}_config$it.png" }

    @Test
    fun adaptiveLayoutMatchesStockTheme13() {
        val expected = buildSet {
            addAll(listOf("config.xml", "config_1.xml", "config_2.xml", "config_3.xml", "config_4.xml"))
            addAll(variants("theme_dynamic_calendar"))
            addAll(variants("theme_dynamic_clock"))
            addAll(variants("theme_folder_add"))
            addAll(variants("theme_folder_icon"))
            addAll(variants("theme_mask_icon"))
            add("theme_info_dynamic_calendar.xml")
            add("theme_info_icon.xml")
            add("theme_shortcut_bg_settings.png")
            add("theme_bg_icon-0_65.png")
            add("theme_bg_icon-66_100.png")
        }
        val actual = ThemeLayout.specialFiles(
            ThemeStyle.ADAPTIVE,
            IconShape.SQUIRCLE,
            ShapeAsset.entries.toSet(),
            includeIconInfo = true,
        )
        assertEquals(expected, actual.toSet())
        assertEquals(actual.size, actual.toSet().size)
    }

    @Test
    fun fixedLayoutMatchesStockTheme60() {
        val expected = setOf(
            "config.xml",
            "theme_dynamic_calendar.png",
            "theme_dynamic_clock.png",
            "theme_folder_add.png",
            "theme_info_dynamic_calendar.xml",
            "theme_mask_icon.png",
            "theme_shortcut_bg_settings.png",
            "theme_bg_icon-0_65.png",
            "theme_bg_icon-66_100.png",
        )
        val actual = ThemeLayout.specialFiles(
            ThemeStyle.FIXED,
            IconShape.SQUIRCLE,
            setOf(ShapeAsset.MASK, ShapeAsset.FOLDER_ADD, ShapeAsset.DYNAMIC_CALENDAR, ShapeAsset.DYNAMIC_CLOCK),
            includeIconInfo = false,
        )
        assertEquals(expected, actual.toSet())
    }

    @Test
    fun shapeSuffixesFollowConfigNames() {
        assertEquals("", IconShape.CIRCLE.variantSuffix)
        assertEquals("_4", IconShape.TEARDROP.variantSuffix)
        assertEquals("theme_mask_icon_config.png", ShapeAsset.MASK.fileName(ThemeStyle.ADAPTIVE, IconShape.CIRCLE))
        assertEquals("theme_dynamic_clock_config_2.png", ShapeAsset.DYNAMIC_CLOCK.fileName(ThemeStyle.ADAPTIVE, IconShape.ROUNDED_SQUARE))
        assertEquals("theme_dynamic_clock.png", ShapeAsset.DYNAMIC_CLOCK.fileName(ThemeStyle.FIXED, IconShape.ROUNDED_SQUARE))
        assertEquals("theme_dynamic_clock.png", ShapeAsset.DYNAMIC_CLOCK.fileName(ThemeStyle.FIXED, FixedIconShape.SQUARE))
        assertEquals("config.xml", ThemeLayout.configFileName(ThemeStyle.FIXED, IconShape.LEAF))
    }

    @Test
    fun fixedShapesArePreRenderedChoicesNotSystemConfigs() {
        assertTrue(FixedIconShape.NONE.isOriginal)
        assertEquals("", FixedIconShape.NONE.svgPath)
        assertEquals(false, FixedIconShape.SQUARE.isOriginal)
        assertEquals(false, FixedIconShape.COOKIE_6.isOriginal)
        assertEquals(20, FixedIconShape.entries.size)
        FixedIconShape.entries.filterNot { it.isOriginal }.forEach { shape ->
            assertEquals(shape.name, true, shape.svgPath.isNotBlank())
        }
        val fixed = BuildOptions(style = ThemeStyle.FIXED)
        assertEquals(FixedIconShape.NONE, fixed.fixedShape)
        assertEquals(FixedIconComposition.OVERLAY, fixed.fixedComposition)
        assertEquals(0.66f, fixed.fixedIconScale)
        assertEquals(1f, fixed.fixedIconAlpha)
        assertEquals(1f, fixed.fixedBackgroundScale)
        assertEquals(GradientMix.BLOBS, fixed.generatedWallpaper.mix)
        assertEquals(45f, fixed.generatedWallpaper.angle)
    }

    @Test
    fun previewPathsAreTwoDigits() {
        assertEquals("preview/preview00.jpg", NebulaSpec.previewPath(0))
        assertEquals("preview/preview05.jpg", NebulaSpec.previewPath(5))
    }

    @Test
    fun previewStemsAreSafeComponentNames() {
        NebulaSpec.PREVIEW_STEMS.forEach { assertEquals(it, true, AppComponent.isSafeStem(it) && '-' in it) }
    }
}
