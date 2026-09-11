package com.nathanhanapps.nebulaThemePorter.debug

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nathanhanapps.nebulaThemePorter.build.ThemeBuilder
import com.nathanhanapps.nebulaThemePorter.core.BuildOptions
import com.nathanhanapps.nebulaThemePorter.core.IconPlanner
import com.nathanhanapps.nebulaThemePorter.core.IconShape
import com.nathanhanapps.nebulaThemePorter.core.StockComponentIndex
import com.nathanhanapps.nebulaThemePorter.core.ThemeMetadata
import com.nathanhanapps.nebulaThemePorter.core.ThemeStyle
import com.nathanhanapps.nebulaThemePorter.source.IconPackSource
import com.nathanhanapps.nebulaThemePorter.source.InstalledApps
import com.nathanhanapps.nebulaThemePorter.source.MtzSource
import java.io.File
import kotlinx.coroutines.launch

/**
 * Builds a theme without the UI. Push the input into the app's external files directory, then:
 *
 * adb shell am start -n com.nathanhanapps.nebulaThemePorter/.debug.DebugBuildActivity \
 *   --es source theme.mtz --es output out.zmtp --es style ADAPTIVE --ez onlyInstalled true
 *
 * Progress and the result go to <output>.status.txt next to the output (logcat is unreliable on NebulaAIOS).
 * The status file's first line is RUNNING, DONE or FAILED.
 */
class DebugBuildActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir(null) ?: filesDir
        val sourceName = intent.getStringExtra("source")
        val output = intent.getStringExtra("output") ?: "${sourceName?.substringBeforeLast('.')}.zmtp"
        val status = File(dir, "$output.status.txt")
        if (sourceName == null) {
            status.writeText("FAILED\nmissing --es source\n")
            finish()
            return
        }
        val style = intent.getStringExtra("style")?.let(ThemeStyle::valueOf) ?: ThemeStyle.ADAPTIVE
        val shape = intent.getStringExtra("shape")?.let(IconShape::valueOf) ?: IconShape.SQUIRCLE
        val onlyInstalled = intent.getBooleanExtra("onlyInstalled", true)
        status.writeText("RUNNING\n")

        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val notes = StringBuilder()
            runCatching {
                val input = File(dir, sourceName)
                val source = if (sourceName.endsWith(".apk")) IconPackSource.open(this@DebugBuildActivity, input) else MtzSource.open(this@DebugBuildActivity, input)
                source.use { src ->
                    val stock = StockComponentIndex.parse(assets.open("stock_components.txt").bufferedReader().use { it.readText() })
                    val planner = IconPlanner(stock, InstalledApps.launcherActivities(this@DebugBuildActivity))
                    val options = BuildOptions(style = style, defaultShape = shape, onlyInstalledApps = onlyInstalled)
                    val assignments = planner.autoAssignSystemApps(src.icons)
                    val plan = planner.plan(src.icons, assignments, style, onlyInstalled)
                    notes.append("plan sources=${src.icons.size} images=${plan.icons.map { it.sourceId }.toSet().size} names=${plan.icons.size}\n")
                    notes.append("system=${assignments.keys.sorted()}\n")
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
                        status.writeText("RUNNING\n${progress.done}/${progress.total} ${progress.label}\n$notes")
                    }
                }
            }.onSuccess { summary ->
                status.writeText("DONE\n$summary\n${SystemClock.elapsedRealtime() - started} ms\n$notes")
            }.onFailure { error ->
                status.writeText("FAILED\n$notes${error.stackTraceToString()}")
            }
            finish()
        }
    }
}
