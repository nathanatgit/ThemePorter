package com.nathanhanapps.nebulaThemePorter.build

import android.content.Context
import android.graphics.Bitmap
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.IconPlan
import com.nathanhanapps.nebulaThemePorter.core.IconMaskShape
import com.nathanhanapps.nebulaThemePorter.core.IconShape
import com.nathanhanapps.nebulaThemePorter.core.IconsZipWriter
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.ShapeAsset
import com.nathanhanapps.nebulaThemePorter.core.ThemeArchiveParts
import com.nathanhanapps.nebulaThemePorter.core.ThemeArchiveWriter
import com.nathanhanapps.nebulaThemePorter.core.ThemeLayout
import com.nathanhanapps.nebulaThemePorter.core.ThemeMetadata
import com.nathanhanapps.nebulaThemePorter.core.ThemeStyle
import com.nathanhanapps.nebulaThemePorter.core.ThemeXml
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApps
import com.nathanhanapps.nebulaThemePorter.render.Bitmaps
import com.nathanhanapps.nebulaThemePorter.render.DynamicIcons
import com.nathanhanapps.nebulaThemePorter.render.FixedIconArt
import com.nathanhanapps.nebulaThemePorter.render.IconLayering
import com.nathanhanapps.nebulaThemePorter.render.PreviewRenderer
import com.nathanhanapps.nebulaThemePorter.render.Shapes
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.source.ThemeSource
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class BuildProgress(val done: Int, val total: Int, val label: String) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

data class BuildSummary(val iconFiles: Int, val sourceImages: Int, val fileNames: Int, val bytes: Long)

/** Renders every asset of a theme and writes the .zmtp to the stream from `openOutput`. */
class ThemeBuilder(private val context: Context) {
    private class RenderedIcon(val files: List<Pair<String, ByteArray>>, val stems: List<String>, val thumbnail: Bitmap)

    private val showcaseStems: Set<String> = buildSet {
        addAll(NebulaSpec.PREVIEW_STEMS)
        ZteSystemApps.all.forEach { addAll(it.stems) }
    }

