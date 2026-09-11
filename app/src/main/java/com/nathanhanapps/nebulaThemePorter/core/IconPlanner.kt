package com.nathanhanapps.nebulaThemePorter.core

import java.util.Locale

/** One icon file to write; layered or flat is decided by the theme style, not per icon. */
data class PlannedIcon(val stem: String, val sourceId: String) {
    val isComponent: Boolean get() = '-' in stem
}

data class IconPlan(
    val icons: List<PlannedIcon>,
    /** ZteSystemApp.id to SourceIcon.id. */
    val systemAssignments: Map<String, String>,
)

/**
 * Decides which file names each source image is written under.
 *
 * Priority, first writer wins: manual launcher-app assignments, ZTE system app assignments, exact components
 * from the source, launcher activities installed on this device, activities known from stock themes, then
 * package-only fallbacks.
 */
class IconPlanner(
    private val stock: StockComponentIndex,
    /** Package name to launcher activity class names on this device. */
    private val installedLaunchers: Map<String, List<String>>,
) {
    fun autoAssignSystemApps(sources: List<SourceIcon>): Map<String, String> {
        val byKey = LinkedHashMap<String, SourceIcon>()
        sources.forEach { byKey.putIfAbsent(it.key.lowercase(Locale.ROOT), it) }
        return buildMap {
            ZteSystemApps.all.forEach { app ->
                app.aliases.firstNotNullOfOrNull { byKey[it.lowercase(Locale.ROOT)] }?.let { put(app.id, it.id) }
            }
        }
    }

    fun plan(
        sources: List<SourceIcon>,
        systemAssignments: Map<String, String>,
        onlyInstalledApps: Boolean,
        manualAssignments: Map<String, String> = emptyMap(),
        generateMissingAppIcons: Boolean = false,
    ): IconPlan {
        val sourceIds = sources.mapTo(HashSet()) { it.id }
        val output = LinkedHashMap<String, PlannedIcon>()
        fun add(stem: String, sourceId: String) {
            if (AppComponent.isSafeStem(stem)) output.putIfAbsent(stem, PlannedIcon(stem, sourceId))
        }

        manualAssignments.filterValues { it in sourceIds }.forEach { (stem, sourceId) -> add(stem, sourceId) }

        val assignments = systemAssignments.filterValues { it in sourceIds }
        ZteSystemApps.all.forEach { app ->
            val sourceId = assignments[app.id] ?: return@forEach
            app.stems.forEach { add(it, sourceId) }
        }

        val eligible = sources.filter { source ->
            val packageName = source.packageName()
            AppComponent.looksLikePackage(packageName) &&
                (!onlyInstalledApps || installedLaunchers[packageName].orEmpty().isNotEmpty())
        }

        // Exact components first so an icon pack entry for the current activity beats an entry for an old one.
        eligible.forEach { source ->
            source.component?.takeIf { it.className != null }?.let { add(it.stem, source.id) }
        }
        eligible.forEach { source ->
            val packageName = source.packageName()
            val installed = installedLaunchers[packageName].orEmpty()
            val stems = if (installed.isNotEmpty()) {
                installed.map { AppComponent(packageName, it).stem }
            } else {
                stock.stemsFor(AppComponent.sanitize(packageName))
            }
            stems.forEach { add(it, source.id) }
        }
        val fallbacks = HashSet<String>()
        eligible.forEach { source ->
            val packageName = source.packageName()
            // MIUI alias names such as com.android.contacts.activities.TwelveKeyDialer are not packages here.
            if (ZteSystemApps.matchAlias(source.key) != null && packageName !in installedLaunchers) return@forEach
            if (fallbacks.add(packageName)) add(AppComponent.sanitize(packageName), source.id)
        }

        // Last resort: apps the pack/theme has no artwork for at all get a temp resource generated from their
        // own launcher icon, so background/tint still apply instead of the launcher's plain fallback plate.
        if (generateMissingAppIcons) {
            installedLaunchers.forEach { (packageName, classNames) ->
                classNames.forEach { className -> add(AppComponent(packageName, className).stem, DeviceIconId.of(packageName, className)) }
            }
        }
        return IconPlan(output.values.toList(), assignments)
    }

    private fun SourceIcon.packageName(): String = component?.packageName ?: key
}
