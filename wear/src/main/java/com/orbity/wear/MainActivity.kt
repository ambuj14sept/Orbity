package com.orbity.wear

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.orbity.shared.CaptureMode
import com.orbity.shared.Flash
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val viewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme { RemoteScreen(viewModel) }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.connect()
    }

    override fun onStop() {
        viewModel.disconnect()
        super.onStop()
    }
}

private val Scrim = Color.Black.copy(alpha = 0.5f)
private val Accent = Color(0xFFFFD60A)
private val RecordRed = Color(0xFFE53935)

/** Control icons sit on an arc across the top of the round screen, 30° apart. */
private const val ICON_SPACING_DEG = 30f

@Composable
private fun RemoteScreen(vm: RemoteViewModel) {
    val focus = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current

    // The bezel delivers rotary events only to the focused element; take focus back
    // whenever the screen content changes.
    LaunchedEffect(vm.control, vm.preview == null) { focus.requestFocus() }
    LaunchedEffect(vm.captures) {
        if (vm.captures > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                // The Watch 6 Classic bezel sends one event per click; use only the direction.
                val px = it.verticalScrollPixels
                if (px > 0f) vm.onBezel(1) else if (px < 0f) vm.onBezel(-1)
                true
            }
            .focusRequester(focus)
            .focusable()
    ) {
        val frame = vm.preview
        if (frame == null) {
            StatusMessage(vm)
            return@BoxWithConstraints
        }
        val half = minOf(maxWidth, maxHeight) / 2
        val iconRing = half - 24.dp
        val lowerRing = half - 32.dp

        // Live preview; tap anywhere on it to focus there.
        val filter = vm.camera.filter
        val colorFilter = remember(filter) {
            filter.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it.copyOf())) }
        }
        Image(
            frame,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        vm.focus(tap.x / size.width, tap.y / size.height, tap)
                    }
                },
        )
        FocusRing(vm)

        vm.progress(vm.control)?.let {
            CircularProgressIndicator(
                progress = it,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                indicatorColor = Accent,
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeWidth = 4.dp,
            )
        }

        // What the bezel adjusts: tap an icon to choose.
        Control.entries.forEachIndexed { i, c ->
            val angle = (i - (Control.entries.size - 1) / 2f) * ICON_SPACING_DEG
            ControlIcon(
                icon = controlIcon(c, vm),
                label = c.label,
                selected = c == vm.control,
                onClick = { vm.selectControl(c) },
                modifier = Modifier.align(Alignment.Center).polar(iconRing, angle),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).offset(y = -iconRing + 30.dp),
        ) {
            Text(
                "${vm.control.label}: ${vm.valueLabel(vm.control)}",
                fontSize = 12.sp,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.pill(),
            )
            val active = vm.activeSettings()
            if (active.isNotEmpty()) {
                Text(
                    active,
                    fontSize = 10.sp,
                    color = Accent,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp).pill(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }

        if (vm.countdown > 0) {
            Text(
                text = vm.countdown.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            BezelHint(vm, Modifier.align(Alignment.Center))
        }

        vm.recordingSince?.let { since ->
            var seconds by remember(since) { mutableLongStateOf(0L) }
            LaunchedEffect(since) {
                while (true) {
                    seconds = (SystemClock.elapsedRealtime() - since) / 1000
                    delay(250)
                }
            }
            Text(
                "● %d:%02d".format(seconds / 60, seconds % 60),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = half - 72.dp)
                    .pill(background = RecordRed, horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // Hear the phone's microphone; off by default (feedback whistle if the watch is near the phone).
        ControlIcon(
            icon = if (vm.listening) Icons.Filled.Hearing else Icons.Filled.HearingDisabled,
            label = if (vm.listening) "Stop listening" else "Listen",
            selected = vm.listening,
            onClick = vm::toggleListening,
            modifier = Modifier.align(Alignment.Center).polar(lowerRing, 257f),
        )

        ControlIcon(
            icon = Icons.Filled.Cameraswitch,
            label = "Flip camera",
            selected = false,
            onClick = vm::flip,
            modifier = Modifier.align(Alignment.Center).polar(lowerRing, 225f),
        )

        vm.lastPhoto?.let {
            // 34 dp thumbnail inside a 44 dp touch target; tap to view full screen.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .polar(lowerRing, 135f)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = vm::openLast),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    it,
                    contentDescription = "Last capture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White, CircleShape),
                )
            }
        }

        Shutter(
            video = vm.camera.mode.isVideo,
            recording = vm.camera.recording,
            onClick = vm::shutter,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )

        if (vm.viewingLast) LastCaptureViewer(vm)
    }
}

/** The last photo (or a video's first frame) filling the screen; tap anywhere to go back. */
@Composable
private fun LastCaptureViewer(vm: RemoteViewModel) {
    val image = vm.lastPhoto ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = vm::closeLast),
    ) {
        Image(
            image,
            contentDescription = "Last capture",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            if (vm.lastWasVideo) "Video saved · tap to close" else "Tap to close",
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).pill(),
        )
    }
}

