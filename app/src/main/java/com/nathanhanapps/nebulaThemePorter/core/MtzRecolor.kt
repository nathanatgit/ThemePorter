package com.nathanhanapps.nebulaThemePorter.core

import java.util.Locale

/**
 * Which entries of a MIUI icons archive a recolor rewrites, and under what name an icon generated for an app
 * the theme does not cover is added to it.
 *
 * A recolor keeps the .mtz exactly as it was found - same components, same entry order, same compression -
 * and changes only the pixels inside the "icons" component, so a theme that MIUI already accepts still
 * installs afterwards. That makes deciding what each entry *is* the whole of the format knowledge involved,
 * which is why it lives here, next to [MtzParser], rather than in the Android builder that acts on it.
 */
object MtzRecolor {
    /** Where generated icons go when the theme itself has no drawable directory to follow. */
    const val DEFAULT_DRAWABLE_DIR = "res/drawable-xxhdpi/"

    /** Appended to the source file's name so a recolor never silently replaces the theme it came from. */
    const val DEFAULT_SUFFIX = "_tinted"

    private const val FANCY_DIR = "fancy_icons/"
    private val rasterExtensions = setOf("png", "webp", "jpg", "jpeg")
    private val drawableEntry = Regex("^(res/drawable[^/]*/)([^/]+)\\.([A-Za-z0-9]+)$", RegexOption.IGNORE_CASE)

    /** What a recolor does with one entry of the icons archive. */
    enum class Use {
        /** Written back byte for byte: manifests, transform_config.xml, directory entries, icon_mask. */
        COPY,

        /** Theme-wide artwork (the icon plate, border, folder, calendar and clock frames): tinted, never reshaped. */
        ASSET,

        /** One app's icon: goes through the same render pipeline as a Nebula build, shape and all. */
        APP_ICON,
    }

    fun use(entry: String): Use {
        if (entry.endsWith('/')) return Use.COPY
        val match = drawableEntry.matchEntire(entry)
        if (match != null) {
            if (match.groupValues[3].lowercase(Locale.ROOT) !in rasterExtensions) return Use.COPY
            val name = match.groupValues[2]
            return when {
                MtzParser.isIconName(name) -> Use.APP_ICON
                // The stencil MIUI cuts unthemed icons with. Only its alpha is ever read, so recoloring it
                // would cost a decode and an encode to produce a file the launcher cannot tell apart.
                name.equals("icon_mask", ignoreCase = true) -> Use.COPY
                // Quick-settings toggles ride along in this component, and their on/off states are told apart
                // by color. One theme-wide tint would flatten that distinction, so they keep their own colors.
                name.startsWith("status_bar_", ignoreCase = true) -> Use.COPY
                else -> Use.ASSET
            }
        }
        if (!entry.startsWith(FANCY_DIR)) return Use.COPY
        return if (entry.substringAfterLast('.').lowercase(Locale.ROOT) in rasterExtensions) Use.ASSET else Use.COPY
    }

    /** The drawable name of an [Use.APP_ICON] entry: a package name, or a MIUI activity alias. */
    fun stemOf(entry: String): String? = drawableEntry.matchEntire(entry)?.groupValues?.get(2)

    /** The directory the theme keeps its icons in, so generated ones land beside them at the same density. */
    fun drawableDir(entries: List<String>): String = entries
        .filter { use(it) == Use.APP_ICON }
        .mapNotNull { drawableEntry.matchEntire(it)?.groupValues?.get(1) }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?: DEFAULT_DRAWABLE_DIR

    fun entryFor(drawableDir: String, packageName: String): String = "$drawableDir$packageName.png"

    /**
     * The installed app an existing drawable themes, if any. MIUI names most icons after the package, but
     * addresses a few individual activities by class ("com.android.contacts.activities.TwelveKeyDialer"), so a
     * stem also counts for the longest installed package it extends.
     */
    fun packageFor(stem: String, installed: Set<String>): String? {
        if (stem in installed) return stem
        return installed.filter { stem.startsWith("$it.") }.maxByOrNull { it.length }
    }

    /** Installed apps the theme has no icon for at all - the ones a recolor can generate one for. */
    fun missingPackages(stems: Collection<String>, installed: Collection<String>): List<String> {
        val installedSet = installed.toSet()
        val covered = stems.mapNotNullTo(HashSet()) { packageFor(it, installedSet) }
        return installed.distinct().filterNot { it in covered }.sorted()
    }

    /** "Aurora.mtz" becomes "Aurora_tinted.mtz"; a name that already carries the suffix is left alone. */
    fun outputName(sourceFileName: String, suffix: String = DEFAULT_SUFFIX): String {
        val cleaned = sourceFileName.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "theme.mtz" }
        val base = cleaned.substringBeforeLast('.', cleaned)
        val stem = if (base.endsWith(suffix)) base else base + suffix
        return "$stem.mtz"
    }
}
