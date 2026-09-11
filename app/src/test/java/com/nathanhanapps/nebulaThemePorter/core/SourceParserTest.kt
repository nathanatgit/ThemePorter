package com.nathanhanapps.nebulaThemePorter.core

import com.nathanhanapps.nebulaThemePorter.source.IconPackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceParserTest {
    @Test
    fun appFilterReadsItemsPlatesAndCalendar() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
              <iconback img1="iconback_1" img2="iconback_2"/>
              <iconmask img1="iconmask"/>
              <iconupon img1="iconupon"/>
              <scale factor="0.82"/>
              <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="wechat"/>
              <item component="ComponentInfo{com.foo/.Main}" drawable="@drawable/foo.png"/>
              <item component=":BROWSER" drawable="browser"/>
              <calendar component="ComponentInfo{com.android.calendar/com.android.calendar.AllInOneActivity}" prefix="calendar_"/>
            </resources>
        """.trimIndent()
        val filter = AppFilterParser.parse(xml)
        assertEquals(2, filter.items.size)
        assertEquals("com_tencent_mm-com_tencent_mm_ui_LauncherUI", filter.items[0].component.stem)
        assertEquals("com_foo-com_foo_Main", filter.items[1].component.stem)
        assertEquals("foo", filter.items[1].drawable)
        assertEquals(listOf("iconback_1", "iconback_2"), filter.iconBacks)
        assertEquals(listOf("iconmask"), filter.iconMasks)
        assertEquals(listOf("iconupon"), filter.iconUpons)
        assertEquals(0.82f, filter.scale!!, 0.0001f)
        assertEquals("calendar_", filter.calendars.single().prefix)
        assertTrue("calendar_31" in filter.requestedDrawables)
    }

    @Test
    fun componentWithoutActivityKeepsPackageOnly() {
        val component = AppFilterParser.parseComponent("ComponentInfo{com.example.app/}")
        assertEquals("com_example_app", component!!.stem)
        assertNull(AppFilterParser.parseComponent("ComponentInfo{not a package/x}"))
    }

    @Test
    fun mtzPackageFindsIconsWallpapersAndMetadata() {
        val description = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <theme>
            <version><![CDATA[1.0]]></version>
            <author><![CDATA[AP简约护眼]]></author>
            <designer><![CDATA[AP]]></designer>
            <title><![CDATA[简约风AP]]></title>
            <description><![CDATA[上滑解锁]]></description>
            <titles><title locale="zh_CN"><![CDATA[localized]]></title></titles>
            </theme>
        """.trimIndent()
        val entries = listOf(
            "description.xml",
            "icons",
            "wallpaper/default_wallpaper.jpg",
            "wallpaper/default_lock_wallpaper.jpg",
            "wallpaper/blue_hour.webp",
            "preview/preview_icons_0.jpg",
            "lockscreen",
        )
        val pkg = MtzParser.readPackage(entries) { if (it == "description.xml") description else null }
        assertEquals("简约风AP", pkg.metadata.title)
        assertEquals("AP简约护眼", pkg.metadata.author)
        assertEquals("icons", pkg.iconsEntry)
        assertEquals("wallpaper/default_wallpaper.jpg", pkg.wallpaperEntry)
        assertEquals("wallpaper/default_lock_wallpaper.jpg", pkg.lockWallpaperEntry)
        assertEquals(
            listOf(
                "wallpaper/default_wallpaper.jpg",
                "wallpaper/default_lock_wallpaper.jpg",
                "wallpaper/blue_hour.webp",
            ),
            pkg.wallpaperEntries,
        )
        assertEquals(listOf("preview/preview_icons_0.jpg"), pkg.previewEntries)
    }

    @Test
    fun iconPackWallpaperDiscoveryKeepsArtworkAndRejectsPreviews() {
        val entries = listOf(
            "assets/wallpapers/aurora.jpg",
            "assets/wallpapers/aurora_thumbnail.jpg",
            "res/drawable-hdpi/wallpaper_sunset.png",
            "res/drawable-xxxhdpi/wallpaper_sunset.png",
            "res/drawable-nodpi/icon_back.png",
            "res/drawable-nodpi/wallpaper_preview.png",
            "assets/screenshots/wallpaper_demo.jpg",
        )

        assertEquals(
            listOf("assets/wallpapers/aurora.jpg", "res/drawable-xxxhdpi/wallpaper_sunset.png"),
            IconPackSource.discoverWallpapers(entries),
        )
    }

    @Test
    fun mtzIconsPreferDenseDrawablesAndSkipNonApps() {
        val dayEntries = (1..31).map { "fancy_icons/com.android.calendar/calendar_$it.png" }
        val entries = listOf(
            "res/drawable-xhdpi/com.tencent.mm.png",
            "res/drawable-xxhdpi/com.tencent.mm.png",
            "res/drawable-xxhdpi/com.android.contacts.activities.TwelveKeyDialer.png",
            "res/drawable-xxhdpi/icon_mask.png",
            "res/drawable-xxhdpi/icon_pattern.png",
            "res/drawable-xxhdpi/icon_folder.png",
            "res/drawable-xxhdpi/status_bar_toggle_lock.png",
            "res/drawable-xxhdpi/com.proper.soft.mobile.isj-1.png",
            "res/drawable-xxhdpi/android.png",
            "fancy_icons/com.android.calendar/manifest.xml",
            "fancy_icons/com.android.deskclock/bg.png",
            "fancy_icons/com.android.deskclock/hour.png",
            "fancy_icons/com.android.deskclock/minute.png",
            "fancy_icons/com.android.deskclock/second.png",
            "fancy_icons/com.android.deskclock/manifest.xml",
            "transform_config.xml",
        ) + dayEntries
        val texts = mapOf(
            "fancy_icons/com.android.calendar/manifest.xml" to """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?><Icon height="224" width="224">
                  <Image align="center" alignV="center" src="calendar.png" srcid="#date" x="112" y="112"/>
                  <DateTime x="112" y="56" align="center" color="#4E4E4E" size="34" format="EEEE"/>
                </Icon>
            """.trimIndent(),
            "fancy_icons/com.android.deskclock/manifest.xml" to "<Icon frameRate=\"0\" height=\"224\" width=\"224\"></Icon>",
            "transform_config.xml" to """
                <IconTransform><PointsMapping>
                  <Point fromX="0.0" fromY="0.0" toX="-2" toY="-2"/>
                  <Point fromX="90.0" fromY="90.0" toX="92.0" toY="92.0"/>
                </PointsMapping></IconTransform>
            """.trimIndent(),
        )
        val icons = MtzParser.readIcons(entries) { texts[it] }
        assertEquals(setOf("com.tencent.mm", "com.android.contacts.activities.TwelveKeyDialer"), icons.icons.keys)
        assertEquals("res/drawable-xxhdpi/com.tencent.mm.png", icons.icons["com.tencent.mm"])
        assertEquals("res/drawable-xxhdpi/icon_mask.png", icons.maskEntry)
        assertEquals("res/drawable-xxhdpi/icon_pattern.png", icons.patternEntry)
        assertEquals("res/drawable-xxhdpi/icon_folder.png", icons.folderEntry)
        val calendar = assertNotNullAndGet(icons.calendar)
        assertEquals(31, calendar.dayEntries.size)
        assertEquals(224, calendar.canvasSize)
        assertTrue(calendar.showWeek)
        val clock = assertNotNullAndGet(icons.clock)
        assertEquals("fancy_icons/com.android.deskclock/hour.png", clock.hourEntry)
        assertEquals("fancy_icons/com.android.deskclock/minute.png", clock.minuteEntry)
        assertEquals("fancy_icons/com.android.deskclock/bg.png", clock.dialEntry)
        assertEquals(94f / 90f, icons.iconScale!!, 0.001f)
    }

    @Test
    fun mtzCalendarWithBackgroundAndTextKeepsTextStyle() {
        val entries = listOf("fancy_icons/com.android.calendar/bg.png", "fancy_icons/com.android.calendar/manifest.xml")
        val manifest = """
            <Icon height="168" width="168">
              <Image src="bg.png" x="84" y="84"/>
              <DateTime color="#000000" format="E" size="0" x="84" y="54"/>
              <Text bold="true" color="#ffffff" size="55" textExp="#date" x="84" y="84"/>
            </Icon>
        """.trimIndent()
        val calendar = MtzParser.readIcons(entries) { if (it.endsWith("manifest.xml")) manifest else null }.calendar!!
        assertTrue(calendar.dayEntries.isEmpty())
        assertEquals("fancy_icons/com.android.calendar/bg.png", calendar.backgroundEntry)
        assertEquals(0xFFFFFFFFL, calendar.textColor)
        assertEquals(55f, calendar.textSize!!, 0f)
        assertFalse(calendar.showWeek)
    }

    @Test
    fun colorsParseWithAndWithoutAlpha() {
        assertEquals(0xFF4E4E4EL, MtzParser.parseColor("#4E4E4E"))
        assertEquals(0x80FFFFFFL, MtzParser.parseColor("#80ffffff"))
        assertNull(MtzParser.parseColor("red"))
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
