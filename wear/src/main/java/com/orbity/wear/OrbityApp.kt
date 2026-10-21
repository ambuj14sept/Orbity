package com.orbity.wear

import android.app.Application
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.orbity.shared.CAPABILITY_PHONE
import com.orbity.shared.CrashLog
import com.orbity.shared.LOG_TAG
import com.orbity.shared.LogShipper
import com.orbity.shared.Paths
import java.util.concurrent.TimeUnit

class OrbityApp : Application() {

    @Volatile private var phoneNode: String? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, "Watch app")
        val header = "Watch ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
            "Orbity ${BuildConfig.VERSION_NAME} built ${BuildConfig.BUILD_TIME}"
        // Watch logs share the link with preview frames: only Orbity's own lines plus warnings,
        // errors and crashes from everything else.
        // HWUI warns once per decoded preview frame; keep only its errors.
        LogShipper.start(header, listOf("$LOG_TAG:V", "HWUI:E", "*:W"), ::sendLogsToPhone)
    }

    /** Runs on the log shipping thread; the phone app forwards these to the log server. */
    private fun sendLogsToPhone(text: String): Boolean {
        return try {
            val node = phoneNode ?: Tasks.await(
                Wearable.getCapabilityClient(this).getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
                TIMEOUT_S, TimeUnit.SECONDS,
            ).nodes.firstOrNull()?.id?.also { phoneNode = it } ?: return false
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(node, Paths.LOG, text.toByteArray()),
                TIMEOUT_S, TimeUnit.SECONDS,
            )
            true
        } catch (e: Exception) {
            phoneNode = null
            false
        }
    }

    private companion object {
        const val TIMEOUT_S = 5L
    }
}
