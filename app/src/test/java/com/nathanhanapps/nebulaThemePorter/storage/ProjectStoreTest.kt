package com.nathanhanapps.nebulaThemePorter.storage

import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.CurvePoint
import com.nathanhanapps.nebulaThemePorter.core.FixedIconComposition
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.GeneratedWallpaper
import com.nathanhanapps.nebulaThemePorter.core.GradientMix
import com.nathanhanapps.nebulaThemePorter.core.GrayscaleCurve
import com.nathanhanapps.nebulaThemePorter.core.TintBlendMode
import com.nathanhanapps.nebulaThemePorter.source.SourceKind
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole point of saving a project is not having to redo per-icon curves, so these cover the round trip
 * rather than any one field: what comes back has to equal what went in, and a project written by an older
 * version has to still open once new [BuildOptions] fields exist.
 */
class ProjectStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = ProjectStore(temp.root)

    private fun sample(id: String = "p1") = SavedProject(
        id = id,
        name = "Cyan squircles",
        savedAt = 1_700_000_000_000L,
        source = ProjectSource(
            kind = SourceKind.MTZ,
            path = "/sdcard/Download/pack.mtz",
            fileName = "pack.mtz",
        ),
        labelZh = "青色",
        labelEn = "Cyan",
        author = "Han",
        intro = "test",
        outputName = "cyan.zmtp",
        options = BuildOptions(
            fixedShape = FixedIconShape.SQUARE,
            fixedComposition = FixedIconComposition.CLIP,
            fixedTintColor = 0xFF3E6CF0L,
            fixedTintStrength = 0.75f,
            fixedTintBlendMode = TintBlendMode.SOFT_LIGHT,
            fixedIconScale = 1.1f,
            fixedIconAlpha = 0.9f,
            fixedBackgroundScale = 0.6f,
            padBackground = 0xFF102030L,
            onlyInstalledApps = false,
            generatedIconOwnBackground = false,
            generatedWallpaper = GeneratedWallpaper(
                colorA = 0xFF111111L, colorB = 0xFF222222L, colorC = 0xFF333333L,
                blur = 0.25f, mix = GradientMix.RADIAL, angle = 123f, seed = 9,
            ),
        ),
        assignments = mapOf("clock" to "img_clock", "camera" to "img_cam"),
        userAssignments = mapOf("com_example-Main" to "img_x"),
        iconCurves = mapOf(
            "sys:clock" to GrayscaleCurve(listOf(CurvePoint(0f, 0.1f), CurvePoint(0.5f, 0.42f), CurvePoint(1f, 0.95f))),
            "user:com_example-Main" to GrayscaleCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.8f))),
        ),
        selectedWallpaperId = "wall_3",
        wallpaperName = "my wallpaper.png",
        fixedBackgroundName = "plate.png",
    )

    @Test
    fun `round trips every saved field`() {
        val store = store()
        val original = sample()
        store.write(original)

        val read = requireNotNull(store.read(original.id))
        assertEquals(original, read)
    }

    @Test
    fun `keeps curve control points exactly`() {
        val store = store()
        store.write(sample())

        val curves = requireNotNull(store.read("p1")).iconCurves
        assertEquals(2, curves.size)
        assertEquals(
            listOf(CurvePoint(0f, 0.1f), CurvePoint(0.5f, 0.42f), CurvePoint(1f, 0.95f)),
            curves.getValue("sys:clock").points,
        )
    }

    @Test
    fun `lists newest first and skips unreadable directories`() {
        val store = store()
        store.write(sample("old").copy(savedAt = 1_000L))
        store.write(sample("new").copy(savedAt = 2_000L))
        // A directory with no project.json at all, e.g. a half-finished write.
        File(temp.root, "junk").mkdirs()

        assertEquals(listOf("new", "old"), store.list().map(SavedProject::id))
    }

    @Test
    fun `delete removes the whole project directory`() {
        val store = store()
        store.write(sample())
        store.wallpaperFile("p1").writeText("image bytes")

        store.delete("p1")

        assertNull(store.read("p1"))
        assertTrue(store.list().isEmpty())
        assertTrue(!store.dir("p1").exists())
    }

    /**
     * A project written before a BuildOptions field existed must still open, taking that field's default
     * rather than failing the whole read - otherwise every new option silently orphans saved work.
     */
    @Test
    fun `older project missing newer option fields falls back to defaults`() {
        val store = store()
        store.write(sample())
        val file = File(store.dir("p1"), "project.json")
        val json = JSONObject(file.readText())
        json.getJSONObject("options").apply {
            remove("fixedTintBlendMode")
            remove("fixedBackgroundScale")
            remove("generatedWallpaper")
        }
        file.writeText(json.toString())

        val read = requireNotNull(store.read("p1"))
        val defaults = BuildOptions()
        assertEquals(defaults.fixedTintBlendMode, read.options.fixedTintBlendMode)
        assertEquals(defaults.fixedBackgroundScale, read.options.fixedBackgroundScale, 0f)
        assertEquals(defaults.generatedWallpaper, read.options.generatedWallpaper)
        // Everything the older file did carry still comes back.
        assertEquals(FixedIconShape.SQUARE, read.options.fixedShape)
        assertEquals(2, read.iconCurves.size)
    }

    @Test
    fun `unknown enum values fall back instead of throwing`() {
        val store = store()
        store.write(sample())
        val file = File(store.dir("p1"), "project.json")
        val json = JSONObject(file.readText())
        json.getJSONObject("options").put("fixedComposition", "SOME_FUTURE_MODE")
        file.writeText(json.toString())

        val read = requireNotNull(store.read("p1"))
        assertEquals(BuildOptions().fixedComposition, read.options.fixedComposition)
    }

    @Test
    fun `null tint colour stays null rather than becoming zero`() {
        val store = store()
        store.write(sample().copy(options = BuildOptions(fixedTintColor = null)))

        assertNull(requireNotNull(store.read("p1")).options.fixedTintColor)
    }
}
