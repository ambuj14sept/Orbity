package com.orbity.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.CrashLog
import com.orbity.shared.Filter

class CameraActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    companion object {
        const val EXTRA_WATCH_NODE = "watch_node"
        private const val STATE_WATCH_NODE = "watch_node"

        /** True while this screen is visible and listening for watch messages itself. */
        @Volatile var isActive = false
            private set
    }

    private lateinit var camera: RemoteCamera
    private var canLaunchFromWatch by mutableStateOf(false)
    private var crashReports by mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) {
                camera.start()
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

        if (hasPermission(Manifest.permission.CAMERA)) {
            camera.start()
        } else {
            val wanted = buildList {
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(wanted.toTypedArray())
        }
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
        ) {
            Pill(if (camera.watchConnected) "⌚ Controlled by watch" else "Open Orbity on your watch")
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
            Box(
                Modifier
                    .size(76.dp)
                    .border(5.dp, Color.White, CircleShape)
                    .padding(9.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { camera.capture(0) }
            )
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
