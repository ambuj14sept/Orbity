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
 * (`orbity.logServer` in local.properties, reached over Tailscale). Disabled when not configured.
 */
object LogUploader {
    private const val MAX_QUEUED = 300
    private const val RETRY_MS = 5_000L

    private class Item(val channel: String, val text: String, val onSent: (() -> Unit)? = null)

    private val server = BuildConfig.LOG_SERVER.trimEnd('/')
    val enabled = server.isNotBlank()

    private val queue = LinkedBlockingDeque<Item>()
    private val crashFilesQueued = mutableSetOf<String>()

    /** null until the first upload attempt; shown on the phone screen. */
    var reachable by mutableStateOf<Boolean?>(null)
        private set

    init {
        if (enabled) thread(name = "orbity-upload", isDaemon = true) { uploadLoop() }
    }

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
            while (!post(item)) Thread.sleep(RETRY_MS)
            item.onSent?.invoke()
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
