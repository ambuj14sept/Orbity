package com.orbity.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.LOG_TAG
import com.orbity.shared.Paths

/**
 * Runs the camera as a foreground service, so the watch can use it with the phone locked or
 * another app open. Android requires the ongoing notification while the camera is in use.
 * When nothing needs the camera (no watch, recording, listening or camera screen) it closes the
 * camera within a second and stops itself 30 s later unless the watch comes back.
 */
class CameraService : LifecycleService(), MessageClient.OnMessageReceivedListener {

    companion object {
        private const val EXTRA_WATCH_NODE = "watch_node"
        private const val ACTION_STOP = "com.orbity.action.STOP_CAMERA"
        /** Minimised: no status bar icon, collapsed at the bottom of the shade, silent. */
        private const val CHANNEL_ID = "camera_active_min"
        private const val OLD_CHANNEL_ID = "camera_active"
        private const val NOTIFICATION_ID = 2
        /** Closes the camera this soon after nothing needs it (green dot off). */
        private const val IDLE_CLOSE_MS = 1_000L
        /** Then the service (and its notification) goes away unless the watch comes back. */
        private const val IDLE_STOP_MS = 30_000L

        /** True from creation until destruction; the service handles watch messages itself then. */
        @Volatile var isRunning = false
            private set

        private val foregroundWaiters = mutableListOf<() -> Unit>()
        private var inForeground = false

        /** Starts (or updates) the service. Must be called while the app is visible. */
        fun start(context: Context, watchNode: String?) {
            val intent = Intent(context, CameraService::class.java).putExtra(EXTRA_WATCH_NODE, watchNode)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Runs [action] on the main thread once the service holds the camera in the foreground. */
        fun whenForeground(action: () -> Unit) {
            if (inForeground) action() else foregroundWaiters += action
        }
    }

    inner class LocalBinder : Binder() {
        val camera: RemoteCamera get() = this@CameraService.camera

        fun attachScreen(view: PreviewView?) = this@CameraService.attachScreen(view)
    }

    private lateinit var camera: RemoteCamera
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var screenAttached = false
    private var destroyed = false
    private val closeIfIdle = Runnable {
        if (!destroyed && !screenAttached && !camera.isBusy) camera.pause()
    }
    private val stopIfIdle = Runnable {
        if (!destroyed && !screenAttached && !camera.isBusy) {
            Log.i(LOG_TAG, "Camera idle; stopping service")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        camera = RemoteCamera(this, this, lifecycleScope, onIdleCheck = ::scheduleIdleCheck)
        Wearable.getMessageClient(this).addListener(this)
        Log.i(LOG_TAG, "Camera service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Log.i(LOG_TAG, "Camera stopped from notification")
            stopSelf()
            return START_NOT_STICKY
        }
        val node = intent?.getStringExtra(EXTRA_WATCH_NODE)
        if (!enterForeground()) {
            // The watch shows "open Orbity on your phone".
            node?.let { Wearable.getMessageClient(this).sendMessage(it, Paths.NEEDS_PHONE, null) }
            stopSelf()
            return START_NOT_STICKY
        }
        camera.start()
        node?.let(camera::attachWatch)
        scheduleIdleCheck()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    /** The camera screen is visible and shows [view]; null when it hides. */
    fun attachScreen(view: PreviewView?) {
        screenAttached = view != null
        camera.attachPreview(view)
        scheduleIdleCheck()
    }

    override fun onMessageReceived(event: MessageEvent) {
        camera.handle(event)
        if (event.path == Paths.START) {
            handler.removeCallbacks(closeIfIdle)
            handler.removeCallbacks(stopIfIdle)
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        Wearable.getMessageClient(this).removeListener(this)
        camera.release()
        isRunning = false
        inForeground = false
        foregroundWaiters.clear()
        Log.i(LOG_TAG, "Camera service destroyed")
        super.onDestroy()
    }

    private fun scheduleIdleCheck() {
        handler.removeCallbacks(closeIfIdle)
        handler.removeCallbacks(stopIfIdle)
        if (!destroyed && !screenAttached && !camera.isBusy) {
            handler.postDelayed(closeIfIdle, IDLE_CLOSE_MS)
            handler.postDelayed(stopIfIdle, IDLE_STOP_MS)
        }
    }

    /**
     * Android only grants camera (and microphone) access to a foreground service started while
     * the app is visible; callers ensure that (camera screen or [LaunchActivity]).
     */
    private fun enterForeground(): Boolean {
        if (inForeground) return true
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // Camera/microphone service types exist from Android 11; on Android 10 the manifest types apply.
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                (if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            inForeground = true
            Log.i(LOG_TAG, "Camera service in foreground (microphone: $hasMic)")
            foregroundWaiters.toList().forEach { it() }
            foregroundWaiters.clear()
            true
        } catch (e: Exception) {
            // E.g. started from the background without being exempt, or permission missing.
            Log.e(LOG_TAG, "Cannot start camera service in foreground", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Android requires a notification while the camera runs in the background; keep it minimal.
        // A channel's importance cannot be lowered after creation, hence the new channel id.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Camera in use", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the watch is using the phone camera"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CameraActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Orbity camera on")
            .setContentText("Controlled by your watch")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}
