package com.orbity.wear

import android.app.Application
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.CAPABILITY_PHONE
import com.orbity.shared.CameraState
import com.orbity.shared.CrashLog
import com.orbity.shared.Filter
import com.orbity.shared.Flash
import com.orbity.shared.LOG_TAG
import com.orbity.shared.FRAME_HEADER_BYTES
import com.orbity.shared.Paths
import com.orbity.shared.frameSeq
import com.orbity.shared.seqBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

enum class Status { CONNECTING, OPENING, STREAMING, NEEDS_PHONE, NO_PHONE, CLOSED }

/** What the bezel currently adjusts; chosen by tapping the icons around the top edge. */
enum class Control(val label: String) {
    MODE("Mode"), ZOOM("Zoom"), FILTER("Filter"), EXPOSURE("Exposure"), TIMER("Timer"), FLASH("Flash")
}

private val TIMER_OPTIONS = listOf(0, 3, 5, 10)

/** Zoom multiplier per bezel click; the Watch 6 Classic bezel has 24 clicks per turn (1.1^24 ≈ 10x). */
private const val ZOOM_STEP = 1.1f

class RemoteViewModel(app: Application) : AndroidViewModel(app), MessageClient.OnMessageReceivedListener {

    private val messageClient = Wearable.getMessageClient(app)
    private val capabilityClient = Wearable.getCapabilityClient(app)
    private var phoneNode: String? = null
    private var introShown = false

    private var statusState by mutableStateOf(Status.CONNECTING)
    var status: Status
        get() = statusState
        private set(value) {
            if (value != statusState) Log.i(LOG_TAG, "Status ${statusState.name} -> ${value.name}")
            statusState = value
        }
    private var framesReceived = 0
    /** Newest frame shown; with two frames in flight an older one can finish decoding last. */
    private var lastShownSeq = 0
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var lastPhoto by mutableStateOf<ImageBitmap?>(null)
        private set
    var lastWasVideo by mutableStateOf(false)
        private set
    /** Live audio from the phone microphone is playing on the watch. */
    var listening by mutableStateOf(false)
        private set
    private val player = LivePlayer()

    /** True while the last capture is shown full screen. */
    var viewingLast by mutableStateOf(false)
        private set
    var countdown by mutableIntStateOf(0)
        private set
    var camera by mutableStateOf(CameraState())
        private set
    var timer by mutableIntStateOf(0)
        private set
    var control by mutableStateOf(Control.ZOOM)
        private set

    /** elapsedRealtime when the current video started, or null when not recording. */
    var recordingSince by mutableStateOf<Long?>(null)
        private set

    /** Last tap-to-focus position in screen pixels, and the phone's answer (null = pending). */
    var focusTap by mutableStateOf<Offset?>(null)
        private set
    var focusResult by mutableStateOf<Boolean?>(null)
        private set
    var focusId by mutableIntStateOf(0)
        private set

    /** Short text flashed in the middle of the screen after a bezel change. */
    var hint by mutableStateOf("")
        private set
    var hintId by mutableIntStateOf(0)
        private set
    var hintMillis by mutableLongStateOf(900L)
        private set

    /** Incremented on every successful capture so the UI can buzz. */
    var captures by mutableIntStateOf(0)
        private set

    init {
        messageClient.addListener(this)
    }

    fun connect() {
        status = Status.CONNECTING
        lastShownSeq = 0
        viewModelScope.launch {
            val node = findPhone()
            phoneNode = node
            Log.i(LOG_TAG, "Phone node: $node")
            if (node == null) {
                status = Status.NO_PHONE
            } else {
                status = Status.OPENING
                send(Paths.START)
                sendCrashReports(node)
            }
        }
    }

    fun disconnect() {
        send(Paths.STOP)
        preview = null
        stopListening()
    }

    fun toggleListening() {
        if (listening) {
            stopListening()
            send(Paths.LISTEN, "0".toByteArray())
        } else {
            listening = true
            player.start()
            send(Paths.LISTEN, "1".toByteArray())
            showHint("Listening")
        }
        Log.i(LOG_TAG, "Listening ${if (listening) "on" else "off"}")
    }

    private fun stopListening() {
        listening = false
        player.stop()
    }

    fun shutter() {
        Log.i(LOG_TAG, "Shutter: mode ${camera.mode.name}, timer $timer, recording ${camera.recording}")
        send(Paths.SHUTTER, byteArrayOf(timer.toByte()))
    }

    fun flip() {
        Log.i(LOG_TAG, "Flip camera")
        send(Paths.FLIP)
    }

