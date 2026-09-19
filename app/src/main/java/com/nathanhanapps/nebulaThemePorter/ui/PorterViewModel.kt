package com.nathanhanapps.nebulaThemePorter.ui

import android.app.Application
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.content.Intent
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
import com.nathanhanapps.nebulaThemePorter.build.MtzRecolorBuilder
import com.nathanhanapps.nebulaThemePorter.build.RecolorSummary
import com.nathanhanapps.nebulaThemePorter.build.ThemeBuilder
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.AppComponent
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.IconPlan
import com.nathanhanapps.nebulaThemePorter.core.IconPlanner
import com.nathanhanapps.nebulaThemePorter.core.MtzRecolor
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
import com.nathanhanapps.nebulaThemePorter.render.MiuiIconArt
import com.nathanhanapps.nebulaThemePorter.render.Shapes
import com.nathanhanapps.nebulaThemePorter.source.IconPackSource
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.source.MtzSource
import com.nathanhanapps.nebulaThemePorter.source.SourceFiles
import com.nathanhanapps.nebulaThemePorter.source.SourceKind
import com.nathanhanapps.nebulaThemePorter.source.SourceWallpaper
import com.nathanhanapps.nebulaThemePorter.source.ThemeSource
import com.nathanhanapps.nebulaThemePorter.storage.PorterPreferences
import com.nathanhanapps.nebulaThemePorter.storage.ProjectSource
import com.nathanhanapps.nebulaThemePorter.storage.ProjectStore
import com.nathanhanapps.nebulaThemePorter.storage.SavedProject
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

enum class Stage { HOME, LOADING, CONFIGURE, RECOLOR, BUILDING, DONE, ERROR }

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

