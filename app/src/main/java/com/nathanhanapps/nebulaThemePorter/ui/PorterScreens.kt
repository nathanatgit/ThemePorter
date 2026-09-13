@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nathanhanapps.nebulaThemePorter.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.build.BuildProgress
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.CurvePoint
import com.nathanhanapps.nebulaThemePorter.core.DeviceIconId
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.FixedIconComposition
import com.nathanhanapps.nebulaThemePorter.core.GradientMix
import com.nathanhanapps.nebulaThemePorter.core.GrayscaleCurve
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.TintBlendMode
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApp
import com.nathanhanapps.nebulaThemePorter.core.ZteSystemApps
import com.nathanhanapps.nebulaThemePorter.render.Shapes
import com.nathanhanapps.nebulaThemePorter.source.SourceKind
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.storage.StorageAccess
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import androidx.core.graphics.ColorUtils

private const val GITHUB_REPO_URL = "https://github.com/nathanatgit/ThemePorter"

@Composable
fun PorterApp(viewModel: PorterViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showInstalledIconPacks by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshFileAccess()
        onPauseOrDispose { }
    }

    val openIconPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.open(SourceKind.ICON_PACK, it) }
    }
    val openMtz = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.open(SourceKind.MTZ, it) }
    }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setWallpaper)
    }
    val pickFixedBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setFixedBackground)
    }
    val saveTheme = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::build)
    }
    val chooseOutputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setOutputFolder)
    }
    val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshFileAccess()
    }
    val legacyAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshFileAccess()
    }
    val requestFileAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { allFilesAccess.launch(StorageAccess.appSettingsIntent(context)) }
                .onFailure { runCatching { allFilesAccess.launch(StorageAccess.generalSettingsIntent()) } }
        } else {
            legacyAccess.launch(StorageAccess.legacyPermissions)
        }
    }

    BackHandler(enabled = state.stage in setOf(Stage.CONFIGURE, Stage.DONE, Stage.ERROR)) { viewModel.back() }

    when (state.stage) {
        Stage.HOME -> HomeScreen(
            state = state,
            onInstalledIconPack = {
                showInstalledIconPacks = true
                viewModel.loadInstalledIconPacks()
            },
            onIconPackFile = { openIconPack.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
            onMtz = { openMtz.launch(arrayOf("*/*")) },
            onRequestAccess = requestFileAccess,
            onDismissCrash = viewModel::dismissCrash,
            onCopyCrash = { report ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.last_crash_title), report))
                Toast.makeText(context, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            },
            onOpenThemes = { openThemesApp(context) },
        )
        Stage.LOADING -> BusyScreen(stringResource(R.string.reading_source), null)
        Stage.CONFIGURE -> ConfigureScreen(
            state = state,
            viewModel = viewModel,
            onPickWallpaper = { pickWallpaper.launch(arrayOf("image/*")) },
            onPickFixedBackground = { pickFixedBackground.launch(arrayOf("image/png")) },
            // Nubia DocumentsUI disables choosing /Theme itself. Start from its normal location picker instead.
            onChooseOutputFolder = { runCatching { chooseOutputFolder.launch(null) } },
            onRequestAccess = requestFileAccess,
            onBuild = {
                if (state.hasFileAccess) viewModel.buildToFolder() else saveTheme.launch(viewModel.suggestedFileName())
            },
        )
        Stage.BUILDING -> BusyScreen(state.progress?.label ?: stringResource(R.string.building), state.progress)
        Stage.DONE -> DoneScreen(state, onAdjust = viewModel::back, onHome = viewModel::reset, onOpenThemes = { openThemesApp(context) })
        Stage.ERROR -> ErrorScreen(state.error.orEmpty(), onHome = viewModel::reset)
    }

    if (state.stage == Stage.HOME && showInstalledIconPacks) {
        InstalledIconPackDialog(
            state = state,
            onRefresh = viewModel::loadInstalledIconPacks,
            onPick = { pack ->
                showInstalledIconPacks = false
                viewModel.openInstalledIconPack(pack)
            },
            onDismiss = { showInstalledIconPacks = false },
        )
    }
}

private fun themesAppIntent(context: Context) =
    context.packageManager.getLaunchIntentForPackage(StorageAccess.THEMES_APP_PACKAGE)

private fun openThemesApp(context: Context) {
    themesAppIntent(context)?.let { runCatching { context.startActivity(it) } }
}

