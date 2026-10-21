package com.orbity.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.orbity.shared.AUDIO_CHUNK_SAMPLES
import com.orbity.shared.AUDIO_SAMPLE_RATE
import com.orbity.shared.LOG_TAG
import com.orbity.shared.MuLaw
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Streams the phone microphone in µ-law chunks to [send] while running. [onFailure] is called
 * (on the capture thread) if the microphone cannot be opened.
 */
class LiveAudio(
    private val context: Context,
    private val onFailure: (String) -> Unit,
    private val send: (ByteArray) -> Unit,
) {

    /** Each start gets a new generation; a capture thread exits once its generation is stale. */
    @Volatile private var generation = 0
    @Volatile private var running = false

    /** Returns false (and does nothing) without microphone permission. */
    fun start(): Boolean {
        if (running) return true
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        running = true
        val gen = ++generation
        thread(name = "orbity-mic", isDaemon = true) { captureLoop(gen) }
        return true
    }

    fun stop() {
        running = false
        generation++
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun captureLoop(gen: Int) {
        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, AUDIO_CHUNK_SAMPLES * 2 * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Live audio: microphone unavailable")
            record?.release()
            if (gen == generation) {
                running = false
                onFailure("Phone microphone is busy")
            }
            return
        }
        Log.i(LOG_TAG, "Live audio started")
        val pcm = ShortArray(AUDIO_CHUNK_SAMPLES)
        var sumSquares = 0.0
        var samples = 0L
        var lastLevelLog = SystemClock.elapsedRealtime()
        try {
            record.startRecording()
            while (gen == generation) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) {
                    Log.w(LOG_TAG, "Live audio read returned $n")
                    SystemClock.sleep(50)
                    continue
                }
                send(MuLaw.encode(pcm, n))
                for (i in 0 until n) sumSquares += pcm[i] * pcm[i].toDouble()
                samples += n
                // Level every ~5 s: silence near -90 dBFS means another recorder took the microphone.
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelLog >= LEVEL_LOG_MS) {
                    val rms = sqrt(sumSquares / samples)
                    val dbfs = if (rms > 0) 20 * log10(rms / Short.MAX_VALUE) else -120.0
                    Log.d(LOG_TAG, "Live audio level ${"%.0f".format(Locale.US, dbfs)} dBFS")
                    sumSquares = 0.0
                    samples = 0
                    lastLevelLog = now
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Live audio failed", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            if (gen == generation) running = false
            Log.i(LOG_TAG, "Live audio stopped")
        }
    }

    private companion object {
        const val LEVEL_LOG_MS = 5_000L
    }
}
