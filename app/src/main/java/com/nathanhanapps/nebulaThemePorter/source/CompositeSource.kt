package com.nathanhanapps.nebulaThemePorter.source

import com.nathanhanapps.nebulaThemePorter.core.SourceIcon

/**
 * Several sources opened together. The first is the main one: its image ids are kept as they are, so a project
 * saved from it alone still resolves, and its icons come first so the planner (first writer wins) prefers them.
 * Icons from the others are exposed under prefixed ids so the user can pick them by hand in the app list.
 */
class CompositeSource(private val sources: List<ThemeSource>) : ThemeSource {
    init {
        require(sources.isNotEmpty())
    }

    private val primary = sources.first()

    override val kind get() = primary.kind
    override val fileName: String = sources.joinToString(", ") { it.fileName }
    override val suggestedLabel get() = primary.suggestedLabel
    override val author get() = primary.author
    override val description get() = primary.description

    override val icons: List<SourceIcon> = sources.flatMapIndexed { index, source ->
        source.icons.map { if (index == 0) it else it.copy(id = prefixed(index, it.id)) }
    }

    override val extras: SourceExtras = primary.extras.let { base ->
        base.copy(
            wallpapers = base.wallpapers + sources.drop(1).flatMapIndexed { i, source ->
                source.extras.wallpapers.map { it.copy(id = prefixed(i + 1, it.id)) }
            },
        )
    }

    override fun readBytes(imageId: String): ByteArray? {
        val match = PREFIX.matchEntire(imageId)
        val index = match?.groupValues?.get(1)?.toIntOrNull()
        return if (match != null && index != null && index in 1 until sources.size) {
            sources[index].readBytes(match.groupValues[2])
        } else {
            primary.readBytes(imageId)
        }
    }

    override fun close() = sources.forEach { runCatching { it.close() } }

    private fun prefixed(index: Int, id: String) = "src$index|$id"

    private companion object {
        val PREFIX = Regex("^src(\\d+)\\|(.*)$", RegexOption.DOT_MATCHES_ALL)
    }
}