    fun openLast() {
        if (lastPhoto != null) viewingLast = true
    }

    fun closeLast() {
        viewingLast = false
    }

    fun selectControl(c: Control) {
        if (c == control) return
        control = c
        Log.d(LOG_TAG, "Bezel now controls ${c.name}")
        showHint("${c.label}: ${valueLabel(c)}")
    }

    /** [x], [y] are 0..1 across the preview; [tap] is the screen position for the focus ring. */
    fun focus(x: Float, y: Float, tap: Offset) {
        if (x !in 0f..1f || y !in 0f..1f) return
        focusTap = tap
        focusResult = null
        focusId++
        Log.d(LOG_TAG, "Focus tap at ${"%.2f".format(Locale.US, x)},${"%.2f".format(Locale.US, y)}")
        send(Paths.FOCUS, String.format(Locale.US, "%.4f,%.4f", x, y).toByteArray())
    }

    /** One call per bezel click; [steps] is +1 clockwise, -1 anticlockwise. */
    fun onBezel(steps: Int) {
        val s = camera
        when (control) {
            Control.MODE -> {
                if (s.recording) return showHint("Stop recording first")
                val modes = s.availableModes
                val index = modes.indexOf(s.mode).coerceAtLeast(0)
                val mode = modes[(index + steps).mod(modes.size)]
                if (mode == s.mode) return
                camera = s.copy(mode = mode)
                send(Paths.MODE, mode.name.toByteArray())
            }
            Control.ZOOM -> {
                if (s.maxZoom <= s.minZoom) return showHint("Zoom not available")
                val ratio = (s.zoomRatio * ZOOM_STEP.pow(steps)).coerceIn(s.minZoom, s.maxZoom)
                if (ratio == s.zoomRatio) return
                camera = s.copy(zoomRatio = ratio)
                send(Paths.ZOOM, ratio.toString().toByteArray())
            }
            Control.FILTER -> {
                val filters = Filter.entries
                val filter = filters[(s.filter.ordinal + steps).mod(filters.size)]
                camera = s.copy(filter = filter)
                send(Paths.FILTER, filter.name.toByteArray())
            }
            Control.EXPOSURE -> {
                if (s.exposureMax <= s.exposureMin) return showHint("Exposure not available")
                val index = (s.exposure + steps).coerceIn(s.exposureMin, s.exposureMax)
                if (index == s.exposure) return
                camera = s.copy(exposure = index)
                send(Paths.EXPOSURE, index.toString().toByteArray())
            }
            Control.TIMER -> {
                val i = (TIMER_OPTIONS.indexOf(timer) + steps).coerceIn(0, TIMER_OPTIONS.lastIndex)
                if (TIMER_OPTIONS[i] == timer) return
                timer = TIMER_OPTIONS[i]
            }
            Control.FLASH -> {
                val i = (s.flash.ordinal + steps).coerceIn(0, Flash.entries.lastIndex)
                if (i == s.flash.ordinal) return
                camera = s.copy(flash = Flash.entries[i])
                send(Paths.FLASH, camera.flash.name.toByteArray())
            }
        }
        Log.d(LOG_TAG, "Bezel ${if (steps > 0) "+" else "-"} ${control.name} = ${valueLabel(control)}")
        showHint(valueLabel(control))
    }

    fun valueLabel(c: Control): String {
        val s = camera
        return when (c) {
            Control.MODE -> s.mode.label
            Control.ZOOM -> String.format(Locale.US, "%.1f×", s.zoomRatio)
            Control.FILTER -> s.filter.label
            Control.EXPOSURE -> String.format(Locale.US, "%+.1f EV", s.exposure * s.exposureStep)
            Control.TIMER -> if (timer == 0) "Off" else "${timer}s"
            Control.FLASH -> if (s.mode.isVideo) "Torch ${if (s.flash == Flash.ON) "On" else "Off"}" else s.flash.label
        }
    }

    /** 0..1 position for the edge arc, or null when the control has no range. */
    fun progress(c: Control): Float? {
        val s = camera
        return when (c) {
            Control.ZOOM -> if (s.maxZoom > s.minZoom && s.minZoom > 0f) {
                (ln(s.zoomRatio / s.minZoom) / ln(s.maxZoom / s.minZoom)).coerceIn(0f, 1f)
            } else null
            Control.EXPOSURE -> if (s.exposureMax > s.exposureMin) {
                (s.exposure - s.exposureMin).toFloat() / (s.exposureMax - s.exposureMin)
            } else null
            else -> null
        }
    }

