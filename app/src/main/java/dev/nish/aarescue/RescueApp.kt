package dev.nish.aarescue

import android.app.Application

class RescueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RescueLog.init(this)
        Rescue.init(this)
    }
}
