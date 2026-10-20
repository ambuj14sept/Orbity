package com.orbity.wear

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.orbity.shared.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

enum class Status { CONNECTING, OPENING, STREAMING, NEEDS_PHONE, NO_PHONE, CLOSED }

/** What the bezel currently adjusts. Tapping the label at the top cycles through these. */
enum class Control(val label: String) {
    ZOOM("Zoom"), FILTER("Filter"), EXPOSURE("Exposure"), TIMER("Timer"), FLASH("Flash")
}

private val TIMER_OPTIONS = listOf(0, 3, 5, 10)

/** Zoom multiplier per bezel click; the Watch 6 Classic bezel has 24 clicks per turn (1.1^24 ≈ 10x). */
private const val ZOOM_STEP = 1.1f

class RemoteViewModel(app: Application) : AndroidViewModel(app), MessageClient.OnMessageReceivedListener {

    private val messageClient = Wearable.getMessageClient(app)
    private val capabilityClient = Wearable.getCapabilityClient(app)
    private var phoneNode: String? = null

    var status by mutableStateOf(Status.CONNECTING)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var lastPhoto by mutableStateOf<ImageBitmap?>(null)
        private set
    var countdown by mutableIntStateOf(0)
        private set
    var camera by mutableStateOf(CameraState())
        private set
    var timer by mutableIntStateOf(0)
        private set
    var control by mutableStateOf(Control.ZOOM)
        private set

    /** Short text flashed in the middle of the screen after a bezel change. */
    var hint by mutableStateOf("")
        private set
    var hintId by mutableIntStateOf(0)
        private set

    /** Incremented on every successful capture so the UI can buzz. */
    var captures by mutableIntStateOf(0)
        private set

    init {
        messageClient.addListener(this)
    }

    fun connect() {
        status = Status.CONNECTING
        viewModelScope.launch {
            val node = findPhone()
            phoneNode = node
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
    }

    fun shutter() = send(Paths.SHUTTER, byteArrayOf(timer.toByte()))

    fun flip() = send(Paths.FLIP)

    fun nextControl() {
        control = Control.entries[(control.ordinal + 1) % Control.entries.size]
    }

    /** One call per bezel click; [steps] is +1 clockwise, -1 anticlockwise. */
    fun onBezel(steps: Int) {
        val s = camera
        when (control) {
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
        showHint(valueLabel(control))
    }

    fun valueLabel(c: Control): String {
        val s = camera
        return when (c) {
            Control.ZOOM -> String.format(Locale.US, "%.1f×", s.zoomRatio)
            Control.FILTER -> s.filter.label
            Control.EXPOSURE -> String.format(Locale.US, "%+.1f EV", s.exposure * s.exposureStep)
            Control.TIMER -> if (timer == 0) "Off" else "${timer}s"
            Control.FLASH -> s.flash.label
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
        if (camera.filter != Filter.ORIGINAL) add(camera.filter.label)
        if (camera.flash != Flash.OFF) add("Flash ${camera.flash.label}")
        if (timer > 0) add("${timer}s timer")
    }.joinToString(" · ")

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Paths.PREVIEW -> viewModelScope.launch {
                val frame = decode(event.data) ?: return@launch
                if (status == Status.CLOSED) return@launch
                preview = frame
                status = Status.STREAMING
            }
            Paths.STATE -> CameraState.decode(event.data)?.let {
                camera = it
                if (status == Status.CLOSED || status == Status.NEEDS_PHONE) status = Status.OPENING
            }
            Paths.COUNTDOWN -> countdown = event.data.firstOrNull()?.toInt() ?: 0
            Paths.CAPTURED -> {
                countdown = 0
                captures++
                viewModelScope.launch { decode(event.data)?.let { lastPhoto = it } }
            }
            Paths.CAPTURE_FAILED -> {
                countdown = 0
                showHint("Capture failed")
            }
            Paths.NEEDS_PHONE -> status = Status.NEEDS_PHONE
            Paths.CLOSED -> {
                status = Status.CLOSED
                preview = null
            }
        }
    }

    override fun onCleared() {
        messageClient.removeListener(this)
    }

    private fun showHint(text: String) {
        hint = text
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
            }.onFailure { Log.w(TAG, "Could not forward crash report", it) }
        }
    }

    private fun send(path: String, data: ByteArray? = null) {
        val node = phoneNode ?: return
        messageClient.sendMessage(node, path, data)
    }

    private suspend fun decode(bytes: ByteArray?): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    private companion object {
        const val TAG = "RemoteViewModel"
    }
}
