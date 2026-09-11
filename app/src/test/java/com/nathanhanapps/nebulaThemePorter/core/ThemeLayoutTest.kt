package com.nathanhanapps.nebulaThemePorter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeLayoutTest {
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
            setOf(ShapeAsset.MASK, ShapeAsset.FOLDER_ADD, ShapeAsset.DYNAMIC_CALENDAR, ShapeAsset.DYNAMIC_CLOCK),
        )
        assertEquals(expected, actual.toSet())
    }

    @Test
    fun shapeAssetFileNamesHaveNoShapeVariant() {
        assertEquals("theme_mask_icon.png", ShapeAsset.MASK.fileName())
        assertEquals("theme_dynamic_clock.png", ShapeAsset.DYNAMIC_CLOCK.fileName())
        assertEquals("config.xml", ThemeLayout.CONFIG_FILE)
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
        val fixed = BuildOptions()
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
