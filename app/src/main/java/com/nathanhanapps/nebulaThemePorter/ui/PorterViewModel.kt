package com.nathanhanapps.nebulaThemePorter.ui

import android.app.Application
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
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
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.SourceIcon
import com.nathanhanapps.nebulaThemePorter.core.StockComponentIndex
import com.nathanhanapps.nebulaThemePorter.core.ThemeMetadata
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApp
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApps
import com.nathanhanapps.nebulaThemePorter.render.PreviewRenderer
import com.nathanhanapps.nebulaThemePorter.render.FixedIconArt
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val sourceWallpapers: List<SourceWallpaper> = emptyList(),
    val selectedWallpaperId: String? = null,
    val wallpaperName: String? = null,
    val fixedBackgroundName: String? = null,
    val fixedPreviewIconId: String? = null,
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

    suspend fun thumbnail(imageId: String): Bitmap? {
        synchronized(thumbnails) { thumbnails.get(imageId) }?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (DeviceIconId.isDeviceIcon(imageId)) InstalledApps.decodeIcon(app, imageId, 128)
                else source?.decode(imageId, 128)
            }.getOrNull()
        } ?: return null
        synchronized(thumbnails) { thumbnails.put(imageId, bitmap) }
        return bitmap
    }

    fun fixedPreviewIconId(): String? = mutableState.value.fixedPreviewIconId ?: fixedPreviewIconChoices().firstOrNull()

    fun setFixedPreviewIcon(imageId: String?) {
        if (imageId != null && source?.icons?.none { it.id == imageId } != false) return
        mutableState.update { it.copy(fixedPreviewIconId = imageId) }
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

    /** A disposable sample for the fixed-icon controls; callers own and recycle the returned bitmap. */
    suspend fun fixedIconPreview(imageId: String, options: BuildOptions): Bitmap? {
        val active = source ?: return null
        // Decoding and pixel work must never run in the Compose/UI coroutine while a slider is moving.
        return withContext(Dispatchers.IO) {
            val icon = runCatching { active.decode(imageId, 256) }.getOrNull() ?: return@withContext null
            val iconBack = fixedBackgroundFile?.let { file ->
                runCatching { com.nathanhanapps.nebulaThemePorter.render.Bitmaps.decode(file.readBytes(), NebulaSpec.FIXED_ICON_SIZE) }.getOrNull()
            } ?: active.extras.iconBack?.let { id ->
                runCatching { active.decode(id, NebulaSpec.FIXED_ICON_SIZE) }.getOrNull()
            }
            try {
                withContext(Dispatchers.Default) {
                    FixedIconArt.render(
                        source = icon,
                        shape = options.fixedShape,
                        composition = options.fixedComposition,
                        iconScale = options.fixedIconScale,
                        iconAlpha = options.fixedIconAlpha,
                        tintColor = options.fixedTintColor?.toInt(),
                        tintStrength = options.fixedTintStrength,
                        backgroundScale = options.fixedBackgroundScale,
                        padColor = options.padBackground.toInt(),
                        iconBack = iconBack,
                    )
                }
            } finally {
                icon.recycle()
                iconBack?.recycle()
            }
        }
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
        mutableState.update { it.copy(options = transform(it.options)) }
        replan()
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
        synchronized(thumbnails) { thumbnails.evictAll() }
    }

    private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}
