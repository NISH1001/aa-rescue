package dev.nish.aarescue

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Taps Google Maps' "Start"/"Resume" button, but only while Rescue has armed it
 * right after an Android Auto drop. Events are restricted to the Maps package
 * in res/xml/maps_clicker.xml, and the screen is only read while Maps is in
 * front, so no other app's content is ever looked at.
 */
class MapsClicker : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val labels = listOf("start", "resume", "start navigation", "resume navigation")

    override fun onServiceConnected() {
        instance = this
        Rescue.init(this)
        RescueLog.i("accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Rescue.navClickArmed()) tryClick()
    }

    /** Events alone may not fire if Maps is already showing; poll while armed. */
    fun poke() {
        main.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                if (!Rescue.navClickArmed()) return
                if (!tryClick()) main.postDelayed(this, 700)
            }
        }
        main.postDelayed(tick, 700)
    }

    private fun tryClick(): Boolean {
        // Only ever look at the screen when Maps is the app in front.
        val root = rootInActiveWindow?.takeIf { it.packageName == Rescue.MAPS } ?: return false
        val target = find(root) ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null && !node.isClickable) node = node.parent
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        RescueLog.i("nav: tapped '${target.text ?: target.contentDescription}' clicked=$clicked")
        if (clicked) {
            Rescue.onNavStarted((target.text ?: target.contentDescription).toString())
            main.removeCallbacksAndMessages(null)
        }
        return clicked
    }

    private fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
        if (label != null && label in labels && n.isVisibleToUser && n.isEnabled) return n
        for (i in 0 until n.childCount) {
            val hit = n.getChild(i)?.let { find(it) }
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        @Volatile var instance: MapsClicker? = null
            private set
    }
}
