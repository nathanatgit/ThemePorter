package com.nathanhanapps.nebulaThemePorter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entry names here are taken from the icons archives of two real MIUI themes, so the classification is checked
 * against what MIUI actually packs rather than against an idea of it.
 */
class MtzRecolorTest {
    private val realEntries = listOf(
        "res/",
        "res/drawable-xxhdpi/",
        "res/drawable-xxhdpi/com.tencent.mm.png",
        "res/drawable-xxhdpi/com.android.contacts.activities.TwelveKeyDialer.png",
        "res/drawable-xxhdpi/icon_mask.png",
        "res/drawable-xxhdpi/icon_pattern.png",
        "res/drawable-xxhdpi/icon_folder.png",
        "res/drawable-xxhdpi/icon_folder_light.png",
        "res/drawable-xxhdpi/status_bar_toggle_wifi_on.png",
        "fancy_icons/",
        "fancy_icons/com.android.calendar/",
        "fancy_icons/com.android.calendar/calendar_11.png",
        "fancy_icons/com.android.calendar/manifest.xml",
        "fancy_icons/com.android.deskclock/hour.png",
        "transform_config.xml",
    )

    @Test
    fun appIconsAreTheOnlyEntriesTheShapePipelineTouches() {
        assertEquals(MtzRecolor.Use.APP_ICON, MtzRecolor.use("res/drawable-xxhdpi/com.tencent.mm.png"))
        assertEquals(MtzRecolor.Use.APP_ICON, MtzRecolor.use("res/drawable-xxhdpi/com.android.contacts.activities.TwelveKeyDialer.png"))
        assertEquals(MtzRecolor.Use.APP_ICON, MtzRecolor.use("res/drawable/com.tencent.mm.webp"))
    }

    @Test
    fun themeArtworkIsTintedButNotReshaped() {
        assertEquals(MtzRecolor.Use.ASSET, MtzRecolor.use("res/drawable-xxhdpi/icon_pattern.png"))
        assertEquals(MtzRecolor.Use.ASSET, MtzRecolor.use("res/drawable-xxhdpi/icon_folder.png"))
        assertEquals(MtzRecolor.Use.ASSET, MtzRecolor.use("fancy_icons/com.android.calendar/calendar_11.png"))
        assertEquals(MtzRecolor.Use.ASSET, MtzRecolor.use("fancy_icons/com.android.deskclock/hour.png"))
    }

    /** The mask is read for its alpha only, and quick-settings toggles tell their states apart by color. */
    @Test
    fun theMaskManifestsAndStatusBarTogglesAreLeftAlone() {
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("res/drawable-xxhdpi/icon_mask.png"))
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("res/drawable-xxhdpi/status_bar_toggle_wifi_on.png"))
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("fancy_icons/com.android.calendar/manifest.xml"))
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("transform_config.xml"))
    }

    @Test
    fun directoryEntriesAreCopiedRatherThanMistakenForArtwork() {
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("res/"))
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("res/drawable-xxhdpi/"))
        assertEquals(MtzRecolor.Use.COPY, MtzRecolor.use("fancy_icons/com.android.calendar/"))
    }

    @Test
    fun generatedIconsLandBesideTheThemesOwnAtTheSameDensity() {
        assertEquals("res/drawable-xxhdpi/", MtzRecolor.drawableDir(realEntries))
        assertEquals(
            "res/drawable-xxhdpi/com.foo.bar.png",
            MtzRecolor.entryFor(MtzRecolor.drawableDir(realEntries), "com.foo.bar"),
        )
    }

    @Test
    fun aThemeWithNoIconsAtAllStillHasSomewhereToPutGeneratedOnes() {
        assertEquals(MtzRecolor.DEFAULT_DRAWABLE_DIR, MtzRecolor.drawableDir(listOf("transform_config.xml")))
    }

    /** MIUI addresses a few individual activities by class name, so a stem also covers the package it extends. */
    @Test
    fun anActivityAliasCountsAsCoverForItsOwnPackage() {
        val installed = setOf("com.android.contacts", "com.tencent.mm")
        assertEquals("com.android.contacts", MtzRecolor.packageFor("com.android.contacts.activities.TwelveKeyDialer", installed))
        assertEquals("com.tencent.mm", MtzRecolor.packageFor("com.tencent.mm", installed))
        assertNull(MtzRecolor.packageFor("icon_pattern", installed))
        assertNull(MtzRecolor.packageFor("com.notinstalled.app", installed))
    }

    @Test
    fun coverageResolvesEachIconToTheAppItThemes() {
        val installed = setOf("com.android.contacts", "com.tencent.mm", "com.example.notes")
        val icons = listOf(
            "com.tencent.mm" to "i:mm.png",
            "com.android.contacts.activities.TwelveKeyDialer" to "i:dialer.png",
            "icon_pattern" to "i:pattern.png",
            "com.notinstalled.app" to "i:absent.png",
        )

        val covered = MtzRecolor.coverage(icons, installed)

        assertEquals(mapOf("com.tencent.mm" to "i:mm.png", "com.android.contacts" to "i:dialer.png"), covered)
    }

    /** Whichever order the archive lists them in, the package's own drawable is the one shown for the app. */
    @Test
    fun anIconNamedForThePackageBeatsOneNamedForAnActivity() {
        val installed = setOf("com.android.contacts")
        val aliasFirst = listOf(
            "com.android.contacts.activities.TwelveKeyDialer" to "i:dialer.png",
            "com.android.contacts" to "i:contacts.png",
        )
        val packageFirst = aliasFirst.reversed()

        assertEquals("i:contacts.png", MtzRecolor.coverage(aliasFirst, installed)["com.android.contacts"])
        assertEquals("i:contacts.png", MtzRecolor.coverage(packageFirst, installed)["com.android.contacts"])
    }

    @Test
    fun missingPackagesAreTheInstalledAppsNoStemAccountsFor() {
        val stems = listOf("com.tencent.mm", "com.android.contacts.activities.TwelveKeyDialer", "icon_pattern")
        val installed = listOf("com.tencent.mm", "com.android.contacts", "com.example.notes", "com.example.maps")

        val missing = MtzRecolor.missingPackages(stems, installed)

        assertEquals(listOf("com.example.maps", "com.example.notes"), missing)
    }

    @Test
    fun aRecolorNeverOverwritesTheThemeItCameFrom() {
        assertEquals("Aurora_tinted.mtz", MtzRecolor.outputName("Aurora.mtz"))
        assertEquals("Aurora_tinted.mtz", MtzRecolor.outputName("Aurora_tinted.mtz"))
        assertEquals("theme_tinted.mtz", MtzRecolor.outputName(""))
        assertTrue(MtzRecolor.outputName("超方程.mtz").endsWith("_tinted.mtz"))
    }
}
