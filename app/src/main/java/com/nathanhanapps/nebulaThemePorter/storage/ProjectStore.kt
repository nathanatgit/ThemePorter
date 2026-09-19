package com.nathanhanapps.nebulaThemePorter.storage

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a saved project finds its source pack/theme again. The file itself is referenced, never copied - a pack
 * APK or .mtz runs to tens of megabytes, so copying one per project would cost more storage than the themes
 * they build. The trade is that a source the user later moves, deletes or uninstalls can no longer be opened -
 * which surfaces as a "can't find the source" message and a fresh import, not as losing the tweaks.
 */
data class ProjectSource(
    val kind: SourceKind,
    /** An installed icon pack, re-resolved by package name: an app update moves its APK path. */
    val packageName: String? = null,
    /** A real path on this phone, when All-files access let the source be read in place. */
    val path: String? = null,
    /** A picked document, kept openable by a persistable read grant taken when it was first opened. */
    val uri: String? = null,
    val fileName: String,
)

/**
 * Everything a user tweaked, saved so the same pack can be reopened and adjusted later instead of re-curving
 * every icon from scratch. Deliberately only the durable half of PorterState - what was imported, what was
 * decided about it, and the two images that aren't in the source - never the loaded [SourceKind] contents,
 * decoded bitmaps, planning counts or in-flight progress, all of which are recomputed on open.
 */
data class SavedProject(
    val id: String,
    val name: String,
    val savedAt: Long,
    val source: ProjectSource,
    val labelZh: String = "",
    val labelEn: String = "",
    val author: String = "",
    val intro: String = "",
    val outputName: String = "",
    val options: BuildOptions = BuildOptions(),
    val assignments: Map<String, String> = emptyMap(),
    val userAssignments: Map<String, String> = emptyMap(),
    val iconCurves: Map<String, GrayscaleCurve> = emptyMap(),
    val selectedWallpaperId: String? = null,
    val wallpaperName: String? = null,
    val fixedBackgroundName: String? = null,
)

/**
 * Saved projects on disk, one directory each under `filesDir/projects`. Kept in `filesDir` rather than the
 * cache the imported wallpaper/background currently live in, since Android may evict a cache directory at any
 * time and a saved project has to outlive that.
 */
class ProjectStore(private val root: File) {

    fun dir(id: String): File = File(root, id)

    /** The custom wallpaper image, copied out of the cache so it survives eviction. */
    fun wallpaperFile(id: String): File = File(dir(id), "wallpaper")

    /** The custom fixed-icon background image, likewise. */
    fun backgroundFile(id: String): File = File(dir(id), "background")

    fun previewFile(id: String): File = File(dir(id), "preview.png")

    fun newId(): String = "p${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}"

    /** Newest first, skipping any directory whose JSON is missing or unreadable. */
    fun list(): List<SavedProject> =
        root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { read(it.name) }
            .sortedByDescending(SavedProject::savedAt)

    fun read(id: String): SavedProject? = runCatching {
        val json = JSONObject(File(dir(id), FILE).readText())
        fromJson(id, json)
    }.getOrNull()

    fun write(project: SavedProject) {
        dir(project.id).mkdirs()
        File(dir(project.id), FILE).writeText(toJson(project).toString())
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    // --- JSON ---------------------------------------------------------------------------------------------
    // Hand-rolled over org.json rather than a serialization dependency: the shape is small, and the reader
    // falls back to each field's own default so a project written by an older version still opens after new
    // BuildOptions fields are added (VERSION exists to make a genuinely breaking change detectable).

    private fun toJson(p: SavedProject) = JSONObject().apply {
        put("version", VERSION)
        put("name", p.name)
        put("savedAt", p.savedAt)
        put("source", JSONObject().apply {
            put("kind", p.source.kind.name)
            p.source.packageName?.let { put("packageName", it) }
            p.source.path?.let { put("path", it) }
            p.source.uri?.let { put("uri", it) }
            put("fileName", p.source.fileName)
        })
        put("labelZh", p.labelZh)
        put("labelEn", p.labelEn)
        put("author", p.author)
        put("intro", p.intro)
        put("outputName", p.outputName)
        put("options", optionsToJson(p.options))
        put("assignments", JSONObject(p.assignments.toMap()))
        put("userAssignments", JSONObject(p.userAssignments.toMap()))
        put("iconCurves", JSONObject().apply {
            p.iconCurves.forEach { (key, curve) ->
                put(key, JSONArray().apply {
                    curve.points.forEach { point -> put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) }
                })
            }
        })
        p.selectedWallpaperId?.let { put("selectedWallpaperId", it) }
        p.wallpaperName?.let { put("wallpaperName", it) }
        p.fixedBackgroundName?.let { put("fixedBackgroundName", it) }
    }

    private fun fromJson(id: String, json: JSONObject): SavedProject {
        val source = json.getJSONObject("source")
        return SavedProject(
            id = id,
            name = json.optString("name", id),
            savedAt = json.optLong("savedAt", 0L),
            source = ProjectSource(
                kind = runCatching { SourceKind.valueOf(source.getString("kind")) }.getOrDefault(SourceKind.ICON_PACK),
                packageName = source.optStringOrNull("packageName"),
                path = source.optStringOrNull("path"),
                uri = source.optStringOrNull("uri"),
                fileName = source.optString("fileName", ""),
            ),
            labelZh = json.optString("labelZh", ""),
            labelEn = json.optString("labelEn", ""),
            author = json.optString("author", ""),
            intro = json.optString("intro", ""),
            outputName = json.optString("outputName", ""),
            options = json.optJSONObject("options")?.let(::optionsFromJson) ?: BuildOptions(),
            assignments = json.optJSONObject("assignments").toStringMap(),
            userAssignments = json.optJSONObject("userAssignments").toStringMap(),
            iconCurves = json.optJSONObject("iconCurves").toCurveMap(),
            selectedWallpaperId = json.optStringOrNull("selectedWallpaperId"),
            wallpaperName = json.optStringOrNull("wallpaperName"),
            fixedBackgroundName = json.optStringOrNull("fixedBackgroundName"),
        )
    }

