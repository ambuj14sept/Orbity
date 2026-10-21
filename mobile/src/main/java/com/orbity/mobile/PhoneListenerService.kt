package com.orbity.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.orbity.shared.CrashLog
import com.orbity.shared.LOG_TAG
import com.orbity.shared.Paths

/**
 * Wakes up for watch messages while the camera is not running: starts the camera silently in
 * the background (via [LaunchActivity]) when the watch app opens, and handles logs and crash reports.
 */
class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Paths.LOG -> {
                LogUploader.enqueue("watch", String(event.data))
                return
            }
            Paths.CRASH -> {
                CrashLog.save(this, "Forwarded from watch:\n\n" + String(event.data))
                LogUploader.uploadCrashReports(this)
                return
            }
        }
        // While the camera service runs it handles watch messages itself.
        if (event.path != Paths.START || CameraService.isRunning) return

        val intent = Intent(this, LaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(LaunchActivity.EXTRA_WATCH_NODE, event.sourceNodeId)

        // Apps allowed to draw over other apps may start activities from the background.
        if (Settings.canDrawOverlays(this)) {
            Log.i(LOG_TAG, "Watch asked for camera; starting it in the background")
            startActivity(intent)
        } else {
            Log.i(LOG_TAG, "Watch asked for camera; no overlay permission, showing notification")
            showOpenCameraNotification(intent)
            Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, Paths.NEEDS_PHONE, null)
        }
    }

    private fun showOpenCameraNotification(intent: Intent) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch remote", NotificationManager.IMPORTANCE_HIGH)
        )
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Watch wants to use the camera")
            .setContentText("Tap to start the camera")
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "watch_remote"
        const val NOTIFICATION_ID = 1
    }
}
