package com.orbity.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.orbity.shared.CaptureMode
import com.orbity.shared.CrashLog
import com.orbity.shared.Filter
import com.orbity.shared.LOG_TAG

/**
 * The phone's camera screen. The camera itself runs in [CameraService]; this screen starts it,
 * shows its preview while visible, and leaves it running for the watch when hidden.
 */
@SuppressLint("ClickableViewAccessibility") // performClick is called on tap
class CameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private var service: CameraService.LocalBinder? = null
    private var camera by mutableStateOf<RemoteCamera?>(null)
    private var canLaunchFromWatch by mutableStateOf(false)
    private var crashReports by mutableIntStateOf(0)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val local = binder as CameraService.LocalBinder
            service = local
            camera = local.camera
            local.attachScreen(previewView)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            camera = null
        }
    }
    private var bound = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            Log.i(LOG_TAG, "Permission results: $grants")
            if (hasPermission(Manifest.permission.CAMERA)) {
                connectCamera()
            } else {
                Toast.makeText(this, "Orbity needs camera access. Allow it in Settings › Apps.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        // Tap to focus, like the system camera.
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                camera?.focusAtView(event.x, event.y)
                view.performClick()
            }
            true
        }

        setContent {
            CameraScreen(
                previewView = previewView,
                camera = camera,
                canLaunchFromWatch = canLaunchFromWatch,
                onAllowLaunch = {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                },
                crashReports = crashReports,
                onShareReports = ::shareCrashReports,
                onClearReports = {
                    CrashLog.clear(this)
                    crashReports = 0
                },
                onToggleLogs = { LogUploader.setUserEnabled(this, !LogUploader.userEnabled) },
            )
        }

        // The microphone (video sound, live listening) is optional; ask for anything missing.
        val missing = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::hasPermission)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        if (hasPermission(Manifest.permission.CAMERA)) connectCamera()
    }

    override fun onResume() {
        super.onResume()
        canLaunchFromWatch = Settings.canDrawOverlays(this)
        Log.d(LOG_TAG, "Camera screen resumed, overlay permission=$canLaunchFromWatch")
        crashReports = CrashLog.reports(this).size
    }

    override fun onStop() {
        // The camera keeps running for the watch; the service stops itself once idle.
        service?.attachScreen(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        camera = null
        super.onStop()
    }

    /** Starts the camera service (allowed now: this screen is visible) and shows its preview. */
    private fun connectCamera() {
        if (bound) return
        CameraService.start(this, watchNode = null)
        bound = bindService(Intent(this, CameraService::class.java), connection, 0)
    }

    private fun shareCrashReports() {
        val text = CrashLog.reports(this).joinToString("\n\n----------\n\n") { it.readText() }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Orbity crash reports")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share crash reports"))
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun CameraScreen(
    previewView: PreviewView,
    camera: RemoteCamera?,
    canLaunchFromWatch: Boolean,
    onAllowLaunch: () -> Unit,
    crashReports: Int,
    onShareReports: () -> Unit,
    onClearReports: () -> Unit,
    onToggleLogs: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        camera?.focusRing?.let { ring ->
            val color = when (ring.focused) {
                true -> Color(0xFFFFD60A)
                false -> Color(0xFFFF6B6B)
                null -> Color.White
            }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color, radius = 36.dp.toPx(), center = Offset(ring.x, ring.y), style = Stroke(2.dp.toPx()))
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
        ) {
            Pill(
                when {
                    camera == null -> "Starting camera…"
                    camera.watchConnected -> "⌚ Controlled by watch"
                    else -> "Open Orbity on your watch"
                }
            )
            if (LogUploader.available) {
                // dev builds only; tap to switch log sending on or off.
                val logs = when {
                    !LogUploader.userEnabled -> "Live logs off (tap to turn on)"
                    LogUploader.reachable == true -> "Live logs ✓"
                    LogUploader.reachable == false -> "Live logs: server unreachable — is Tailscale on?"
                    else -> "Live logs: connecting…"
                }
                Pill(logs, Modifier.padding(top = 8.dp).clickable(onClick = onToggleLogs))
            }
            if (camera?.listening == true) {
                Pill("🎧 Watch is listening", Modifier.padding(top = 8.dp))
            }
            if (camera != null && camera.recordingSeconds >= 0) {
                Pill("● REC ${formatDuration(camera.recordingSeconds)}", Modifier.padding(top = 8.dp), Color(0xFFE53935))
            }
            if (camera != null && camera.mode != CaptureMode.PHOTO) {
                Pill("Mode: ${camera.mode.label}", Modifier.padding(top = 8.dp))
            }
            if (camera != null && camera.filter != Filter.ORIGINAL) {
                Pill("Filter: ${camera.filter.label}", Modifier.padding(top = 8.dp))
            }
            if (crashReports > 0) {
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = onShareReports) { Text("Share crash reports ($crashReports)") }
                    TextButton(onClick = onClearReports) { Text("Clear", color = Color.White) }
                }
            }
        }

        if (camera != null && camera.countdown > 0) {
            Text(
                text = camera.countdown.toString(),
                color = Color.White,
                fontSize = 120.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        ) {
            if (!canLaunchFromWatch) {
                Button(onClick = onAllowLaunch, modifier = Modifier.padding(bottom = 20.dp)) {
                    Text("Allow watch to start the camera")
                }
            }
            val recording = camera != null && camera.recordingSeconds >= 0
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .clickable { camera?.shutter(0) }
                    .border(5.dp, Color.White, CircleShape)
                    .padding(if (recording) 22.dp else 9.dp)
                    .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                    .background(if (camera?.mode?.isVideo == true) Color(0xFFE53935) else Color.White)
            )
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, background: Color = Color.Black.copy(alpha = 0.5f)) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private fun formatDuration(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
