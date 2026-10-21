package com.orbity.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Choreographer
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.CameraState
import com.orbity.shared.CaptureMode
import com.orbity.shared.Filter
import com.orbity.shared.Flash
import com.orbity.shared.LOG_TAG
import com.orbity.shared.Paths
import com.orbity.shared.frameSeq
import com.orbity.shared.frameWithHeader
import com.orbity.shared.parseFocusPoint
import com.orbity.shared.watchPointToBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** A tap-to-focus ring on the phone screen, in preview view pixels. */
data class FocusRing(val x: Float, val y: Float, val focused: Boolean?, val id: Int)

/**
 * Owns the CameraX session on the phone and executes commands coming from the watch. Lives in
 * [CameraService] so it keeps working with the phone screen off; the camera screen only attaches
 * an on-screen preview while it is visible.
 */
class RemoteCamera(
    context: Context,
    private val owner: LifecycleOwner,
    private val scope: CoroutineScope,
    /** Called (main thread) when the watch disconnects or a recording ends, so the host can stop when idle. */
    private val onIdleCheck: () -> Unit,
) {
    // Read from background threads.
    @Volatile var watchNode: String? = null
    @Volatile var streaming = false

    // Compose state for the phone UI; only written on the main thread.
    var watchConnected by mutableStateOf(false)
        private set
    var countdown by mutableIntStateOf(0)
        private set
    var flash by mutableStateOf(Flash.OFF)
        private set
    var filter by mutableStateOf(Filter.ORIGINAL)
        private set
    var mode by mutableStateOf(CaptureMode.PHOTO)
        private set
    /** Seconds recorded so far, or -1 when not recording. */
    var recordingSeconds by mutableIntStateOf(-1)
        private set
    var focusRing by mutableStateOf<FocusRing?>(null)
        private set
    /** The watch is listening to the microphone; shown on the phone screen. */
    var listening by mutableStateOf(false)
        private set

    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var availableModes = listOf(CaptureMode.PHOTO, CaptureMode.VIDEO)

    private val appContext = context.applicationContext
    /** The camera screen's preview, while it is visible. */
    private var previewView: PreviewView? = null
    private var preview: Preview? = null
    private var started = false
    /** Camera closed while nothing needs it; reopened on demand. */
    private var paused = false
    /** Whether the bound mode streams watch frames from the analysis stream (else screen snapshots). */
    private var analysisBound = true
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messageClient = Wearable.getMessageClient(appContext)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameAt = 0L
    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var captureJob: Job? = null
    private var snapshotJob: Job? = null
    private var focusId = 0
    private val liveAudio = LiveAudio(
        appContext,
        onFailure = { reason ->
            send(Paths.LISTEN_FAILED, reason.toByteArray())
            mainHandler.post { listening = false }
        },
        send = { chunk -> send(Paths.AUDIO, chunk) },
    )

    // Geometry of the latest analysis frame, for mapping watch taps back to the camera.
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile private var frameRotation = 0

    /**
     * The screen is locked to portrait, so follow how the phone is physically held (e.g. landscape
     * on a tripod) to save photos and videos and stream watch frames the right way up.
     */
    private var targetRotation = Surface.ROTATION_0
    private val orientationListener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation == targetRotation) return
            targetRotation = rotation
            imageCapture?.targetRotation = rotation
            imageAnalysis?.targetRotation = rotation
            // Takes effect for the next recording; a running one keeps its orientation.
            videoCapture?.targetRotation = rotation
        }
    }

    /** True while something needs the camera: a connected watch, a recording or live listening. */
    val isBusy get() = watchConnected || recording != null || listening

    fun start() {
        if (started) return
        started = true
        orientationListener.enable()
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val extensionsFuture = ExtensionsManager.getInstanceAsync(appContext, cameraProvider)
            extensionsFuture.addListener({
                extensions = runCatching { extensionsFuture.get() }
                    .onFailure { Log.w(LOG_TAG, "Camera extensions unavailable: $it") }
                    .getOrNull()
                bind()
            }, mainExecutor)
        }, mainExecutor)
    }

    /** Closes the camera (the green privacy dot goes off) while nothing needs it. */
    fun pause() {
        if (paused || provider == null) return
        paused = true
        Log.i(LOG_TAG, "Camera closed while idle")
        stopRecording()
        snapshotJob?.cancel()
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        zoomAnimating = false
        provider?.unbindAll()
        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        videoCapture = null
    }

    /** Reopens the camera after [pause]. */
    private fun resume() {
        if (!paused) return
        paused = false
        Log.i(LOG_TAG, "Camera reopened")
        bind()
    }

    /** Stops everything and tells the watch the camera is closed. */
    fun release() {
        Log.i(LOG_TAG, "Camera released")
        updateListening(false)
        stopRecording()
        send(Paths.CLOSED)
        orientationListener.disable()
        snapshotJob?.cancel()
        provider?.unbindAll()
        mainHandler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        analysisExecutor.shutdown()
        shutterSound.release()
    }

    fun handle(event: MessageEvent) {
        // Logs and crash reports are handled by PhoneListenerService.
        if (event.path == Paths.LOG || event.path == Paths.CRASH) return
        watchNode = event.sourceNodeId
        val text = String(event.data)
        if (event.path != Paths.ZOOM && event.path != Paths.FRAME_ACK) {
            val payload = if (event.path == Paths.SHUTTER) "timer=${event.data.firstOrNull()}" else text
            Log.d(LOG_TAG, "watch -> ${event.path.substringAfterLast('/')} $payload")
        }
        when (event.path) {
            Paths.FRAME_ACK -> frameSeq(event.data)?.let(::onFrameAck)
            Paths.START -> {
                streaming = true
                watchConnected = true
                resetInFlight()
                resetFrameStats()
                resume()
                sendState()
            }
            Paths.STOP -> {
                streaming = false
                watchConnected = false
                updateListening(false)
                onIdleCheck()
            }
            Paths.SHUTTER -> shutter(event.data.firstOrNull()?.toInt() ?: 0)
            Paths.FLIP -> flip()
            Paths.MODE -> CaptureMode.entries.firstOrNull { it.name == text }
                ?.takeIf { it in availableModes && it != mode }
                ?.let { changeMode(it) }
            Paths.FLASH -> Flash.entries.firstOrNull { it.name == text }?.let {
                flash = it
                imageCapture?.flashMode = it.toCameraX()
                applyTorch()
            }
            Paths.FILTER -> Filter.entries.firstOrNull { it.name == text }?.let {
                filter = it
                applyPreviewFilter()
            }
            Paths.ZOOM -> text.toFloatOrNull()?.let(::zoomTo)
            Paths.EXPOSURE -> {
                val index = text.toIntOrNull() ?: return
                val exposure = camera?.cameraInfo?.exposureState ?: return
                if (!exposure.isExposureCompensationSupported) return
                val range = exposure.exposureCompensationRange
                camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
            }
            Paths.FOCUS -> parseFocusPoint(text)?.let { (u, v) -> focusFromWatch(u, v) }
            Paths.LISTEN -> updateListening(text == "1")
        }
    }

    /** Called when the watch started us via [PhoneListenerService]. */
    fun attachWatch(node: String) {
        Log.i(LOG_TAG, "Watch attached: $node")
        watchNode = node
        streaming = true
        watchConnected = true
        resetInFlight()
        resetFrameStats()
        resume()
        sendState()
    }

    /** The camera screen shows [view] while visible, and passes null when hidden. */
    fun attachPreview(view: PreviewView?) {
        if (view === previewView) return
        Log.d(LOG_TAG, "Phone screen preview ${if (view != null) "attached" else "detached"}")
        previewView?.setLayerType(View.LAYER_TYPE_NONE, null)
        previewView = view
        preview?.setSurfaceProvider(view?.surfaceProvider)
        applyPreviewFilter()
        if (view != null) resume()
        // Screen snapshots were feeding the watch; without a screen fall back to a mode that streams.
        if (view == null && !analysisBound && camera != null) {
            Log.i(LOG_TAG, "Mode ${mode.name} needs the phone screen for watch frames; switching to photo")
            mode = CaptureMode.PHOTO
            bind()
        }
    }

    /** Takes a photo, or starts/stops a video, after an optional countdown. */
    fun shutter(timerSeconds: Int) {
        if (recording != null) {
            stopRecording()
            return
        }
        if (captureJob?.isActive == true) return
        captureJob = scope.launch {
            for (s in timerSeconds downTo 1) {
                countdown = s
                send(Paths.COUNTDOWN, byteArrayOf(s.toByte()))
                delay(1000)
            }
            countdown = 0
            if (mode.isVideo) startRecording() else takePicture()
        }
    }

    /** Tap on the phone screen at view coordinates. */
    fun focusAtView(x: Float, y: Float) {
        val view = previewView ?: return
        val id = ++focusId
        focusRing = FocusRing(x, y, null, id)
        focus(view.meteringPointFactory.createPoint(x, y)) { ok ->
            if (focusRing?.id == id) focusRing = focusRing?.copy(focused = ok)
        }
        scope.launch {
            delay(FOCUS_RING_MS)
            if (focusRing?.id == id) focusRing = null
        }
    }

    private fun updateListening(on: Boolean) {
        if (on) {
            if (!liveAudio.start()) {
                Log.w(LOG_TAG, "Live audio needs microphone permission")
                send(Paths.LISTEN_FAILED, "Allow microphone for Orbity on the phone".toByteArray())
                listening = false
                return
            }
        } else {
            liveAudio.stop()
        }
        val changed = listening != on
        listening = on
        if (changed && !on) onIdleCheck()
    }

    // ---- Binding -------------------------------------------------------------------------

    private fun changeMode(newMode: CaptureMode) {
        mode = newMode
        // The bezel can sweep through several modes quickly; rebind once it settles.
        mainHandler.removeCallbacks(rebind)
        mainHandler.postDelayed(rebind, MODE_SETTLE_MS)
    }

    private val rebind = Runnable { bind() }

    private fun bind() {
        val provider = provider ?: return
        if (paused) return
        stopRecording()
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        zoomAnimating = false

        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        availableModes = buildList {
            add(CaptureMode.PHOTO)
            add(CaptureMode.VIDEO)
            CaptureMode.entries.forEach { m ->
                val ext = m.extensionMode() ?: return@forEach
                val available = runCatching { extensions?.isExtensionAvailable(base, ext) == true }.getOrDefault(false)
                // Without the phone screen, a mode must stream watch frames from the analysis stream.
                val canStream = previewView != null ||
                    runCatching { extensions?.isImageAnalysisSupported(base, ext) == true }.getOrDefault(false)
                if (available && canStream) add(m)
            }
        }
        if (mode !in availableModes) mode = CaptureMode.PHOTO
        val extensionMode = mode.extensionMode()
        val selector = extensionMode?.let { extensions?.getExtensionEnabledCameraSelector(base, it) } ?: base
        val analysisSupported = extensionMode?.let {
            runCatching { extensions?.isImageAnalysisSupported(base, it) == true }.getOrDefault(false)
        } ?: true

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
            this.preview = it
        }
        val analysis = buildAnalysis()
        var photo: ImageCapture? = null
        var video: VideoCapture<Recorder>? = null
        val output: UseCase = if (mode.isVideo) buildVideoCapture().also { video = it } else buildImageCapture().also { photo = it }

        // Some modes cannot add the small analysis stream that feeds the watch; fall back to
        // snapshots of the on-screen preview in that case.
        var withAnalysis = analysisSupported
        var bound = if (withAnalysis) tryBind(provider, selector, preview, output, analysis) else null
        if (bound == null) {
            withAnalysis = false
            analysis.clearAnalyzer()
            bound = tryBind(provider, selector, preview, output)
        }
        camera = bound
        imageCapture = photo
        videoCapture = video
        imageAnalysis = analysis.takeIf { withAnalysis }
        analysisBound = withAnalysis
        frameWidth = 0
        startSnapshots(enabled = bound != null && !withAnalysis)

        bound?.cameraInfo?.let { info ->
            val zoom = info.zoomState.value
            val exposure = info.exposureState
            Log.i(
                LOG_TAG,
                "Camera bound: ${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"}, " +
                    "mode ${mode.name}, modes available ${availableModes.joinToString { it.name }}, " +
                    "watch frames from ${if (withAnalysis) "analysis" else "screen snapshots"}, " +
                    "zoom ${zoom?.minZoomRatio}-${zoom?.maxZoomRatio}, " +
                    "exposure ${exposure.exposureCompensationRange} step ${exposure.exposureCompensationStep}, " +
                    "flash unit ${info.hasFlashUnit()}, " +
                    "output ${photo?.resolutionInfo?.resolution ?: video?.output?.qualitySelector}, " +
                    "analysis ${imageAnalysis?.resolutionInfo?.resolution}"
            )
        }
        applyTorch()
        applyPreviewFilter()
        sendState()
    }

    private fun tryBind(provider: ProcessCameraProvider, selector: CameraSelector, vararg useCases: UseCase): Camera? =
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, *useCases)
        }.onFailure {
            Log.e(LOG_TAG, "Binding ${useCases.joinToString { it.javaClass.simpleName }} failed", it)
        }.getOrNull()

    private fun buildImageCapture() = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetRotation(targetRotation)
        .setFlashMode(flash.toCameraX())
        // ~12 MP keeps full-resolution filter processing well within memory limits.
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
        )
        .build()

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
            )
            .build()
        return VideoCapture.Builder(recorder).setTargetRotation(targetRotation).build()
    }

    private fun buildAnalysis() = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetRotation(targetRotation)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
        .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

    private fun flip() {
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val hasLens = runCatching {
            provider?.hasCamera(CameraSelector.Builder().requireLensFacing(target).build()) == true
        }.getOrDefault(false)
        if (!hasLens) return
        lensFacing = target
        bind()
    }

    /** In video mode "flash on" means the torch stays lit. */
    private fun applyTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(mode.isVideo && flash == Flash.ON)
    }

    /** Tints the on-phone preview; the watch applies the same matrix to its own frames. */
    private fun applyPreviewFilter() {
        val view = previewView ?: return
        val matrix = filter.matrix
        if (matrix == null) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    // ---- Zoom (animated so bezel clicks glide instead of jumping) ------------------------

    private var zoomTarget = 1f
    private var zoomCurrent = 1f
    private var zoomAnimating = false
    private val zoomFrame = Choreographer.FrameCallback { stepZoom() }

    private fun zoomTo(ratio: Float) {
        val zoom = camera?.cameraInfo?.zoomState?.value ?: return
        zoomTarget = ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        if (zoomAnimating) return
        zoomCurrent = zoom.zoomRatio
        zoomAnimating = true
        Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    private fun stepZoom() {
        val cam = camera ?: run { zoomAnimating = false; return }
        val remaining = zoomTarget / zoomCurrent
        // Ease in log space: each display frame covers a fixed share of the remaining ratio.
        zoomCurrent = if (abs(ln(remaining)) < ZOOM_SNAP) zoomTarget else zoomCurrent * remaining.pow(ZOOM_EASING)
        cam.cameraControl.setZoomRatio(zoomCurrent)
        if (zoomCurrent == zoomTarget) {
            zoomAnimating = false
        } else {
            Choreographer.getInstance().postFrameCallback(zoomFrame)
        }
    }

    // ---- Focus ---------------------------------------------------------------------------

    private fun focusFromWatch(u: Float, v: Float) {
        val analysis = imageAnalysis
        val point = if (analysis != null && frameWidth > 0) {
            val (x, y) = watchPointToBuffer(
                u, v, frameWidth, frameHeight, frameRotation,
                mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT,
            )
            SurfaceOrientedMeteringPointFactory(frameWidth.toFloat(), frameHeight.toFloat(), analysis)
                .createPoint(x, y)
        } else {
            // Snapshot frames are the centre square of the on-screen preview.
            val view = previewView ?: return
            val side = minOf(view.width, view.height).toFloat()
            view.meteringPointFactory.createPoint(
                (view.width - side) / 2 + u * side,
                (view.height - side) / 2 + v * side,
            )
        }
        focus(point) { ok -> send(Paths.FOCUS_RESULT, (if (ok) "1" else "0").toByteArray()) }
    }

    private fun focus(point: MeteringPoint, onResult: (Boolean) -> Unit) {
        val cam = camera ?: return
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_HOLD_S, TimeUnit.SECONDS)
            .build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            // A newer tap cancels the previous request; that is not a focus failure.
            val ok = runCatching { future.get().isFocusSuccessful }.getOrNull() ?: return@addListener
            Log.d(LOG_TAG, "Focus ${if (ok) "locked" else "not locked"}")
            onResult(ok)
        }, mainExecutor)
    }

    // ---- Watch preview frames ------------------------------------------------------------
    //
    // At most MAX_FRAMES_IN_FLIGHT frames are unconfirmed at once: a new frame is sent only when
    // the watch confirms it has shown an earlier one (FRAME_ACK). Frames therefore never pile up
    // between the devices, while two in flight keep the link busy for a smoother frame rate.
    // Frame size adapts to the measured latency.

    private class SentFrame(val takenAt: Long, val bytes: Int)

    /** Guards [inFlight] and [reserved]; touched from the analysis thread and the main thread. */
    private val frameLock = Any()
    private val inFlight = HashMap<Int, SentFrame>()
    /** Slots taken for frames still being encoded. */
    private var reserved = 0
    private val nextSeq = AtomicInteger(0)
    private val frameTimeouts = AtomicInteger(0)
    @Volatile private var qualityLevel = 0
    private var latencyAverage = 0f
    private var framesSinceQualityChange = 0

    private fun tryReserveFrame(now: Long): Boolean = synchronized(frameLock) {
        // Frames never acknowledged (lost in transit) give their slot back after a timeout.
        val expired = inFlight.filterValues { now - it.takenAt >= ACK_TIMEOUT_MS }.keys
        if (expired.isNotEmpty()) {
            inFlight.keys.removeAll(expired)
            frameTimeouts.addAndGet(expired.size)
        }
        if (inFlight.size + reserved >= MAX_FRAMES_IN_FLIGHT) return false
        reserved++
        true
    }

    /** Gives back a reservation whose frame could not be encoded. */
    private fun cancelReservation() = synchronized(frameLock) {
        if (reserved > 0) reserved--
    }

    /** The watch (re)connected: forget everything in flight. */
    private fun resetInFlight() = synchronized(frameLock) {
        inFlight.clear()
        reserved = 0
    }

    private fun onFrame(image: ImageProxy) {
        image.use {
            frameWidth = it.width
            frameHeight = it.height
            frameRotation = it.imageInfo.rotationDegrees
            val node = watchNode
            val now = SystemClock.elapsedRealtime()
            if (!streaming || node == null || now - lastFrameAt < FRAME_INTERVAL_MS) return
            if (!tryReserveFrame(now)) return
            lastFrameAt = now
            val jpeg = runCatching { encodeFrame(it) }.getOrNull()
            if (jpeg == null) {
                cancelReservation()
                return
            }
            sendFrame(node, jpeg, takenAt = now)
        }
    }

    /** Fallback when no analysis stream is bound: copies the on-screen preview a few times a second. */
    private fun startSnapshots(enabled: Boolean) {
        snapshotJob?.cancel()
        snapshotJob = null
        if (!enabled) return
        snapshotJob = scope.launch {
            while (true) {
                delay(SNAPSHOT_INTERVAL_MS)
                val node = watchNode ?: continue
                val view = previewView
                if (!streaming || view == null || !view.isAttachedToWindow) continue
                val now = SystemClock.elapsedRealtime()
                if (!tryReserveFrame(now)) continue
                val bitmap = runCatching { view.bitmap }.getOrNull()
                val jpeg = bitmap?.let {
                    withContext(Dispatchers.Default) { runCatching { squareJpeg(it) }.getOrNull() }
                }
                if (jpeg == null) {
                    cancelReservation()
                    continue
                }
                sendFrame(node, jpeg, takenAt = now)
            }
        }
    }

    private fun sendFrame(node: String, jpeg: ByteArray, takenAt: Long) {
        val seq = nextSeq.incrementAndGet()
        synchronized(frameLock) {
            if (reserved > 0) reserved--
            inFlight[seq] = SentFrame(takenAt, jpeg.size)
        }
        messageClient.sendMessage(node, Paths.PREVIEW, frameWithHeader(seq, jpeg))
            .addOnFailureListener { e ->
                Log.w(LOG_TAG, "Preview frame send failed: $e")
                synchronized(frameLock) { inFlight.remove(seq) }
            }
    }

    /** Main thread: the watch has displayed frame [seq]. */
    private fun onFrameAck(seq: Int) {
        val frame = synchronized(frameLock) { inFlight.remove(seq) } ?: return
        recordFrame(SystemClock.elapsedRealtime() - frame.takenAt, frame.bytes)
    }

    private var statFrames = 0
    private var statLatencyMs = 0L
    private var statBytes = 0L
    private var statsSince = SystemClock.elapsedRealtime()

    private fun resetFrameStats() {
        statFrames = 0
        statLatencyMs = 0
        statBytes = 0
        frameTimeouts.set(0)
        statsSince = SystemClock.elapsedRealtime()
    }

    /** Main thread. Adapts frame size to latency; logs streaming health every 100 frames. */
    private fun recordFrame(latencyMs: Long, size: Int) {
        latencyAverage = if (latencyAverage == 0f) latencyMs.toFloat() else latencyAverage * 0.8f + latencyMs * 0.2f
        if (++framesSinceQualityChange >= QUALITY_SETTLE_FRAMES) {
            val level = when {
                latencyAverage > SLOW_LATENCY_MS && qualityLevel < FRAME_LEVELS.lastIndex -> qualityLevel + 1
                latencyAverage < FAST_LATENCY_MS && qualityLevel > 0 -> qualityLevel - 1
                else -> qualityLevel
            }
            if (level != qualityLevel) {
                Log.i(LOG_TAG, "Watch frames ${FRAME_LEVELS[level].first}px (latency ${latencyAverage.toInt()} ms)")
                qualityLevel = level
                framesSinceQualityChange = 0
            }
        }
        statFrames++
        statLatencyMs += latencyMs
        statBytes += size
        if (statFrames < 100) return
        val seconds = (SystemClock.elapsedRealtime() - statsSince) / 1000f
        Log.d(
            LOG_TAG,
            "Preview: ${"%.1f".format(Locale.US, statFrames / seconds)} fps, " +
                "latency avg ${statLatencyMs / statFrames} ms, avg ${statBytes / statFrames / 1024} KB, " +
                "${FRAME_LEVELS[qualityLevel].first}px, timeouts ${frameTimeouts.get()}"
        )
        resetFrameStats()
    }

    /** Upright, center-cropped, square, small JPEG — sized for a round watch face. */
    private fun encodeFrame(image: ImageProxy): ByteArray {
        val (size, quality) = FRAME_LEVELS[qualityLevel]
        val src = image.toBitmap()
        val side = minOf(src.width, src.height)
        val scale = size.toFloat() / side
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(scale, scale)
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f)
        }
        val square = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side, matrix, true
        )
        return square.toJpeg(quality)
    }

    /** Centre square of an already upright bitmap, scaled for the watch. */
    private fun squareJpeg(src: Bitmap): ByteArray {
        val (size, quality) = FRAME_LEVELS[qualityLevel]
        val side = minOf(src.width, src.height)
        val scale = size.toFloat() / side
        val matrix = Matrix().apply { postScale(scale, scale) }
        val square = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side, matrix, true
        )
        src.recycle()
        return square.toJpeg(quality)
    }

    // ---- Photos --------------------------------------------------------------------------

    private fun takePicture() {
        val capture = imageCapture ?: return
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        Log.i(LOG_TAG, "Capturing: mode ${mode.name}, filter ${filter.name}, flash ${flash.name}")
        val matrix = filter.matrix
        if (matrix == null) saveOriginal(capture) else saveFiltered(capture, matrix)
    }

    /** No filter: let CameraX write the JPEG directly, keeping full EXIF metadata. */
    private fun saveOriginal(capture: ImageCapture) {
        val options = ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newMediaValues(video = false)
        ).build()
        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.i(LOG_TAG, "Photo saved: ${output.savedUri}")
                send(Paths.CAPTURED, output.savedUri?.let(::loadThumbnailOrNull))
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(LOG_TAG, "Capture failed", e)
                send(Paths.CAPTURE_FAILED)
            }
        })
    }

    /** With a filter: capture in memory, rotate upright, apply the colour matrix, then save. */
    private fun saveFiltered(capture: ImageCapture, matrix: FloatArray) {
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val thumbnail = runCatching { image.use { renderAndSave(it, matrix) } }
                    .onFailure { Log.e(LOG_TAG, "Saving filtered photo failed", it) }
                    .getOrNull()
                if (thumbnail == null) send(Paths.CAPTURE_FAILED) else send(Paths.CAPTURED, thumbnail)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(LOG_TAG, "Capture failed", e)
                send(Paths.CAPTURE_FAILED)
            }
        })
    }

    /** Returns a small JPEG thumbnail of the saved photo. */
    private fun renderAndSave(image: ImageProxy, matrix: FloatArray): ByteArray {
        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val swap = rotation % 180 != 0
        val out = Bitmap.createBitmap(
            if (swap) src.height else src.width,
            if (swap) src.width else src.height,
            Bitmap.Config.ARGB_8888,
        )
        val transform = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postRotate(rotation.toFloat())
            postTranslate(out.width / 2f, out.height / 2f)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, transform, paint)
        Log.i(LOG_TAG, "Filtered photo ${out.width}x${out.height}, rotation $rotation")
        src.recycle()
        try {
            writeToGallery(out)
            val scale = THUMBNAIL_SIZE.toFloat() / maxOf(out.width, out.height)
            return Bitmap.createScaledBitmap(
                out, (out.width * scale).toInt(), (out.height * scale).toInt(), true
            ).toJpeg(THUMBNAIL_QUALITY)
        } finally {
            out.recycle()
        }
    }

    private fun writeToGallery(bitmap: Bitmap) {
        val resolver = appContext.contentResolver
        val values = newMediaValues(video = false).apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "MediaStore insert failed"
        }
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "Cannot open $uri" }.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, SAVE_QUALITY, it)) { "JPEG encode failed" }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    // ---- Video ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // audio is only enabled after checking the permission
    private fun startRecording() {
        val video = videoCapture ?: return
        val options = MediaStoreOutputOptions.Builder(
            appContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(newMediaValues(video = true)).build()
        var pending = video.output.prepareRecording(appContext, options)
        val withAudio = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (withAudio) pending = pending.withAudioEnabled()
        Log.i(LOG_TAG, "Recording: audio $withAudio, flash ${flash.name}")
        shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = pending.start(mainExecutor, ::onRecordEvent)
    }

    private fun stopRecording() {
        val active = recording ?: return
        recording = null
        active.stop()
        sendState()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                recordingSeconds = 0
                send(Paths.RECORDING, "1".toByteArray())
            }
            is VideoRecordEvent.Status -> {
                val seconds = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos).toInt()
                if (seconds != recordingSeconds) recordingSeconds = seconds
            }
            is VideoRecordEvent.Finalize -> {
                recordingSeconds = -1
                mainHandler.post(onIdleCheck)
                shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                send(Paths.RECORDING, "0".toByteArray())
                val uri = event.outputResults.outputUri
                // Errors such as the camera closing mid-recording still leave a playable file.
                if (event.hasError()) Log.w(LOG_TAG, "Recording finished with error ${event.error}", event.cause)
                if (uri == Uri.EMPTY) {
                    send(Paths.CAPTURE_FAILED)
                } else {
                    Log.i(LOG_TAG, "Video saved: $uri")
                    captureExecutor.execute { send(Paths.CAPTURED, loadThumbnailOrNull(uri)) }
                }
            }
        }
    }

    // ---- Shared helpers ------------------------------------------------------------------

    private fun newMediaValues(video: Boolean) = ContentValues().apply {
        val name = "Orbity_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Orbity" else "Pictures/Orbity")
    }

    private fun loadThumbnailOrNull(uri: Uri): ByteArray? = runCatching {
        appContext.contentResolver
            .loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
            .toJpeg(THUMBNAIL_QUALITY)
    }.onFailure { Log.w(LOG_TAG, "Thumbnail failed for $uri", it) }.getOrNull()

    private fun sendState() {
        val info = camera?.cameraInfo
        val zoom = info?.zoomState?.value
        val exposure = info?.exposureState?.takeIf { it.isExposureCompensationSupported }
        val state = CameraState(
            flash = flash,
            frontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT,
            filter = filter,
            zoomRatio = zoom?.zoomRatio ?: 1f,
            minZoom = zoom?.minZoomRatio ?: 1f,
            maxZoom = zoom?.maxZoomRatio ?: 1f,
            exposure = exposure?.exposureCompensationIndex ?: 0,
            exposureMin = exposure?.exposureCompensationRange?.lower ?: 0,
            exposureMax = exposure?.exposureCompensationRange?.upper ?: 0,
            exposureStep = exposure?.exposureCompensationStep?.toFloat() ?: 0f,
            mode = mode,
            availableModes = availableModes,
            recording = recording != null,
        )
        send(Paths.STATE, state.encode())
    }

    /** Thread-safe: MessageClient may be used from any thread. */
    private fun send(path: String, data: ByteArray? = null) {
        val node = watchNode ?: return
        messageClient.sendMessage(node, path, data)
    }

    private fun CaptureMode.extensionMode(): Int? = when (this) {
        CaptureMode.NIGHT -> ExtensionMode.NIGHT
        CaptureMode.HDR -> ExtensionMode.HDR
        CaptureMode.PORTRAIT -> ExtensionMode.BOKEH
        CaptureMode.PHOTO, CaptureMode.VIDEO -> null
    }

    private fun Flash.toCameraX() = when (this) {
        Flash.OFF -> ImageCapture.FLASH_MODE_OFF
        Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        Flash.ON -> ImageCapture.FLASH_MODE_ON
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private companion object {
        const val FRAME_INTERVAL_MS = 80L // ~12 fps upper bound
        const val SNAPSHOT_INTERVAL_MS = 180L
        /** Watch frame size (px) and JPEG quality, from best to lightest. */
        val FRAME_LEVELS = listOf(320 to 55, 272 to 50, 224 to 45, 192 to 40)
        const val ACK_TIMEOUT_MS = 1_000L
        const val MAX_FRAMES_IN_FLIGHT = 2
        const val SLOW_LATENCY_MS = 220f
        const val FAST_LATENCY_MS = 110f
        const val QUALITY_SETTLE_FRAMES = 15
        /** Last-capture image shown full screen on the watch. */
        const val THUMBNAIL_SIZE = 480
        const val THUMBNAIL_QUALITY = 70
        const val SAVE_QUALITY = 95
        const val MODE_SETTLE_MS = 350L
        const val FOCUS_HOLD_S = 5L
        const val FOCUS_RING_MS = 1_500L
        const val ZOOM_EASING = 0.35f
        const val ZOOM_SNAP = 0.004f

        /**
         * Runs capture callbacks and full-resolution filtering off the main thread. Process-wide
         * and never shut down, so a capture finishing after the screen closes still saves.
         */
        val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
