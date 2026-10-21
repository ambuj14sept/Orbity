package com.orbity.wear

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.orbity.shared.AUDIO_CHUNK_SAMPLES
import com.orbity.shared.AUDIO_SAMPLE_RATE
import com.orbity.shared.LOG_TAG
import com.orbity.shared.MuLaw
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Plays live µ-law audio from the phone, dropping old chunks so playback stays near real time. */
class LivePlayer {
    private val queue = LinkedBlockingQueue<ByteArray>()

    /** Each start gets a new generation; a playback thread exits once its generation is stale. */
    @Volatile private var generation = 0
    @Volatile private var active = false

    fun start() {
        if (active) return
        active = true
        queue.clear()
        val gen = ++generation
        thread(name = "orbity-speaker", isDaemon = true) { playLoop(gen) }
    }

    fun stop() {
        active = false
        generation++
        queue.clear()
    }

    fun enqueue(chunk: ByteArray) {
        if (active) queue.offer(chunk)
    }

    private fun playLoop(gen: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_CHUNK_SAMPLES * 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.onFailure { Log.e(LOG_TAG, "Live audio: cannot open speaker", it) }.getOrNull() ?: return

        Log.i(LOG_TAG, "Live audio playback started")
        var dropped = 0
        try {
            track.play()
            while (gen == generation) {
                val chunk = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                // More than ~0.3 s waiting means we fell behind: skip ahead to stay live.
                while (queue.size > MAX_QUEUED) {
                    queue.poll()
                    if (++dropped % 25 == 1) Log.d(LOG_TAG, "Live audio behind; dropped $dropped chunks")
                }
                val pcm = MuLaw.decode(chunk)
                track.write(pcm, 0, pcm.size)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Live audio playback failed", e)
        } finally {
            runCatching { track.stop() }
            track.release()
            Log.i(LOG_TAG, "Live audio playback stopped")
        }
    }

    private companion object {
        const val MAX_QUEUED = 2
    }
}
