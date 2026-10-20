package com.orbity.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.orbity.shared.CrashLog
import com.orbity.shared.Paths

/**
 * Wakes up when the watch app opens while the camera screen is not in the foreground,
 * and brings the camera up on the phone.
 */
class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Paths.CRASH) {
            CrashLog.save(this, "Forwarded from watch:\n\n" + String(event.data))
            return
        }
        // While the camera screen is visible it handles messages itself.
        if (event.path != Paths.START || CameraActivity.isActive) return

        val intent = Intent(this, CameraActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CameraActivity.EXTRA_WATCH_NODE, event.sourceNodeId)

        // Apps allowed to draw over other apps may start activities from the background.
        if (Settings.canDrawOverlays(this)) {
            startActivity(intent)
        } else {
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
            .setContentText("Tap to open Orbity")
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
