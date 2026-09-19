package com.nathanhanapps.nebulaThemePorter.core

import java.util.Locale
import org.w3c.dom.Element

data class MtzMetadata(
    val title: String,
    val author: String,
    val designer: String,
    val description: String,
)

/** Top level of a MIUI/HyperOS .mtz archive. Entry names refer to that archive. */
data class MtzPackage(
    val metadata: MtzMetadata,
    /** The "icons" entry is itself a ZIP archive. */
    val iconsEntry: String?,
    val wallpaperEntry: String?,
    val lockWallpaperEntry: String?,
    val wallpaperEntries: List<String>,
    val previewEntries: List<String>,
)

data class MtzCalendar(
    /** Day of month to entry; these are complete icons with the date drawn in. */
    val dayEntries: Map<Int, String>,
    val backgroundEntry: String?,
    val canvasSize: Int,
    val textColor: Long?,
    val textSize: Float?,
    val showWeek: Boolean,
)

/** Hands point at 12 o'clock and share the dial's canvas (angle="#minute*6" in the MIUI manifest). */
data class MtzClock(
    val dialEntry: String?,
    val hourEntry: String?,
    val minuteEntry: String?,
    val canvasSize: Int,
)

/** Contents of the nested icons archive. Entry names refer to that inner archive. */
data class MtzIcons(
    /** Drawable name (package or MIUI alias such as com.android.contacts.activities.TwelveKeyDialer) to entry. */
    val icons: Map<String, String>,
    val maskEntry: String?,
    val patternEntry: String?,
    val borderEntry: String?,
    val folderEntry: String?,
    val calendar: MtzCalendar?,
    val clock: MtzClock?,
    /** Icon scale from transform_config.xml (1.0 means the artwork fills the 90-unit grid). */
    val iconScale: Float?,
)

object MtzParser {
    private val drawablePattern = Regex("^res/drawable(?:-([A-Za-z0-9-]+))?/([^/]+)\\.(png|webp|jpg|jpeg)$", RegexOption.IGNORE_CASE)
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val dayPattern = Regex("^(?:calendar|date|day)_?(\\d{1,2})\\.png$", RegexOption.IGNORE_CASE)
    private const val CALENDAR_DIR = "fancy_icons/com.android.calendar/"
    private const val CLOCK_DIR = "fancy_icons/com.android.deskclock/"

    fun readPackage(entries: List<String>, readText: (String) -> String?): MtzPackage {
        val names = entries.toSet()
        val metadata = readText("description.xml")?.let { runCatching { parseDescription(it) }.getOrNull() }
            ?: MtzMetadata("", "", "", "")
        fun firstImage(base: String) = imageExtensions.map { "$base.$it" }.firstOrNull { it in names }
        val wallpaperEntries = entries
            .filter { it.startsWith("wallpaper/", ignoreCase = true) && it.substringAfterLast('.').lowercase(Locale.ROOT) in imageExtensions }
            .distinct()
            .sortedWith(compareBy<String> {
                when {
                    it.substringBeforeLast('.').equals("wallpaper/default_wallpaper", ignoreCase = true) -> 0
                    it.substringBeforeLast('.').equals("wallpaper/default_lock_wallpaper", ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { it.lowercase(Locale.ROOT) })
        return MtzPackage(
            metadata = metadata,
            iconsEntry = "icons".takeIf { it in names },
            wallpaperEntry = firstImage("wallpaper/default_wallpaper"),
            lockWallpaperEntry = firstImage("wallpaper/default_lock_wallpaper"),
            wallpaperEntries = wallpaperEntries,
            previewEntries = entries
                .filter { it.startsWith("preview/") && it.substringAfterLast('.').lowercase(Locale.ROOT) in imageExtensions }
                .sorted(),
        )
    }

    fun parseDescription(xml: String): MtzMetadata {
        val root = SafeXml.parse(xml).documentElement
        fun direct(tag: String): String {
            val children = root.childNodes
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node is Element && node.tagName == tag) return node.textContent.trim()
            }
            return ""
        }
        return MtzMetadata(direct("title"), direct("author"), direct("designer"), direct("description"))
    }

    fun readIcons(entries: List<String>, readText: (String) -> String?): MtzIcons {
        val best = HashMap<String, Pair<Int, String>>()
        entries.forEach { entry ->
            val match = drawablePattern.matchEntire(entry) ?: return@forEach
            val score = densityScore(match.groupValues[1])
            val name = match.groupValues[2]
            val previous = best[name]
            if (previous == null || score > previous.first) best[name] = score to entry
        }
        val drawables = best.mapValues { it.value.second }
        val icons = drawables.filterKeys(::isIconName).toSortedMap()

        return MtzIcons(
            icons = icons,
            maskEntry = drawables["icon_mask"],
            patternEntry = drawables["icon_pattern"],
            borderEntry = drawables["icon_border"],
            folderEntry = drawables["icon_folder"],
            calendar = readCalendar(entries, readText),
            clock = readClock(entries, readText),
            iconScale = readText("transform_config.xml")?.let { runCatching { parseTransformScale(it) }.getOrNull() },
        )
    }

    /**
     * Whether a drawable in the icons component is one app's icon rather than theme-wide artwork: the mask,
     * plate, border and folder assets, the quick-settings toggles and the density-qualified variants all share
     * the directory with them. Also what [MtzRecolor] classifies entries by, so a recolor treats exactly the
     * files this parser calls icons as icons.
     */
    fun isIconName(name: String): Boolean =
        '-' !in name && !name.startsWith("icon_") && !name.startsWith("status_bar_") &&
            !name.startsWith("folder_") && AppComponent.looksLikePackage(name)

    fun parseTransformScale(xml: String): Float? {
        val points = SafeXml.parse(xml).getElementsByTagName("Point")
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (index in 0 until points.length) {
            val point = points.item(index) as? Element ?: continue
            val x = point.getAttribute("toX").toFloatOrNull() ?: continue
            min = minOf(min, x)
            max = maxOf(max, x)
        }
        if (min == Float.MAX_VALUE || max <= min) return null
        return ((max - min) / 90f).coerceIn(0.5f, 1.25f)
    }

    fun parseColor(raw: String): Long? {
        val hex = raw.trim().removePrefix("#")
        val value = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> 0xFF000000L or value
            8 -> value
            else -> null
        }
    }

