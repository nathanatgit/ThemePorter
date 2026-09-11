package com.nathanhanapps.nebulaThemePorter.core

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource

/** Text files of a theme, matching the stock formatting closely enough that diffs against stock stay readable. */
object ThemeXml {
    fun description(meta: ThemeMetadata, style: ThemeStyle, defaultShape: IconShape): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n")
        item("id", meta.id)
        item("LockScreenWallpaperType", meta.lockScreenWallpaperType.toString())
        item("label-en", meta.labelEn)
        item("label-zh-rCN", meta.labelZh)
        item("intro-en", meta.introEn)
        item("intro-zh-rCN", meta.introZh)
        item("author", meta.author)
        item("ischarge", "0")
        item("price", "0")
        item("classify-zh-rCN", "其它")
        item("classify-en", "Other")
        item("Theme Version", meta.version)
        if (style == ThemeStyle.ADAPTIVE) item("defaultIconShape", defaultShape.configName)
        item("recompiled", "true")
        if (style == ThemeStyle.ADAPTIVE) item("isSupportIconShapeChange", "true")
        append("</root>\n")
    }

    fun shapeConfig(shape: IconShape): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
            "    <string name=\"config_icon_zte\" translatable=\"false\">\"${shape.svgPath}\"</string>\n" +
            "</resources>\n"

    fun calendarInfo(style: CalendarTextStyle): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n" +
            "    <item key=\"showWeekInfo\" value=\"${if (style.showWeekInfo) 1 else 0}\" />\n" +
            "    <item key=\"paddingTop\" value=\"${style.paddingTop}\" />\n" +
            "    <item key=\"textColor\" value=\"${argbHex(style.textColor)}\" />\n" +
            "    <item key=\"textSize\" value=\"${style.textSize}\" />\n" +
            "</root>\n"

    /** Lists layered icons as packageName/className stems, one line per icon (stock theme_info_icon.xml). */
    fun iconInfo(componentStems: Collection<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n")
        componentStems.filter { '-' in it }.distinct().forEach { stem ->
            append("    <item packageName=\"").append(escape(stem.substringBefore('-')))
                .append("\" className=\"").append(escape(stem.substringAfter('-'))).append("\"/>\n")
        }
        append("</root>\n")
    }

    /** overlapBg=1 makes the launcher draw themed icons over theme_bg_icon (community fixed-shape ports). */
    fun themeInfo(overlapBackground: Boolean): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n" +
            "    <item key=\"overlapBg\" value=\"${if (overlapBackground) 1 else 0}\" />\n" +
            "</root>\n"

    fun escape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> Unit
                else -> if (c >= ' ' || c == '\t') append(c)
            }
        }
    }

    private fun StringBuilder.item(key: String, value: String) {
        append("  <item key=\"").append(key).append("\" value=\"").append(escape(value)).append("\"/>\n")
    }
}

/** DOM parsing with DTDs and external entities disabled; theme and icon pack XML is untrusted input. */
internal object SafeXml {
    fun parse(xml: String): Document {
        val text = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) {
            "XML with a document type declaration is not supported."
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.setExpandEntityReferences(false) }
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    }

    private fun setFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
            // Android's XML providers support different feature sets; the DOCTYPE check above still applies.
        }
    }
}
