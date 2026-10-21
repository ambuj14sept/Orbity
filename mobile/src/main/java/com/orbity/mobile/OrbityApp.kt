package com.orbity.mobile

import android.app.Application
import android.os.Build
import com.orbity.shared.CrashLog
import com.orbity.shared.LOG_TAG
import com.orbity.shared.LogShipper

class OrbityApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, "Phone app")
        if (LogUploader.enabled) {
            val header = "Phone ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                "Orbity ${BuildConfig.VERSION_NAME} built ${BuildConfig.BUILD_TIME}"
            // HTTP upload is cheap: keep other tags from Info up (CameraX diagnostics are useful).
            LogShipper.start(header, listOf("$LOG_TAG:V", "*:I")) { text ->
                LogUploader.enqueue("phone", text)
                true
            }
            LogUploader.uploadCrashReports(this)
        }
    }
}
