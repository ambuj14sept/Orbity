package com.orbity.wear

import android.app.Application
import com.orbity.shared.CrashLog

class OrbityApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, "Watch app")
    }
}
