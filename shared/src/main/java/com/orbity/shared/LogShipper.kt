package com.orbity.shared

import android.os.Process
import kotlin.concurrent.thread

/** Log tag used by all Orbity code. */
const val LOG_TAG = "Orbity"

/**
 * Streams this app's own logcat and hands it to [start]'s sink in batches every couple of
 * seconds. Batches the sink cannot deliver are kept (up to a limit) and retried.
 */
object LogShipper {
    private const val FLUSH_MS = 2_000L
    private const val MAX_CHUNK = 60_000      // stays under the Data Layer's ~100 KB message limit
    private const val MAX_BUFFER = 500_000    // oldest output is dropped beyond this while offline

    private val buffer = StringBuilder()
    private var started = false

    /**
     * [filters] are logcat filter specs (e.g. "Orbity:V", "*:W"). [sink] runs on a background
     * thread and returns true once the text is delivered.
     */
    @Synchronized
    fun start(header: String, filters: List<String>, sink: (String) -> Boolean) {
        if (started) return
        started = true
        append("===== $header =====\n")
        thread(name = "orbity-logcat", isDaemon = true) { captureLogcat(filters) }
        thread(name = "orbity-logship", isDaemon = true) { shipLoop(sink) }
    }

    private fun captureLogcat(filters: List<String>) {
        var restart = false
        while (true) {
            runCatching {
                val command = mutableListOf("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                // On restart only stream new lines; the first run also dumps what is already buffered.
                if (restart) command += listOf("-T", "1")
                command += filters
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().forEachLine { append(it + "\n") }
            }.onFailure { append("[log capture error: $it]\n") }
            restart = true
            Thread.sleep(5_000)
        }
    }

    private fun shipLoop(sink: (String) -> Boolean) {
        while (true) {
            Thread.sleep(FLUSH_MS)
            val chunk = takeChunk() ?: continue
            val delivered = runCatching { sink(chunk) }.getOrDefault(false)
            if (!delivered) {
                synchronized(buffer) {
                    buffer.insert(0, chunk)
                    trim()
                }
            }
        }
    }

    private fun takeChunk(): String? = synchronized(buffer) {
        if (buffer.isEmpty()) return null
        var end = minOf(MAX_CHUNK, buffer.length)
        if (end < buffer.length) {
            // Prefer to cut at a line break.
            val newline = buffer.lastIndexOf("\n", end - 1)
            if (newline > 0) end = newline + 1
        }
        val chunk = buffer.substring(0, end)
        buffer.delete(0, end)
        chunk
    }

    private fun append(text: String) = synchronized(buffer) {
        buffer.append(text)
        trim()
    }

    private fun trim() {
        if (buffer.length > MAX_BUFFER) buffer.delete(0, buffer.length - MAX_BUFFER)
    }
}