    private fun readCalendar(entries: List<String>, readText: (String) -> String?): MtzCalendar? {
        val files = entries.filter { it.startsWith(CALENDAR_DIR) && '/' !in it.removePrefix(CALENDAR_DIR) }
        if (files.isEmpty()) return null
        val days = files.mapNotNull { entry ->
            val day = dayPattern.matchEntire(entry.substringAfterLast('/'))?.groupValues?.get(1)?.toInt()
            day?.takeIf { it in 1..31 }?.let { it to entry }
        }.toMap()
        val background = files.firstOrNull { it.substringAfterLast('/').lowercase(Locale.ROOT) in setOf("bg.png", "calendar_bg.png", "background.png") }
        if (days.isEmpty() && background == null) return null

        var size = 168
        var color: Long? = null
        var textSize: Float? = null
        var showWeek = false
        readText(CALENDAR_DIR + "manifest.xml")?.let { xml ->
            runCatching {
                val document = SafeXml.parse(xml)
                document.documentElement.getAttribute("width").toFloatOrNull()?.let { size = it.toInt() }
                val texts = document.getElementsByTagName("Text")
                val dateText = (0 until texts.length).mapNotNull { texts.item(it) as? Element }
                    .firstOrNull { "date" in it.getAttribute("textExp") } ?: texts.item(0) as? Element
                dateText?.let {
                    color = parseColor(it.getAttribute("color"))
                    textSize = it.getAttribute("size").toFloatOrNull()
                }
                val dateTimes = document.getElementsByTagName("DateTime")
                showWeek = (0 until dateTimes.length).mapNotNull { dateTimes.item(it) as? Element }
                    .any { "E" in it.getAttribute("format") && (it.getAttribute("size").toFloatOrNull() ?: 1f) > 0f }
            }
        }
        return MtzCalendar(days, background, size, color, textSize, showWeek)
    }

    private fun readClock(entries: List<String>, readText: (String) -> String?): MtzClock? {
        val files = entries.filter { it.startsWith(CLOCK_DIR) && it.endsWith(".png", ignoreCase = true) }
        if (files.isEmpty()) return null
        fun find(vararg needles: String) = files.firstOrNull { entry ->
            val name = entry.substringAfterLast('/').lowercase(Locale.ROOT)
            needles.any { name.contains(it) }
        }
        val hour = find("hour")
        val minute = find("minute", "min")
        val dial = files.firstOrNull { it.substringAfterLast('/').lowercase(Locale.ROOT) in setOf("bg.png", "dial.png", "clock_bg.png", "background.png") }
        if (hour == null || minute == null) return null
        val size = readText(CLOCK_DIR + "manifest.xml")?.let { xml ->
            runCatching { SafeXml.parse(xml).documentElement.getAttribute("width").toFloatOrNull()?.toInt() }.getOrNull()
        } ?: 168
        return MtzClock(dial, hour, minute, size)
    }

    private fun densityScore(qualifier: String): Int {
        val q = qualifier.lowercase(Locale.ROOT)
        return when {
            "xxxhdpi" in q -> 6
            "xxhdpi" in q -> 5
            "xhdpi" in q -> 4
            "nodpi" in q -> 3
            "hdpi" in q -> 2
            else -> 1
        }
    }
}