@Composable
private fun HomeScreen(
    state: PorterState,
    onInstalledIconPack: () -> Unit,
    onIconPackFile: () -> Unit,
    onMtz: () -> Unit,
    onRequestAccess: () -> Unit,
    onDismissCrash: () -> Unit,
    onCopyCrash: (String) -> Unit,
    onOpenThemes: () -> Unit,
) {
    val context = LocalContext.current
    val canOpenThemes = remember(context) { themesAppIntent(context) != null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text("NTP", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            stringResource(R.string.home_title),
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.home_tagline),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            state.lastCrash?.let { crash ->
                item {
                    CrashNoticeCard(crash, onCopyCrash, onDismissCrash)
                }
            }

            if (!state.hasFileAccess) {
                item {
                    NoticeCard(
                        title = stringResource(R.string.all_files_access_title),
                        detail = stringResource(R.string.all_files_access_detail),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        actionLabel = stringResource(R.string.open_settings),
                        onAction = onRequestAccess,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.create_theme), stringResource(R.string.choose_source)) }
            item {
                SourceCard(
                    badge = "APP",
                    title = stringResource(R.string.installed_icon_pack),
                    subtitle = stringResource(R.string.installed_icon_pack_subtitle),
                    detail = stringResource(R.string.installed_icon_pack_detail),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onInstalledIconPack,
                )
            }
            item {
                SourceCard(
                    badge = "APK",
                    title = stringResource(R.string.icon_pack_apk),
                    subtitle = stringResource(R.string.icon_pack_subtitle),
                    detail = stringResource(R.string.icon_pack_detail),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onIconPackFile,
                )
            }
            item {
                SourceCard(
                    badge = "MTZ",
                    title = stringResource(R.string.miui_theme),
                    subtitle = stringResource(R.string.miui_theme_subtitle),
                    detail = stringResource(R.string.miui_theme_detail),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = onMtz,
                )
            }

            if (canOpenThemes) {
                item {
                    FilledTonalButton(
                        onClick = onOpenThemes,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.open_themes_app))
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.private_by_design),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = stringResource(R.string.github_repo_link),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Hint(stringResource(R.string.offline_detail))
                        Hint(
                            if (state.hasFileAccess) {
                                stringResource(R.string.themes_save_to, StorageAccess.friendlyPath(state.outputDir))
                            } else {
                                stringResource(R.string.save_then_apply)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    badge: String,
    title: String,
    subtitle: String,
    detail: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor, contentColor = contentColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp, pressedElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = CircleShape, color = contentColor.copy(alpha = 0.12f), contentColor = contentColor) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Text(badge, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.78f))
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun InstalledIconPackDialog(
    state: PorterState,
    onRefresh: () -> Unit,
    onPick: (InstalledApps.IconPack) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.installed_icon_packs), style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
                    actions = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) } },
                )
                when {
                    state.installedIconPacksLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.detecting_icon_packs))
                        }
                    }
                    state.installedIconPacksLoaded && state.installedIconPacks.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.no_installed_icon_packs), textAlign = TextAlign.Center)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { Hint(stringResource(R.string.installed_icon_packs_detail)) }
                        items(state.installedIconPacks, key = { it.packageName }) { pack ->
                            ElevatedCard(
                                onClick = { onPick(pack) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text("APK", modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(pack.label, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            pack.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text("›", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CrashNoticeCard(report: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onCopy(report) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.last_crash_title), style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(report, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.crash_copy_hint), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onCopy(report) }) { Text(stringResource(R.string.copy_crash)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    detail: String,
    containerColor: Color,
    contentColor: Color,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun BusyScreen(label: String, progress: BuildProgress?) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (progress == null) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (progress != null) {
                        Text("${(progress.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureScreen(
    state: PorterState,
    viewModel: PorterViewModel,
    onPickWallpaper: () -> Unit,
    onPickFixedBackground: () -> Unit,
    onChooseOutputFolder: () -> Unit,
    onRequestAccess: () -> Unit,
    onBuild: () -> Unit,
) {
    val summary = state.summary ?: return
    val options = state.options
    // fixedPreviewIconChoices sorts the whole source's icon list, which can be thousands of entries for a large
    // pack; remember it per loaded source instead of recomputing on every recomposition (e.g. every keystroke
    // in an unrelated text field), which was previously making typing feel laggy once a big source was loaded.
    val fixedPreviewIconChoices = remember(summary) { viewModel.fixedPreviewIconChoices() }
    var pickerSystemAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var systemAppsExpanded by rememberSaveable { mutableStateOf(false) }
    var pickerUserStem by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    val selectionActive = state.selectedGridKeys.isNotEmpty()
    BackHandler(enabled = selectionActive) { viewModel.clearGridSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (selectionActive) {
                        Text(
                            pluralStringResource(R.plurals.icons_selected_count, state.selectedGridKeys.size, state.selectedGridKeys.size),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        Column {
                            Text(stringResource(R.string.customize_theme), style = MaterialTheme.typography.titleLarge)
                            Text(summary.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (selectionActive) {
                        TextButton(onClick = viewModel::clearGridSelection) { Text(stringResource(R.string.cancel)) }
                    } else {
                        TextButton(onClick = viewModel::reset) { Text(stringResource(R.string.close)) }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (selectionActive) {
                    CurveSelectionPanel(state, viewModel)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.plan_summary, state.plannedImages, state.plannedNames),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = onBuild,
                            enabled = state.plannedNames > 0 && (state.labelZh.isNotBlank() || state.labelEn.isNotBlank()),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                if (state.hasFileAccess) stringResource(R.string.build_into, File(state.outputDir).name)
                                else stringResource(R.string.build_zmtp),
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                // Tapping anywhere that isn't itself clickable (a grid cell, a field, ...) collapses the curve
                // panel; those consume their own tap first, so this only ever fires on genuinely empty space.
                .pointerInput(selectionActive) {
                    if (selectionActive) detectTapGestures(onTap = { viewModel.clearGridSelection() })
                },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SummaryCard(summary) }
            item {
                Section(stringResource(R.string.theme_info)) {
                    OutlinedTextField(state.labelZh, viewModel::setLabelZh, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name_chinese)) }, singleLine = true)
                    OutlinedTextField(state.labelEn, viewModel::setLabelEn, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name_english)) }, singleLine = true)
                    OutlinedTextField(state.author, viewModel::setAuthor, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.author)) }, singleLine = true)
                    OutlinedTextField(state.intro, viewModel::setIntro, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.description)) }, maxLines = 4)
                }
            }
            item {
                Section(stringResource(R.string.output)) {
                    OutlinedTextField(state.outputName, viewModel::setOutputName, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true)
                    if (state.hasFileAccess) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                StorageAccess.friendlyPath(state.outputDir),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.StartEllipsis,
                            )
                            TextButton(onClick = onChooseOutputFolder) { Text(stringResource(R.string.change_folder)) }
                        }
                        Hint(stringResource(R.string.replace_file_hint))
                    } else {
                        Hint(stringResource(R.string.choose_save_each_time))
                        TextButton(onClick = onRequestAccess) { Text(stringResource(R.string.allow_all_files_access)) }
                    }
                }
            }
            item {
                Section(stringResource(R.string.wallpaper)) {
                    val selectedLabel = state.wallpaperName
                        ?: state.sourceWallpapers.firstOrNull { it.id == state.selectedWallpaperId }?.label
                        ?: stringResource(R.string.generated_gradient)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.width(96.dp)
                                .aspectRatio(NebulaSpec.WALLPAPER_WIDTH.toFloat() / NebulaSpec.WALLPAPER_HEIGHT.toFloat())
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            state.wallpaperPreview?.let { preview ->
                                Image(
                                    preview.asImageBitmap(),
                                    contentDescription = stringResource(R.string.selected_wallpaper_preview),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selectedLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.sourceWallpapers.isNotEmpty()) {
                                Hint(pluralStringResource(R.plurals.embedded_wallpapers_found, state.sourceWallpapers.size, state.sourceWallpapers.size))
                            } else {
                                Hint(stringResource(R.string.no_embedded_wallpaper))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                WallpaperChoiceCard(
                                    label = stringResource(R.string.generated),
                                    imageId = null,
                                    selected = state.wallpaperName == null && state.selectedWallpaperId == null,
                                    viewModel = viewModel,
                                    onClick = { viewModel.selectSourceWallpaper(null) },
                                )
                                state.sourceWallpapers.forEach { wallpaper ->
                                    WallpaperChoiceCard(
                                        label = wallpaper.label,
                                        imageId = wallpaper.id,
                                        selected = state.wallpaperName == null && state.selectedWallpaperId == wallpaper.id,
                                        viewModel = viewModel,
                                        onClick = { viewModel.selectSourceWallpaper(wallpaper.id) },
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (state.wallpaperName != null) TextButton(onClick = viewModel::clearWallpaper) { Text(stringResource(R.string.use_source)) }
                                FilledTonalButton(onClick = onPickWallpaper) { Text(stringResource(R.string.choose_image)) }
                            }
                        }
                    }
                    if (state.wallpaperName == null && state.selectedWallpaperId == null) {
                        GeneratedWallpaperStudio(options, viewModel)
                    }
                }
            }
            item {
                Section(stringResource(R.string.fixed_icon_shape)) {
                    FixedShapePicker(options.fixedShape, viewModel::setFixedShape, state.fixedBackgroundName, onPickFixedBackground, viewModel::clearFixedBackground)
                    Hint(stringResource(R.string.fixed_shape_section_detail))
                }
            }
            if (!options.fixedShape.isOriginal) {
                item {
                    FixedIconCompositionSection(
                        options,
                        fixedPreviewIconChoices,
                        viewModel,
                        state.wallpaperPalette,
                        hasCalendar = summary.hasCalendar,
                        hasClock = summary.hasClock,
                    )
                }
            }
            item {
                SectionHeader(stringResource(R.string.manual_replacement), stringResource(R.string.system_apps_detail))
            }
            if (state.visibleSystemApps.isEmpty()) {
                item { Hint(stringResource(R.string.no_visible_system_apps)) }
            } else {
                item {
                    CollapsibleAppsHeader(
                        title = stringResource(R.string.system_apps),
                        expanded = systemAppsExpanded,
                        detail = pluralStringResource(R.plurals.launcher_apps_ready, state.visibleSystemApps.size, state.visibleSystemApps.size),
                        onClick = { systemAppsExpanded = !systemAppsExpanded },
                    )
                }
                if (systemAppsExpanded) {
                    items(state.visibleSystemApps.chunked(AppGridColumns), key = { row -> row.joinToString(",") { "system:${it.id}" } }) { row ->
                        AppGridRow(row.size) {
                            row.forEach { app ->
                                val key = PorterViewModel.systemContrastKey(app.id)
                                SystemAppGridCell(
                                    app = app,
                                    sourceId = state.assignments[app.id],
                                    fallbackEnabled = options.generateMissingAppIcons,
                                    tintColor = options.fixedTintColor,
                                    tintStrength = options.fixedTintStrength,
                                    curve = state.iconCurves[key] ?: GrayscaleCurve(),
                                    fixedOptions = options,
                                    selected = key in state.selectedGridKeys,
                                    viewModel = viewModel,
                                    onClick = {
                                        if (state.selectedGridKeys.isEmpty()) pickerSystemAppId = app.id else viewModel.toggleGridSelection(key)
                                    },
                                    onLongClick = {
                                        if (state.selectedGridKeys.isEmpty()) viewModel.startGridSelection(key) else viewModel.toggleGridSelection(key)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                CollapsibleUserAppsHeader(
                    expanded = state.userAppsExpanded,
                    loading = state.userAppsLoading,
                    loaded = state.userAppsLoaded,
                    count = state.userApps.size,
                    onClick = viewModel::toggleUserApps,
                )
            }
            if (state.userAppsExpanded && state.userAppsLoading) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            if (state.userAppsExpanded && !state.userAppsLoading) {
                items(state.userApps.chunked(AppGridColumns), key = { row -> row.joinToString(",") { "user:${it.stem}" } }) { row ->
                    AppGridRow(row.size) {
                        row.forEach { app ->
                            val sourceId = state.userAssignments[app.stem] ?: state.userSuggestions[app.stem]
                            val key = PorterViewModel.userContrastKey(app.stem)
                            UserAppGridCell(
                                app = app,
                                sourceId = sourceId,
                                manuallyAssigned = app.stem in state.userAssignments,
                                fallbackEnabled = options.generateMissingAppIcons,
                                tintColor = options.fixedTintColor,
                                tintStrength = options.fixedTintStrength,
                                curve = state.iconCurves[key] ?: GrayscaleCurve(),
                                fixedOptions = options,
                                selected = key in state.selectedGridKeys,
                                viewModel = viewModel,
                                onClick = {
                                    if (state.selectedGridKeys.isEmpty()) pickerUserStem = app.stem else viewModel.toggleGridSelection(key)
                                },
                                onLongClick = {
                                    if (state.selectedGridKeys.isEmpty()) viewModel.startGridSelection(key) else viewModel.toggleGridSelection(key)
                                },
                            )
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.options)) {
                    SwitchRow(
                        stringResource(R.string.installed_apps_only),
                        stringResource(R.string.installed_apps_detail, summary.installedPackages, summary.packageCount),
                        options.onlyInstalledApps,
                        viewModel::setOnlyInstalled,
                    )
                    SwitchRow(
                        stringResource(R.string.generate_dynamic_icons),
                        stringResource(R.string.generate_dynamic_icons_detail),
                        options.generateMissingDynamicIcons,
                        viewModel::setGenerateDynamic,
                    )
                    SwitchRow(
                        stringResource(R.string.generate_missing_app_icons),
                        stringResource(R.string.generate_missing_app_icons_detail),
                        options.generateMissingAppIcons,
                        viewModel::setGenerateMissingAppIcons,
                    )
                    if (options.generateMissingAppIcons) {
                        SwitchRow(
                            stringResource(R.string.generated_icon_own_background),
                            stringResource(R.string.generated_icon_own_background_detail),
                            options.generatedIconOwnBackground,
                            viewModel::setGeneratedIconOwnBackground,
                        )
                    }
                }
            }
        }
    }

    pickerSystemAppId?.let { appId ->
        val app = ZteSystemApps.byId(appId)
        if (app != null) {
            val language = LocalConfiguration.current.locales[0].language
            IconPickerDialog(
                title = if (language == Locale.CHINESE.language) app.labelZh else app.labelEn,
                subtitle = null,
                viewModel = viewModel,
                tintColor = options.fixedTintColor,
                tintStrength = options.fixedTintStrength,
                fixedOptions = options,
                onAssign = { viewModel.assign(app.id, it) },
                onDismiss = { pickerSystemAppId = null },
            )
        }
    }
    pickerUserStem?.let { stem ->
        state.userApps.firstOrNull { it.stem == stem }?.let { app ->
            IconPickerDialog(
                title = app.label,
                subtitle = app.packageName,
                viewModel = viewModel,
                tintColor = options.fixedTintColor,
                tintStrength = options.fixedTintStrength,
                fixedOptions = options,
                onAssign = { viewModel.assignUserApp(stem, it) },
                onDismiss = { pickerUserStem = null },
            )
        }
    }
}

/**
 * Docked above the build button while [PorterState.selectedGridKeys] is non-empty. Shows a grayscale histogram
 * of every selected icon with an editable tone curve over it; every drag/tap/long-press applies live to all of
 * them via [PorterViewModel.setCurveForSelection] - see [CurveEditor] for the gesture handling itself.
 */
@Composable
private fun CurveSelectionPanel(state: PorterState, viewModel: PorterViewModel) {
    val selection = state.selectedGridKeys
    val firstKey = selection.firstOrNull() ?: return
    val curve = state.iconCurves[firstKey] ?: GrayscaleCurve()
    val selectionSignature = remember(selection) { selection.sorted().joinToString(",") }
    val imageIds = remember(selectionSignature, state.assignments, state.userAssignments, state.userSuggestions, state.options.generateMissingAppIcons) {
        selection.mapNotNull { key -> viewModel.resolveSourceId(key) }
    }
    val histogram by produceState<IntArray?>(initialValue = null, selectionSignature) { value = viewModel.grayscaleHistogram(imageIds) }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.icon_contrast), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::normalizeSelection, enabled = selection.size > 1) { Text(stringResource(R.string.normalize)) }
                TextButton(onClick = { viewModel.setCurveForSelection(GrayscaleCurve()) }) { Text(stringResource(R.string.reset)) }
                TextButton(onClick = viewModel::clearGridSelection) { Text("✕") }
            }
            Text(
                stringResource(R.string.icon_contrast_detail),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
            )
            if (selection.size > 1) {
                Text(
                    stringResource(R.string.normalize_detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
            CurveEditor(
                points = curve.points,
                histogram = histogram,
                onChange = { newPoints -> viewModel.setCurveForSelection(GrayscaleCurve(newPoints)) },
            )
        }
    }
}

private const val MaxCurvePoints = 12
private val CurveHitRadius = 22.dp
private val CurveEditorSize = 200.dp

/** Margin between the canvas edge and the plotted curve area, so the start/end handles sit fully on-screen
 * instead of half-clipped at the exact corner. */
private val CurveInset = 16.dp

/**
 * A histogram-backed tone curve: tap empty space to add a point, drag any point to move it in any direction
 * (including the two endpoints - moving one inward just flat-extrapolates the curve past it, which
 * [GrayscaleCurve.lut] already does), long-press an interior point to remove it.
 */
@Composable
private fun CurveEditor(points: List<CurvePoint>, histogram: IntArray?, onChange: (List<CurvePoint>) -> Unit) {
    val latestPoints = rememberUpdatedState(points)
    val histColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.28f)
    val gridColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val curveColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.primary
    val pointCenterColor = MaterialTheme.colorScheme.onPrimary
    val maxCount = remember(histogram) { (histogram?.maxOrNull() ?: 0).coerceAtLeast(1) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(CurveEditorSize)
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    var dragIndex: Int? = null
                    val insetPx = CurveInset.toPx()
                    val hitRadiusPx = CurveHitRadius.toPx()
                    detectDragGestures(
                        onDragStart = { offset -> dragIndex = nearestCurvePointIndex(latestPoints.value, offset, plotRect(size.toSize(), insetPx), hitRadiusPx) },
                        onDragEnd = { dragIndex = null },
                        onDragCancel = { dragIndex = null },
                    ) { change, _ ->
                        val index = dragIndex ?: return@detectDragGestures
                        change.consume()
                        onChange(moveCurvePoint(latestPoints.value, index, change.position, plotRect(size.toSize(), insetPx)))
                    }
                }
                .pointerInput(Unit) {
                    val insetPx = CurveInset.toPx()
                    val hitRadiusPx = CurveHitRadius.toPx()
                    detectTapGestures(
                        onTap = { offset ->
                            val current = latestPoints.value
                            val plot = plotRect(size.toSize(), insetPx)
                            if (current.size < MaxCurvePoints && nearestCurvePointIndex(current, offset, plot, hitRadiusPx) == null) {
                                onChange(insertCurvePoint(current, offset, plot))
                            }
                        },
                        onLongPress = { offset ->
                            val current = latestPoints.value
                            val index = nearestCurvePointIndex(current, offset, plotRect(size.toSize(), insetPx), hitRadiusPx)
                            if (index != null && index != 0 && index != current.lastIndex) {
                                onChange(current.filterIndexed { i, _ -> i != index })
                            }
                        },
                    )
                },
        ) {
            val plot = plotRect(size, CurveInset.toPx())
            drawRect(color = borderColor, topLeft = plot.topLeft, size = plot.size, style = Stroke(width = 1.5f))

            if (histogram != null) {
                val barWidth = (plot.width / 256f).coerceAtLeast(1f)
                histogram.forEachIndexed { level, count ->
                    if (count == 0) return@forEachIndexed
                    val barHeight = plot.height * (count.toFloat() / maxCount).coerceIn(0f, 1f)
                    drawRect(
                        color = histColor,
                        topLeft = Offset(plot.left + level / 255f * plot.width, plot.bottom - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }
            }
            drawLine(gridColor, Offset(plot.left, plot.center.y), Offset(plot.right, plot.center.y), strokeWidth = 1f)
            drawLine(gridColor, Offset(plot.center.x, plot.top), Offset(plot.center.x, plot.bottom), strokeWidth = 1f)

            val lut = GrayscaleCurve(points).lut()
            val curvePath = Path()
            lut.forEachIndexed { level, value ->
                val x = plot.left + level / 255f * plot.width
                val y = plot.bottom - value / 255f * plot.height
                if (level == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
            }
            drawPath(curvePath, color = curveColor, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            points.forEach { point ->
                val center = curvePointToOffset(point, plot)
                drawCircle(color = pointColor, radius = 11f, center = center)
                drawCircle(color = pointCenterColor, radius = 4.5f, center = center)
            }
        }
    }
}

/**
 * The curve's 0..1 coordinate space is mapped inside this rect, inset from the canvas edges by [CurveInset] on
 * every side - without the inset, the start/end handles sit exactly on the corners, half off-canvas and much
 * harder to see or grab than the interior ones.
 */
private fun plotRect(canvasSize: Size, insetPx: Float): Rect = Rect(
    insetPx,
    insetPx,
    (canvasSize.width - insetPx).coerceAtLeast(insetPx + 1f),
    (canvasSize.height - insetPx).coerceAtLeast(insetPx + 1f),
)

private fun curvePointToOffset(point: CurvePoint, plot: Rect): Offset =
    Offset(plot.left + point.x * plot.width, plot.bottom - point.y * plot.height)

private fun offsetToCurvePoint(offset: Offset, plot: Rect): CurvePoint = CurvePoint(
    ((offset.x - plot.left) / plot.width).coerceIn(0f, 1f),
    (1f - (offset.y - plot.top) / plot.height).coerceIn(0f, 1f),
)

private fun nearestCurvePointIndex(points: List<CurvePoint>, offset: Offset, plot: Rect, thresholdPx: Float): Int? {
    var bestIndex: Int? = null
    var bestDistance = thresholdPx
    points.forEachIndexed { index, point ->
        val distance = (curvePointToOffset(point, plot) - offset).getDistance()
        if (distance <= bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

/**
 * Every point, endpoints included, is draggable in both directions - only clamped so it can't cross its
 * immediate neighbor (keeps [points] sorted by x without a resort) or leave the 0..1 plot. Pulling an endpoint
 * inward just flat-extrapolates the curve past it, which [GrayscaleCurve.lut] already does on its own.
 */
private fun moveCurvePoint(points: List<CurvePoint>, index: Int, offset: Offset, plot: Rect): List<CurvePoint> {
    val normalized = offsetToCurvePoint(offset, plot)
    val minX = if (index == 0) 0f else points[index - 1].x + 0.01f
    val maxX = if (index == points.lastIndex) 1f else points[index + 1].x - 0.01f
    val x = normalized.x.coerceIn(minX.coerceAtMost(maxX), maxX.coerceAtLeast(minX))
    return points.toMutableList().apply { this[index] = CurvePoint(x, normalized.y) }
}

private fun insertCurvePoint(points: List<CurvePoint>, offset: Offset, plot: Rect): List<CurvePoint> {
    val normalized = offsetToCurvePoint(offset, plot)
    val x = normalized.x.coerceIn(0.02f, 0.98f)
    return (points + CurvePoint(x, normalized.y)).sortedBy { it.x }
}

@Composable
private fun SummaryCard(summary: SourceSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (summary.kind == SourceKind.ICON_PACK) stringResource(R.string.icon_pack_caps) else stringResource(R.string.miui_theme_caps),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(pluralStringResource(R.plurals.image_count, summary.imageCount, summary.imageCount), style = MaterialTheme.typography.headlineSmall)
            Text(pluralStringResource(R.plurals.package_count, summary.packageCount, summary.packageCount), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Feature(stringResource(R.string.calendar), summary.hasCalendar)
                Feature(stringResource(R.string.clock), summary.hasClock)
                Feature(stringResource(R.string.wallpaper), summary.hasWallpaper)
                Feature(stringResource(R.string.icon_plate), summary.hasIconBack)
            }
        }
    }
}

@Composable
private fun Feature(label: String, present: Boolean) {
    val color = if (present) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (present) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = color, contentColor = contentColor) {
        Text(
            if (present) "✓  $label" else "—  $label",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChoiceChips(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun FixedShapePicker(
    selected: FixedIconShape,
    onSelect: (FixedIconShape) -> Unit,
    customBackgroundName: String?,
    onPickCustomBackground: () -> Unit,
    onClearCustomBackground: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FixedIconShape.entries.forEach { shape ->
            val isSelected = shape == selected
            val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                onClick = { onSelect(shape) },
                modifier = Modifier.widthIn(min = 76.dp),
                shape = MaterialTheme.shapes.medium,
                color = containerColor,
                contentColor = contentColor,
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (shape.isOriginal) {
                        Canvas(Modifier.size(36.dp)) {
                            val stroke = size.minDimension * 0.09f
                            val radius = size.minDimension / 2f - stroke
                            drawCircle(color = contentColor, radius = radius, style = Stroke(width = stroke))
                            val offset = radius * 0.70f
                            drawLine(
                                color = contentColor,
                                start = Offset(center.x - offset, center.y - offset),
                                end = Offset(center.x + offset, center.y + offset),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        Canvas(Modifier.size(36.dp)) {
                            drawPath(Shapes.path(shape, size.minDimension).asComposePath(), color = contentColor)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(localizedFixedShapeLabel(shape), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val customSelected = customBackgroundName != null
        Surface(
            onClick = onPickCustomBackground,
            modifier = Modifier.widthIn(min = 76.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (customSelected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (customSelected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            border = if (customSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("PNG", modifier = Modifier.size(36.dp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.fixed_background_custom_grid), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    customBackgroundName?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onClearCustomBackground) { Text(stringResource(R.string.clear_background)) }
        }
    }
}

@Composable
private fun FixedIconCompositionSection(
    options: com.nathanhanapps.nebulaThemePorter.core.BuildOptions,
    previewIconChoices: List<String>,
    viewModel: PorterViewModel,
    wallpaperPalette: List<Long>,
    hasCalendar: Boolean,
    hasClock: Boolean,
) {
    Section(stringResource(R.string.fixed_icon_composition)) {
        FixedIconPreviewGrid(options, previewIconChoices, viewModel, hasCalendar, hasClock)
        ChoiceChips(
            listOf(
                FixedIconComposition.OVERLAY to stringResource(R.string.fixed_comp_overlay),
                FixedIconComposition.COVER to stringResource(R.string.fixed_comp_cover),
                FixedIconComposition.CLIP to stringResource(R.string.fixed_comp_clip),
            ),
            options.fixedComposition,
            viewModel::setFixedComposition,
        )
        Hint(
            when (options.fixedComposition) {
                FixedIconComposition.OVERLAY -> stringResource(R.string.fixed_comp_overlay_detail)
                FixedIconComposition.COVER -> stringResource(R.string.fixed_comp_cover_detail)
                FixedIconComposition.CLIP -> stringResource(R.string.fixed_comp_clip_detail)
            },
        )
        if (options.fixedComposition == FixedIconComposition.OVERLAY) {
            Text(stringResource(R.string.fixed_icon_alpha, (options.fixedIconAlpha * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
            Slider(options.fixedIconAlpha, viewModel::setFixedIconAlpha, valueRange = 0f..1f)
        }
        FixedIconSizeControls(options, viewModel)
        FixedTintPaletteControls(options, wallpaperPalette, viewModel)
    }
}

/** Icon size and background size folded together behind one collapsed toggle, since they're adjusted together far
 * less often than composition/tint - keeping both expanded by default just pushed those more common controls
 * further down the section for no benefit. */
@Composable
private fun FixedIconSizeControls(options: com.nathanhanapps.nebulaThemePorter.core.BuildOptions, viewModel: PorterViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.fixed_size_controls), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
    }
    if (expanded) {
        if (options.fixedComposition != FixedIconComposition.COVER) {
            Text(stringResource(R.string.fixed_icon_size, (options.fixedIconScale * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
            Slider(options.fixedIconScale, viewModel::setFixedIconScale, valueRange = 0.25f..1.25f)
        }
        Text(stringResource(R.string.fixed_background_size, (options.fixedBackgroundScale * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(options.fixedBackgroundScale, viewModel::setFixedBackgroundScale, valueRange = 0.35f..1.25f)
    }
}

/**
 * A live, at-a-glance preview: one enlarged rendition of the first representative icon, then the same five
 * representative imported icons plus (when relevant) the calendar and clock as a strip of small tiles - every
 * tile rendered through the exact same shape/tint/curve pipeline the real build uses, not one shared "pick an
 * icon to preview" box, so a composition/tint/shape change shows its effect across several different icons and
 * the dynamic assets all at once. Calendar/clock always pick up the same tint as every other icon automatically
 * (there's no separate toggle for them); this is what catches a tint that doesn't suit them before building,
 * instead of only after opening the finished theme.
 */
@Composable
private fun FixedIconPreviewGrid(
    options: com.nathanhanapps.nebulaThemePorter.core.BuildOptions,
    previewIconChoices: List<String>,
    viewModel: PorterViewModel,
    hasCalendar: Boolean,
    hasClock: Boolean,
) {
    Text(stringResource(R.string.fixed_icon_preview_pick), style = MaterialTheme.typography.labelLarge)
    previewIconChoices.firstOrNull()?.let { primary ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Thumbnail(primary, 96, viewModel, options.fixedTintColor, options.fixedTintStrength, fixedOptions = options)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        previewIconChoices.forEach { id ->
            PreviewCell {
                Thumbnail(id, 48, viewModel, options.fixedTintColor, options.fixedTintStrength, fixedOptions = options)
            }
        }
        if (hasCalendar || options.generateMissingDynamicIcons) {
            DynamicPreviewCell(stringResource(R.string.calendar), options, viewModel::calendarIconPreview)
        }
        if (hasClock || options.generateMissingDynamicIcons) {
            DynamicPreviewCell(stringResource(R.string.clock), options, viewModel::clockIconPreview)
        }
    }
    Hint(stringResource(R.string.fixed_icon_preview_pick_detail))
}

@Composable
private fun DynamicPreviewCell(
    label: String,
    options: com.nathanhanapps.nebulaThemePorter.core.BuildOptions,
    preview: suspend (com.nathanhanapps.nebulaThemePorter.core.BuildOptions) -> android.graphics.Bitmap?,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        options.fixedShape, options.fixedComposition, options.fixedTintColor, options.fixedTintStrength, options.fixedTintBlendMode,
        options.fixedIconScale, options.fixedIconAlpha, options.fixedBackgroundScale, options.padBackground, options.generateMissingDynamicIcons,
    ) {
        delay(90)
        value = preview(options)
    }
    PreviewCell(label) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun PreviewCell(label: String? = null, content: @Composable BoxScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FixedTintPaletteControls(
    options: com.nathanhanapps.nebulaThemePorter.core.BuildOptions,
    palette: List<Long>,
    viewModel: PorterViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.material_you_palette), style = MaterialTheme.typography.titleSmall)
        CollapsibleTintPanel(
            title = stringResource(R.string.background_tint),
            detail = stringResource(R.string.selected_wallpaper_palette_detail),
        ) {
            BackgroundTintControls(
                selected = options.padBackground,
                palette = palette,
                onSelect = viewModel::setPadBackground,
            )
            if (options.fixedComposition != FixedIconComposition.OVERLAY) {
                Hint(stringResource(R.string.fixed_comp_background_may_be_hidden))
            }
        }
        CollapsibleTintPanel(
            title = stringResource(R.string.fixed_icon_tint),
            detail = stringResource(R.string.fixed_icon_tint_detail),
        ) {
            IconTintControls(options.fixedTintColor, options.fixedTintStrength, options.fixedTintBlendMode, palette, viewModel)
        }
    }
}

@Composable
private fun CollapsibleTintPanel(
    title: String,
    detail: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable
private fun IconTintControls(selected: Long?, strength: Float, blendMode: TintBlendMode, palette: List<Long>, viewModel: PorterViewModel) {
    val fallback = listOf(
        MaterialTheme.colorScheme.primary.toArgb().toLong(),
        MaterialTheme.colorScheme.secondary.toArgb().toLong(),
        MaterialTheme.colorScheme.tertiary.toArgb().toLong(),
        MaterialTheme.colorScheme.primaryContainer.toArgb().toLong(),
    ).map { it and 0xFFFFFFFFL }
    val colors = (palette.ifEmpty { fallback }).take(5)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { viewModel.setFixedTintColor(null) }, label = { Text(stringResource(R.string.fixed_shape_none)) })
        colors.forEach { color -> TintColorChip(color, selected) { viewModel.setFixedTintColor(it) } }
    }
    if (selected != null) {
        Text(stringResource(R.string.fixed_tint_blend_mode), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            listOf(
                TintBlendMode.MULTIPLY to stringResource(R.string.tint_blend_multiply),
                TintBlendMode.OVERLAY to stringResource(R.string.tint_blend_overlay),
                TintBlendMode.SOFT_LIGHT to stringResource(R.string.tint_blend_soft_light),
                TintBlendMode.LIGHTEN to stringResource(R.string.tint_blend_lighten),
            ),
            blendMode,
            viewModel::setFixedTintBlendMode,
        )
        val hsv = remember(selected) { FloatArray(3).also { android.graphics.Color.colorToHSV(selected.toInt(), it) } }
        Text(stringResource(R.string.fixed_tint_hue, hsv[0].roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(hsv[0], viewModel::setFixedTintHue, valueRange = 0f..360f)
        Text(stringResource(R.string.fixed_tint_saturation, (hsv[1] * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(hsv[1], viewModel::setFixedTintSaturation, valueRange = 0f..1f)
        Text(stringResource(R.string.fixed_tint_brightness, (hsv[2] * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(hsv[2], viewModel::setFixedTintBrightness, valueRange = 0f..1f)
        Text(stringResource(R.string.fixed_tint_strength, (strength * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(strength, viewModel::setFixedTintStrength, valueRange = 0f..1f)
    }
}

@Composable
private fun TintColorChip(color: Long, selected: Long?, onSelect: (Long) -> Unit) {
    FilterChip(
        selected = selected?.and(0x00FFFFFFL) == color.and(0x00FFFFFFL),
        onClick = { onSelect(color) },
        label = { Text(" ") },
        leadingIcon = {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Color(color.toInt()))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), CircleShape),
            )
        },
    )
}

@Composable
private fun GeneratedWallpaperStudio(options: com.nathanhanapps.nebulaThemePorter.core.BuildOptions, viewModel: PorterViewModel) {
    val settings = options.generatedWallpaper
    var editingColor by rememberSaveable { mutableStateOf<Int?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.gradient_studio), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
    }
    if (expanded) {
        Text(stringResource(R.string.gradient_colors), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientColorChip(stringResource(R.string.gradient_color_a), settings.colorA) { editingColor = 0 }
            GradientColorChip(stringResource(R.string.gradient_color_b), settings.colorB) { editingColor = 1 }
            GradientColorChip(stringResource(R.string.gradient_color_c), settings.colorC) { editingColor = 2 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = viewModel::randomizeGeneratedWallpaperColors) { Text(stringResource(R.string.randomize_colors)) }
            FilledTonalButton(onClick = viewModel::shuffleGeneratedWallpaperPlacement) { Text(stringResource(R.string.shuffle_placement)) }
        }
        Hint(stringResource(R.string.gradient_random_detail))
        Text(stringResource(R.string.gradient_mix), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            listOf(
                GradientMix.LINEAR to stringResource(R.string.gradient_mix_linear),
                GradientMix.RADIAL to stringResource(R.string.gradient_mix_radial),
                GradientMix.BLOBS to stringResource(R.string.gradient_mix_blobs),
            ),
            settings.mix,
            viewModel::setGeneratedWallpaperMix,
        )
        if (settings.mix == GradientMix.LINEAR || settings.mix == GradientMix.RADIAL) {
            Text(stringResource(R.string.gradient_angle, settings.angle.roundToInt()), style = MaterialTheme.typography.labelLarge)
            Slider(settings.angle, viewModel::setGeneratedWallpaperAngle, valueRange = 0f..360f)
        }
        Text(stringResource(R.string.gradient_blur, (settings.blur * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(settings.blur, viewModel::setGeneratedWallpaperBlur, valueRange = 0f..1f)
        editingColor?.let { slot ->
            val initial = when (slot) {
                0 -> settings.colorA
                1 -> settings.colorB
                else -> settings.colorC
            }
            ColorPickerDialog(initial, onSelect = { viewModel.setGeneratedWallpaperColor(slot, it) }, onDismiss = { editingColor = null })
        }
    }
}

@Composable
private fun GradientColorChip(label: String, color: Long, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(Color(color.toInt()))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), CircleShape),
            )
        },
    )
}

@Composable
private fun BackgroundTintControls(
    selected: Long,
    palette: List<Long>,
    onSelect: (Long) -> Unit,
) {
    val fallback = listOf(
        MaterialTheme.colorScheme.primary.toArgb().toLong(),
        MaterialTheme.colorScheme.secondary.toArgb().toLong(),
        MaterialTheme.colorScheme.tertiary.toArgb().toLong(),
        MaterialTheme.colorScheme.surfaceContainerHighest.toArgb().toLong(),
        MaterialTheme.colorScheme.primaryContainer.toArgb().toLong(),
    ).map { it and 0xFFFFFFFFL }
    val colors = (palette.ifEmpty { fallback } + listOf(0xFFFFFFFFL, 0xFFF1F3F4L, 0xFF202124L))
        .distinctBy { it and 0x00FFFFFFL }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val alpha = ((selected ushr 24) and 0xFF).toFloat() / 255f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { color ->
                TintColorChip(color, selected) { chosen ->
                    onSelect((selected and 0xFF000000L) or (chosen and 0x00FFFFFFL))
                }
            }
            FilterChip(selected = false, onClick = { showPicker = true }, label = { Text("+") })
        }
        Text(stringResource(R.string.background_opacity, (alpha * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = alpha,
            onValueChange = { value ->
                val rgb = selected and 0x00FFFFFFL
                onSelect(((value * 255).roundToInt().toLong() shl 24) or rgb)
            },
            valueRange = 0f..1f,
        )
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = selected,
            onSelect = onSelect,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ColorPickerDialog(initial: Long, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    val initialHsl = remember(initial) {
        FloatArray(3).also { ColorUtils.colorToHSL(initial.toInt(), it) }
    }
    var hue by remember(initial) { mutableStateOf(initialHsl[0]) }
    var saturation by remember(initial) { mutableStateOf(initialHsl[1]) }
    var lightness by remember(initial) { mutableStateOf(0.5f) }
    val rgb = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)).toLong() and 0x00FFFFFFL
    val chosen = (initial and 0xFF000000L) or rgb
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(72.dp).clip(MaterialTheme.shapes.medium)
                        .background(Color(chosen.toInt()))
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
                )
                Text(stringResource(R.string.hue))
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.saturation))
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
                Text(stringResource(R.string.lightness))
                Slider(value = lightness, onValueChange = { lightness = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(chosen); onDismiss() }) { Text(stringResource(R.string.use_color)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun WallpaperChoiceCard(
    label: String,
    imageId: String?,
    selected: Boolean,
    viewModel: PorterViewModel,
    onClick: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, imageId) {
        value = imageId?.let { viewModel.thumbnail(it) }?.asImageBitmap()
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(76.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(bitmap!!, contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text("N", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (selected) stringResource(R.string.selected) else stringResource(R.string.wallpaper_choice_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Hint(detail)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val AppGridColumns = 4

/** Lays out up to [AppGridColumns] grid cells in a row, padding out a short trailing row so cells stay a fixed width. */
@Composable
private fun AppGridRow(cellCount: Int, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        repeat(AppGridColumns - cellCount) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun RowScope.SystemAppGridCell(
    app: ZteSystemApp,
    sourceId: String?,
    fallbackEnabled: Boolean,
    tintColor: Long?,
    tintStrength: Float,
    curve: GrayscaleCurve,
    fixedOptions: BuildOptions,
    selected: Boolean,
    viewModel: PorterViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val key = remember(sourceId) { sourceId?.let(viewModel::iconKey) }
    val isFallback = key == null && sourceId == null && fallbackEnabled
    val language = LocalConfiguration.current.locales[0].language
    AppGridCell(
        label = if (language == Locale.CHINESE.language) app.labelZh else app.labelEn,
        thumbnailId = sourceId,
        isFallback = isFallback,
        tintColor = tintColor,
        tintStrength = tintStrength,
        curve = curve,
        fixedOptions = fixedOptions,
        selected = selected,
        viewModel = viewModel,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun CollapsibleUserAppsHeader(expanded: Boolean, loading: Boolean, loaded: Boolean, count: Int, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.user_apps), style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        loading -> stringResource(R.string.loading_launcher_apps)
                        count > 0 -> pluralStringResource(R.plurals.launcher_apps_ready, count, count)
                        loaded -> stringResource(R.string.no_user_launcher_apps)
                        else -> stringResource(R.string.user_apps_folded_detail)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
            Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** A simpler fold toggle for a list that's already loaded (no async loading state to show), e.g. system apps. */
@Composable
private fun CollapsibleAppsHeader(title: String, expanded: Boolean, detail: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
            Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun RowScope.UserAppGridCell(
    app: InstalledApps.LauncherApp,
    sourceId: String?,
    manuallyAssigned: Boolean,
    fallbackEnabled: Boolean,
    tintColor: Long?,
    tintStrength: Float,
    curve: GrayscaleCurve,
    fixedOptions: BuildOptions,
    selected: Boolean,
    viewModel: PorterViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isFallback = sourceId == null && fallbackEnabled
    val thumbnailId = sourceId ?: (if (fallbackEnabled) DeviceIconId.of(app.packageName, app.activityName) else null)
    AppGridCell(
        label = app.label,
        thumbnailId = thumbnailId,
        isFallback = isFallback,
        statusColor = if (manuallyAssigned) MaterialTheme.colorScheme.primary else null,
        tintColor = tintColor,
        tintStrength = tintStrength,
        curve = curve,
        fixedOptions = fixedOptions,
        selected = selected,
        viewModel = viewModel,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * One icon + name cell shared by the system- and user-app grids; [statusColor], if set, marks a manual override.
 * Long-pressing any cell enters batch curve-selection mode ([selected]); while it's active a tap toggles
 * selection instead of opening the icon picker - see the `selectedGridKeys`-driven callbacks in [ConfigureScreen].
 */
@Composable
private fun RowScope.AppGridCell(
    label: String,
    thumbnailId: String?,
    isFallback: Boolean,
    tintColor: Long?,
    tintStrength: Float,
    curve: GrayscaleCurve,
    fixedOptions: BuildOptions,
    selected: Boolean,
    viewModel: PorterViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    statusColor: Color? = null,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Thumbnail(thumbnailId, 48, viewModel, tintColor, tintStrength, curve, fixedOptions)
            val badgeColor = statusColor ?: if (isFallback) MaterialTheme.colorScheme.tertiary else null
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            } else if (badgeColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .background(badgeColor, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Thumbnail(
    imageId: String?,
    sizeDp: Int,
    viewModel: PorterViewModel,
    tintColor: Long? = null,
    tintStrength: Float = 1f,
    curve: GrayscaleCurve = GrayscaleCurve(),
    fixedOptions: BuildOptions? = null,
) {
    val ownBackground = fixedOptions?.generatedIconOwnBackground ?: true
    val tintBlendMode = fixedOptions?.fixedTintBlendMode ?: TintBlendMode.MULTIPLY
    val bitmap by produceState<ImageBitmap?>(initialValue = null, imageId, tintColor, tintStrength, curve, fixedOptions, ownBackground, tintBlendMode) {
        // Coalesce a slider drag into one render per settle. Every tick mints a new BuildOptions, so all of the
        // preview tiles and every visible grid cell re-key at once - and a superseded render still runs to
        // completion, because the pixel loops inside it have no suspension point to cancel at, so without this
        // a drag buries Dispatchers.Default under renders whose results are already stale.
        // produceState keeps the last value across a key change, so this waits only on a re-render: the first
        // paint of a tile that has just scrolled into view stays immediate.
        if (value != null) delay(90)
        value = imageId?.let { viewModel.thumbnail(it, tintColor?.toInt(), tintStrength, curve, fixedOptions, ownBackground, tintBlendMode) }?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp / 4).dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun IconPickerDialog(
    title: String,
    subtitle: String?,
    viewModel: PorterViewModel,
    onAssign: (String?) -> Unit,
    onDismiss: () -> Unit,
    tintColor: Long? = null,
    tintStrength: Float = 1f,
    fixedOptions: BuildOptions? = null,
) {
    var query by remember { mutableStateOf("") }
    val icons = remember(query) { viewModel.searchIcons(query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
                    actions = {
                        TextButton(onClick = {
                            onAssign(null)
                            onDismiss()
                        }) { Text(stringResource(R.string.clear)) }
                    },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    label = { Text(stringResource(R.string.search_package_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                Text(
                    pluralStringResource(R.plurals.available_icons, icons.size, icons.size),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(84.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(icons, key = { "${it.key}|${it.id}" }) { icon ->
                        ElevatedCard(
                            onClick = {
                                onAssign(icon.id)
                                onDismiss()
                            },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Thumbnail(icon.id, 56, viewModel, tintColor, tintStrength, fixedOptions = fixedOptions)
                                Text(
                                    icon.key.substringAfterLast('.'),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneScreen(state: PorterState, onAdjust: () -> Unit, onHome: () -> Unit, onOpenThemes: () -> Unit) {
    val result = state.result
    val context = LocalContext.current
    val canOpenThemes = remember { themesAppIntent(context) != null }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text("✓", modifier = Modifier.padding(horizontal = 17.dp, vertical = 11.dp), style = MaterialTheme.typography.headlineMedium)
                    }
                    Text(stringResource(R.string.theme_saved), style = MaterialTheme.typography.headlineMedium)
                    state.outputPath?.let {
                        Text(StorageAccess.friendlyPath(it), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    if (result != null) {
                        Text(
                            stringResource(R.string.build_result_summary, result.sourceImages, result.fileNames, formatBytes(result.bytes)),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        if (state.hasFileAccess) {
                            stringResource(R.string.open_themes_to_apply)
                        } else {
                            stringResource(R.string.move_then_apply)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (canOpenThemes) {
                Button(onClick = onOpenThemes, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.large) {
                    Text(stringResource(R.string.open_themes_app))
                }
            }
            FilledTonalButton(onClick = onAdjust, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.adjust_and_rebuild))
            }
            TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.convert_another)) }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onHome: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.read_file_error_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    FilledTonalButton(onClick = onHome) { Text(stringResource(R.string.back_to_home)) }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)) else "${bytes / 1024} KB"

@Composable
private fun localizedFixedShapeLabel(shape: FixedIconShape): String = stringResource(
    when (shape) {
        FixedIconShape.NONE -> R.string.fixed_shape_none
        FixedIconShape.CIRCLE -> R.string.shape_circle
        FixedIconShape.SQUARE -> R.string.shape_square
        FixedIconShape.SAMSUNG_SQUIRCLE -> R.string.shape_samsung_squircle
        FixedIconShape.IOS_SQUIRCLE -> R.string.shape_ios_squircle
        FixedIconShape.SLANTED -> R.string.shape_slanted
        FixedIconShape.FAN -> R.string.shape_fan
        FixedIconShape.PENTAGON -> R.string.shape_pentagon
        FixedIconShape.GEM -> R.string.shape_gem
        FixedIconShape.SUNNY -> R.string.shape_sunny
        FixedIconShape.COOKIE_6 -> R.string.shape_cookie_6
        FixedIconShape.COOKIE_9 -> R.string.shape_cookie_9
        FixedIconShape.COOKIE_12 -> R.string.shape_cookie_12
        FixedIconShape.CLOVER_4 -> R.string.shape_clover_4
        FixedIconShape.CLOVER_8 -> R.string.shape_clover_8
        FixedIconShape.SOFT_BURST -> R.string.shape_soft_burst
        FixedIconShape.FLOWER -> R.string.shape_flower
        FixedIconShape.GHOSTISH -> R.string.shape_ghostish
        FixedIconShape.PIXEL_CIRCLE -> R.string.shape_pixel_circle
        FixedIconShape.HEART -> R.string.shape_heart
    },
)