    suspend fun build(
        source: ThemeSource,
        metadata: ThemeMetadata,
        options: BuildOptions,
        plan: IconPlan,
        wallpaperOverride: File?,
        wallpaperSourceId: String?,
        fixedBackgroundOverride: File?,
        openOutput: () -> OutputStream,
        onProgress: (BuildProgress) -> Unit,
    ): BuildSummary = withContext(Dispatchers.Default) {
        val workDir = File(context.cacheDir, "build").apply {
            deleteRecursively()
            mkdirs()
        }
        val iconsZip = File(workDir, "icons_cur.zip")
        val style = options.style
        val systemShapes = ThemeLayout.shapes(style, options.defaultShape)
        // Fixed themes carry no per-shape config variants. Their visible assets therefore follow the same baked
        // outline as app icons, including custom outlines the system itself cannot select.
        val fixedVisualShape: IconMaskShape = options.fixedShape.takeUnless { it.isOriginal } ?: options.defaultShape
        val assetShapes: List<IconMaskShape> = if (style == ThemeStyle.FIXED) listOf(fixedVisualShape) else systemShapes
        // A generated device icon has no shape or plate of its own, unlike real pack/theme artwork, so it can
        // never use FixedIconShape.NONE's "keep the imported PNG as-is" passthrough - fall back to the same
        // outline the unthemed-app background plate already uses.
        val fallbackFixedShape: FixedIconShape = options.fixedShape.takeUnless { it.isOriginal } ?: options.defaultShape.toFixedShape()
        val extras = source.extras
        val iconsBySource = plan.icons.groupBy { it.sourceId }
        val total = iconsBySource.size + 8
        var done = 0
        fun step(label: String) = onProgress(BuildProgress(++done, total, label))

        val sourceIconBack = visible(source, extras.iconBack, NebulaSpec.LAYER_SIZE)
        val customFixedBack = if (style == ThemeStyle.FIXED) {
            fixedBackgroundOverride?.let { file ->
                runCatching { Bitmaps.decode(file.readBytes(), NebulaSpec.LAYER_SIZE) }.getOrNull()
                    ?.takeIf { Bitmaps.alphaBounds(it, threshold = 8) != null }
            }
        } else {
            null
        }
        val iconBack = customFixedBack ?: sourceIconBack
        val showcase = LinkedHashMap<String, Bitmap>()
        var fileCount: Int

        iconsZip.outputStream().use { raw ->
            IconsZipWriter(raw).use { zip ->
                systemShapes.forEach { zip.putText(ThemeLayout.configFileName(style, it), ThemeXml.shapeConfig(it)) }

                val sourceMask = if (style == ThemeStyle.FIXED) visible(source, extras.iconMask, NebulaSpec.ASSET_SIZE) else null
                val sourceFolder = visible(source, extras.folderIcon, NebulaSpec.LAYER_SIZE)
                assetShapes.forEach { shape ->
                    val mask = if (style == ThemeStyle.FIXED && !options.fixedShape.isOriginal) {
                        Shapes.mask(shape, NebulaSpec.ASSET_SIZE)
                    } else {
                        sourceMask?.let { Bitmaps.fit(it, NebulaSpec.ASSET_SIZE) } ?: Shapes.mask(shape, NebulaSpec.ASSET_SIZE)
                    }
                    zip.putPng(ShapeAsset.MASK.fileName(style, shape), Bitmaps.png(mask))
                    val folder = when {
                        style == ThemeStyle.FIXED && !options.fixedShape.isOriginal -> sourceFolder?.let {
                            Shapes.clip(Bitmaps.trimToSquare(it) ?: it, shape, NebulaSpec.LAYER_SIZE)
                        }
                        style == ThemeStyle.FIXED -> sourceFolder?.let { Bitmaps.fit(it, NebulaSpec.LAYER_SIZE) }
                        sourceFolder != null -> Shapes.clip(Bitmaps.trimToSquare(sourceFolder) ?: sourceFolder, shape, NebulaSpec.LAYER_SIZE)
                        else -> Shapes.folderIcon(shape, NebulaSpec.LAYER_SIZE)
                    }
                    folder?.let { zip.putPng(ShapeAsset.FOLDER_ICON.fileName(style, shape), Bitmaps.png(it)) }
                    zip.putPng(ShapeAsset.FOLDER_ADD.fileName(style, shape), Bitmaps.png(Shapes.folderAdd(shape, NebulaSpec.ASSET_SIZE)))
                }
                step(context.getString(R.string.progress_shapes))

                val clipSource = style == ThemeStyle.ADAPTIVE || (style == ThemeStyle.FIXED && !options.fixedShape.isOriginal)
                val dynamicTintColor = if (style == ThemeStyle.FIXED) options.fixedTintColor?.toInt() else null
                if (extras.calendar != null || options.generateMissingDynamicIcons) {
                    val art = DynamicIcons.loadCalendar(source, extras.calendar, dynamicTintColor, options.fixedTintStrength)
                    assetShapes.forEach { shape ->
                        zip.putPng(ShapeAsset.DYNAMIC_CALENDAR.fileName(style, shape), Bitmaps.png(DynamicIcons.calendarStrip(art, shape, clipSource)))
                    }
                    zip.putText(NebulaSpec.CALENDAR_INFO, ThemeXml.calendarInfo(DynamicIcons.calendarTextStyle(art)))
                }
                step(context.getString(R.string.progress_calendar))
                if (extras.clock != null || options.generateMissingDynamicIcons) {
                    val art = DynamicIcons.loadClock(source, extras.clock, dynamicTintColor, options.fixedTintStrength)
                    assetShapes.forEach { shape ->
                        zip.putPng(ShapeAsset.DYNAMIC_CLOCK.fileName(style, shape), Bitmaps.png(DynamicIcons.clockStrip(art, shape, clipSource, dynamicTintColor)))
                    }
                }
                step(context.getString(R.string.progress_clock))

                val transparentFront = Bitmaps.png(Bitmaps.square(NebulaSpec.LAYER_SIZE))
                iconsBySource.entries.chunked(8).forEach { chunk ->
                    ensureActive()
                    val rendered = coroutineScope {
                        chunk.map { (sourceId, planned) ->
                            async { renderIcon(source, sourceId, planned.map { it.stem }, options, iconBack, transparentFront, fallbackFixedShape) }
                        }.awaitAll()
                    }
                    rendered.forEach { icon ->
                        icon ?: return@forEach
                        icon.files.forEach { (name, bytes) -> zip.putPng(name, bytes) }
                        val keep = icon.stems.firstOrNull { it in showcaseStems } ?: icon.stems.first().takeIf { showcase.size < 40 }
                        if (keep != null && keep !in showcase) showcase[keep] = icon.thumbnail else icon.thumbnail.recycle()
                    }
                    done += chunk.size
                    onProgress(BuildProgress(done, total, context.getString(R.string.progress_icons, done - 3, iconsBySource.size)))
                }

                if (style == ThemeStyle.ADAPTIVE) zip.putText(NebulaSpec.ICON_INFO, ThemeXml.iconInfo(plan.componentStems))
                // The launcher only composites an unthemed app icon on theme_bg_icon when overlapBg is set.
                // Write it for every selected fixed outline, including packs without an iconback resource.
                if (style == ThemeStyle.FIXED && !options.fixedShape.isOriginal) {
                    zip.putText(NebulaSpec.THEME_INFO, ThemeXml.themeInfo(true))
                }
                val shortcut = when (style) {
                    ThemeStyle.ADAPTIVE -> DynamicIcons.shortcutBackground(NebulaSpec.LAYER_SIZE, null)
                    ThemeStyle.FIXED -> DynamicIcons.shortcutBackground(NebulaSpec.ASSET_SIZE, fixedVisualShape)
                }
                zip.putPng(NebulaSpec.SHORTCUT_BG, Bitmaps.png(shortcut))
                writeBackgroundIcons(zip, options, iconBack, fixedVisualShape)
                if (style == ThemeStyle.FIXED) {
                    visible(source, extras.iconUpon, NebulaSpec.FIXED_ICON_SIZE)?.let {
                        zip.putPng(NebulaSpec.ICON_EFFECT_TOP, Bitmaps.png(Bitmaps.fit(it, NebulaSpec.FIXED_ICON_SIZE)))
                    }
                }
                fileCount = zip.fileCount
            }
        }
        step(context.getString(R.string.progress_wallpapers))

        val wallpaper = wallpaperOverride?.let { Bitmaps.decode(it.readBytes(), NebulaSpec.WALLPAPER_HEIGHT) }
            ?: wallpaperSourceId?.let { source.decode(it, NebulaSpec.WALLPAPER_HEIGHT) }
            ?: PreviewRenderer.gradientWallpaper(NebulaSpec.WALLPAPER_WIDTH, NebulaSpec.WALLPAPER_HEIGHT, options.generatedWallpaper)
        val lockWallpaper = when {
            wallpaperOverride != null -> wallpaper
            wallpaperSourceId == extras.wallpaper -> extras.lockWallpaper?.let { source.decode(it, NebulaSpec.WALLPAPER_HEIGHT) } ?: wallpaper
            else -> wallpaper
        }

        val previewIcons = orderedShowcase(showcase)
        val previews = buildList {
            add(Bitmaps.jpeg(PreviewRenderer.homeScreen(wallpaper, previewIcons), 88))
            add(Bitmaps.jpeg(PreviewRenderer.iconSheet(previewIcons), 88))
            add(Bitmaps.jpeg(PreviewRenderer.lockScreen(lockWallpaper), 88))
            extras.previewImages.take(NebulaSpec.MAX_PREVIEWS - 3).forEach { id ->
                source.decode(id, NebulaSpec.PREVIEW_HEIGHT)?.let {
                    add(Bitmaps.jpeg(Bitmaps.cover(it, NebulaSpec.PREVIEW_WIDTH, NebulaSpec.PREVIEW_HEIGHT), 88))
                }
            }
        }
        step(context.getString(R.string.progress_previews))

        val parts = ThemeArchiveParts(
            descriptionXml = ThemeXml.description(metadata, style, options.defaultShape),
            wallpaperJpeg = Bitmaps.jpeg(Bitmaps.cover(wallpaper, NebulaSpec.WALLPAPER_WIDTH, NebulaSpec.WALLPAPER_HEIGHT), 92),
            lockscreenJpeg = Bitmaps.jpeg(Bitmaps.cover(lockWallpaper, NebulaSpec.WALLPAPER_WIDTH, NebulaSpec.WALLPAPER_HEIGHT), 92),
            overlayFiles = NebulaSpec.OVERLAY_APKS.associateWith { path -> context.assets.open(path).use { it.readBytes() } },
            androidzteShapeConfig = if (style == ThemeStyle.FIXED) ThemeXml.shapeConfig(options.defaultShape) else null,
            iconsCurZip = iconsZip,
            previewJpegs = previews,
        )
        withContext(Dispatchers.IO) {
            openOutput().use { ThemeArchiveWriter.write(it, parts) }
        }
        step(context.getString(R.string.progress_saved))
        showcase.values.forEach(Bitmap::recycle)
        val bytes = iconsZip.length()
        workDir.deleteRecursively()
        BuildSummary(fileCount, iconsBySource.size, plan.icons.size, bytes)
    }

