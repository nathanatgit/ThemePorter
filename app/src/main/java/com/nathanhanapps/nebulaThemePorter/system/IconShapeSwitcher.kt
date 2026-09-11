package com.nathanhanapps.nebulaThemePorter.system

import android.content.Context
import com.nathanhanapps.nebulaThemePorter.R
import com.nathanhanapps.nebulaThemePorter.core.IconShape
import java.util.concurrent.TimeUnit

/**
 * Switches the icon shape of the applied theme the same way the Themes app does.
 *
 * The Themes app sends `com.zte.theme.ICON_SHAPE_CHANGE` (extras `CONFIG_NAME`, `THIRD_PART`) to ThemeService, which
 * renames the icon cache's `theme_*_<config>.png` assets and updates `persist.sys.icon_config_mask`; the launcher
 * reloads its icons when that property changes. ThemeService does not check which theme is applied, but the receiver
 * requires `androidzte.permission.ZTE_THEME_CHANGE` (signature|privileged) and the Themes app only shows its shape
 * picker for built-in themes, so imported themes send the broadcast through root.
 */
object IconShapeSwitcher {
    private const val ACTION = "com.zte.theme.ICON_SHAPE_CHANGE"
    private const val PROP_SUPPORT = "persist.sys.supprot_icon_change_shape"
    private const val PROP_MASK = "persist.sys.icon_config_mask"
    private const val PROP_CLOSE_THIRD = "persist.sys.icon_close_third_beautify"

    /** Apps cannot read this one; it is fetched through root together with the change. */
    private const val PROP_THEME = "persist.sys.theme_icon_name"

    data class Status(val supportsShapes: Boolean, val current: IconShape?)

    /** [themeName] is the applied theme as ThemeService reports it, when root answered. */
    data class ApplyResult(val error: String?, val themeName: String?)

    /** Null when this is not a NebulaAIOS (MiFavor) system. */
    fun status(): Status? {
        val mask = prop(PROP_MASK)
        if (mask.isEmpty()) return null
        return Status(prop(PROP_SUPPORT) == "true", IconShape.entries.firstOrNull { it.configName == mask })
    }

    /** Blocks for up to a few seconds (longer while a root prompt is open); call off the main thread. */
    fun apply(context: Context, shape: IconShape): ApplyResult {
        val thirdPart = prop(PROP_CLOSE_THIRD) == "1"
        val command = "getprop $PROP_THEME; am broadcast -a $ACTION --es CONFIG_NAME ${shape.configName} --ez THIRD_PART $thirdPart"
        val result = run(listOf("su", "-c", command), timeoutSeconds = 60)
            ?: return ApplyResult(context.getString(R.string.root_unavailable), null)
        val lines = result.output.lines().map(String::trim).filter(String::isNotEmpty)
        if (result.exitCode != 0 || lines.none { it.startsWith("Broadcast completed") }) {
            val detail = lines.lastOrNull()?.let { ": $it" }.orEmpty()
            return ApplyResult(context.getString(R.string.root_failed, detail), null)
        }
        val themeName = lines.firstOrNull()?.takeUnless { it.startsWith("Broadcasting") }
        repeat(40) {
            if (prop(PROP_MASK) == shape.configName) return ApplyResult(null, themeName)
            Thread.sleep(150)
        }
        return ApplyResult(context.getString(R.string.shape_not_confirmed), themeName)
    }

    private data class Output(val exitCode: Int, val output: String)

    private fun prop(name: String): String = run(listOf("getprop", name))?.output?.trim().orEmpty()

    private fun run(command: List<String>, timeoutSeconds: Long = 5): Output? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        Output(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
}
