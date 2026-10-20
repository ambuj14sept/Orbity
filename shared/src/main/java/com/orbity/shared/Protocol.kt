package com.orbity.shared

/** Data Layer message paths exchanged between the watch remote and the phone camera. */
object Paths {
    // watch -> phone
    const val START = "/orbity/start"       // open camera on phone and start streaming preview
    const val STOP = "/orbity/stop"         // stop streaming preview
    const val SHUTTER = "/orbity/shutter"   // payload: [timerSeconds]
    const val FLIP = "/orbity/flip"
    const val FLASH = "/orbity/flash"       // payload: Flash.name
    const val FILTER = "/orbity/filter"     // payload: Filter.name
    const val ZOOM = "/orbity/zoom"         // payload: zoom ratio as UTF-8 text
    const val EXPOSURE = "/orbity/exposure" // payload: exposure compensation index as UTF-8 text
    const val CRASH = "/orbity/crash"       // payload: crash report text from the watch

    // phone -> watch
    const val PREVIEW = "/orbity/preview"     // payload: square JPEG frame (unfiltered)
    const val STATE = "/orbity/state"         // payload: CameraState.encode()
    const val COUNTDOWN = "/orbity/countdown" // payload: [secondsRemaining]
    const val CAPTURED = "/orbity/captured"   // payload: JPEG thumbnail of the saved photo
    const val CAPTURE_FAILED = "/orbity/capture_failed"
    const val NEEDS_PHONE = "/orbity/needs_phone" // phone could not open itself; user must tap notification
    const val CLOSED = "/orbity/closed"           // camera screen left the foreground on the phone
}

/** Declared by the phone app in res/values/wear.xml so the watch can find it. */
const val CAPABILITY_PHONE = "orbity_phone"

enum class Flash(val label: String) { OFF("Off"), AUTO("Auto"), ON("On") }

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
) {
    fun encode(): ByteArray = listOf(
        flash.name, frontCamera, filter.name, zoomRatio, minZoom, maxZoom,
        exposure, exposureMin, exposureMax, exposureStep,
    ).joinToString(";").toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CameraState? = runCatching {
            val p = String(bytes).split(";")
            require(p.size == 10)
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
            )
        }.getOrNull()
    }
}
