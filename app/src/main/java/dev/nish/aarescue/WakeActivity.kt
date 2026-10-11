package dev.nish.aarescue

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Invisible: wakes the screen, gets past the lock screen if allowed, then opens Maps. */
class WakeActivity : Activity() {
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        // isDeviceLocked is false when a trusted device (Extend Unlock) is keeping
        // the phone unlocked; then the lock screen can be dismissed without a PIN.
        RescueLog.i("nav: keyguardLocked=${km.isKeyguardLocked} deviceLocked=${km.isDeviceLocked}")
        if (!km.isKeyguardLocked) openMaps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Asking before we're actually showing over the lock screen gets cancelled.
        if (!hasFocus || asked || isFinishing) return
        asked = true
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = openMaps()
            override fun onDismissCancelled() = fail("cancelled")
            override fun onDismissError() = fail("error")
        })
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun openMaps() {
        // Android Auto may have come back (or we gave up) while the lock screen was up.
        if (!Rescue.navPending) {
            RescueLog.i("nav: no longer needed, not opening Maps")
            finish()
            return
        }
        // Best: Maps' own "open this trip" action, which goes straight back to navigation
        // even when Maps' phone screen has forgotten the trip (e.g. after a locked drop).
        MediaWatcher.tripIntent?.let { trip ->
            val sent = runCatching {
                val opts = if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    ).toBundle()
                } else null
                trip.send(this, 0, null, null, null, null, opts)
            }
            if (sent.isSuccess) {
                RescueLog.i("nav: Maps opened via trip notification action")
                finish()
                return
            }
            RescueLog.i("nav: trip action failed (${sent.exceptionOrNull()?.javaClass?.simpleName}), opening Maps")
        }
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
        Rescue.onNavBlocked()
        finish()
    }

    companion object {
        @Volatile var current: WakeActivity? = null
            private set
    }
}
