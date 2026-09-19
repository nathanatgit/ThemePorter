package com.nathanhanapps.nebulaThemePorter.debug

import android.os.Bundle
import android.os.SystemClock
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nathanhanapps.nebulaThemePorter.build.MtzRecolorBuilder
import com.nathanhanapps.nebulaThemePorter.build.ThemeBuilder
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.MtzRecolor
import com.nathanhanapps.nebulaThemePorter.core.FixedIconComposition
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.IconPlanner
import com.nathanhanapps.nebulaThemePorter.core.StockComponentIndex
import com.nathanhanapps.nebulaThemePorter.core.ThemeMetadata
import com.nathanhanapps.nebulaThemePorter.core.TintBlendMode
import com.nathanhanapps.nebulaThemePorter.source.IconPackSource
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.source.MtzSource
import java.io.File
import kotlinx.coroutines.launch

private const val NL = "\n"

/**
 * Builds a theme without the UI. Push the input into the app's external files directory, then:
 *
 * adb shell am start -n com.nathanhanapps.nebulaThemePorter/.debug.DebugBuildActivity \
 *   --es source theme.mtz --es output out.zmtp --ez onlyInstalled true
 *
 * Add --ez recolor true to rewrite a .mtz as a tinted .mtz instead of porting it, which needs no plan and
 * ignores every option except the ones that decide how an icon looks (--es tintColor, --es blendMode,
 * --es shape, --ez onlyInstalled, --ez fillMissing).
 *
 * Progress and the result go to <output>.status.txt next to the output (logcat is unreliable on NebulaAIOS).
 * The status file's first line is RUNNING, DONE or FAILED. The same text is mirrored on screen - a headless
 * build of a large pack takes tens of seconds, and without it the phone just shows a blank activity with no
 * indication of whether anything is happening. The activity stays up on the result rather than finishing, so
 * the outcome is still readable afterwards; start it again to run another build (force-stop first).
 */
class DebugBuildActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = TextView(this).apply {
            textSize = 13f
            setPadding(40, 80, 40, 40)
            keepScreenOn = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(screen) })
        val dir = getExternalFilesDir(null) ?: filesDir
        val sourceName = intent.getStringExtra("source")
        val recolor = intent.getBooleanExtra("recolor", false)
        val defaultOutput = if (recolor) {
            MtzRecolor.outputName(sourceName.orEmpty())
        } else {
            "${sourceName?.substringBeforeLast('.')}.zmtp"
        }
        val output = intent.getStringExtra("output") ?: defaultOutput
        val status = File(dir, "$output.status.txt")
        if (sourceName == null) {
            report(screen, status, "FAILED" + NL + "missing --es source")
            return
        }
        val onlyInstalled = intent.getBooleanExtra("onlyInstalled", true)
        val shape = intent.getStringExtra("shape")?.let { runCatching { FixedIconShape.valueOf(it) }.getOrNull() } ?: FixedIconShape.NONE
        val composition = intent.getStringExtra("composition")?.let { runCatching { FixedIconComposition.valueOf(it) }.getOrNull() } ?: FixedIconComposition.OVERLAY
        val padColor = intent.getStringExtra("padColor")?.let { runCatching { android.graphics.Color.parseColor(it).toLong() and 0xFFFFFFFFL }.getOrNull() }
        val tintColor = intent.getStringExtra("tintColor")?.let { runCatching { android.graphics.Color.parseColor(it).toLong() and 0xFFFFFFFFL }.getOrNull() }
        val blendMode = intent.getStringExtra("blendMode")?.let { runCatching { TintBlendMode.valueOf(it) }.getOrNull() } ?: TintBlendMode.MULTIPLY
        val iconScale = intent.getFloatExtra("iconScale", 0.66f)
        val backgroundScale = intent.getFloatExtra("backgroundScale", 1f)
        report(screen, status, "RUNNING")

        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val notes = StringBuilder()
            runCatching {
                val input = File(dir, sourceName)
                val source = if (sourceName.endsWith(".apk")) IconPackSource.open(this@DebugBuildActivity, input) else MtzSource.open(this@DebugBuildActivity, input)
                source.use { src ->
                    if (recolor) {
                        require(src is MtzSource) { "recolor needs a .mtz source" }
                        val options = BuildOptions(
                            onlyInstalledApps = onlyInstalled, fixedShape = shape, fixedComposition = composition,
                            padBackground = padColor ?: 0xFFFFFFFFL, fixedTintColor = tintColor,
                            fixedTintBlendMode = blendMode,
                            fixedIconScale = iconScale, fixedBackgroundScale = backgroundScale,
                            generateMissingAppIcons = intent.getBooleanExtra("fillMissing", true),
                            // Matches the recolor screen's own default, so a headless run shows what the app does.
                            generatedIconOwnBackground = intent.getBooleanExtra("ownBackground", false),
                        )
                        val installed = InstalledApps.launcherActivities(this@DebugBuildActivity).keys
                        notes.append("recolor icons=${src.icons.size} installed=${installed.size}\n")
                        notes.append("shape=$shape tint=$tintColor blend=$blendMode onlyInstalled=$onlyInstalled\n")
                        return@use MtzRecolorBuilder(this@DebugBuildActivity).recolor(
                            src, options, installed, { File(dir, output).outputStream() },
                        ) { progress ->
                            val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                            report(
                                screen, status,
                                "RUNNING" + NL + "%.1fs  ${progress.done}/${progress.total}  ${progress.label}".format(elapsed) + NL + notes,
                            )
                        }
                    }
                    val stock = StockComponentIndex.parse(assets.open("stock_components.txt").bufferedReader().use { it.readText() })
                    val planner = IconPlanner(stock, InstalledApps.launcherActivities(this@DebugBuildActivity))
                    val options = BuildOptions(
                        onlyInstalledApps = onlyInstalled, fixedShape = shape, fixedComposition = composition,
                        padBackground = padColor ?: 0xFFFFFFFFL, fixedTintColor = tintColor,
                        fixedTintBlendMode = blendMode,
                        fixedIconScale = iconScale, fixedBackgroundScale = backgroundScale,
                    )
                    val assignments = planner.autoAssignSystemApps(src.icons)
                    val plan = planner.plan(src.icons, assignments, onlyInstalled)
                    notes.append("plan sources=${src.icons.size} images=${plan.icons.map { it.sourceId }.toSet().size} names=${plan.icons.size}\n")
                    notes.append("system=${assignments.keys.sorted()}\n")
                    notes.append("comp=$composition icon=$iconScale bg=$backgroundScale\n")
                    notes.append("shape=$shape tint=$tintColor blend=$blendMode\n")
                    val metadata = ThemeMetadata(
                        id = output.removeSuffix(".zmtp"),
                        labelEn = src.suggestedLabel,
                        labelZh = src.suggestedLabel,
                        introEn = src.description,
                        introZh = src.description,
                        author = src.author.ifBlank { "Nebula Theme Porter" },
                    )
                    ThemeBuilder(this@DebugBuildActivity).build(
                        src,
                        metadata,
                        options,
                        plan,
                        null,
                        src.extras.wallpaper,
                        null,
                        { File(dir, output).outputStream() },
                    ) { progress ->
                        val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                        report(
                            screen, status,
                            "RUNNING" + NL + "%.1fs  ${progress.done}/${progress.total}  ${progress.label}".format(elapsed) + NL + notes,
                        )
                    }
                }
            }.onSuccess { summary ->
                report(screen, status, "DONE" + NL + summary + NL + "${SystemClock.elapsedRealtime() - started} ms" + NL + notes)
            }.onFailure { error ->
                report(screen, status, "FAILED" + NL + notes + error.stackTraceToString())
            }
        }
    }

    /** One place to write the status file and put the same text on screen, so they can never disagree. */
    private fun report(screen: TextView, status: File, text: String) {
        status.writeText(text)
        runOnUiThread { screen.text = text }
    }

}
