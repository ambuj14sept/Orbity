package com.orbity.mobile

import android.app.Application
import com.orbity.shared.CrashLog

class OrbityApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, "Phone app")
    }
}