    private fun IconShape.toFixedShape(): FixedIconShape = when (this) {
        IconShape.CIRCLE -> FixedIconShape.CIRCLE
        IconShape.SQUIRCLE -> FixedIconShape.SQUARE
        IconShape.ROUNDED_SQUARE -> FixedIconShape.SQUARE
        IconShape.LEAF -> FixedIconShape.GEM
        IconShape.TEARDROP -> FixedIconShape.GEM
    }

    /** Decodes optional artwork; fully transparent images (common MIUI placeholders) count as absent. */
    private fun visible(source: ThemeSource, imageId: String?, maxSize: Int): Bitmap? =
        imageId?.let { runCatching { source.decode(it, maxSize) }.getOrNull() }
            ?.takeIf { Bitmaps.alphaBounds(it, threshold = 8) != null }

    /**
     * Resolves a [PlannedIcon.sourceId] to a bitmap, transparently generating a temp resource for apps the
     * pack/theme has no artwork for so the rest of [renderIcon] (background, tint) never needs to know the
     * difference.
     */
    private fun decodeSource(source: ThemeSource, sourceId: String, ownBackground: Boolean): Bitmap? = runCatching {
        if (DeviceIconId.isDeviceIcon(sourceId)) InstalledApps.decodeIcon(context, sourceId, NebulaSpec.LAYER_SIZE * 2, ownBackground)
        else source.decode(sourceId, NebulaSpec.LAYER_SIZE * 2)
    }.getOrNull()

