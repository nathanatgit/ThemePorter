package com.nathanhanapps.nebulaThemePorter.core

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the recolor's classification over every entry of real MIUI themes, which is the half of it that has to
 * agree with what Xiaomi actually packs. Set NEBULA_MTZ_SAMPLES to a folder of .mtz files; skipped otherwise.
 *
 * The rewrite itself needs Android's Bitmap and so cannot run here. What this pins down is that no entry of a
 * real theme falls through the classifier into the wrong bucket - a manifest treated as artwork would be
 * decoded and re-encoded into rubbish, and an app icon treated as a copy would come out untinted.
 */
class MtzRecolorArchiveTest {
    private fun samples(): List<File> {
        val dir = System.getenv("NEBULA_MTZ_SAMPLES")?.let(::File)
        val files = dir?.listFiles { file -> file.isFile && file.name.endsWith(".mtz", ignoreCase = true) }?.sorted().orEmpty()
        assumeTrue("NEBULA_MTZ_SAMPLES not set or holds no .mtz files", files.isNotEmpty())
        return files
    }

    /** Entry names of the nested icons archive, in the order the theme packs them. */
    private fun iconEntries(file: File): List<Pair<String, Int>> = ZipFile(file).use { outer ->
        val icons = outer.getEntry("icons") ?: return emptyList()
        val bytes = outer.getInputStream(icons).readBytes()
        buildList {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name to entry.method)
                }
            }
        }
    }

    @Test
    fun everyEntryOfARealThemeLandsInABucketThatMatchesItsContent() {
        samples().forEach { file ->
            val entries = iconEntries(file)
            assumeTrue("${file.name} has no icons component", entries.isNotEmpty())
            entries.forEach { (name, _) ->
                val use = MtzRecolor.use(name)
                val extension = name.substringAfterLast('.', "").lowercase()
                if (use != MtzRecolor.Use.COPY) {
                    assertTrue(
                        "${file.name}: $name is treated as artwork but is not a raster image",
                        extension in setOf("png", "webp", "jpg", "jpeg"),
                    )
                }
                if (extension == "xml" || name.endsWith("/")) {
                    assertEquals("${file.name}: $name must be copied untouched", MtzRecolor.Use.COPY, use)
                }
            }
        }
    }

    @Test
    fun aRealThemeYieldsAppIconsAndSomewhereToPutGeneratedOnes() {
        samples().forEach { file ->
            val names = iconEntries(file).map { it.first }
            assumeTrue("${file.name} has no icons component", names.isNotEmpty())
            val appIcons = names.filter { MtzRecolor.use(it) == MtzRecolor.Use.APP_ICON }
            assertTrue("${file.name}: no app icons recognised at all", appIcons.size > 10)

            val dir = MtzRecolor.drawableDir(names)
            assertTrue("${file.name}: $dir is not a drawable directory", dir.startsWith("res/drawable"))
            assertTrue("${file.name}: $dir does not end in a separator", dir.endsWith("/"))
            // Generated icons must not collide with an icon the theme already carries.
            val generated = MtzRecolor.entryFor(dir, "com.example.absent")
            assertTrue("${file.name}: generated entry collides", generated !in names)
            assertEquals(MtzRecolor.Use.APP_ICON, MtzRecolor.use(generated))
        }
    }

    /**
     * Every icon the recolor rewrites must be one the parser also sees, or a tinted theme would carry icons
     * under names MIUI never reads.
     */
    @Test
    fun theEntriesRewrittenAsAppIconsAreTheOnesTheParserCallsIcons() {
        samples().forEach { file ->
            val names = iconEntries(file).map { it.first }
            assumeTrue("${file.name} has no icons component", names.isNotEmpty())
            val parsed = MtzParser.readIcons(names) { null }.icons.values.toSet()
            val rewritten = names.filter { MtzRecolor.use(it) == MtzRecolor.Use.APP_ICON }.toSet()

            assertTrue(
                "${file.name}: ${(parsed - rewritten).take(5)} parsed as icons but not rewritten",
                parsed.all { it in rewritten },
            )
        }
    }

    /** A recolor keeps each entry's compression, so the archive still looks like the one MIUI packed. */
    @Test
    fun realThemesUseOneCompressionMethodTheRewriteCanReproduce() {
        samples().forEach { file ->
            iconEntries(file).forEach { (name, method) ->
                assertTrue(
                    "${file.name}: $name uses method $method",
                    method == ZipEntry.DEFLATED || method == ZipEntry.STORED,
                )
            }
        }
    }
}
