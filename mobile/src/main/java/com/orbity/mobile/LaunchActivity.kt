package com.orbity.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.orbity.shared.LOG_TAG

/**
 * Invisible, momentary activity that starts [CameraService] for the watch. Android grants camera
 * and microphone access only to a foreground service started while the app is visible, so this
 * appears for a split second (over the lock screen too, waking the screen if it is off), starts
 * the service and finishes as soon as the service holds the camera.
 */
@SuppressLint("CustomSplashScreen") // not a splash screen: an invisible trampoline for the camera service
class LaunchActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onResume() {
        super.onResume()
        if (launched) return
        launched = true
        Log.i(LOG_TAG, "Starting camera for the watch")
        CameraService.start(this, intent.getStringExtra(EXTRA_WATCH_NODE))
        CameraService.whenForeground(::close)
        // Never linger, even if the service could not start.
        handler.postDelayed(::close, MAX_VISIBLE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun close() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_WATCH_NODE = "watch_node"
        private const val MAX_VISIBLE_MS = 3_000L
    }
}