    private fun renderIcon(
        source: ThemeSource,
        sourceId: String,
        stems: List<String>,
        options: BuildOptions,
        iconBack: Bitmap?,
        transparentFront: ByteArray,
        fallbackFixedShape: FixedIconShape,
    ): RenderedIcon? {
        val bitmap = decodeSource(source, sourceId, options.generatedIconOwnBackground) ?: return null
        val files = ArrayList<Pair<String, ByteArray>>()
        val flat: Bitmap = when (options.style) {
            ThemeStyle.ADAPTIVE -> {
                val forceShape = options.defaultShape.takeIf { DeviceIconId.isDeviceIcon(sourceId) }
                val layers = IconLayering.layer(bitmap, options.layerMode, options.padBackground.toInt(), iconBack, forceShape)
                val back = Bitmaps.png(layers.back)
                val front = if (layers.frontIsEmpty) transparentFront else Bitmaps.png(layers.front)
                // Theme-card copies show what the launcher will draw: the visible middle of the layers.
                val composite = IconLayering.composite(layers)
                val square = IconLayering.launcherView(composite, NebulaSpec.LAYER_SIZE)
                composite.recycle()
                val flat = Shapes.clip(square, options.defaultShape, NebulaSpec.LAYER_SIZE)
                val flatBytes by lazy { Bitmaps.png(flat) }
                val squareBytes by lazy { Bitmaps.png(square) }
                stems.forEach { stem ->
                    files += "$stem${NebulaSpec.SUFFIX_BACK}.png" to back
                    files += "$stem${NebulaSpec.SUFFIX_FRONT}.png" to front
                    val preview = stem in NebulaSpec.PREVIEW_STEMS
                    if (preview) files += "$stem${NebulaSpec.SUFFIX_SQUARE}.png" to squareBytes
                    // Stock ships flat copies for theme-card icons; package-only names get one as a fallback.
                    if (preview || '-' !in stem) files += "$stem.png" to flatBytes
                }
                layers.back.recycle()
                if (!layers.frontIsEmpty) layers.front.recycle()
                square.recycle()
                flat
            }
            ThemeStyle.FIXED -> {
                val flat = FixedIconArt.render(
                    source = bitmap,
                    shape = if (DeviceIconId.isDeviceIcon(sourceId)) fallbackFixedShape else options.fixedShape,
                    composition = options.fixedComposition,
                    iconScale = options.fixedIconScale,
                    iconAlpha = options.fixedIconAlpha,
                    tintColor = options.fixedTintColor?.toInt(),
                    tintStrength = options.fixedTintStrength,
                    backgroundScale = options.fixedBackgroundScale,
                    padColor = options.padBackground.toInt(),
                    iconBack = iconBack,
                )
                val bytes = Bitmaps.png(flat)
                stems.forEach { files += "$it.png" to bytes }
                flat
            }
        }
        val thumbnail = Bitmap.createScaledBitmap(flat, 176, 176, true)
        if (thumbnail !== flat) flat.recycle()
        bitmap.recycle()
        return RenderedIcon(files, stems, thumbnail)
    }

