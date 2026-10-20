package com.orbity.wear

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

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

    Box(
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
            return@Box
        }

        val filter = vm.camera.filter
        val colorFilter = remember(filter) {
            filter.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it.copyOf())) }
        }
        Image(
            frame,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )

        vm.progress(vm.control)?.let {
            CircularProgressIndicator(
                progress = it,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                indicatorColor = Accent,
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeWidth = 4.dp,
            )
        }

        // Tap to choose what the bezel adjusts.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        ) {
            CompactChip(
                onClick = vm::nextControl,
                label = {
                    Text(
                        "${vm.control.label}: ${vm.valueLabel(vm.control)}",
                        maxLines = 1,
                        fontSize = 12.sp,
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = Scrim, contentColor = Color.White),
            )
            val active = vm.activeSettings()
            if (active.isNotEmpty()) {
                Text(
                    active,
                    fontSize = 10.sp,
                    color = Accent,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Scrim)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
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

        Button(
            onClick = vm::flip,
            colors = ButtonDefaults.buttonColors(backgroundColor = Scrim),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(ButtonDefaults.SmallButtonSize),
        ) {
            Icon(Icons.Filled.Cameraswitch, contentDescription = "Flip camera", tint = Color.White)
        }

        vm.lastPhoto?.let {
            Image(
                it,
                contentDescription = "Last photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(56.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(7.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = vm::shutter)
        )
    }
}

/** Shows the new value for a moment after each bezel click. */
@Composable
private fun BezelHint(vm: RemoteViewModel, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(vm.hintId) {
        if (vm.hintId == 0) return@LaunchedEffect
        visible = true
        delay(900)
        visible = false
    }
    if (visible) {
        Text(
            vm.hint,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(Scrim)
                .padding(horizontal = 14.dp, vertical = 6.dp),
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
