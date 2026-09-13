package com.nathanhanapps.nebulaThemePorter.build

import android.content.Context
import android.graphics.Bitmap
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.GrayscaleCurve
import com.nathanhanapps.nebulaThemePorter.core.IconPlan
import com.nathanhanapps.nebulaThemePorter.core.IconMaskShape
import com.nathanhanapps.nebulaThemePorter.core.IconsZipWriter
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.ShapeAsset
import com.nathanhanapps.nebulaThemePorter.core.ThemeArchiveParts
import com.nathanhanapps.nebulaThemePorter.core.ThemeArchiveWriter
import com.nathanhanapps.nebulaThemePorter.core.ThemeLayout
import com.nathanhanapps.nebulaThemePorter.core.ThemeMetadata
import com.nathanhanapps.nebulaThemePorter.core.ThemeXml
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApps
import com.nathanhanapps.nebulaThemePorter.render.Bitmaps
import com.nathanhanapps.nebulaThemePorter.render.DynamicIcons
import com.nathanhanapps.nebulaThemePorter.render.FixedIconArt
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
        /**
         * [PlannedIcon.sourceId] to a tone-curve override, applied before [BuildOptions.fixedTintColor]. Keyed by
         * source icon rather than by output stem: a single app can land in the plan under several file names (its
         * real installed activity, plus whatever aliases the icon pack's own appfilter declares for it), and the
         * launcher does not always read the one this app's own code considers canonical. Keying by the icon
         * itself means every one of those duplicate files gets the same edit regardless of which the ROM reads.
         */
        iconCurves: Map<String, GrayscaleCurve> = emptyMap(),
        onProgress: (BuildProgress) -> Unit,
    ): BuildSummary = withContext(Dispatchers.Default) {
        val workDir = File(context.cacheDir, "build").apply {
            deleteRecursively()
            mkdirs()
        }
        val iconsZip = File(workDir, "icons_cur.zip")
        // Fixed themes carry no per-shape config variants. Their visible assets therefore follow the same baked
        // outline as app icons, including custom outlines the system itself cannot select.
        val fixedVisualShape: FixedIconShape = options.fixedShape.takeUnless { it.isOriginal } ?: FixedIconShape.SQUARE
        // A generated device icon has no shape or plate of its own, unlike real pack/theme artwork, so it can
        // never use FixedIconShape.NONE's "keep the imported PNG as-is" passthrough - fall back to the same
        // outline the unthemed-app background plate already uses.
        val fallbackFixedShape: FixedIconShape = fixedVisualShape
        val extras = source.extras
        val iconsBySource = plan.icons.groupBy { it.sourceId }
        val total = iconsBySource.size + 8
        var done = 0
        fun step(label: String) = onProgress(BuildProgress(++done, total, label))

        val sourceIconBack = visible(source, extras.iconBack, NebulaSpec.LAYER_SIZE)
        val customFixedBack = fixedBackgroundOverride?.let { file ->
            runCatching { Bitmaps.decode(file.readBytes(), NebulaSpec.LAYER_SIZE) }.getOrNull()
                ?.takeIf { Bitmaps.alphaBounds(it, threshold = 8) != null }
        }
        val iconBack = customFixedBack ?: sourceIconBack
        // One plate for the whole pack. [FixedIconArt.render] otherwise rebuilds this identical
        // cover/tint/flatten/clip bitmap once per icon, which is the largest redundant cost in a build - and got
        // markedly more expensive once the tint gained selectable blend modes. fallbackFixedShape is
        // fixedVisualShape and a non-original options.fixedShape is too, so this one plate serves both branches
        // of renderIcon's shape choice; an original options.fixedShape makes render() return before it is read.
        val plateSize = (NebulaSpec.FIXED_ICON_SIZE * options.fixedBackgroundScale.coerceIn(0.35f, 1.25f))
            .toInt().coerceAtLeast(1)
        val iconPlate = FixedIconArt.buildPlate(
            fixedVisualShape, plateSize, options.padBackground.toInt(), iconBack, options.fixedTintBlendMode,
        )
        val showcase = LinkedHashMap<String, Bitmap>()
        var fileCount: Int

        iconsZip.outputStream().use { raw ->
            IconsZipWriter(raw).use { zip ->
                zip.putText(ThemeLayout.CONFIG_FILE, ThemeXml.shapeConfig(fixedVisualShape))

                val sourceMask = visible(source, extras.iconMask, NebulaSpec.ASSET_SIZE)
                val sourceFolder = visible(source, extras.folderIcon, NebulaSpec.LAYER_SIZE)
                val mask = if (!options.fixedShape.isOriginal) {
                    Shapes.mask(fixedVisualShape, NebulaSpec.ASSET_SIZE)
                } else {
                    sourceMask?.let { Bitmaps.fit(it, NebulaSpec.ASSET_SIZE) } ?: Shapes.mask(fixedVisualShape, NebulaSpec.ASSET_SIZE)
                }
                zip.putPng(ShapeAsset.MASK.fileName(), Bitmaps.png(mask))
                val folder = if (!options.fixedShape.isOriginal) {
                    sourceFolder?.let { Shapes.clip(Bitmaps.trimToSquare(it) ?: it, fixedVisualShape, NebulaSpec.LAYER_SIZE) }
                } else {
                    sourceFolder?.let { Bitmaps.fit(it, NebulaSpec.LAYER_SIZE) }
                }
                folder?.let { zip.putPng(ShapeAsset.FOLDER_ICON.fileName(), Bitmaps.png(it)) }
                zip.putPng(ShapeAsset.FOLDER_ADD.fileName(), Bitmaps.png(Shapes.folderAdd(fixedVisualShape, NebulaSpec.ASSET_SIZE)))
                step(context.getString(R.string.progress_shapes))

                val clipSource = !options.fixedShape.isOriginal
                val dynamicTintColor = options.fixedTintColor?.toInt()
                if (extras.calendar != null || options.generateMissingDynamicIcons) {
                    val art = DynamicIcons.loadCalendar(source, extras.calendar, dynamicTintColor, options.fixedTintStrength, options.fixedTintBlendMode)
                    val calendarStrip = DynamicIcons.calendarStrip(
                        art, fixedVisualShape, clipSource, options.padBackground.toInt(), iconBack, options.fixedTintBlendMode,
                        options.fixedComposition, options.fixedIconScale, options.fixedIconAlpha, options.fixedBackgroundScale,
                    )
                    zip.putPng(ShapeAsset.DYNAMIC_CALENDAR.fileName(), Bitmaps.png(calendarStrip))
                    zip.putText(NebulaSpec.CALENDAR_INFO, ThemeXml.calendarInfo(DynamicIcons.calendarTextStyle(art)))
                }
                step(context.getString(R.string.progress_calendar))
                if (extras.clock != null || options.generateMissingDynamicIcons) {
                    val art = DynamicIcons.loadClock(source, extras.clock, dynamicTintColor, options.fixedTintStrength, options.fixedTintBlendMode)
                    val clockStrip = DynamicIcons.clockStrip(
                        art, fixedVisualShape, clipSource, dynamicTintColor, options.padBackground.toInt(), iconBack, options.fixedTintBlendMode,
                        options.fixedComposition, options.fixedIconScale, options.fixedIconAlpha, options.fixedBackgroundScale,
                    )
                    zip.putPng(ShapeAsset.DYNAMIC_CLOCK.fileName(), Bitmaps.png(clockStrip))
                }
                step(context.getString(R.string.progress_clock))

                iconsBySource.entries.chunked(8).forEach { chunk ->
                    ensureActive()
                    val rendered = coroutineScope {
                        chunk.map { (sourceId, planned) ->
                            val curve = iconCurves[sourceId] ?: GrayscaleCurve()
                            val curveLut = if (curve.isIdentity) null else curve.lut()
                            async {
                                renderIcon(source, sourceId, planned.map { it.stem }, options, iconBack, fallbackFixedShape, curveLut, iconPlate)
                            }
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
                iconPlate.recycle()

                // The launcher only composites an unthemed app icon on theme_bg_icon when overlapBg is set.
                // Write it for every selected fixed outline, including packs without an iconback resource.
                if (!options.fixedShape.isOriginal) {
                    zip.putText(NebulaSpec.THEME_INFO, ThemeXml.themeInfo(true))
                }
                val shortcut = DynamicIcons.shortcutBackground(NebulaSpec.ASSET_SIZE, fixedVisualShape)
                zip.putPng(NebulaSpec.SHORTCUT_BG, Bitmaps.png(shortcut))
                writeBackgroundIcons(zip, options, iconBack, fixedVisualShape)
                visible(source, extras.iconUpon, NebulaSpec.FIXED_ICON_SIZE)?.let {
                    zip.putPng(NebulaSpec.ICON_EFFECT_TOP, Bitmaps.png(Bitmaps.fit(it, NebulaSpec.FIXED_ICON_SIZE)))
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
            descriptionXml = ThemeXml.description(metadata),
            wallpaperJpeg = Bitmaps.jpeg(Bitmaps.cover(wallpaper, NebulaSpec.WALLPAPER_WIDTH, NebulaSpec.WALLPAPER_HEIGHT), 92),
            lockscreenJpeg = Bitmaps.jpeg(Bitmaps.cover(lockWallpaper, NebulaSpec.WALLPAPER_WIDTH, NebulaSpec.WALLPAPER_HEIGHT), 92),
            overlayFiles = NebulaSpec.OVERLAY_APKS.associateWith { path -> context.assets.open(path).use { it.readBytes() } },
            androidzteShapeConfig = ThemeXml.shapeConfig(fixedVisualShape),
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
        fallbackFixedShape: FixedIconShape,
        curveLut: IntArray?,
        /** Shared across every icon in the build; owned by the caller, never recycled here. */
        plate: Bitmap,
    ): RenderedIcon? {
        val bitmap = decodeSource(source, sourceId, options.generatedIconOwnBackground) ?: return null
        val files = ArrayList<Pair<String, ByteArray>>()
        val flat = FixedIconArt.render(
            source = bitmap,
            shape = if (DeviceIconId.isDeviceIcon(sourceId)) fallbackFixedShape else options.fixedShape,
            composition = options.fixedComposition,
            iconScale = options.fixedIconScale,
            iconAlpha = options.fixedIconAlpha,
            tintColor = options.fixedTintColor?.toInt(),
            tintStrength = options.fixedTintStrength,
            curveLut = curveLut,
            tintBlendMode = options.fixedTintBlendMode,
            backgroundScale = options.fixedBackgroundScale,
            padColor = options.padBackground.toInt(),
            iconBack = iconBack,
            plate = plate,
        )
        val bytes = Bitmaps.png(flat)
        stems.forEach { files += "$it.png" to bytes }
        val thumbnail = Bitmap.createScaledBitmap(flat, 176, 176, true)
        if (thumbnail !== flat) flat.recycle()
        bitmap.recycle()
        return RenderedIcon(files, stems, thumbnail)
    }

    /** Plates for unthemed apps (stock 60: #EBEDED and #282728), 156 px and already clipped to the shape. */
    private fun writeBackgroundIcons(
        zip: IconsZipWriter,
        options: BuildOptions,
        iconBack: Bitmap?,
        fixedVisualShape: IconMaskShape,
    ) {
        val size = NebulaSpec.ASSET_SIZE
        if (!options.fixedShape.isOriginal) {
            // These are the only assets the launcher uses for apps absent from the icon pack.
            // Make their plate match the selected fixed outline and the background-size/tint controls.
            val plateSize = (size * options.fixedBackgroundScale.coerceIn(0.35f, 1.25f)).toInt().coerceAtLeast(1)
            val content = iconBack?.let {
                val covered = Bitmaps.cover(it, plateSize, plateSize)
                Bitmaps.tint(covered, options.padBackground.toInt(), 1f, options.fixedTintBlendMode).also { covered.recycle() }
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
            val bytes = Bitmaps.png(Bitmaps.fit(iconBack, size))
            zip.putPng(NebulaSpec.BG_ICON_LIGHT, bytes)
            zip.putPng(NebulaSpec.BG_ICON_DARK, bytes)
        } else {
            zip.putPng(NebulaSpec.BG_ICON_LIGHT, Bitmaps.png(Shapes.clip(Bitmaps.solid(size, 0xFFEBEDED.toInt()), fixedVisualShape, size)))
            zip.putPng(NebulaSpec.BG_ICON_DARK, Bitmaps.png(Shapes.clip(Bitmaps.solid(size, 0xFF282728.toInt()), fixedVisualShape, size)))
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