    /** Non-default settings, shown under the control label so nothing is set by surprise. */
    fun activeSettings(): String = buildList {
        if (camera.mode.ordinal > 0 && control != Control.MODE) add(camera.mode.label)
        if (camera.filter != Filter.ORIGINAL && control != Control.FILTER) add(camera.filter.label)
        if (camera.flash != Flash.OFF && control != Control.FLASH) {
            add(if (camera.mode.isVideo) "Torch" else "Flash ${camera.flash.label}")
        }
        if (timer > 0 && control != Control.TIMER) add("${timer}s timer")
    }.joinToString(" · ")

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Paths.PREVIEW) {
            if (++framesReceived % 100 == 1) Log.d(LOG_TAG, "Preview frames received: $framesReceived")
        } else if (event.path != Paths.AUDIO) {
            Log.d(LOG_TAG, "phone -> ${event.path.substringAfterLast('/')} (${event.data?.size ?: 0} bytes)")
        }
        when (event.path) {
            Paths.PREVIEW -> viewModelScope.launch {
                val seq = frameSeq(event.data) ?: return@launch
                val frame = decode(event.data, offset = FRAME_HEADER_BYTES)
                // Acknowledge even undecodable or stale frames so the phone sends the next one.
                send(Paths.FRAME_ACK, seqBytes(seq))
                if (frame == null || status == Status.CLOSED || seq <= lastShownSeq) return@launch
                lastShownSeq = seq
                preview = frame
                if (status != Status.STREAMING) {
                    status = Status.STREAMING
                    if (!introShown) {
                        introShown = true
                        showHint("Tap an icon, turn the bezel.\nTap the picture to focus.", millis = 3_000)
                    }
                }
            }
            Paths.STATE -> CameraState.decode(event.data)?.let {
                // Sent when the phone (re)attaches, whose frame numbers may restart from 1.
                lastShownSeq = 0
                camera = it
                setRecording(it.recording)
                if (status == Status.CLOSED || status == Status.NEEDS_PHONE) status = Status.OPENING
            }
            Paths.COUNTDOWN -> countdown = event.data.firstOrNull()?.toInt() ?: 0
            Paths.CAPTURED -> {
                countdown = 0
                captures++
                val video = camera.mode.isVideo
                viewModelScope.launch {
                    decode(event.data)?.let {
                        lastPhoto = it
                        lastWasVideo = video
                    }
                }
            }
            Paths.CAPTURE_FAILED -> {
                countdown = 0
                showHint("Capture failed")
            }
            Paths.FOCUS_RESULT -> focusResult = String(event.data) == "1"
            Paths.RECORDING -> {
                val on = String(event.data) == "1"
                countdown = 0
                camera = camera.copy(recording = on)
                setRecording(on)
            }
            Paths.AUDIO -> if (listening) player.enqueue(event.data)
            Paths.LISTEN_FAILED -> {
                stopListening()
                showHint(String(event.data), millis = 2_500)
            }
            Paths.NEEDS_PHONE -> status = Status.NEEDS_PHONE
            Paths.CLOSED -> {
                status = Status.CLOSED
                preview = null
                setRecording(false)
                stopListening()
            }
        }
    }

    override fun onCleared() {
        messageClient.removeListener(this)
        player.stop()
    }

    private fun setRecording(on: Boolean) {
        if (on && recordingSince == null) recordingSince = SystemClock.elapsedRealtime()
        if (!on) recordingSince = null
    }

    private fun showHint(text: String, millis: Long = 900) {
        hint = text
        hintMillis = millis
        hintId++
    }

    private suspend fun findPhone(): String? = runCatching {
        val nodes = capabilityClient
            .getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }.getOrNull()

    /** Forwards crash reports saved on the watch to the phone, where they can be shared. */
    private suspend fun sendCrashReports(node: String) {
        val app = getApplication<Application>()
        for (file in withContext(Dispatchers.IO) { CrashLog.reports(app) }) {
            runCatching {
                val text = withContext(Dispatchers.IO) { file.readText() }
                messageClient.sendMessage(node, Paths.CRASH, text.toByteArray()).await()
                withContext(Dispatchers.IO) { file.delete() }
            }.onFailure { Log.w(LOG_TAG, "Could not forward crash report", it) }
        }
    }

    private fun send(path: String, data: ByteArray? = null) {
        val node = phoneNode ?: return
        messageClient.sendMessage(node, path, data)
    }

    private suspend fun decode(bytes: ByteArray?, offset: Int = 0): ImageBitmap? {
        if (bytes == null || bytes.size <= offset) return null
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset)?.asImageBitmap()
        }
    }
}