/** Places the element [radius] from the centre at [degrees] clockwise from 12 o'clock. */
private fun Modifier.polar(radius: Dp, degrees: Float): Modifier {
    val rad = Math.toRadians(degrees.toDouble())
    return offset(x = radius * sin(rad).toFloat(), y = -radius * cos(rad).toFloat())
}

private fun Modifier.pill(
    background: Color = Scrim,
    horizontal: Dp = 10.dp,
    vertical: Dp = 3.dp,
): Modifier = clip(RoundedCornerShape(50)).background(background).padding(horizontal = horizontal, vertical = vertical)

private fun controlIcon(c: Control, vm: RemoteViewModel): ImageVector = when (c) {
    Control.MODE -> when (vm.camera.mode) {
        CaptureMode.PHOTO -> Icons.Filled.PhotoCamera
        CaptureMode.VIDEO -> Icons.Filled.Videocam
        CaptureMode.NIGHT -> Icons.Filled.NightsStay
        CaptureMode.HDR -> Icons.Filled.HdrOn
        CaptureMode.PORTRAIT -> Icons.Filled.Portrait
    }
    Control.ZOOM -> Icons.Filled.ZoomIn
    Control.FILTER -> Icons.Filled.AutoAwesome
    Control.EXPOSURE -> Icons.Filled.Exposure
    Control.TIMER -> Icons.Filled.Timer
    Control.FLASH -> when (vm.camera.flash) {
        Flash.OFF -> Icons.Filled.FlashOff
        Flash.AUTO -> Icons.Filled.FlashAuto
        Flash.ON -> Icons.Filled.FlashOn
    }
}

/** A 30 dp icon with a 40 dp touch target. */
@Composable
private fun ControlIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (selected) Accent else Scrim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Shutter(video: Boolean, recording: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(54.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(4.dp, Color.White, CircleShape)
            .padding(if (recording) 17.dp else 6.dp)
            .clip(if (recording) RoundedCornerShape(4.dp) else CircleShape)
            .background(if (video) RecordRed else Color.White)
    )
}

/** Ring at the last tap: white while focusing, yellow when locked, red when it failed. */
@Composable
private fun FocusRing(vm: RemoteViewModel) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(vm.focusId) {
        if (vm.focusId == 0) return@LaunchedEffect
        visible = true
        delay(1_500)
        visible = false
    }
    val tap = vm.focusTap
    if (!visible || tap == null) return
    val color = when (vm.focusResult) {
        true -> Accent
        false -> Color(0xFFFF6B6B)
        null -> Color.White
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color, radius = 16.dp.toPx(), center = tap, style = Stroke(2.dp.toPx()))
    }
}

/** Shows the new value for a moment after each bezel click. */
@Composable
private fun BezelHint(vm: RemoteViewModel, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(vm.hintId) {
        if (vm.hintId == 0) return@LaunchedEffect
        visible = true
        delay(vm.hintMillis)
        visible = false
    }
    if (visible) {
        Text(
            vm.hint,
            fontSize = if (vm.hint.length > 20) 13.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = modifier.padding(horizontal = 28.dp).pill(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StatusMessage(vm: RemoteViewModel) {
    val (message, action) = when (vm.status) {
        Status.CONNECTING -> "Connecting to phone…" to null
        Status.OPENING, Status.STREAMING -> "Opening camera on phone…" to null
        Status.NEEDS_PHONE -> "Tap the Orbity notification on your phone" to "Retry"
        Status.NO_PHONE -> "Phone not reachable.\nIs Orbity installed on it?" to "Retry"
        Status.CLOSED -> "Camera closed on phone" to "Reopen"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Text(message, textAlign = TextAlign.Center, color = Color.White)
        if (action != null) {
            CompactChip(
                onClick = vm::connect,
                label = { Text(action) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
