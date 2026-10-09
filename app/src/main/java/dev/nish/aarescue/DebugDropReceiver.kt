package dev.nish.aarescue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets adb fake a drop for testing (guarded by the DUMP permission, which only
 * the shell/system holds):
 *   adb shell am broadcast -n dev.nish.aarescue/.DebugDropReceiver --ez nav true
 */
class DebugDropReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Rescue.init(context)
        Rescue.onDropped(test = true, forceNav = intent.getBooleanExtra("nav", false))
    }
}
