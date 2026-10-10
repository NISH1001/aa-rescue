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
    // After an Android Auto drop Maps may show the route preview ("Start"), a
    // paused trip ("Resume"), or the trip summary card ("Restart").
    private val labels = listOf(
        "start", "resume", "restart",
        "start navigation", "resume navigation", "restart navigation",
    )

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
        val root = rootInActiveWindow?.takeIf { it.packageName == Rescue.MAPS }
        val target = root?.let { find(it) }
        if (target == null) {
            // No button to tap: fine only if Maps is genuinely navigating again.
            if (Rescue.navAlreadyRunning()) {
                Rescue.onNavStarted(button = null)
                main.removeCallbacksAndMessages(null)
                return true
            }
            return false
        }
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

    /** For the log when Start wasn't found: which app is in front, and Maps' button labels. */
    fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "no active window"
        if (root.packageName != Rescue.MAPS) return "front app is ${root.packageName}"
        val labels = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo) {
            if (labels.size >= 15) return
            if (n.isClickable && n.isVisibleToUser) {
                (n.text ?: n.contentDescription)?.toString()?.trim()?.take(30)?.takeIf { it.isNotEmpty() }
                    ?.let { labels += it }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return "Maps buttons=$labels"
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
