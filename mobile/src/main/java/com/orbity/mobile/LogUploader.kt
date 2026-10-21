package com.orbity.mobile

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orbity.shared.CrashLog
import com.orbity.shared.LOG_TAG
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/**
 * Sends phone logs, watch logs and crash reports to the developer's log server
 * (`orbity.logServer` in local.properties, reached over Tailscale). Only dev builds have a
 * server (and the INTERNET permission); there the user can also switch uploads off.
 */
object LogUploader {
    private const val MAX_QUEUED = 300
    private const val RETRY_MS = 5_000L
    private const val PREFS = "orbity"
    private const val KEY_ENABLED = "send_logs"

    private class Item(val channel: String, val text: String, val onSent: (() -> Unit)? = null)

    private val server = BuildConfig.LOG_SERVER.trimEnd('/')

    /** This build can upload at all (dev builds). */
    val available = server.isNotBlank()

    /** The switch on the phone screen; remembered across launches. */
    var userEnabled by mutableStateOf(true)
        private set

    val enabled get() = available && userEnabled

    private val queue = LinkedBlockingDeque<Item>()
    private val crashFilesQueued = mutableSetOf<String>()
    private var started = false

    /** null until the first upload attempt; shown on the phone screen. */
    var reachable by mutableStateOf<Boolean?>(null)
        private set

    @Synchronized
    fun init(context: Context) {
        if (!available || started) return
        started = true
        userEnabled = prefs(context).getBoolean(KEY_ENABLED, true)
        thread(name = "orbity-upload", isDaemon = true) { uploadLoop() }
    }

    fun setUserEnabled(context: Context, on: Boolean) {
        if (!available) return
        userEnabled = on
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        Log.i(LOG_TAG, "Log upload ${if (on) "on" else "off"}")
        if (on) {
            uploadCrashReports(context)
        } else {
            // Nothing queued while off is sent later.
            queue.clear()
            synchronized(crashFilesQueued) { crashFilesQueued.clear() }
            reachable = null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enqueue(channel: String, text: String) = enqueue(Item(channel, text))

    /** Uploads crash reports that have not reached the server yet; marks them sent afterwards. */
    fun uploadCrashReports(context: Context) {
        if (!enabled) return
        for (file in CrashLog.unsent(context)) {
            val isNew = synchronized(crashFilesQueued) { crashFilesQueued.add(file.name) }
            if (!isNew) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            enqueue(Item("crash", text) { CrashLog.markSent(file) })
        }
    }

    private fun enqueue(item: Item) {
        if (!enabled) return
        queue.offerLast(item)
        while (queue.size > MAX_QUEUED) queue.pollFirst()
    }

    private fun uploadLoop() {
        while (true) {
            val item = queue.takeFirst()
            var sent = false
            while (enabled) {
                if (post(item)) {
                    sent = true
                    break
                }
                Thread.sleep(RETRY_MS)
            }
            if (sent) item.onSent?.invoke()
        }
    }

    private fun post(item: Item): Boolean {
        val ok = runCatching {
            val channel = URLEncoder.encode(item.channel, "UTF-8")
            val connection = URL("$server/log?device=$channel").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                connection.outputStream.use { it.write(item.text.toByteArray()) }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            if (reachable != false) Log.w(LOG_TAG, "Log server unreachable: $it")
        }.getOrDefault(false)
        reachable = ok
        return ok
    }
}
