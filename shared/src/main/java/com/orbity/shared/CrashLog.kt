package com.orbity.shared

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves uncaught exceptions to app storage so they can be shared later; there is no
 * logcat access when the devices are not connected to a computer.
 */
object CrashLog {
    private const val DIR = "crash-reports"

    /** Message size on the Data Layer is limited to ~100 KB. */
    const val MAX_REPORT_CHARS = 60_000

    fun install(context: Context, source: String) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                save(appContext, "$source crashed on thread ${thread.name}\n\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun save(context: Context, report: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = "Orbity report $time\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n\n"
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, "report-${System.currentTimeMillis()}.txt").writeText((header + report).take(MAX_REPORT_CHARS))
    }

    fun reports(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()?.sortedBy { it.name }.orEmpty()

    fun clear(context: Context) {
        reports(context).forEach { it.delete() }
    }
}
