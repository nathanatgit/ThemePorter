package com.nathanhanapps.nebulaThemePorter.core

import org.w3c.dom.Element

data class AppFilterItem(val component: AppComponent, val drawable: String)

data class CalendarPrefix(val component: AppComponent, val prefix: String)

/** The parts of an icon pack's appfilter.xml a theme can use. */
data class AppFilter(
    val items: List<AppFilterItem>,
    val iconBacks: List<String> = emptyList(),
    val iconMasks: List<String> = emptyList(),
    val iconUpons: List<String> = emptyList(),
    val scale: Float? = null,
    /** Dynamic calendar drawables are prefix + day of month (1..31). */
    val calendars: List<CalendarPrefix> = emptyList(),
) {
    val requestedDrawables: Set<String>
        get() = buildSet {
            items.forEach { add(it.drawable) }
            addAll(iconBacks)
            addAll(iconMasks)
            addAll(iconUpons)
            calendars.forEach { calendar -> (1..31).forEach { add(calendar.prefix + it) } }
        }
}

object AppFilterParser {
    private val componentPattern = Regex("ComponentInfo\\{([^/}]+)/([^}]*)\\}")

    fun parse(xml: String): AppFilter {
        val document = SafeXml.parse(xml)
        fun elements(tag: String): List<Element> {
            val nodes = document.getElementsByTagName(tag)
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }

        val items = elements("item").mapNotNull { element ->
            val component = parseComponent(element.getAttribute("component")) ?: return@mapNotNull null
            val drawable = normalizeDrawable(element.getAttribute("drawable"))
            if (drawable.isEmpty()) null else AppFilterItem(component, drawable)
        }.distinct()

        val calendars = elements("calendar").mapNotNull { element ->
            val component = parseComponent(element.getAttribute("component")) ?: return@mapNotNull null
            val prefix = element.getAttribute("prefix").trim()
            if (prefix.isEmpty()) null else CalendarPrefix(component, prefix)
        }

        return AppFilter(
            items = items,
            iconBacks = images(elements("iconback")),
            iconMasks = images(elements("iconmask")),
            iconUpons = images(elements("iconupon")),
            scale = elements("scale").firstNotNullOfOrNull { it.getAttribute("factor").trim().toFloatOrNull() },
            calendars = calendars,
        )
    }

    fun parseComponent(raw: String): AppComponent? {
        val match = componentPattern.find(raw.trim()) ?: return null
        val packageName = match.groupValues[1].trim()
        if (!AppComponent.looksLikePackage(packageName)) return null
        val className = match.groupValues[2].trim().ifEmpty { null }
        return AppComponent(packageName, className)
    }

    fun normalizeDrawable(raw: String): String {
        val name = raw.trim().substringAfterLast('/')
        return name.substringBeforeLast('.', name)
    }

    private fun images(elements: List<Element>): List<String> = elements.flatMap { element ->
        val attributes = element.attributes
        (0 until attributes.length)
            .map { attributes.item(it) }
            .filter { it.nodeName.startsWith("img") }
            .sortedBy { it.nodeName }
            .map { normalizeDrawable(it.nodeValue) }
            .filter(String::isNotEmpty)
    }.distinct()
}