    /** Plates for unthemed apps. Every stock theme ships both: a light and a dark variant. */
    private fun writeBackgroundIcons(
        zip: IconsZipWriter,
        options: BuildOptions,
        iconBack: Bitmap?,
        fixedVisualShape: IconMaskShape,
    ) {
        when (options.style) {
            ThemeStyle.ADAPTIVE -> {
                // 216 px, opaque and unshaped (stock 04: white and #6C7887); the launcher applies the shape.
                val size = NebulaSpec.LAYER_SIZE
                val light = iconBack?.let { Bitmaps.cover(it, size, size) } ?: Bitmaps.solid(size, 0xFFFFFFFF.toInt())
                val dark = iconBack?.let { Bitmaps.cover(it, size, size) } ?: Bitmaps.solid(size, 0xFF6C7887.toInt())
                zip.putPng(NebulaSpec.BG_ICON_LIGHT, Bitmaps.png(light))
                zip.putPng(NebulaSpec.BG_ICON_DARK, Bitmaps.png(dark))
            }
            ThemeStyle.FIXED -> {
                // 156 px, already clipped to the shape (stock 60: #EBEDED and #282728).
                val size = NebulaSpec.ASSET_SIZE
                if (!options.fixedShape.isOriginal) {
                    // These are the only assets the launcher uses for apps absent from the icon pack.
                    // Make their plate match the selected fixed outline and the background-size/tint controls.
                    val plateSize = (size * options.fixedBackgroundScale.coerceIn(0.35f, 1.25f)).toInt().coerceAtLeast(1)
                    val content = iconBack?.let {
                        val covered = Bitmaps.cover(it, plateSize, plateSize)
                        Bitmaps.tint(covered, options.padBackground.toInt(), 1f).also { covered.recycle() }
                    } ?: Bitmaps.solid(plateSize, options.padBackground.toInt())
                    val shaped = Shapes.clip(content, fixedVisualShape, plateSize)
                    content.recycle()
                    val plate = Bitmaps.square(size)
                    val offset = (size - plateSize) / 2
                    android.graphics.Canvas(plate).drawBitmap(shaped, offset.toFloat(), offset.toFloat(), null)
                    shaped.recycle()
                    val bytes = Bitmaps.png(plate)
                    zip.putPng(NebulaSpec.BG_ICON_LIGHT, bytes)
                    zip.putPng(NebulaSpec.BG_ICON_DARK, bytes)
                } else if (iconBack != null) {
                    val shapedBack = if (options.fixedShape.isOriginal) Bitmaps.fit(iconBack, size) else Shapes.clip(iconBack, fixedVisualShape, size)
                    val bytes = Bitmaps.png(shapedBack)
                    zip.putPng(NebulaSpec.BG_ICON_LIGHT, bytes)
                    zip.putPng(NebulaSpec.BG_ICON_DARK, bytes)
                } else {
                    zip.putPng(NebulaSpec.BG_ICON_LIGHT, Bitmaps.png(Shapes.clip(Bitmaps.solid(size, 0xFFEBEDED.toInt()), fixedVisualShape, size)))
                    zip.putPng(NebulaSpec.BG_ICON_DARK, Bitmaps.png(Shapes.clip(Bitmaps.solid(size, 0xFF282728.toInt()), fixedVisualShape, size)))
                }
            }
        }
    }

    private fun orderedShowcase(showcase: Map<String, Bitmap>): List<Bitmap> {
        val priority = ZteSystemApps.all.flatMap { it.stems } + NebulaSpec.PREVIEW_STEMS
        val ordered = LinkedHashMap<String, Bitmap>()
        priority.forEach { stem -> showcase[stem]?.let { ordered.putIfAbsent(stem, it) } }
        showcase.forEach { (stem, bitmap) -> ordered.putIfAbsent(stem, bitmap) }
        return ordered.values.distinct()
    }
}
