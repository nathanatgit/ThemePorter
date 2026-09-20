package com.nathanhanapps.nebulaThemePorter.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPlannerTest {
    private val phoneStem = "com_android_contacts-com_android_contacts_activities_DialtactsActivity"
    private val contactsStem = "com_android_contacts-com_android_contacts_activities_PeopleActivity"
    private val wechatStem = "com_tencent_mm-com_tencent_mm_ui_LauncherUI"

    private val mtzSources = listOf(
        SourceIcon("i:dialer", "com.android.contacts.activities.TwelveKeyDialer"),
        SourceIcon("i:contacts", "com.android.contacts"),
        SourceIcon("i:mm", "com.tencent.mm"),
    )

    @Test
    fun miuiAliasesMapToZteSystemAppsAndPackageOnlyFallbacks() {
        val planner = IconPlanner(StockComponentIndex(listOf(wechatStem)), emptyMap())
        val assignments = planner.autoAssignSystemApps(mtzSources)
        assertEquals("i:dialer", assignments["phone"])
        assertEquals("i:contacts", assignments["contacts"])

        val plan = planner.plan(mtzSources, assignments, onlyInstalledApps = false)
        assertEquals(listOf(phoneStem, contactsStem, wechatStem, "com_tencent_mm"), plan.icons.map { it.stem })
        assertEquals("i:mm", plan.icons.last().sourceId)
    }

    /**
     * The gallery case. A pack ships its own com.android.gallery3d artwork, so that package used to answer to
     * three names at once - the stock stem from the assignment, plus the device's real activity and the
     * package-only name from the pack's artwork - and the icon shown was not the one picked. Stock themes name
     * a system app with exactly one file, so an assigned system app now does too.
     */
    @Test
    fun anAssignedSystemAppShipsOnlyItsStockStem() {
        val chosen = SourceIcon("i:chosen", "com.miui.gallery")
        val packsOwn = SourceIcon("i:packs", "com.android.gallery3d")
        val planner = IconPlanner(
            StockComponentIndex(emptyList()),
            mapOf("com.android.gallery3d" to listOf("com.zte.gallery3d.activity.launcher.MainGallery")),
        )

        val plan = planner.plan(
            listOf(chosen, packsOwn),
            mapOf("gallery" to "i:chosen"),
            onlyInstalledApps = false,
            generateMissingAppIcons = true,
        )

        val gallery = plan.icons.filter { it.stem.startsWith("com_android_gallery3d") }
        assertEquals(
            "exactly the stock name, as a stock theme ships it",
            listOf("com_android_gallery3d-com_android_newgallery_NewGallery"),
            gallery.map { it.stem },
        )
        assertEquals("i:chosen", gallery.single().sourceId)
    }

    /** An unassigned package keeps every name it had: only a deliberate assignment reserves one. */
    @Test
    fun packageOnlyFallbackStillComesFromThePackWhenNothingIsAssigned() {
        val planner = IconPlanner(
            StockComponentIndex(emptyList()),
            mapOf("com.android.gallery3d" to listOf("com.zte.gallery3d.activity.launcher.MainGallery")),
        )

        val plan = planner.plan(
            listOf(SourceIcon("i:packs", "com.android.gallery3d")),
            emptyMap(),
            onlyInstalledApps = false,
        )

        assertEquals("i:packs", plan.icons.associate { it.stem to it.sourceId }["com_android_gallery3d"])
    }

    /** Phone and Contacts are both com.android.contacts: a package-only file could only show one of them. */
    @Test
    fun aPackageTwoSystemAppsShareIsLeftToThePacksOwnArtwork() {
        val planner = IconPlanner(StockComponentIndex(emptyList()), emptyMap())
        val assignments = planner.autoAssignSystemApps(mtzSources)

        val plan = planner.plan(mtzSources, assignments, onlyInstalledApps = false)

        assertFalse(
            "phone/contacts must not claim the shared package name",
            "com_android_contacts" in plan.icons.map { it.stem },
        )
    }

    @Test
    fun unknownActivityFallsBackToPackageName() {
        val planner = IconPlanner(StockComponentIndex(emptyList()), emptyMap())
        val plan = planner.plan(listOf(SourceIcon("i:x", "org.example.reader")), emptyMap(), onlyInstalledApps = false)
        assertEquals(listOf("org_example_reader"), plan.icons.map { it.stem })
    }

    @Test
    fun exactComponentBeatsOlderEntryForInstalledActivity() {
        val old = SourceIcon("res/old.png", "com.foo.app", AppComponent("com.foo.app", "com.foo.app.OldActivity"))
        val current = SourceIcon("res/new.png", "com.foo.app", AppComponent("com.foo.app", ".MainActivity"))
        val notInstalled = SourceIcon("res/bar.png", "com.bar", AppComponent("com.bar", "com.bar.Main"))
        val planner = IconPlanner(StockComponentIndex(emptyList()), mapOf("com.foo.app" to listOf("com.foo.app.MainActivity")))

        val plan = planner.plan(listOf(old, current, notInstalled), emptyMap(), onlyInstalledApps = true)
        val byStem = plan.icons.associate { it.stem to it.sourceId }
        assertEquals("res/old.png", byStem["com_foo_app-com_foo_app_OldActivity"])
        assertEquals("res/new.png", byStem["com_foo_app-com_foo_app_MainActivity"])
        assertFalse(byStem.keys.any { it.startsWith("com_bar") })
        // Every eligible source also gets a package-only fallback stem; the first one wins.
        assertEquals("res/old.png", byStem["com_foo_app"])
    }

    @Test
    fun firstAliasWins() {
        val sources = listOf(SourceIcon("a", "com.android.dialer"), SourceIcon("b", "com.google.android.dialer"))
        val assignments = IconPlanner(StockComponentIndex(emptyList()), emptyMap()).autoAssignSystemApps(sources)
        assertEquals("b", assignments["phone"])
    }

    @Test
    fun onlineToolAliasesWithStockEquivalentsAreCovered() {
        val expected = mapOf(
            "com.android.calendary" to "calendar",
            "com.android.voicedialer" to "voice_assistant",
            "com.miui.hybrid" to "browser",
            "com.miui.misupport" to "health",
            "com.miui.supermarket" to "app_store",
            "com.mlab.cam" to "camera",
        )
        expected.forEach { (alias, appId) -> assertEquals(appId, ZteSystemApps.matchAlias(alias)?.id) }
    }

    @Test
    fun assignmentsToMissingSourcesAreDropped() {
        val planner = IconPlanner(StockComponentIndex(emptyList()), emptyMap())
        val plan = planner.plan(emptyList(), mapOf("phone" to "gone"), onlyInstalledApps = false)
        assertTrue(plan.icons.isEmpty())
        assertTrue(plan.systemAssignments.isEmpty())
    }

    @Test
    fun manualLauncherAssignmentOverridesAutomaticComponent() {
        val stem = "com_foo_app-com_foo_app_MainActivity"
        val automatic = SourceIcon("automatic", "com.foo.app", AppComponent("com.foo.app", ".MainActivity"))
        val chosen = SourceIcon("chosen", "com.example.other")
        val planner = IconPlanner(StockComponentIndex(emptyList()), mapOf("com.foo.app" to listOf("com.foo.app.MainActivity")))

        val plan = planner.plan(
            listOf(automatic, chosen),
            emptyMap(),
            onlyInstalledApps = true,
            manualAssignments = mapOf(stem to "chosen", "../unsafe" to "chosen", "com_missing-Main" to "gone"),
        )

        assertEquals("chosen", plan.icons.first { it.stem == stem }.sourceId)
        assertFalse(plan.icons.any { it.stem == "../unsafe" })
    }

    @Test
    fun generatesDeviceFallbackOnlyForAppsWithNoOtherIcon() {
        val matched = SourceIcon("res/foo.png", "com.foo.app", AppComponent("com.foo.app", ".MainActivity"))
        val planner = IconPlanner(
            StockComponentIndex(emptyList()),
            mapOf("com.foo.app" to listOf("com.foo.app.MainActivity"), "com.bar.app" to listOf("com.bar.app.MainActivity")),
        )

        val plan = planner.plan(
            listOf(matched),
            emptyMap(),
            onlyInstalledApps = true,
            generateMissingAppIcons = true,
        )

        val byStem = plan.icons.associate { it.stem to it.sourceId }
        assertEquals("res/foo.png", byStem["com_foo_app-com_foo_app_MainActivity"])
        assertEquals(
            DeviceIconId.of("com.bar.app", "com.bar.app.MainActivity"),
            byStem["com_bar_app-com_bar_app_MainActivity"],
        )
    }

    @Test
    fun deviceFallbackIsOffByDefaultAndSkippableByFlag() {
        val planner = IconPlanner(StockComponentIndex(emptyList()), mapOf("com.bar.app" to listOf("com.bar.app.MainActivity")))
        val plan = planner.plan(emptyList(), emptyMap(), onlyInstalledApps = true)
        assertTrue(plan.icons.isEmpty())
    }

    @Test
    fun componentNamingRules() {
        assertEquals("com_foo-com_foo_Main", AppComponent("com.foo", ".Main").stem)
        assertEquals("com_foo-com_foo_Outer_Inner", AppComponent("com.foo", "com.foo.Outer\$Inner").stem)
        assertFalse(AppComponent.isSafeStem("../evil"))
        assertFalse(AppComponent.isSafeStem("a-b-c"))
        assertTrue(AppComponent.looksLikePackage("com.android.contacts"))
        assertFalse(AppComponent.looksLikePackage("android"))
    }

    @Test
    fun systemAppTableIsConsistentWithStockThemes() {
        val asset = File("src/main/assets/stock_components.txt")
        assertTrue("run from the app module", asset.isFile)
        val stockStems = asset.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val aliases = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        ZteSystemApps.all.forEach { app ->
            assertTrue("duplicate id ${app.id}", ids.add(app.id))
            app.stems.forEach { stem ->
                assertTrue("unsafe $stem", AppComponent.isSafeStem(stem))
                assertTrue("$stem is not in any stock theme", stem in stockStems)
            }
            app.aliases.forEach { assertTrue("alias $it used twice", aliases.add(it.lowercase())) }
        }
    }
}
