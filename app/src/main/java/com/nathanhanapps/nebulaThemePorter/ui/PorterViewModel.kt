package com.nathanhanapps.nebulaThemePorter.ui

import android.app.Application
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.nathanhanapps.nebulaThemePorter.R
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.nebulaThemePorter.CrashLog
import com.nathanhanapps.nebulaThemePorter.build.BuildProgress
import com.nathanhanapps.nebulaThemePorter.build.BuildSummary
import com.nathanhanapps.nebulaThemePorter.build.ThemeBuilder
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.AppComponent
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.IconPlan
import com.nathanhanapps.nebulaThemePorter.core.IconPlanner
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.FixedIconComposition
import com.nathanhanapps.nebulaThemePorter.core.GradientMix
import com.nathanhanapps.nebulaThemePorter.core.CurvePoint
import com.nathanhanapps.nebulaThemePorter.core.GrayscaleCurve
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.SourceIcon
import com.nathanhanapps.nebulaThemePorter.core.TintBlendMode
import com.nathanhanapps.nebulaThemePorter.core.StockComponentIndex
import com.nathanhanapps.nebulaThemePorter.core.ThemeMetadata
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApp
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApps
import com.nathanhanapps.nebulaThemePorter.render.Bitmaps
import com.nathanhanapps.nebulaThemePorter.render.DynamicIcons
import com.nathanhanapps.nebulaThemePorter.render.PreviewRenderer
import com.nathanhanapps.nebulaThemePorter.render.FixedIconArt
import com.nathanhanapps.nebulaThemePorter.render.Shapes
import com.nathanhanapps.nebulaThemePorter.source.IconPackSource
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.source.MtzSource
import com.nathanhanapps.nebulaThemePorter.source.SourceFiles
import com.nathanhanapps.nebulaThemePorter.source.SourceKind
import com.nathanhanapps.nebulaThemePorter.source.SourceWallpaper
import com.nathanhanapps.nebulaThemePorter.source.ThemeSource
import com.nathanhanapps.nebulaThemePorter.storage.PorterPreferences
import com.nathanhanapps.nebulaThemePorter.storage.StorageAccess
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Stage { HOME, LOADING, CONFIGURE, BUILDING, DONE, ERROR }

data class SourceSummary(
    val kind: SourceKind,
    val fileName: String,
    val imageCount: Int,
    val packageCount: Int,
    val installedPackages: Int,
    val hasCalendar: Boolean,
    val hasClock: Boolean,
    val hasWallpaper: Boolean,
    val hasIconBack: Boolean,
)

data class PorterState(
    val stage: Stage = Stage.HOME,
    val installedIconPacks: List<InstalledApps.IconPack> = emptyList(),
    val installedIconPacksLoading: Boolean = false,
    val installedIconPacksLoaded: Boolean = false,
    val summary: SourceSummary? = null,
    val labelZh: String = "",
    val labelEn: String = "",
    val author: String = "",
    val intro: String = "",
    val options: BuildOptions = BuildOptions(),
    val assignments: Map<String, String> = emptyMap(),
    /** Installed and launcher-visible ZTE targets only. */
    val visibleSystemApps: List<ZteSystemApp> = emptyList(),
    val userAppsExpanded: Boolean = false,
    val userAppsLoading: Boolean = false,
    val userAppsLoaded: Boolean = false,
    val userApps: List<InstalledApps.LauncherApp> = emptyList(),
    /** Launcher component stem to manually selected source image id. */
    val userAssignments: Map<String, String> = emptyMap(),
    /** Automatic source match shown before the user overrides an app. */
    val userSuggestions: Map<String, String> = emptyMap(),
    /** Per-app tone-curve override applied before [BuildOptions.fixedTintColor], keyed by
     * [PorterViewModel.systemContrastKey] / [PorterViewModel.userContrastKey]. */
    val iconCurves: Map<String, GrayscaleCurve> = emptyMap(),
    /** Grid cells currently selected for batch curve editing, same keys as [iconCurves]. Non-empty means the
     * system/user app grids are in selection mode instead of opening the icon picker on tap. */
    val selectedGridKeys: Set<String> = emptySet(),
    val sourceWallpapers: List<SourceWallpaper> = emptyList(),
    val selectedWallpaperId: String? = null,
    val wallpaperName: String? = null,
    val fixedBackgroundName: String? = null,
    val wallpaperPreview: Bitmap? = null,
    val wallpaperPalette: List<Long> = emptyList(),
    val wallpaperRevision: Int = 0,
    val plannedImages: Int = 0,
    val plannedNames: Int = 0,
    val progress: BuildProgress? = null,
    val result: BuildSummary? = null,
    val error: String? = null,
    val hasFileAccess: Boolean = false,
    val outputDir: String = "",
    val outputName: String = "",
    val outputPath: String? = null,
    val lastCrash: String? = null,
)

class PorterViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        /** ZteSystemApps ids for the most recognisable, universally-known app glyphs, in preview priority order. */
        private val previewAppPriority = listOf(
            "settings", "phone", "gallery", "chrome", "music", "camera", "contacts", "calculator", "browser", "clock",
        )

        /** Common non-system apps recognisable enough for a preview, checked by exact package name. */
        private val extraPreviewPackages = listOf("com.tencent.mm", "com.tencent.mobileqq")

        /** Keys for [PorterState.iconCurves] and [PorterState.selectedGridKeys] - shared with the grid item keys in PorterScreens.kt. */
        fun systemContrastKey(appId: String) = "system:$appId"
        fun userContrastKey(stem: String) = "user:$stem"
    }

    private val app get() = getApplication<Application>()
    private val prefs = PorterPreferences(application)
    private val mutableState = MutableStateFlow(
        PorterState(
            hasFileAccess = StorageAccess.hasAllFilesAccess(application),
            outputDir = prefs.outputDir.absolutePath,
            lastCrash = CrashLog.latestReport(application),
        ),
    )
    val state: StateFlow<PorterState> = mutableState.asStateFlow()

    private var source: ThemeSource? = null
    private var planner: IconPlanner? = null
    private var wallpaperFile: File? = null
    private var fixedBackgroundFile: File? = null
    private var outputNameEdited = false
    private var planJob: Job? = null
    private var buildJob: Job? = null
    private val thumbnails = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun refreshFileAccess() = mutableState.update { it.copy(hasFileAccess = StorageAccess.hasAllFilesAccess(app)) }

    fun dismissCrash() {
        CrashLog.clear(app)
        mutableState.update { it.copy(lastCrash = null) }
    }

    /** From the system file picker. Files on this phone's storage are read in place; anything else is copied first. */
    fun open(kind: SourceKind, uri: Uri) = load {
        val file = StorageAccess.fileFor(app, uri)
        when {
            file != null && kind == SourceKind.ICON_PACK -> IconPackSource.open(app, file)
            file != null -> MtzSource.open(app, file)
            kind == SourceKind.ICON_PACK -> IconPackSource.open(app, uri)
            else -> MtzSource.open(app, uri)
        }
    }

    fun loadInstalledIconPacks() {
        if (mutableState.value.installedIconPacksLoading) return
        mutableState.update { it.copy(installedIconPacksLoading = true) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { InstalledApps.iconPacks(app) } }
                .onSuccess { packs ->
                    mutableState.update {
                        it.copy(installedIconPacks = packs, installedIconPacksLoading = false, installedIconPacksLoaded = true)
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(installedIconPacksLoading = false, error = error.userMessage()) }
                }
        }
    }

    fun openInstalledIconPack(iconPack: InstalledApps.IconPack) = load {
        IconPackSource.open(app, File(iconPack.apkPath))
    }

    fun setLabelZh(value: String) {
        mutableState.update { it.copy(labelZh = value) }
        syncOutputName()
    }

    fun setLabelEn(value: String) {
        mutableState.update { it.copy(labelEn = value) }
        syncOutputName()
    }

    fun setAuthor(value: String) = mutableState.update { it.copy(author = value) }
    fun setIntro(value: String) = mutableState.update { it.copy(intro = value) }

    fun setOutputName(value: String) {
        outputNameEdited = true
        mutableState.update { it.copy(outputName = value) }
    }

    /** From the system folder picker; themes are then written to that folder directly. */
    fun setOutputFolder(treeUri: Uri) {
        val dir = StorageAccess.directoryFor(app, treeUri)
        if (dir == null) {
            mutableState.update { it.copy(error = app.getString(R.string.choose_local_folder)) }
            return
        }
        prefs.outputDir = dir
        mutableState.update { it.copy(outputDir = dir.absolutePath) }
    }

    fun setFixedShape(shape: FixedIconShape) = updateOptions { it.copy(fixedShape = shape) }
    fun setFixedComposition(composition: FixedIconComposition) = updateOptions { it.copy(fixedComposition = composition) }
    fun setFixedIconScale(scale: Float) = updateOptions { it.copy(fixedIconScale = scale.coerceIn(0.25f, 1.25f)) }
    fun setFixedIconAlpha(alpha: Float) = updateOptions { it.copy(fixedIconAlpha = alpha.coerceIn(0f, 1f)) }
    fun setFixedTintColor(color: Long?) = updateOptions { it.copy(fixedTintColor = color) }
    fun setFixedTintStrength(strength: Float) = updateOptions { it.copy(fixedTintStrength = strength.coerceIn(0f, 1f)) }
    fun setFixedTintBlendMode(mode: TintBlendMode) = updateOptions { it.copy(fixedTintBlendMode = mode) }
    fun setFixedTintHue(value: Float) = updateFixedTintHsv { it[0] = value.coerceIn(0f, 360f) }
    fun setFixedTintSaturation(value: Float) = updateFixedTintHsv { it[1] = value.coerceIn(0f, 1f) }
    fun setFixedTintBrightness(value: Float) = updateFixedTintHsv { it[2] = value.coerceIn(0f, 1f) }
    fun setFixedBackgroundScale(scale: Float) = updateOptions { it.copy(fixedBackgroundScale = scale.coerceIn(0.35f, 1.25f)) }
    fun setPadBackground(color: Long) = updateOptions { it.copy(padBackground = color) }
    fun setOnlyInstalled(value: Boolean) = updateOptions { it.copy(onlyInstalledApps = value) }
    fun setGenerateDynamic(value: Boolean) = updateOptions { it.copy(generateMissingDynamicIcons = value) }
    fun setGenerateMissingAppIcons(value: Boolean) = updateOptions { it.copy(generateMissingAppIcons = value) }
    fun setGeneratedIconOwnBackground(value: Boolean) = updateOptions { it.copy(generatedIconOwnBackground = value) }

    fun setGeneratedWallpaperMix(mix: GradientMix) = updateGeneratedWallpaper { it.copy(mix = mix) }
    fun setGeneratedWallpaperBlur(blur: Float) = updateGeneratedWallpaper { it.copy(blur = blur.coerceIn(0f, 1f)) }
    fun setGeneratedWallpaperAngle(angle: Float) = updateGeneratedWallpaper { it.copy(angle = angle.coerceIn(0f, 360f)) }
    /** Keeps the chosen colors and changes only where their soft fields sit. */
    fun shuffleGeneratedWallpaperPlacement() = updateGeneratedWallpaper { it.copy(seed = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()) }
    fun randomizeGeneratedWallpaperColors() = updateGeneratedWallpaper {
        val combinations = arrayOf(
            longArrayOf(0xFF7BDFF2, 0xFFF7A8B8, 0xFFA78BFA),
            longArrayOf(0xFFFFD6A5, 0xFFFF8FA3, 0xFF6C63FF),
            longArrayOf(0xFFC7F464, 0xFFFF9A76, 0xFFDC5B9B),
            longArrayOf(0xFF20C9E8, 0xFFFFD23F, 0xFFFF5C35),
            longArrayOf(0xFFFF4FA3, 0xFF00D9E8, 0xFF6A4CFF),
            longArrayOf(0xFFBFE9FF, 0xFF96E6B3, 0xFF5E60CE),
        )
        val colors = combinations[Math.floorMod(System.currentTimeMillis().hashCode(), combinations.size)]
        it.copy(colorA = colors[0], colorB = colors[1], colorC = colors[2])
    }
    fun setGeneratedWallpaperColor(slot: Int, color: Long) = updateGeneratedWallpaper {
        when (slot) {
            0 -> it.copy(colorA = color)
            1 -> it.copy(colorB = color)
            else -> it.copy(colorC = color)
        }
    }

    fun assign(appId: String, sourceId: String?) {
        mutableState.update {
            it.copy(assignments = if (sourceId == null) it.assignments - appId else it.assignments + (appId to sourceId))
        }
        replan()
    }

    fun assignUserApp(stem: String, sourceId: String?) {
        if (!AppComponent.isSafeStem(stem)) return
        mutableState.update {
            it.copy(userAssignments = if (sourceId == null) it.userAssignments - stem else it.userAssignments + (stem to sourceId))
        }
        replan()
    }

    /** Long-press on a system/user app grid cell: enters batch curve-editing mode with just that cell selected. */
    fun startGridSelection(key: String) = mutableState.update { it.copy(selectedGridKeys = setOf(key)) }

    /** Tap on a grid cell while selection mode is already active. */
    fun toggleGridSelection(key: String) = mutableState.update {
        it.copy(selectedGridKeys = if (key in it.selectedGridKeys) it.selectedGridKeys - key else it.selectedGridKeys + key)
    }

    fun clearGridSelection() = mutableState.update { it.copy(selectedGridKeys = emptySet()) }

    /** Applied live as the batch curve editor is dragged, to every currently selected grid cell. */
    fun setCurveForSelection(curve: GrayscaleCurve) = mutableState.update { state ->
        if (state.selectedGridKeys.isEmpty()) return@update state
        state.copy(iconCurves = state.iconCurves + state.selectedGridKeys.associateWith { curve })
    }

    /** "Normalize": brightness-shifts each selected icon by its own amount so they all land on the same mean
     * grayscale level - unlike [setCurveForSelection], which applies one identical curve to every selection,
     * this gives each app whatever shift ITS source icon needs, so a naturally dark icon and a naturally light
     * one end up looking similarly bright after tint instead of one staying an outlier. The target is the mean
     * of the combined selection (the same histogram already shown behind the curve), so the group's overall
     * brightness stays put and individual icons move toward each other rather than to a fixed reference point.
     * A later drag on the shared curve editor still applies identically to all selected, replacing this. */
    fun normalizeSelection() {
        val state = mutableState.value
        val selection = state.selectedGridKeys
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val sourceIds = selection.mapNotNull { key -> resolveSourceId(state, key)?.let { key to it } }.toMap()
            if (sourceIds.isEmpty()) return@launch
            val means = sourceIds.mapValues { (_, sourceId) -> grayscaleMean(sourceId) }
            val validMeans = means.values.filterNotNull()
            if (validMeans.isEmpty()) return@launch
            val target = validMeans.average().toFloat()
            val newCurves = means.mapNotNull { (key, mean) ->
                mean ?: return@mapNotNull null
                val delta = (target - mean).coerceIn(-1f, 1f)
                key to GrayscaleCurve(listOf(CurvePoint(0f, (0f + delta).coerceIn(0f, 1f)), CurvePoint(1f, (1f + delta).coerceIn(0f, 1f))))
            }.toMap()
            mutableState.update { it.copy(iconCurves = it.iconCurves + newCurves) }
        }
    }

    /** Mean grayscale level (0..1, alpha-weighted) of one source icon, or null if it couldn't be decoded. */
    private suspend fun grayscaleMean(sourceId: String): Float? {
        val bins = grayscaleHistogram(listOf(sourceId))
        val total = bins.sum()
        if (total == 0) return null
        val weighted = bins.indices.sumOf { level -> level.toLong() * bins[level] }
        return (weighted.toDouble() / total / 255.0).toFloat()
    }

    /** Same image id a grid cell actually shows for [key] - including the device-icon fallback a user app with
     * no pack match and no manual override falls back to, which earlier versions of this resolution missed,
     * silently dropping the curve for any app that only ever showed its own launcher icon. */
    private fun resolveSourceId(state: PorterState, key: String): String? = when {
        key.startsWith("system:") -> state.assignments[key.removePrefix("system:")]
        key.startsWith("user:") -> {
            val stem = key.removePrefix("user:")
            state.userAssignments[stem] ?: state.userSuggestions[stem] ?: run {
                if (!state.options.generateMissingAppIcons) return@run null
                state.userApps.firstOrNull { it.stem == stem }?.let { DeviceIconId.of(it.packageName, it.activityName) }
            }
        }
        else -> null
    }

    /** Public entry point for the UI (e.g. the curve panel's histogram) to resolve the same image a grid cell
     * for [key] is actually showing right now, without duplicating [resolveSourceId]'s fallback logic. */
    fun resolveSourceId(key: String): String? = resolveSourceId(mutableState.value, key)

    /** A 256-bucket grayscale histogram (alpha-weighted, transparent pixels excluded) across every image in
     * [imageIds], for the curve editor's backdrop. Reuses cached thumbnail decodes where possible. */
    suspend fun grayscaleHistogram(imageIds: List<String>): IntArray = withContext(Dispatchers.Default) {
        val bins = IntArray(256)
        imageIds.distinct().forEach { id ->
            val bitmap = synchronized(thumbnails) { thumbnails.get(id) } ?: runCatching {
                if (DeviceIconId.isDeviceIcon(id)) InstalledApps.decodeIcon(app, id, 128) else source?.decode(id, 128)
            }.getOrNull() ?: return@forEach
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.forEach { pixel ->
                val alpha = pixel ushr 24
                if (alpha == 0) return@forEach
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val gray = (r * 0.2126f + g * 0.7152f + b * 0.0722f).roundToInt().coerceIn(0, 255)
                bins[gray]++
            }
        }
        bins
    }

    /** User apps and labels are intentionally queried only when this section is first opened. */
    fun toggleUserApps() {
        val current = mutableState.value
        if (current.userAppsExpanded) {
            mutableState.update { it.copy(userAppsExpanded = false) }
            return
        }
        if (current.userAppsLoaded) {
            mutableState.update { it.copy(userAppsExpanded = true) }
            return
        }
        if (current.userAppsLoading || source == null) return
        mutableState.update { it.copy(userAppsExpanded = true, userAppsLoading = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    InstalledApps.launcherApps(app)
                        .filter { !it.isSystem && it.packageName != app.packageName }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                }
            }.onSuccess { apps ->
                val sourceIcons = source?.icons.orEmpty()
                val exact = sourceIcons.mapNotNull { icon -> icon.component?.className?.let { icon.component.stem to icon.id } }.toMap()
                val packages = sourceIcons.groupBy { it.component?.packageName ?: it.key }
                val suggestions = apps.mapNotNull { launcher ->
                    (exact[launcher.stem] ?: packages[launcher.packageName]?.firstOrNull()?.id)?.let { launcher.stem to it }
                }.toMap()
                mutableState.update { it.copy(userApps = apps, userSuggestions = suggestions, userAppsLoading = false, userAppsLoaded = true) }
            }.onFailure { error ->
                mutableState.update { it.copy(userAppsLoading = false, error = error.userMessage()) }
            }
        }
    }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(File(app.cacheDir, "sources").apply { mkdirs() }, "wallpaper")
                    app.contentResolver.openInputStream(uri)?.use { SourceFiles.copyLimited(app, it, file, 64L * 1024 * 1024) }
                        ?: throw IllegalArgumentException(app.getString(R.string.cannot_read_image))
                    file to (SourceFiles.displayName(app, uri) ?: app.getString(R.string.wallpaper))
                }
            }.onSuccess { (file, name) ->
                wallpaperFile = file
                mutableState.update { it.copy(wallpaperName = name) }
                refreshWallpaperVisual()
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.userMessage()) }
            }
        }
    }

    fun setFixedBackground(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(File(app.cacheDir, "sources").apply { mkdirs() }, "fixed_icon_background")
                    app.contentResolver.openInputStream(uri)?.use { SourceFiles.copyLimited(app, it, file, 64L * 1024 * 1024) }
                        ?: throw IllegalArgumentException(app.getString(R.string.cannot_read_image))
                    // Decode now so an unsupported or corrupt document never reaches the build.
                    val decoded = com.nathanhanapps.nebulaThemePorter.render.Bitmaps.decode(file.readBytes(), NebulaSpec.LAYER_SIZE)
                        ?: throw IllegalArgumentException(app.getString(R.string.cannot_read_image))
                    decoded.recycle()
                    file to (SourceFiles.displayName(app, uri) ?: app.getString(R.string.fixed_background_custom))
                }
            }.onSuccess { (file, name) ->
                fixedBackgroundFile?.takeUnless { it == file }?.delete()
                fixedBackgroundFile = file
                mutableState.update { it.copy(fixedBackgroundName = name) }
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.userMessage()) }
            }
        }
    }

    fun clearFixedBackground() {
        fixedBackgroundFile?.delete()
        fixedBackgroundFile = null
        mutableState.update { it.copy(fixedBackgroundName = null) }
    }

    fun selectSourceWallpaper(imageId: String?) {
        if (imageId != null && source?.extras?.wallpapers?.none { it.id == imageId } != false) return
        wallpaperFile?.delete()
        wallpaperFile = null
        mutableState.update { it.copy(selectedWallpaperId = imageId, wallpaperName = null) }
        refreshWallpaperVisual()
    }

    fun clearWallpaper() {
        wallpaperFile?.delete()
        wallpaperFile = null
        mutableState.update { it.copy(wallpaperName = null) }
        refreshWallpaperVisual()
    }

    /** File name for the system "save as" picker. */
    fun suggestedFileName(): String = normalizedOutputName(mutableState.value.outputName)

    /** Writes straight into the chosen folder (Internal storage/Theme by default). */
    fun buildToFolder() {
        val current = mutableState.value
        val dir = File(current.outputDir)
        val name = normalizedOutputName(current.outputName)
        val part = File(dir, "$name.part")
        startBuild(
            prepare = { require(dir.isDirectory || dir.mkdirs()) { app.getString(R.string.cannot_create_path, StorageAccess.friendlyPath(dir.absolutePath)) } },
            openOutput = { part.outputStream() },
            finish = {
                val target = File(dir, name)
                if (target.exists()) require(target.delete()) { app.getString(R.string.cannot_replace_file, target.name) }
                require(part.renameTo(target)) { app.getString(R.string.cannot_rename_file, part.name) }
                target.absolutePath
            },
            cleanup = { part.delete() },
        )
    }

    /** Writes to a document chosen with the system picker. */
    fun build(destination: Uri) {
        startBuild(
            prepare = {},
            openOutput = {
                runCatching { app.contentResolver.openOutputStream(destination, "wt") }.getOrNull()
                    ?: app.contentResolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException(app.getString(R.string.cannot_open_output))
            },
            finish = { SourceFiles.displayName(app, destination) ?: destination.toString() },
            cleanup = {},
        )
    }

    fun back() {
        when (mutableState.value.stage) {
            Stage.DONE -> mutableState.update { it.copy(stage = Stage.CONFIGURE, result = null) }
            Stage.BUILDING, Stage.LOADING -> Unit
            else -> reset()
        }
    }

    fun reset() {
        buildJob?.cancel()
        closeSource()
        clearWallpaper()
        mutableState.value = baseState(mutableState.value)
    }

    fun consumeError() = mutableState.update { it.copy(error = null) }

    fun searchIcons(query: String): List<SourceIcon> {
        val icons = source?.icons.orEmpty().distinctBy { it.key to it.id }
        val trimmed = query.trim()
        return (if (trimmed.isEmpty()) icons else icons.filter { it.key.contains(trimmed, ignoreCase = true) }).take(900)
    }

    fun iconKey(sourceId: String): String? = source?.icons?.firstOrNull { it.id == sourceId }?.key

    private var thumbnailIconBackKey: String? = null
    private var thumbnailIconBack: Bitmap? = null

    /** Same iconBack resolution as [fixedIconPreview], but cached - thumbnails call this once per visible grid
     * cell rather than once per settled slider drag, so re-decoding it from file bytes every time would add up. */
    private suspend fun thumbnailIconBack(): Bitmap? {
        val active = source
        val key = fixedBackgroundFile?.absolutePath ?: active?.extras?.iconBack ?: run {
            thumbnailIconBackKey = null
            thumbnailIconBack = null
            return null
        }
        if (key == thumbnailIconBackKey) return thumbnailIconBack
        val decoded = withContext(Dispatchers.IO) {
            fixedBackgroundFile?.let { file -> runCatching { Bitmaps.decode(file.readBytes(), NebulaSpec.FIXED_ICON_SIZE) }.getOrNull() }
                ?: active?.extras?.iconBack?.let { id -> runCatching { active.decode(id, NebulaSpec.FIXED_ICON_SIZE) }.getOrNull() }
        }
        thumbnailIconBackKey = key
        thumbnailIconBack = decoded
        return decoded
    }

    private var thumbnailPlateKey: String? = null
    private var thumbnailPlate: Bitmap? = null
    private val thumbnailPlateLock = Mutex()

    /**
     * The same background plate [FixedIconArt.render] would otherwise build internally, cached across every
     * visible grid cell that shares shape/backgroundScale/padColor/iconBack/tintBlendMode - only the foreground
     * icon differs between them, so rebuilding this identical cover/tint/flatten/clip plate once per cell was
     * most of the per-thumbnail render cost and the main cause of the app-list grid feeling laggy. A stale plate
     * is left for the garbage collector rather than explicitly recycled on a cache miss, since it could still be
     * mid-draw on another thumbnail's in-flight render call when the key changes underneath it.
     */
    private suspend fun thumbnailPlate(shape: FixedIconShape, backgroundSize: Int, padColor: Int, iconBack: Bitmap?, tintBlendMode: TintBlendMode): Bitmap {
        val key = "$shape:$backgroundSize:$padColor:$tintBlendMode:${iconBack?.let { System.identityHashCode(it) } ?: 0}"
        thumbnailPlate?.takeIf { key == thumbnailPlateKey }?.let { return it }
        return thumbnailPlateLock.withLock {
            // Re-check under the lock. The preview tiles and every visible grid cell launch together and all
            // miss the fast path above at once, so without single-flighting the miss each of them builds its own
            // copy of the identical plate - precisely the work this cache exists to avoid.
            thumbnailPlate?.takeIf { key == thumbnailPlateKey }
                ?: withContext(Dispatchers.Default) {
                    FixedIconArt.buildPlate(shape, backgroundSize, padColor, iconBack, tintBlendMode)
                }.also {
                    thumbnailPlateKey = key
                    thumbnailPlate = it
                }
        }
    }

    /**
     * [tintColor]/[tintStrength] and [curve], when given, preview the same adjustments [FixedIconArt.render]
     * bakes into the real build output - they apply regardless of the chosen fixed shape, so list thumbnails
     * would otherwise look unadjusted right up until export. [fixedOptions], when its shape isn't
     * [FixedIconShape.isOriginal], also composites the selected shape/plate/background so list previews show the
     * same outline the exported icon will have, not just a plain square glyph.
     *
     * [ownBackground] matters only for a device-fallback icon (an app the pack has no artwork for): it decides
     * whether that decode keeps the app's own icon background, exactly like [BuildOptions.generatedIconOwnBackground]
     * does for the real build - done here, before tint/curve run, so a kept background goes through the same
     * pipeline as everything else instead of being pasted in afterwards untouched. Call sites should pass the
     * live option value rather than leaving the default, or the preview and the export can show a different
     * background for the same app - previously the preview always stripped it, hiding which fallback icons
     * would come out dark until the user saw the finished theme.
     */
    suspend fun thumbnail(
        imageId: String,
        tintColor: Int? = null,
        tintStrength: Float = 1f,
        curve: GrayscaleCurve = GrayscaleCurve(),
        fixedOptions: BuildOptions? = null,
        ownBackground: Boolean = true,
        tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
    ): Bitmap? {
        val shaped = fixedOptions?.takeUnless { it.fixedShape.isOriginal }
        val isDeviceIcon = DeviceIconId.isDeviceIcon(imageId)
        val baseKey = if (isDeviceIcon) "$imageId|bg:$ownBackground" else imageId
        val cacheKey = buildString {
            append(baseKey)
            if (tintColor != null) append("|tint:$tintColor:$tintStrength:$tintBlendMode")
            if (!curve.isIdentity) append("|c:${curve.points}")
            if (shaped != null) {
                append("|shape:${shaped.fixedShape}:${shaped.fixedComposition}:${shaped.fixedIconScale}:")
                append("${shaped.fixedIconAlpha}:${shaped.fixedBackgroundScale}:${shaped.padBackground}")
            }
        }
        synchronized(thumbnails) { thumbnails.get(cacheKey) }?.let { return it }
        val base = synchronized(thumbnails) { thumbnails.get(baseKey) } ?: withContext(Dispatchers.IO) {
            runCatching {
                if (isDeviceIcon) InstalledApps.decodeIcon(app, imageId, 128, ownBackground)
                else source?.decode(imageId, 128)
            }.getOrNull()
        }?.also { synchronized(thumbnails) { thumbnails.put(baseKey, it) } } ?: return null
        if (tintColor == null && curve.isIdentity && shaped == null) return base
        val curveLut = if (curve.isIdentity) null else curve.lut()
        val prepared = if (shaped != null) {
            val iconBack = thumbnailIconBack()
            val backgroundSize = (NebulaSpec.PREVIEW_ICON_SIZE * shaped.fixedBackgroundScale.coerceIn(0.35f, 1.25f)).toInt().coerceAtLeast(1)
            val plate = shaped.fixedShape.takeUnless { it.isOriginal }
                ?.let { thumbnailPlate(it, backgroundSize, shaped.padBackground.toInt(), iconBack, tintBlendMode) }
            withContext(Dispatchers.Default) {
                FixedIconArt.render(
                    source = base,
                    shape = shaped.fixedShape,
                    composition = shaped.fixedComposition,
                    iconScale = shaped.fixedIconScale,
                    iconAlpha = shaped.fixedIconAlpha,
                    tintColor = tintColor,
                    tintStrength = tintStrength,
                    backgroundScale = shaped.fixedBackgroundScale,
                    padColor = shaped.padBackground.toInt(),
                    iconBack = iconBack,
                    curveLut = curveLut,
                    tintBlendMode = tintBlendMode,
                    size = NebulaSpec.PREVIEW_ICON_SIZE,
                    plate = plate,
                )
            }
        } else {
            withContext(Dispatchers.Default) {
                val curved = curveLut?.let { Bitmaps.curve(base, it) } ?: base
                tintColor?.let { color ->
                    Bitmaps.tint(curved, color, tintStrength, tintBlendMode).also { if (curved !== base) curved.recycle() }
                } ?: curved
            }
        }
        synchronized(thumbnails) { thumbnails.put(cacheKey, prepared) }
        return prepared
    }

    /** Five recognisable app types first, then ordinary imported icons as a fallback. */
    fun fixedPreviewIconChoices(): List<String> {
        val icons = source?.icons.orEmpty().distinctBy { it.id }
        val byPackage = LinkedHashMap<String, SourceIcon>()
        icons.forEach { icon -> byPackage.putIfAbsent((icon.component?.packageName ?: icon.key).lowercase(Locale.ROOT), icon) }

        val picked = LinkedHashSet<String>()
        previewAppPriority.forEach { id ->
            val icon = ZteSystemApps.byId(id)?.aliases?.firstNotNullOfOrNull { byPackage[it.lowercase(Locale.ROOT)] }
            if (icon != null) picked += icon.id
        }
        extraPreviewPackages.forEach { pkg -> byPackage[pkg]?.let { picked += it.id } }
        if (picked.size < 5) icons.sortedBy { it.key }.forEach { icon -> if (picked.size < 5) picked += icon.id }
        return picked.take(5).toList()
    }

    /**
     * Both dynamic previews build an entire 32-frame calendar strip (or 3-frame clock strip) just to show one
     * ~58dp tile - deliberately, since reusing [DynamicIcons]' own strip code is what keeps the preview from
     * drifting from the build. Caching the finished tile keeps that guarantee while making a repeat of the same
     * settings - a recomposition, a slider dragged back where it was, a shape toggled and toggled back - free
     * instead of another 31 decodes and tints. The keys sit in [thumbnails] behind a "@" prefix that no image id
     * can produce, so they share its eviction budget instead of holding bitmaps alive on their own.
     */
    private fun dynamicPreviewKey(kind: String, options: BuildOptions, iconBack: Bitmap?): String = buildString {
        append("@$kind|shape:${options.fixedShape}|tint:${options.fixedTintColor}:${options.fixedTintStrength}")
        append(":${options.fixedTintBlendMode}|pad:${options.padBackground}|gen:${options.generateMissingDynamicIcons}")
        append("|comp:${options.fixedComposition}:${options.fixedIconScale}:${options.fixedIconAlpha}")
        append(":${options.fixedBackgroundScale}|back:${iconBack?.let { System.identityHashCode(it) } ?: 0}")
    }

    /**
     * A same-tint, same-shape sample of what the calendar's day tiles will actually look like, reusing
     * [DynamicIcons.calendarStrip] itself (not a re-derivation of it) so the preview can never drift from what a
     * real build produces - just today's frame cropped out of the same strip. Null when the source has no
     * calendar and [BuildOptions.generateMissingDynamicIcons] wouldn't generate one either, matching whether the
     * build would actually write a calendar asset at all.
     */
    suspend fun calendarIconPreview(options: BuildOptions): Bitmap? {
        val active = source ?: return null
        if (active.extras.calendar == null && !options.generateMissingDynamicIcons) return null
        val iconBack = thumbnailIconBack()
        val today = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) - 1).coerceIn(0, 31)
        val cacheKey = dynamicPreviewKey("calendar:$today", options, iconBack)
        synchronized(thumbnails) { thumbnails.get(cacheKey) }?.let { return it }
        return withContext(Dispatchers.Default) {
            val art = DynamicIcons.loadCalendar(active, active.extras.calendar, options.fixedTintColor?.toInt(), options.fixedTintStrength, options.fixedTintBlendMode)
            val strip = DynamicIcons.calendarStrip(
                art, options.fixedShape, !options.fixedShape.isOriginal, options.padBackground.toInt(), iconBack, options.fixedTintBlendMode,
                options.fixedComposition, options.fixedIconScale, options.fixedIconAlpha, options.fixedBackgroundScale,
            )
            val size = NebulaSpec.ASSET_SIZE
            Bitmap.createBitmap(strip, today * size, 0, size, size).also { strip.recycle() }
        }.also { synchronized(thumbnails) { thumbnails.put(cacheKey, it) } }
    }

    /**
     * Same idea as [calendarIconPreview] but for the clock: [DynamicIcons.clockStrip] hands back three separate
     * tiles (hour hand, minute hand, dial) meant for the launcher to composite live at the real time, so this
     * just overlays those same three tiles once to give an at-a-glance look, rather than reimplementing hand
     * rotation and risking it not matching what actually gets exported.
     */
    suspend fun clockIconPreview(options: BuildOptions): Bitmap? {
        val active = source ?: return null
        if (active.extras.clock == null && !options.generateMissingDynamicIcons) return null
        val iconBack = thumbnailIconBack()
        val cacheKey = dynamicPreviewKey("clock", options, iconBack)
        synchronized(thumbnails) { thumbnails.get(cacheKey) }?.let { return it }
        return withContext(Dispatchers.Default) {
            val art = DynamicIcons.loadClock(active, active.extras.clock, options.fixedTintColor?.toInt(), options.fixedTintStrength, options.fixedTintBlendMode)
            val strip = DynamicIcons.clockStrip(
                art, options.fixedShape, !options.fixedShape.isOriginal, options.fixedTintColor?.toInt(),
                options.padBackground.toInt(), iconBack, options.fixedTintBlendMode,
                options.fixedComposition, options.fixedIconScale, options.fixedIconAlpha, options.fixedBackgroundScale,
            )
            val size = NebulaSpec.ASSET_SIZE
            val out = Bitmaps.square(size)
            val canvas = Canvas(out)
            val dst = Rect(0, 0, size, size)
            fun frame(index: Int) = Rect(index * size, 0, (index + 1) * size, size)
            canvas.drawBitmap(strip, frame(2), dst, null) // dial
            canvas.drawBitmap(strip, frame(0), dst, null) // hour hand
            canvas.drawBitmap(strip, frame(1), dst, null) // minute hand
            strip.recycle()
            out
        }.also { synchronized(thumbnails) { thumbnails.put(cacheKey, it) } }
    }

    override fun onCleared() {
        closeSource()
        wallpaperFile?.delete()
        fixedBackgroundFile?.delete()
        super.onCleared()
    }

    private fun load(opener: suspend () -> ThemeSource) {
        closeSource()
        wallpaperFile?.delete()
        wallpaperFile = null
        fixedBackgroundFile?.delete()
        fixedBackgroundFile = null
        val base = baseState(mutableState.value)
        mutableState.value = base.copy(stage = Stage.LOADING)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stock = StockComponentIndex.parse(app.assets.open("stock_components.txt").bufferedReader().use { it.readText() })
                    val installed = InstalledApps.launcherActivities(app)
                    Triple(opener(), IconPlanner(stock, installed), installed)
                }
            }.onSuccess { (opened, newPlanner, installed) ->
                source = opened
                planner = newPlanner
                outputNameEdited = false
                val packages = opened.icons.map { it.component?.packageName ?: it.key }.toSet()
                val visiblePackageStems = installed.keys.mapTo(HashSet(), AppComponent::sanitize)
                val visibleSystemApps = ZteSystemApps.all.filter { systemApp ->
                    systemApp.stems.any { it.substringBefore('-') in visiblePackageStems }
                }
                val visibleIds = visibleSystemApps.mapTo(HashSet(), ZteSystemApp::id)
                mutableState.value = base.copy(
                    stage = Stage.CONFIGURE,
                    summary = SourceSummary(
                        kind = opened.kind,
                        fileName = opened.fileName,
                        imageCount = opened.icons.map { it.id }.toSet().size,
                        packageCount = packages.size,
                        installedPackages = packages.count { it in installed },
                        hasCalendar = opened.extras.calendar != null,
                        hasClock = opened.extras.clock != null,
                        hasWallpaper = opened.extras.wallpapers.isNotEmpty() || opened.extras.wallpaper != null,
                        hasIconBack = opened.extras.iconBack != null,
                    ),
                    labelZh = opened.suggestedLabel,
                    labelEn = opened.suggestedLabel,
                    author = opened.author.ifBlank { app.getString(R.string.app_name) },
                    intro = opened.description,
                    assignments = newPlanner.autoAssignSystemApps(opened.icons).filterKeys { it in visibleIds },
                    visibleSystemApps = visibleSystemApps,
                    sourceWallpapers = opened.extras.wallpapers,
                    selectedWallpaperId = opened.extras.wallpaper,
                    outputName = defaultOutputName(opened.suggestedLabel),
                )
                // A fresh gradient palette each time a source loads, so the generated wallpaper isn't the same
                // three colors on every theme unless the user deliberately picks them.
                randomizeGeneratedWallpaperColors()
                replan()
                refreshWallpaperVisual()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.value = base.copy(stage = Stage.ERROR, error = error.userMessage())
            }
        }
    }

    private fun startBuild(
        prepare: () -> Unit,
        openOutput: () -> OutputStream,
        finish: () -> String,
        cleanup: () -> Unit,
    ) {
        val current = mutableState.value
        val activeSource = source ?: return
        val activePlanner = planner ?: return
        buildJob?.cancel()
        mutableState.update { it.copy(stage = Stage.BUILDING, progress = BuildProgress(0, 1, app.getString(R.string.progress_planning)), error = null, outputPath = null) }
        buildJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { prepare() }
                val plan = withContext(Dispatchers.Default) { planFor(activeSource, activePlanner, current) }
                require(plan.icons.isNotEmpty()) { app.getString(R.string.no_icons_to_write) }
                val metadata = ThemeMetadata(
                    // Stock themes use the file name as id; the system identifies imported themes by file name.
                    id = normalizedOutputName(current.outputName).removeSuffix(".zmtp"),
                    labelEn = current.labelEn.ifBlank { current.labelZh },
                    labelZh = current.labelZh.ifBlank { current.labelEn },
                    introEn = current.intro,
                    introZh = current.intro,
                    author = current.author,
                )
                val summary = ThemeBuilder(app).build(
                    activeSource,
                    metadata,
                    current.options,
                    plan,
                    wallpaperFile,
                    current.selectedWallpaperId,
                    fixedBackgroundFile,
                    openOutput,
                    iconCurves = sourceIdCurveMap(current),
                ) { progress ->
                    mutableState.update { it.copy(progress = progress) }
                }
                summary to withContext(Dispatchers.IO) { finish() }
            }.onSuccess { (summary, path) ->
                mutableState.update { it.copy(stage = Stage.DONE, result = summary, progress = null, outputPath = path) }
            }.onFailure { error ->
                runCatching { cleanup() }
                if (error is CancellationException) throw error
                mutableState.update { it.copy(stage = Stage.CONFIGURE, progress = null, error = error.userMessage()) }
            }
        }
    }

    /**
     * Resolves [PorterState.iconCurves] (system app id / user app stem keyed) to the source icon each app is
     * currently showing, since that - not the output file name - is what [ThemeBuilder.build] keys curves by.
     * An app can plan under several output stems (its real installed activity, plus whatever aliases the icon
     * pack's own appfilter declares), and which one a given ROM's launcher actually reads isn't something this
     * app controls; keying by source icon means the edit follows the artwork through every one of those files.
     */
    private fun sourceIdCurveMap(state: PorterState): Map<String, GrayscaleCurve> = buildMap {
        state.iconCurves.forEach { (key, curve) -> resolveSourceId(state, key)?.let { put(it, curve) } }
    }

    private fun baseState(from: PorterState) = PorterState(
        hasFileAccess = StorageAccess.hasAllFilesAccess(app),
        outputDir = prefs.outputDir.absolutePath,
        lastCrash = from.lastCrash,
    )

    private fun syncOutputName() {
        if (outputNameEdited) return
        mutableState.update { it.copy(outputName = defaultOutputName(it.labelZh.ifBlank { it.labelEn })) }
    }

    private fun defaultOutputName(label: String): String {
        val base = label.replace(Regex("[\\\\/:*?\"<>|.\\s]+"), "_").trim('_').ifBlank { "nebula_theme" }
        return "$base.zmtp"
    }

    private fun normalizedOutputName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Nebula theme" }
        return if (cleaned.endsWith(".zmtp", ignoreCase = true)) cleaned else "$cleaned.zmtp"
    }

    private fun updateOptions(transform: (BuildOptions) -> BuildOptions) {
        val before = mutableState.value.options
        mutableState.update { it.copy(options = transform(it.options)) }
        val after = mutableState.value.options
        // [planFor] reads exactly these two options and nothing else, so replanning unconditionally meant every
        // tick of a tint/scale slider kicked off a full plan that could only produce the same answer - while
        // competing with the preview renders for Dispatchers.Default the whole time it did.
        if (before.onlyInstalledApps != after.onlyInstalledApps ||
            before.generateMissingAppIcons != after.generateMissingAppIcons
        ) {
            replan()
        }
    }

    private fun updateFixedTintHsv(change: (FloatArray) -> Unit) = updateOptions { options ->
        val hsv = FloatArray(3).also { Color.colorToHSV((options.fixedTintColor ?: 0xFF4285F4L).toInt(), it) }
        change(hsv)
        options.copy(fixedTintColor = Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL)
    }

    /** Wallpaper edits do not change the icon plan, so keep their preview immediate without re-planning packages. */
    private fun updateGeneratedWallpaper(transform: (com.nathanhanapps.nebulaThemePorter.core.GeneratedWallpaper) -> com.nathanhanapps.nebulaThemePorter.core.GeneratedWallpaper) {
        mutableState.update { state -> state.copy(options = state.options.copy(generatedWallpaper = transform(state.options.generatedWallpaper))) }
        if (mutableState.value.wallpaperName == null && mutableState.value.selectedWallpaperId == null) refreshWallpaperVisual()
    }

    private fun replan() {
        val activeSource = source ?: return
        val activePlanner = planner ?: return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) { planFor(activeSource, activePlanner, mutableState.value) }
            mutableState.update {
                it.copy(plannedImages = plan.icons.map { icon -> icon.sourceId }.toSet().size, plannedNames = plan.icons.size)
            }
        }
    }

    private fun planFor(source: ThemeSource, planner: IconPlanner, state: PorterState): IconPlan =
        planner.plan(
            source.icons,
            state.assignments,
            state.options.onlyInstalledApps,
            state.userAssignments,
            state.options.generateMissingAppIcons,
        )

    private fun refreshWallpaperVisual() {
        val revision = mutableState.value.wallpaperRevision + 1
        mutableState.update { it.copy(wallpaperRevision = revision) }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    wallpaperFile?.let { file -> com.nathanhanapps.nebulaThemePorter.render.Bitmaps.decode(file.readBytes(), 960) }
                        ?: mutableState.value.selectedWallpaperId?.let { source?.decode(it, 960) }
                        ?: PreviewRenderer.gradientWallpaper(540, 960, mutableState.value.options.generatedWallpaper)
                }.getOrNull()
            } ?: return@launch
            val palette = withContext(Dispatchers.Default) { wallpaperPalette(bitmap) }
            mutableState.update { current ->
                if (current.wallpaperRevision == revision) current.copy(wallpaperPreview = bitmap, wallpaperPalette = palette) else current
            }
        }
    }

    private fun wallpaperPalette(bitmap: Bitmap): List<Long> {
        val seeds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { WallpaperColors.fromBitmap(bitmap) }.getOrNull()?.let { colors ->
                listOfNotNull(colors.primaryColor.toArgb(), colors.secondaryColor?.toArgb(), colors.tertiaryColor?.toArgb())
            }.orEmpty()
        } else {
            emptyList()
        }
        val primary = seeds.getOrNull(0) ?: averageColor(bitmap)
        val secondary = seeds.getOrNull(1) ?: ColorUtils.blendARGB(primary, Color.WHITE, 0.24f)
        val tertiary = seeds.getOrNull(2) ?: ColorUtils.blendARGB(primary, 0xFF7D5260.toInt(), 0.34f)
        return listOf(
            primary,
            secondary,
            tertiary,
            ColorUtils.blendARGB(primary, Color.WHITE, 0.82f),
            ColorUtils.blendARGB(primary, Color.BLACK, 0.18f),
        ).map { (it.toLong() and 0x00FFFFFFL) or 0xFF000000L }.distinct()
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until sample.height) for (x in 0 until sample.width) {
            val color = sample.getPixel(x, y)
            if (Color.alpha(color) > 32) {
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        if (sample !== bitmap) sample.recycle()
        return if (count == 0L) 0xFF6750A4.toInt() else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun closeSource() {
        planJob?.cancel()
        source?.let { runCatching { it.close() } }
        source = null
        planner = null
        thumbnailIconBackKey = null
        thumbnailIconBack = null
        synchronized(thumbnails) { thumbnails.evictAll() }
    }

    private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}
