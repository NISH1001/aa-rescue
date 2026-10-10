package dev.nish.aarescue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets adb fake a drop for testing (guarded by the DUMP permission, which only
 * the shell/system holds):
 *   adb shell am broadcast -n dev.nish.aarescue/.DebugDropReceiver --ez nav true
 * --ez real true goes through the real-drop path (picks music from what just stopped).
 */
class DebugDropReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Rescue.init(context)
        val nav = intent.getBooleanExtra("nav", false)
        if (intent.getBooleanExtra("real", false)) Rescue.simulateRealDrop(nav) else Rescue.simulateDrop(nav)
    }
}