    private fun optionsToJson(o: BuildOptions) = JSONObject().apply {
        put("fixedShape", o.fixedShape.name)
        put("fixedComposition", o.fixedComposition.name)
        o.fixedTintColor?.let { put("fixedTintColor", it) }
        put("fixedTintStrength", o.fixedTintStrength.toDouble())
        put("fixedTintBlendMode", o.fixedTintBlendMode.name)
        put("fixedIconScale", o.fixedIconScale.toDouble())
        put("fixedIconAlpha", o.fixedIconAlpha.toDouble())
        put("fixedBackgroundScale", o.fixedBackgroundScale.toDouble())
        put("padBackground", o.padBackground)
        put("onlyInstalledApps", o.onlyInstalledApps)
        put("generateMissingDynamicIcons", o.generateMissingDynamicIcons)
        put("generateMissingAppIcons", o.generateMissingAppIcons)
        put("generatedIconOwnBackground", o.generatedIconOwnBackground)
        put("generatedWallpaper", JSONObject().apply {
            put("colorA", o.generatedWallpaper.colorA)
            put("colorB", o.generatedWallpaper.colorB)
            put("colorC", o.generatedWallpaper.colorC)
            put("blur", o.generatedWallpaper.blur.toDouble())
            put("mix", o.generatedWallpaper.mix.name)
            put("angle", o.generatedWallpaper.angle.toDouble())
            put("seed", o.generatedWallpaper.seed)
        })
    }

    private fun optionsFromJson(json: JSONObject): BuildOptions {
        val d = BuildOptions()
        val gw = json.optJSONObject("generatedWallpaper")
        val dw = d.generatedWallpaper
        return d.copy(
            fixedShape = json.enum("fixedShape", d.fixedShape, FixedIconShape::valueOf),
            fixedComposition = json.enum("fixedComposition", d.fixedComposition, FixedIconComposition::valueOf),
            fixedTintColor = if (json.has("fixedTintColor")) json.getLong("fixedTintColor") else null,
            fixedTintStrength = json.optDouble("fixedTintStrength", d.fixedTintStrength.toDouble()).toFloat(),
            fixedTintBlendMode = json.enum("fixedTintBlendMode", d.fixedTintBlendMode, TintBlendMode::valueOf),
            fixedIconScale = json.optDouble("fixedIconScale", d.fixedIconScale.toDouble()).toFloat(),
            fixedIconAlpha = json.optDouble("fixedIconAlpha", d.fixedIconAlpha.toDouble()).toFloat(),
            fixedBackgroundScale = json.optDouble("fixedBackgroundScale", d.fixedBackgroundScale.toDouble()).toFloat(),
            padBackground = json.optLong("padBackground", d.padBackground),
            onlyInstalledApps = json.optBoolean("onlyInstalledApps", d.onlyInstalledApps),
            generateMissingDynamicIcons = json.optBoolean("generateMissingDynamicIcons", d.generateMissingDynamicIcons),
            generateMissingAppIcons = json.optBoolean("generateMissingAppIcons", d.generateMissingAppIcons),
            generatedIconOwnBackground = json.optBoolean("generatedIconOwnBackground", d.generatedIconOwnBackground),
            generatedWallpaper = if (gw == null) dw else GeneratedWallpaper(
                colorA = gw.optLong("colorA", dw.colorA),
                colorB = gw.optLong("colorB", dw.colorB),
                colorC = gw.optLong("colorC", dw.colorC),
                blur = gw.optDouble("blur", dw.blur.toDouble()).toFloat(),
                mix = gw.enum("mix", dw.mix, GradientMix::valueOf),
                angle = gw.optDouble("angle", dw.angle.toDouble()).toFloat(),
                seed = gw.optInt("seed", dw.seed),
            ),
        )
    }

    private fun <T> JSONObject.enum(key: String, fallback: T, parse: (String) -> T): T =
        optStringOrNull(key)?.let { runCatching { parse(it) }.getOrNull() } ?: fallback

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifEmpty { null }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        this ?: return emptyMap()
        return buildMap { keys().forEach { key -> optStringOrNull(key)?.let { put(key, it) } } }
    }

    private fun JSONObject?.toCurveMap(): Map<String, GrayscaleCurve> {
        this ?: return emptyMap()
        return buildMap {
            keys().forEach { key ->
                val points = optJSONArray(key) ?: return@forEach
                val parsed = (0 until points.length()).mapNotNull { i ->
                    points.optJSONArray(i)?.takeIf { it.length() >= 2 }
                        ?.let { CurvePoint(it.optDouble(0).toFloat(), it.optDouble(1).toFloat()) }
                }
                if (parsed.size >= 2) put(key, GrayscaleCurve(parsed))
            }
        }
    }

    companion object {
        /** Under `filesDir`, not the cache the imported images currently sit in: a cache can be evicted at any
         * time, and a saved project has to outlive that. */
        fun forApp(context: Context): ProjectStore = ProjectStore(File(context.filesDir, "projects"))

        private const val FILE = "project.json"

        /** Bumped only for a change the reader above cannot absorb by falling back to a default. */
        private const val VERSION = 1
    }
}
