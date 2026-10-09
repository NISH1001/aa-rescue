package dev.nish.aarescue

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle

/** Invisible: wakes the screen, gets past the lock screen if allowed, then opens Maps. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            openMaps()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = openMaps()
            override fun onDismissCancelled() = fail("cancelled")
            override fun onDismissError() = fail("error")
        })
    }

    private fun openMaps() {
        val i = packageManager.getLaunchIntentForPackage(Rescue.MAPS)
        if (i == null) {
            fail("Maps not installed")
            return
        }
        // Launcher intent + existing task = Maps comes back exactly where it was.
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        RescueLog.i("nav: Maps opened")
        finish()
    }

    private fun fail(why: String) {
        RescueLog.i("nav: could not get past lock screen ($why)")
        Rescue.onNavBlocked(why)
        finish()
    }
}
