package com.nathanhanapps.nebulaThemePorter.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nathanhanapps.nebulaThemePorter.core.IconShape
import com.nathanhanapps.nebulaThemePorter.system.IconShapeSwitcher
import java.io.File
import kotlin.concurrent.thread

/**
 * Switches the applied theme's icon shape without opening the UI:
 *
 * adb shell am broadcast -n com.nathanhanapps.nebulaThemePorter/.debug.DebugShapeReceiver --es shape SQUIRCLE
 *
 * `shape` is an IconShape name or config name. The result goes to shape.status.txt in the app's external files
 * directory; its first line is RUNNING, DONE or FAILED.
 */
class DebugShapeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = File(context.getExternalFilesDir(null) ?: context.filesDir, "shape.status.txt")
        val name = intent.getStringExtra("shape")
        val shape = IconShape.entries.firstOrNull { it.name == name || it.configName == name }
        if (shape == null) {
            status.writeText("FAILED\nmissing or unknown --es shape: $name\n")
            return
        }
        status.writeText("RUNNING\n")
        val pending = goAsync()
        thread {
            try {
                val before = IconShapeSwitcher.status()
                val result = runCatching { IconShapeSwitcher.apply(context, shape) }
                val after = IconShapeSwitcher.status()
                val error = result.exceptionOrNull()?.stackTraceToString() ?: result.getOrNull()?.error
                status.writeText(
                    "${if (error == null) "DONE" else "FAILED"}\nbefore=$before\nafter=$after\n" +
                        "theme=${result.getOrNull()?.themeName}\n${error.orEmpty()}\n",
                )
            } finally {
                pending.finish()
            }
        }
    }
}
