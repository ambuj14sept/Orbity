package com.orbity.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.CameraState
import com.orbity.shared.Filter
import com.orbity.shared.Flash
import com.orbity.shared.Paths
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the CameraX session on the phone and executes commands coming from the watch. */
class RemoteCamera(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
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
    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val appContext = activity.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameInFlight = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private val shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var captureJob: Job? = null

    /**
     * The screen is locked to portrait, so follow how the phone is physically held (e.g. landscape
     * on a tripod) to save photos and stream watch frames the right way up.
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
        }
    }

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun release() {
        analysisExecutor.shutdown()
        shutterSound.release()
    }

    fun handle(event: MessageEvent) {
        watchNode = event.sourceNodeId
        val text = String(event.data)
        when (event.path) {
            Paths.START -> {
                streaming = true
                watchConnected = true
                sendState()
            }
            Paths.STOP -> {
                streaming = false
                watchConnected = false
            }
            Paths.SHUTTER -> capture(event.data.firstOrNull()?.toInt() ?: 0)
            Paths.FLIP -> flip()
            Paths.FLASH -> Flash.entries.firstOrNull { it.name == text }?.let {
                flash = it
                imageCapture?.flashMode = it.toCameraX()
            }
            Paths.FILTER -> Filter.entries.firstOrNull { it.name == text }?.let {
                filter = it
                applyPreviewFilter()
            }
            Paths.ZOOM -> {
                val ratio = text.toFloatOrNull() ?: return
                val zoom = camera?.cameraInfo?.zoomState?.value ?: return
                camera?.cameraControl?.setZoomRatio(ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
            }
            Paths.EXPOSURE -> {
                val index = text.toIntOrNull() ?: return
                val exposure = camera?.cameraInfo?.exposureState ?: return
                if (!exposure.isExposureCompensationSupported) return
                val range = exposure.exposureCompensationRange
                camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
            }
        }
    }

    /** Called when the watch started us via [PhoneListenerService]. */
    fun attachWatch(node: String) {
        watchNode = node
        streaming = true
        watchConnected = true
        sendState()
    }

    /** The camera screen came back to the foreground; let a waiting watch resume. */
    fun onForeground() {
        orientationListener.enable()
        if (streaming) sendState()
    }

    /** CameraX stops with the lifecycle; tell the watch the preview has stopped. */
    fun onBackground() {
        orientationListener.disable()
        send(Paths.CLOSED)
    }

    fun capture(timerSeconds: Int) {
        if (captureJob?.isActive == true) return
        captureJob = activity.lifecycleScope.launch {
            for (s in timerSeconds downTo 1) {
                countdown = s
                send(Paths.COUNTDOWN, byteArrayOf(s.toByte()))
                delay(1000)
            }
            countdown = 0
            takePicture()
        }
    }

    private fun bind() {
        val provider = provider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .setFlashMode(flash.toCameraX())
            // ~12 MP keeps full-resolution filter processing well within memory limits.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(targetRotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        camera = runCatching {
            provider.bindToLifecycle(activity, selector, preview, capture, analysis)
        }.onFailure {
            Log.e(TAG, "Binding camera failed", it)
        }.getOrNull()
        imageCapture = capture
        imageAnalysis = analysis
        applyPreviewFilter()
        sendState()
    }

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

    /** Tints the on-phone preview; the watch applies the same matrix to its own frames. */
    private fun applyPreviewFilter() {
        val matrix = filter.matrix
        if (matrix == null) {
            previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    private fun onFrame(image: ImageProxy) {
        image.use {
            val node = watchNode
            val now = SystemClock.elapsedRealtime()
            if (!streaming || node == null || now - lastFrameAt < FRAME_INTERVAL_MS) return
            // Drop frames while the previous one is still in transit so latency never builds up.
            if (!frameInFlight.compareAndSet(false, true)) return
            lastFrameAt = now
            val jpeg = runCatching { encodeFrame(it) }.getOrNull()
            if (jpeg == null) {
                frameInFlight.set(false)
                return
            }
            messageClient.sendMessage(node, Paths.PREVIEW, jpeg)
                .addOnCompleteListener { frameInFlight.set(false) }
        }
    }

    /** Upright, center-cropped, square, small JPEG — sized for a round watch face. */
    private fun encodeFrame(image: ImageProxy): ByteArray {
        val src = image.toBitmap()
        val side = minOf(src.width, src.height)
        val scale = PREVIEW_SIZE.toFloat() / side
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(scale, scale)
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f)
        }
        val square = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side, matrix, true
        )
        return square.toJpeg(PREVIEW_QUALITY)
    }

    private fun takePicture() {
        val capture = imageCapture ?: return
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        val matrix = filter.matrix
        if (matrix == null) saveOriginal(capture) else saveFiltered(capture, matrix)
    }

    /** No filter: let CameraX write the JPEG directly, keeping full EXIF metadata. */
    private fun saveOriginal(capture: ImageCapture) {
        val options = ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newPhotoValues()
        ).build()
        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val thumbnail = output.savedUri?.let { uri ->
                    runCatching { loadThumbnail(uri) }
                        .onFailure { Log.w(TAG, "Thumbnail failed", it) }
                        .getOrNull()
                }
                send(Paths.CAPTURED, thumbnail)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "Capture failed", e)
                send(Paths.CAPTURE_FAILED)
            }
        })
    }

    /** With a filter: capture in memory, rotate upright, apply the colour matrix, then save. */
    private fun saveFiltered(capture: ImageCapture, matrix: FloatArray) {
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val thumbnail = runCatching { image.use { renderAndSave(it, matrix) } }
                    .onFailure { Log.e(TAG, "Saving filtered photo failed", it) }
                    .getOrNull()
                if (thumbnail == null) send(Paths.CAPTURE_FAILED) else send(Paths.CAPTURED, thumbnail)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "Capture failed", e)
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
        src.recycle()
        try {
            writeToGallery(out)
            val scale = PREVIEW_SIZE.toFloat() / maxOf(out.width, out.height)
            return Bitmap.createScaledBitmap(
                out, (out.width * scale).toInt(), (out.height * scale).toInt(), true
            ).toJpeg(THUMBNAIL_QUALITY)
        } finally {
            out.recycle()
        }
    }

    private fun writeToGallery(bitmap: Bitmap) {
        val resolver = appContext.contentResolver
        val values = newPhotoValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
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

    private fun newPhotoValues() = ContentValues().apply {
        val name = "Orbity_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Orbity")
    }

    private fun loadThumbnail(uri: Uri): ByteArray =
        appContext.contentResolver
            .loadThumbnail(uri, Size(PREVIEW_SIZE, PREVIEW_SIZE), null)
            .toJpeg(THUMBNAIL_QUALITY)

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
        )
        send(Paths.STATE, state.encode())
    }

    /** Thread-safe: MessageClient may be used from any thread. */
    private fun send(path: String, data: ByteArray? = null) {
        val node = watchNode ?: return
        messageClient.sendMessage(node, path, data)
    }

    private fun Flash.toCameraX() = when (this) {
        Flash.OFF -> ImageCapture.FLASH_MODE_OFF
        Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        Flash.ON -> ImageCapture.FLASH_MODE_ON
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private companion object {
        const val TAG = "RemoteCamera"
        const val FRAME_INTERVAL_MS = 80L // ~12 fps upper bound
        const val PREVIEW_SIZE = 320
        const val PREVIEW_QUALITY = 50
        const val THUMBNAIL_QUALITY = 70
        const val SAVE_QUALITY = 95

        /**
         * Runs capture callbacks and full-resolution filtering off the main thread. Process-wide
         * and never shut down, so a capture finishing after the screen closes still saves.
         */
        val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
