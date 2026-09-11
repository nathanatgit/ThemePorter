package com.nathanhanapps.nebulaThemePorter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ThemeXmlTest {
    private val metadata = ThemeMetadata(
        id = "online_theme_ntp_20260910_0001",
        labelEn = "Ported",
        labelZh = "移植",
        introEn = "intro",
        introZh = "介绍",
        author = "Nathan",
    )

    private fun items(xml: String): Map<String, String> {
        val nodes = SafeXml.parse(xml).getElementsByTagName("item")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .associate { it.getAttribute("key") to it.getAttribute("value") }
    }

    @Test
    fun adaptiveDescriptionEnablesShapeSwitching() {
        val values = items(ThemeXml.description(metadata, ThemeStyle.ADAPTIVE, IconShape.SQUIRCLE))
        assertEquals("config_1", values["defaultIconShape"])
        assertEquals("true", values["isSupportIconShapeChange"])
        assertEquals("true", values["recompiled"])
        assertEquals("16.0.1", values["Theme Version"])
        assertEquals("1", values["LockScreenWallpaperType"])
        assertEquals("移植", values["label-zh-rCN"])
    }

    @Test
    fun fixedDescriptionOmitsShapeKeys() {
        val values = items(ThemeXml.description(metadata, ThemeStyle.FIXED, IconShape.CIRCLE))
        assertFalse("defaultIconShape" in values)
        assertFalse("isSupportIconShapeChange" in values)
        assertEquals("true", values["recompiled"])
    }

    @Test
    fun valuesAreEscaped() {
        val tricky = metadata.copy(labelEn = "A&B \"<x>\"", introEn = "line1\nline2")
        val values = items(ThemeXml.description(tricky, ThemeStyle.ADAPTIVE, IconShape.CIRCLE))
        assertEquals("A&B \"<x>\"", values["label-en"])
        assertEquals("line1\nline2", values["intro-en"])
    }

    @Test
    fun calendarInfoMatchesStockTheme13() {
        val stock = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <item key="showWeekInfo" value="1" />
                <item key="paddingTop" value="7" />
                <item key="textColor" value="#E5000000" />
                <item key="textSize" value="9" />
            </root>
        """.trimIndent()
        assertEquals(stock, ThemeXml.calendarInfo(CalendarTextStyle(true, 7, 0xE5000000, 9)).trim())
    }

    @Test
    fun shapeConfigQuotesThePath() {
        val xml = ThemeXml.shapeConfig(IconShape.CIRCLE)
        assertTrue(xml.contains("<string name=\"config_icon_zte\" translatable=\"false\">\"M50,0A50,50,0,1,1,0,50,50,50,0,0,1,50,0Z\"</string>"))
        SafeXml.parse(xml)
    }

    @Test
    fun iconInfoSplitsComponentStems() {
        val xml = ThemeXml.iconInfo(listOf("com_tencent_mm-com_tencent_mm_ui_LauncherUI", "com_tencent_mm", "com_tencent_mm-com_tencent_mm_ui_LauncherUI"))
        val nodes = SafeXml.parse(xml).getElementsByTagName("item")
        assertEquals(1, nodes.length)
        val item = nodes.item(0) as Element
        assertEquals("com_tencent_mm", item.getAttribute("packageName"))
        assertEquals("com_tencent_mm_ui_LauncherUI", item.getAttribute("className"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun doctypeIsRejected() {
        SafeXml.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><root>&e;</root>")
    }
}
