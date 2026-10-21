package com.orbity.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.CaptureMode
import com.orbity.shared.CrashLog
import com.orbity.shared.Filter
import com.orbity.shared.LOG_TAG

@SuppressLint("ClickableViewAccessibility") // performClick is called on tap
class CameraActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    companion object {
        const val EXTRA_WATCH_NODE = "watch_node"
        private const val STATE_WATCH_NODE = "watch_node"

        /** True while this screen is visible and listening for watch messages itself. */
        @Volatile var isActive = false
            private set
    }

    private lateinit var camera: RemoteCamera
    private var cameraStarted = false
    private var canLaunchFromWatch by mutableStateOf(false)
    private var crashReports by mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            Log.i(LOG_TAG, "Permission results: $grants")
            if (hasPermission(Manifest.permission.CAMERA)) {
                startCamera()
            } else {
                Toast.makeText(this, "Orbity needs camera access. Allow it in Settings › Apps.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        camera = RemoteCamera(this, previewView)
        // Tap to focus, like the system camera.
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                camera.focusAtView(event.x, event.y)
                view.performClick()
            }
            true
        }
        (savedInstanceState?.getString(STATE_WATCH_NODE) ?: intent.getStringExtra(EXTRA_WATCH_NODE))
            ?.let(camera::attachWatch)

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
            )
        }

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        // The microphone (video sound) is optional; ask for anything missing without blocking the camera.
        val missing = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::hasPermission)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        camera.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_WATCH_NODE)?.let(camera::attachWatch)
    }

    // Listen while visible (not just resumed): permission dialogs and the notification
    // shade pause the activity but the camera keeps running.
    override fun onStart() {
        super.onStart()
        isActive = true
        Wearable.getMessageClient(this).addListener(this)
        camera.onForeground()
    }

    override fun onResume() {
        super.onResume()
        canLaunchFromWatch = Settings.canDrawOverlays(this)
        Log.d(LOG_TAG, "Camera screen resumed, overlay permission=$canLaunchFromWatch")
        crashReports = CrashLog.reports(this).size
    }

    override fun onStop() {
        isActive = false
        Wearable.getMessageClient(this).removeListener(this)
        camera.onBackground()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Survive recreation (e.g. dark-mode switch) without losing the watch.
        if (camera.streaming) outState.putString(STATE_WATCH_NODE, camera.watchNode)
    }

    override fun onDestroy() {
        camera.release()
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) = camera.handle(event)

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
    camera: RemoteCamera,
    canLaunchFromWatch: Boolean,
    onAllowLaunch: () -> Unit,
    crashReports: Int,
    onShareReports: () -> Unit,
    onClearReports: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        camera.focusRing?.let { ring ->
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
            Pill(if (camera.watchConnected) "⌚ Controlled by watch" else "Open Orbity on your watch")
            if (LogUploader.enabled) {
                val logs = when (LogUploader.reachable) {
                    true -> "Live logs ✓"
                    false -> "Live logs: server unreachable — is Tailscale on?"
                    null -> "Live logs: connecting…"
                }
                Pill(logs, Modifier.padding(top = 8.dp))
            }
            if (camera.listening) {
                Pill("🎧 Watch is listening", Modifier.padding(top = 8.dp))
            }
            if (camera.recordingSeconds >= 0) {
                Pill("● REC ${formatDuration(camera.recordingSeconds)}", Modifier.padding(top = 8.dp), Color(0xFFE53935))
            }
            if (camera.mode != CaptureMode.PHOTO) {
                Pill("Mode: ${camera.mode.label}", Modifier.padding(top = 8.dp))
            }
            if (camera.filter != Filter.ORIGINAL) {
                Pill("Filter: ${camera.filter.label}", Modifier.padding(top = 8.dp))
            }
            if (crashReports > 0) {
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = onShareReports) { Text("Share crash reports ($crashReports)") }
                    TextButton(onClick = onClearReports) { Text("Clear", color = Color.White) }
                }
            }
        }

        if (camera.countdown > 0) {
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
                    Text("Allow watch to open the camera")
                }
            }
            val recording = camera.recordingSeconds >= 0
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .clickable { camera.shutter(0) }
                    .border(5.dp, Color.White, CircleShape)
                    .padding(if (recording) 22.dp else 9.dp)
                    .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                    .background(if (camera.mode.isVideo) Color(0xFFE53935) else Color.White)
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
