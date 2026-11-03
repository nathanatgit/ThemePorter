package com.nathanhanapps.nebulaThemePorter.source

import com.nathanhanapps.nebulaThemePorter.core.SourceIcon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved projects store the combined image ids, so the id scheme is a file format: the main pack's ids must stay
 * untouched and every other id must route back to the pack it came from, even when two packs share entry names.
 */
class CompositeSourceTest {

    private class FakeSource(override val fileName: String, entries: Map<String, String>) : ThemeSource {
        private val bytes = entries.mapValues { it.value.toByteArray() }
        var closed = false
        override val kind = SourceKind.MTZ
        override val suggestedLabel = fileName
        override val author = ""
        override val description = ""
        override val icons = entries.keys.map { SourceIcon(it, "com.example.${it.substringAfterLast('/')}") }
        override val extras = SourceExtras(wallpapers = listOf(SourceWallpaper("o:wall.jpg", "Wall")))
        override fun readBytes(imageId: String) = bytes[imageId]
        override fun close() {
            closed = true
        }
    }

    private val main = FakeSource("main.mtz", mapOf("i:icons/a" to "main-a", "i:icons/b" to "main-b"))
    private val extra = FakeSource("extra.mtz", mapOf("i:icons/a" to "extra-a", "i:icons/c" to "extra-c"))
    private val combined = CompositeSource(listOf(main, extra))

    @Test
    fun `main pack keeps its ids and comes first`() {
        assertEquals(listOf("i:icons/a", "i:icons/b", "src1|i:icons/a", "src1|i:icons/c"), combined.icons.map { it.id })
        assertEquals(listOf("main.mtz", "extra.mtz"), combined.partNames)
        assertEquals("main.mtz", combined.fileName)
    }

    @Test
    fun `ids route to the pack they came from even when entry names collide`() {
        assertArrayEquals("main-a".toByteArray(), combined.readBytes("i:icons/a"))
        assertArrayEquals("extra-a".toByteArray(), combined.readBytes("src1|i:icons/a"))
        assertEquals(0, combined.partOf("i:icons/a"))
        assertEquals(1, combined.partOf("src1|i:icons/c"))
    }

    @Test
    fun `an id for a pack that is not open falls back to the main pack`() {
        // e.g. a project saved with three packs, reopened as two: nothing must index past the end.
        assertEquals(0, combined.partOf("src5|i:icons/a"))
        assertNull(combined.readBytes("src5|i:icons/a"))
    }

    @Test
    fun `extra wallpapers are offered under prefixed ids and close reaches every pack`() {
        assertEquals(listOf("o:wall.jpg", "src1|o:wall.jpg"), combined.extras.wallpapers.map { it.id })
        combined.close()
        assertTrue(main.closed && extra.closed)
    }
}
