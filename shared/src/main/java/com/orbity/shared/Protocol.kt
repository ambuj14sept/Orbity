package com.orbity.shared

import java.nio.ByteBuffer

/** Data Layer message paths exchanged between the watch remote and the phone camera. */
object Paths {
    // watch -> phone
    const val START = "/orbity/start"       // open camera on phone and start streaming preview
    const val STOP = "/orbity/stop"         // stop streaming preview
    const val SHUTTER = "/orbity/shutter"   // payload: [timerSeconds]; in video mode starts/stops recording
    const val FLIP = "/orbity/flip"
    const val MODE = "/orbity/mode"         // payload: CaptureMode.name
    const val FLASH = "/orbity/flash"       // payload: Flash.name
    const val FILTER = "/orbity/filter"     // payload: Filter.name
    const val ZOOM = "/orbity/zoom"         // payload: zoom ratio as UTF-8 text
    const val EXPOSURE = "/orbity/exposure" // payload: exposure compensation index as UTF-8 text
    const val FOCUS = "/orbity/focus"       // payload: "x,y" tap position, 0..1 across the watch preview
    const val FRAME_ACK = "/orbity/frame_ack" // payload: 4-byte sequence number of the frame just shown
    const val LISTEN = "/orbity/listen"     // payload: "1" stream the phone microphone to the watch, "0" stop
    const val CRASH = "/orbity/crash"       // payload: crash report text from the watch
    const val LOG = "/orbity/log"           // payload: batch of watch logcat lines

    // phone -> watch
    const val PREVIEW = "/orbity/preview"     // payload: 4-byte sequence number + square JPEG (unfiltered)
    const val STATE = "/orbity/state"         // payload: CameraState.encode()
    const val COUNTDOWN = "/orbity/countdown" // payload: [secondsRemaining]
    const val CAPTURED = "/orbity/captured"   // payload: JPEG thumbnail of the saved photo or video
    const val CAPTURE_FAILED = "/orbity/capture_failed"
    const val FOCUS_RESULT = "/orbity/focus_result" // payload: "1" focused, "0" not
    const val RECORDING = "/orbity/recording"       // payload: "1" started, "0" stopped
    const val AUDIO = "/orbity/audio"               // payload: µ-law chunk, see Audio.kt
    const val LISTEN_FAILED = "/orbity/listen_failed" // payload: reason text
    const val NEEDS_PHONE = "/orbity/needs_phone" // phone could not open itself; user must tap notification
    const val CLOSED = "/orbity/closed"           // camera screen left the foreground on the phone
}

/** Declared by the phone app in res/values/wear.xml so the watch can find it. */
const val CAPABILITY_PHONE = "orbity_phone"

enum class Flash(val label: String) { OFF("Off"), AUTO("Auto"), ON("On") }

/** Night, HDR and Portrait use the phone maker's camera extensions and appear only where supported. */
enum class CaptureMode(val label: String) {
    PHOTO("Photo"), VIDEO("Video"), NIGHT("Night"), HDR("HDR"), PORTRAIT("Portrait");

    val isVideo get() = this == VIDEO
}

/**
 * Snapshot of the phone camera. The phone sends it when the camera (re)binds or the watch
 * (re)attaches; in between, the watch updates its copy optimistically as the bezel turns.
 */
data class CameraState(
    val flash: Flash = Flash.OFF,
    val frontCamera: Boolean = false,
    val filter: Filter = Filter.ORIGINAL,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val exposure: Int = 0,
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
    val exposureStep: Float = 0f,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val availableModes: List<CaptureMode> = listOf(CaptureMode.PHOTO, CaptureMode.VIDEO),
    val recording: Boolean = false,
) {
    fun encode(): ByteArray = listOf(
        flash.name, frontCamera, filter.name, zoomRatio, minZoom, maxZoom,
        exposure, exposureMin, exposureMax, exposureStep,
        mode.name, availableModes.joinToString(",") { it.name }, recording,
    ).joinToString(";").toByteArray()

    companion object {
        private const val FIELDS = 13

        fun decode(bytes: ByteArray): CameraState? = runCatching {
            val p = String(bytes).split(";")
            require(p.size == FIELDS)
            CameraState(
                flash = Flash.valueOf(p[0]),
                frontCamera = p[1].toBooleanStrict(),
                filter = Filter.valueOf(p[2]),
                zoomRatio = p[3].toFloat(),
                minZoom = p[4].toFloat(),
                maxZoom = p[5].toFloat(),
                exposure = p[6].toInt(),
                exposureMin = p[7].toInt(),
                exposureMax = p[8].toInt(),
                exposureStep = p[9].toFloat(),
                mode = CaptureMode.valueOf(p[10]),
                availableModes = p[11].split(",").map { CaptureMode.valueOf(it) },
                recording = p[12].toBooleanStrict(),
            )
        }.getOrNull()
    }
}

/** Parses a FOCUS payload; null when malformed or outside 0..1. */
fun parseFocusPoint(text: String): Pair<Float, Float>? {
    val parts = text.split(",")
    if (parts.size != 2) return null
    val x = parts[0].toFloatOrNull() ?: return null
    val y = parts[1].toFloatOrNull() ?: return null
    return if (x in 0f..1f && y in 0f..1f) x to y else null
}

/**
 * Maps a point on the watch preview back into the phone's analysis image. The watch shows the
 * centre square of the analysis image rotated upright by [rotationDegrees] and, for the front
 * camera, mirrored; this undoes those steps. Returns pixel coordinates in the
 * [width] x [height] analysis buffer.
 */
fun watchPointToBuffer(
    u: Float, v: Float, width: Int, height: Int, rotationDegrees: Int, mirrored: Boolean,
): Pair<Float, Float> {
    val mu = if (mirrored) 1f - u else u
    // Inverse of rotating the square clockwise by rotationDegrees.
    val (x, y) = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> v to 1f - mu
        180 -> 1f - mu to 1f - v
        270 -> 1f - v to mu
        else -> mu to v
    }
    val side = minOf(width, height)
    return (width - side) / 2f + x * side to (height - side) / 2f + y * side
}

/** Preview frames carry a sequence number so the watch can acknowledge each one. */
const val FRAME_HEADER_BYTES = 4

fun frameWithHeader(seq: Int, jpeg: ByteArray): ByteArray =
    ByteBuffer.allocate(FRAME_HEADER_BYTES + jpeg.size).putInt(seq).put(jpeg).array()

fun frameSeq(payload: ByteArray): Int? =
    if (payload.size >= FRAME_HEADER_BYTES) ByteBuffer.wrap(payload, 0, FRAME_HEADER_BYTES).int else null

fun seqBytes(seq: Int): ByteArray = ByteBuffer.allocate(FRAME_HEADER_BYTES).putInt(seq).array()
