package com.nathanhanapps.nebulaThemePorter

import android.content.Context
import java.io.File

/**
 * Keeps uncaught exceptions in Android/data/<package>/files/crash, because NebulaAIOS does not reliably keep
 * app entries in logcat. The home screen shows the latest one.
 */
object CrashLog {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(context.applicationContext, previous))
    }

    /** Full latest report: the UI truncates its display but makes this complete text available for copying. */
    fun latestReport(context: Context): String? = runCatching {
        dir(context)?.listFiles { file -> file.name.startsWith(PREFIX) }
            ?.maxByOrNull(File::lastModified)
            ?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        dir(context)?.listFiles { file -> file.name.startsWith(PREFIX) }?.forEach(File::delete)
    }

    private fun dir(context: Context): File? = context.getExternalFilesDir("crash")

    private const val PREFIX = "crash-"

    private class Recorder(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                dir(context)?.let { File(it, "$PREFIX${System.currentTimeMillis()}.txt").writeText(error.stackTraceToString()) }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