/** One launcher-visible app and the artwork a recolor would give it. */
data class RecolorApp(
    val packageName: String,
    val label: String,
    /** The theme's own icon for this app, or a [DeviceIconId] when the recolor has to draw one. */
    val imageId: String,
    val generated: Boolean,
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
    /** Recoloring a MIUI theme back into a .mtz, rather than porting a source into a .zmtp. */
    val recolorMode: Boolean = false,
    /** Installed apps the opened theme has no icon for at all, which a recolor can draw one for. */
    val recolorMissing: Int = 0,
    /** Every launcher-visible app, with the icon the recolor would give it. */
    val recolorApps: List<RecolorApp> = emptyList(),
    val recolorAppsLoading: Boolean = false,
    val recolorAppsLoaded: Boolean = false,
    val progress: BuildProgress? = null,
    val result: BuildSummary? = null,
    val recolorResult: RecolorSummary? = null,
    val error: String? = null,
    val hasFileAccess: Boolean = false,
    val outputDir: String = "",
    val outputName: String = "",
    val outputPath: String? = null,
    val lastCrash: String? = null,
    /** Saved tweaks shown in the home gallery, newest first. */
    val savedProjects: List<SavedProject> = emptyList(),
    /** The saved project this session is editing, so Save updates it instead of piling up copies. */
    val currentProjectId: String? = null,
    /** Set briefly after a save so the UI can acknowledge it. */
    val savedNotice: String? = null,
    /** Build straight after the source finishes loading - the gallery's Build shortcut, which has to wait for
     * the pack to be re-read before there is anything to build. */
    val pendingBuild: Boolean = false,
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

        /** A recolor addresses an app by package, since that is how a MIUI theme names its drawables. */
        fun recolorContrastKey(packageName: String) = "recolor:$packageName"
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

    /** Package to launcher activities, kept from the last load: what a recolor filters and fills against. */
    private var installedLaunchers: Map<String, List<String>> = emptyMap()
    private var wallpaperFile: File? = null
    private var fixedBackgroundFile: File? = null
    private var outputNameEdited = false
    private var planJob: Job? = null
    private var buildJob: Job? = null
    private val projects = ProjectStore.forApp(application)

    /** How the currently loaded source would be found again, carried into any save of this session. */
    private var sourceRef: ProjectSource? = null

    init {
        refreshProjects()
    }
    private val thumbnails = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun refreshFileAccess() = mutableState.update { it.copy(hasFileAccess = StorageAccess.hasAllFilesAccess(app)) }

    fun dismissCrash() {
        CrashLog.clear(app)
        mutableState.update { it.copy(lastCrash = null) }
    }

    /** From the system file picker. Files on this phone's storage are read in place; anything else is copied first. */
    fun open(kind: SourceKind, uri: Uri) = load(
        // Without All-files access a picked document is only reachable through its Uri, and that grant dies
        // with the process - so a project saved from it would be unopenable on next launch. Ask for the
        // persistable one here, while the picker's grant is still live.
        sourceRef = ProjectSource(
            kind = kind,
            path = StorageAccess.fileFor(app, uri)?.absolutePath,
            uri = uri.takeIf { StorageAccess.fileFor(app, it) == null }?.also(::persistRead)?.toString(),
            fileName = SourceFiles.displayName(app, uri) ?: uri.lastPathSegment.orEmpty(),
        ),
    ) {
        val file = StorageAccess.fileFor(app, uri)
        when {
            file != null && kind == SourceKind.ICON_PACK -> IconPackSource.open(app, file)
            file != null -> MtzSource.open(app, file)
            kind == SourceKind.ICON_PACK -> IconPackSource.open(app, uri)
            else -> MtzSource.open(app, uri)
        }
    }

    /**
     * Opens a .mtz to recolor rather than to port. The same read as [open]: what differs is only what happens
     * to it afterwards, so a theme can be tinted and written back as a .mtz for MIUI/HyperOS phones.
     */
    fun openMtzToRecolor(uri: Uri) = load(
        sourceRef = ProjectSource(
            kind = SourceKind.MTZ,
            path = StorageAccess.fileFor(app, uri)?.absolutePath,
            uri = uri.takeIf { StorageAccess.fileFor(app, it) == null }?.also(::persistRead)?.toString(),
            fileName = SourceFiles.displayName(app, uri) ?: uri.lastPathSegment.orEmpty(),
        ),
        recolor = true,
    ) {
        val file = StorageAccess.fileFor(app, uri)
        if (file != null) MtzSource.open(app, file) else MtzSource.open(app, uri)
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

    fun openInstalledIconPack(iconPack: InstalledApps.IconPack) = load(
        sourceRef = ProjectSource(
            kind = SourceKind.ICON_PACK,
            packageName = iconPack.packageName,
            fileName = iconPack.label,
        ),
    ) {
        IconPackSource.open(app, File(iconPack.apkPath))
    }

    private fun persistRead(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
     * this gives each app whatever shift ITS icon needs, so a dark icon and a light one end up looking similarly
     * bright after tint instead of one staying an outlier.
     *
     * It measures each icon as it currently looks - through whatever curve is already on it - and shifts that
     * curve, rather than measuring the untouched source and overwriting the curve with a bare offset. Shaping a
     * few icons by hand and then evening the group out is the normal way to use these two controls together, and
     * the old behaviour silently threw that shaping away. Measuring post-curve also makes the button
     * settle: press it twice and the second press finds the means already level, so it barely moves.
     *
     * The target is the mean of the selection as it stands, so the group's overall brightness stays put and
     * icons move toward each other rather than toward a fixed reference. A later drag on the shared curve editor
     * still applies one identical curve to everything selected, replacing this. */
    fun normalizeSelection() {
        val state = mutableState.value
        val selection = state.selectedGridKeys
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val sourceIds = selection.mapNotNull { key -> resolveSourceId(state, key)?.let { key to it } }.toMap()
            if (sourceIds.isEmpty()) return@launch
            val curves = sourceIds.mapValues { (key, _) -> state.iconCurves[key] ?: GrayscaleCurve() }
            val means = sourceIds.mapValues { (key, sourceId) ->
                grayscaleMean(sourceId, curves.getValue(key).takeUnless(GrayscaleCurve::isIdentity)?.lut())
            }
            val validMeans = means.values.filterNotNull()
            if (validMeans.isEmpty()) return@launch
            val target = validMeans.average().toFloat()
            val newCurves = means.mapNotNull { (key, mean) ->
                mean ?: return@mapNotNull null
                val delta = (target - mean).coerceIn(-1f, 1f)
                key to curves.getValue(key).shiftedBy(delta)
            }.toMap()
            mutableState.update { it.copy(iconCurves = it.iconCurves + newCurves) }
        }
    }

    /**
     * The same curve moved [delta] brighter or darker. Offsetting every control point's y offsets the whole
     * spline by exactly that much: the Hermite tangents are built from secants, which depend on differences
     * between y values and so are unchanged by a constant. Clamping at the ends is the one exception, and is
     * why an icon already pinned at black or white can't be shifted the full amount.
     */
    private fun GrayscaleCurve.shiftedBy(delta: Float): GrayscaleCurve =
        GrayscaleCurve(points.map { CurvePoint(it.x, (it.y + delta).coerceIn(0f, 1f)) })

    /** Mean grayscale level (0..1, alpha-weighted) of one source icon as seen through [lut] (null for the
     * untouched source), or null if it couldn't be decoded. */
    private suspend fun grayscaleMean(sourceId: String, lut: IntArray? = null): Float? {
        val bins = grayscaleHistogram(listOf(sourceId), lut)
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
        key.startsWith("recolor:") -> {
            val packageName = key.removePrefix("recolor:")
            state.recolorApps.firstOrNull { it.packageName == packageName }?.imageId
        }
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
     * [imageIds], for the curve editor's backdrop. Reuses cached thumbnail decodes where possible. [lut], when
     * given, remaps each channel first, exactly as [com.nathanhanapps.nebulaThemePorter.render.Bitmaps.curve]
     * does before the tint runs - so the result describes the icon as it currently looks, not as it was
     * imported. */
    suspend fun grayscaleHistogram(imageIds: List<String>, lut: IntArray? = null): IntArray = withContext(Dispatchers.Default) {
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
                // Per channel, then luma - the order the real pipeline uses. Mapping the grey level instead
                // would agree only for already-grey pixels.
                val r = ((pixel shr 16) and 0xFF).let { lut?.get(it) ?: it }
                val g = ((pixel shr 8) and 0xFF).let { lut?.get(it) ?: it }
                val b = (pixel and 0xFF).let { lut?.get(it) ?: it }
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

    /** Writes the recolored theme straight into the chosen folder, as [buildToFolder] does for a port. */
    fun recolorToFolder() {
        val current = mutableState.value
        val dir = File(current.outputDir)
        val name = normalizedOutputName(current.outputName)
        val part = File(dir, "$name.part")
        startRecolor(
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

    /** Writes the recolored theme to a document chosen with the system picker. */
    fun recolorTo(destination: Uri) {
        startRecolor(
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

    /**
     * The recolor counterpart of [startBuild]. It needs no icon plan: a recolor writes the theme's own icons
     * back under their own names, so nothing has to be mapped onto ZTE file names first.
     */
    private fun startRecolor(
        prepare: () -> Unit,
        openOutput: () -> OutputStream,
        finish: () -> String,
        cleanup: () -> Unit,
    ) {
        val activeSource = source as? MtzSource ?: return
        val options = mutableState.value.options
        val curves = sourceIdCurveMap(mutableState.value)
        buildJob?.cancel()
        mutableState.update {
            it.copy(stage = Stage.BUILDING, progress = BuildProgress(0, 1, app.getString(R.string.progress_planning)), error = null, outputPath = null)
        }
        buildJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { prepare() }
                val summary = MtzRecolorBuilder(app).recolor(
                    activeSource,
                    options,
                    installedLaunchers.keys,
                    openOutput,
                    iconCurves = curves,
                ) { progress ->
                    mutableState.update { it.copy(progress = progress) }
                }
                summary to withContext(Dispatchers.IO) { finish() }
            }.onSuccess { (summary, path) ->
                mutableState.update { it.copy(stage = Stage.DONE, recolorResult = summary, progress = null, outputPath = path) }
            }.onFailure { error ->
                runCatching { cleanup() }
                if (error is CancellationException) throw error
                mutableState.update { it.copy(stage = Stage.RECOLOR, progress = null, error = error.userMessage()) }
            }
        }
    }

    /**
     * Every launcher-visible app on this phone, paired with the artwork a recolor would give it: the theme's
     * own icon where it has one, and otherwise the app's own, which the recolor draws onto the theme's plate.
     * Keyed by package rather than by activity because that is how a MIUI theme names its drawables - one
     * file per package, whichever of its activities the launcher shows.
     */
    fun loadRecolorApps() {
        val current = mutableState.value
        if (current.recolorAppsLoading || current.recolorAppsLoaded) return
        val active = source ?: return
        mutableState.update { it.copy(recolorAppsLoading = true) }
        viewModelScope.launch {
            runCatching {
                val launcherApps = withContext(Dispatchers.IO) {
                    InstalledApps.launcherApps(app)
                        .distinctBy { it.packageName }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                }
                val installed = launcherApps.mapTo(HashSet()) { it.packageName }
                // An icon named for one of a package's activities themes that package too, so every drawable
                // name is resolved back to the app it covers before asking which apps are left over.
                val covered = MtzRecolor.coverage(active.icons.map { it.key to it.id }, installed)
                launcherApps.map { launcher ->
                    val themed = covered[launcher.packageName]
                    RecolorApp(
                        packageName = launcher.packageName,
                        label = launcher.label,
                        imageId = themed ?: DeviceIconId.of(launcher.packageName, null),
                        generated = themed == null,
                    )
                }
            }.onSuccess { apps ->
                mutableState.update {
                    it.copy(
                        recolorApps = apps,
                        recolorAppsLoading = false,
                        recolorAppsLoaded = true,
                        recolorMissing = apps.count(RecolorApp::generated),
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(recolorAppsLoading = false, error = error.userMessage()) }
            }
        }
    }

    private var recolorAssetsFor: ThemeSource? = null
    private var recolorAssets: Triple<Bitmap?, Bitmap?, Bitmap?>? = null
    private val recolorAssetsLock = Mutex()

    /** The theme's own mask, plate and border at preview size, decoded once per loaded theme and untinted. */
    private suspend fun recolorAssets(): Triple<Bitmap?, Bitmap?, Bitmap?> {
        val active = source ?: return Triple(null, null, null)
        recolorAssets?.takeIf { recolorAssetsFor === active }?.let { return it }
        return recolorAssetsLock.withLock {
            recolorAssets?.takeIf { recolorAssetsFor === active } ?: withContext(Dispatchers.IO) {
                fun decode(id: String?) = id?.let { runCatching { active.decode(it, NebulaSpec.PREVIEW_ICON_SIZE) }.getOrNull() }
                Triple(decode(active.extras.iconMask), decode(active.extras.iconBack), decode(active.extras.iconUpon))
            }.also {
                recolorAssetsFor = active
                recolorAssets = it
            }
        }
    }

    /**
     * What one app will look like in the recolored theme. An app the theme already covers goes through the
     * same [thumbnail] path as everything else; one it doesn't is composed exactly as
     * [MtzRecolorBuilder] would compose it, so the grid shows the icon that is actually written rather than a
     * plain tinted glyph.
     */
    suspend fun recolorThumbnail(entry: RecolorApp, options: BuildOptions, curve: GrayscaleCurve = GrayscaleCurve()): Bitmap? {
        val tint = options.fixedTintColor?.toInt()
        if (!entry.generated || !options.fixedShape.isOriginal) {
            return thumbnail(
                entry.imageId, tint, options.fixedTintStrength, curve,
                options.takeUnless { it.fixedShape.isOriginal }, options.generatedIconOwnBackground, options.fixedTintBlendMode,
            )
        }
        val cacheKey = buildString {
            append("miui:${entry.imageId}|bg:${options.generatedIconOwnBackground}")
            append("|tint:$tint:${options.fixedTintStrength}:${options.fixedTintBlendMode}")
            if (!curve.isIdentity) append("|c:${curve.points}")
        }
        synchronized(thumbnails) { thumbnails.get(cacheKey) }?.let { return it }
        val glyph = withContext(Dispatchers.IO) {
            runCatching {
                InstalledApps.decodeIcon(app, entry.imageId, NebulaSpec.PREVIEW_ICON_SIZE * 2, options.generatedIconOwnBackground)
            }.getOrNull()
        } ?: return null
        val (mask, plate, border) = recolorAssets()
        val scale = (source as? MtzSource)?.iconScale ?: 1f
        val rendered = withContext(Dispatchers.Default) {
            // The cached plate and border are untinted, so tint copies for this render and drop them after:
            // they are shared with every other cell, which may be showing a different colour by then.
            val tintedPlate = plate?.let { source -> tint?.let { Bitmaps.tint(source, it, options.fixedTintStrength, options.fixedTintBlendMode) } }
            val tintedBorder = border?.let { source -> tint?.let { Bitmaps.tint(source, it, options.fixedTintStrength, options.fixedTintBlendMode) } }
            MiuiIconArt.render(
                glyph, NebulaSpec.PREVIEW_ICON_SIZE, mask, tintedPlate ?: plate, tintedBorder ?: border, scale,
                tint, options.fixedTintStrength, options.fixedTintBlendMode,
                curveLut = if (curve.isIdentity) null else curve.lut(),
            ).also {
                tintedPlate?.recycle()
                tintedBorder?.recycle()
            }
        }
        glyph.recycle()
        synchronized(thumbnails) { thumbnails.put(cacheKey, rendered) }
        return rendered
    }

    fun back() {
        when (mutableState.value.stage) {
            Stage.DONE -> mutableState.update {
                it.copy(stage = if (it.recolorMode) Stage.RECOLOR else Stage.CONFIGURE, result = null, recolorResult = null)
            }
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

    /**
     * [sourceRef] records how this source could be reopened later; [restore] replays a saved project's tweaks
     * over the freshly read source once it has loaded, which is what makes reopening a project cheap - the pack
     * is re-read from disk, but nothing the user decided about it has to be redone.
     */
    private fun load(sourceRef: ProjectSource?, restore: SavedProject? = null, recolor: Boolean = false, opener: suspend () -> ThemeSource) {
        closeSource()
        wallpaperFile?.delete()
        wallpaperFile = null
        fixedBackgroundFile?.delete()
        fixedBackgroundFile = null
        this.sourceRef = sourceRef
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
                installedLaunchers = installed
                outputNameEdited = false
                val packages = opened.icons.map { it.component?.packageName ?: it.key }.toSet()
                val visiblePackageStems = installed.keys.mapTo(HashSet(), AppComponent::sanitize)
                val visibleSystemApps = ZteSystemApps.all.filter { systemApp ->
                    systemApp.stems.any { it.substringBefore('-') in visiblePackageStems }
                }
                val visibleIds = visibleSystemApps.mapTo(HashSet(), ZteSystemApp::id)
                mutableState.value = base.copy(
                    stage = if (recolor) Stage.RECOLOR else Stage.CONFIGURE,
                    recolorMode = recolor,
                    // A generated icon here is drawn on the theme's own plate, so the app's own backdrop would
                    // only hide it - a black-backed icon stays black among a set that has turned one colour.
                    options = if (recolor) base.options.copy(generatedIconOwnBackground = false) else base.options,
                    recolorMissing = if (recolor) MtzRecolor.missingPackages(opened.icons.map { it.key }, installed.keys).size else 0,
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
                    outputName = if (recolor) MtzRecolor.outputName(opened.fileName) else defaultOutputName(opened.suggestedLabel),
                )
                if (restore == null) {
                    // A fresh gradient palette each time a source loads, so the generated wallpaper isn't the
                    // same three colors on every theme unless the user deliberately picks them.
                    randomizeGeneratedWallpaperColors()
                } else {
                    // Assignments are replayed rather than merged: the saved ones already include whatever the
                    // planner auto-assigned when the project was created, plus every manual override since.
                    restoreImages(restore)
                    mutableState.update {
                        it.copy(
                            labelZh = restore.labelZh,
                            labelEn = restore.labelEn,
                            author = restore.author,
                            intro = restore.intro,
                            outputName = restore.outputName.ifBlank { it.outputName },
                            options = restore.options,
                            assignments = restore.assignments.filterKeys { key -> key in visibleIds },
                            userAssignments = restore.userAssignments,
                            iconCurves = restore.iconCurves,
                            selectedWallpaperId = restore.selectedWallpaperId,
                            wallpaperName = restore.wallpaperName,
                            fixedBackgroundName = restore.fixedBackgroundName,
                            currentProjectId = restore.id,
                        )
                    }
                    outputNameEdited = restore.outputName.isNotBlank()
                }
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
                // A build is the point at which these tweaks are worth keeping, so the theme can be adjusted
                // and rebuilt later without re-curving every icon. Saving manually beforehand is still offered.
                // Silent: the Done screen is showing by now, and the save snackbar belongs to Configure.
                saveProject(notify = false)
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
    fun refreshProjects() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { projects.list() }.getOrDefault(emptyList()) }
            mutableState.update { it.copy(savedProjects = saved) }
        }
    }

    fun consumeSavedNotice() = mutableState.update { it.copy(savedNotice = null) }

    /**
     * Writes the current tweaks to disk, updating this session's project when there is one so repeated saves
     * (and the automatic save after a build) don't pile up near-identical copies. The imported wallpaper and
     * fixed background are copied out of the cache directory, which Android may clear at any time.
     */
    fun saveProject(notify: Boolean = true) {
        val current = mutableState.value
        val ref = sourceRef ?: return
        viewModelScope.launch {
            val id = current.currentProjectId ?: projects.newId()
            val project = SavedProject(
                id = id,
                name = current.labelZh.ifBlank { current.labelEn }.ifBlank { ref.fileName },
                savedAt = System.currentTimeMillis(),
                source = ref,
                labelZh = current.labelZh,
                labelEn = current.labelEn,
                author = current.author,
                intro = current.intro,
                outputName = current.outputName,
                options = current.options,
                assignments = current.assignments,
                userAssignments = current.userAssignments,
                iconCurves = current.iconCurves,
                selectedWallpaperId = current.selectedWallpaperId,
                wallpaperName = current.wallpaperName,
                fixedBackgroundName = current.fixedBackgroundName,
            )
            val preview = runCatching { projectPreview(current) }.getOrNull()
            withContext(Dispatchers.IO) {
                runCatching {
                    projects.write(project)
                    wallpaperFile?.copyTo(projects.wallpaperFile(id), overwrite = true)
                    fixedBackgroundFile?.copyTo(projects.backgroundFile(id), overwrite = true)
                    preview?.let { projects.previewFile(id).writeBytes(Bitmaps.png(it)) }
                }
            }
            preview?.recycle()
            mutableState.update { it.copy(currentProjectId = id, savedNotice = project.name.takeIf { _ -> notify }) }
            refreshProjects()
        }
    }

    fun consumePendingBuild() = mutableState.update { it.copy(pendingBuild = false) }

    /**
     * Reopens a saved project: re-reads its source, then replays every tweak over it. [build] is the gallery's
     * one-tap rebuild, which can only run once the source is actually loaded, so it is carried as a flag the
     * Configure screen acts on rather than being started here.
     */
    fun openProject(project: SavedProject, build: Boolean = false) {
        val ref = project.source
        load(sourceRef = ref, restore = project) {
            val file = when {
                ref.packageName != null -> InstalledApps.iconPacks(app).firstOrNull { it.packageName == ref.packageName }
                    ?.let { File(it.apkPath) }
                    ?: throw IllegalStateException(app.getString(R.string.project_source_missing, ref.fileName))
                ref.path != null -> File(ref.path).takeIf(File::exists)
                    ?: throw IllegalStateException(app.getString(R.string.project_source_missing, ref.fileName))
                else -> null
            }
            when {
                file != null && ref.kind == SourceKind.ICON_PACK -> IconPackSource.open(app, file)
                file != null -> MtzSource.open(app, file)
                ref.uri == null -> throw IllegalStateException(app.getString(R.string.project_source_missing, ref.fileName))
                ref.kind == SourceKind.ICON_PACK -> IconPackSource.open(app, Uri.parse(ref.uri))
                else -> MtzSource.open(app, Uri.parse(ref.uri))
            }
        }
        if (build) mutableState.update { it.copy(pendingBuild = true) }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { projects.delete(id) } }
            mutableState.update { if (it.currentProjectId == id) it.copy(currentProjectId = null) else it }
            refreshProjects()
        }
    }

    /** Copies a saved project's own wallpaper/background back into the cache the rest of the app reads them from. */
    private suspend fun restoreImages(project: SavedProject) = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "sources").apply { mkdirs() }
        runCatching {
            projects.wallpaperFile(project.id).takeIf(File::exists)
                ?.copyTo(File(dir, "wallpaper"), overwrite = true)
                ?.also { wallpaperFile = it }
            projects.backgroundFile(project.id).takeIf(File::exists)
                ?.copyTo(File(dir, "fixed_icon_background"), overwrite = true)
                ?.also { fixedBackgroundFile = it }
        }
        Unit
    }

    /** A few representative icons side by side, as the gallery card's picture of what this project looks like. */
    private suspend fun projectPreview(state: PorterState): Bitmap? {
        val ids = fixedPreviewIconChoices().take(4).ifEmpty { return null }
        val size = NebulaSpec.PREVIEW_ICON_SIZE
        val tiles = ids.mapNotNull { id ->
            thumbnail(
                id, state.options.fixedTintColor?.toInt(), state.options.fixedTintStrength,
                GrayscaleCurve(), state.options.takeUnless { it.fixedShape.isOriginal },
                state.options.generatedIconOwnBackground, state.options.fixedTintBlendMode,
            )
        }
        if (tiles.isEmpty()) return null
        val out = Bitmap.createBitmap(size * tiles.size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        tiles.forEachIndexed { index, tile ->
            canvas.drawBitmap(tile, null, android.graphics.Rect(index * size, 0, (index + 1) * size, size), null)
        }
        return out
    }

    private fun sourceIdCurveMap(state: PorterState): Map<String, GrayscaleCurve> = buildMap {
        state.iconCurves.forEach { (key, curve) -> resolveSourceId(state, key)?.let { put(it, curve) } }
    }

    private fun baseState(from: PorterState) = PorterState(
        hasFileAccess = StorageAccess.hasAllFilesAccess(app),
        outputDir = prefs.outputDir.absolutePath,
        lastCrash = from.lastCrash,
        // The gallery belongs to the app, not to whichever source happens to be open.
        savedProjects = from.savedProjects,
    )

    private fun syncOutputName() {
        if (outputNameEdited) return
        mutableState.update { it.copy(outputName = defaultOutputName(it.labelZh.ifBlank { it.labelEn })) }
    }

    private fun defaultOutputName(label: String): String {
        val base = label.replace(Regex("[\\\\/:*?\"<>|.\\s]+"), "_").trim('_').ifBlank { "nebula_theme" }
        return "$base.zmtp"
    }

    /** A recolor stays a MIUI theme, so it keeps the .mtz extension the Themes app there looks for. */
    private fun normalizedOutputName(raw: String): String {
        val extension = if (mutableState.value.recolorMode) ".mtz" else ".zmtp"
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Nebula theme" }
        return if (cleaned.endsWith(extension, ignoreCase = true)) cleaned else "$cleaned$extension"
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
