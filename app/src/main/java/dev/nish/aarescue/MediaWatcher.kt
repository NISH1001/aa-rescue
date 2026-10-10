package dev.nish.aarescue

import android.app.Notification
import android.content.ComponentName
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent

/**
 * Notification access gives us two things: control of other apps' media
 * sessions (to press play) and visibility of Google Maps' navigation
 * notification (to know a trip was in progress). Being a system-bound
 * listener also keeps this process alive in the background.
 */
class MediaWatcher : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    override fun onListenerConnected() {
        instance = this
        Rescue.init(this)
        RescueLog.i("notification listener connected")
        val msm = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, MediaWatcher::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, me, main)
        track(msm.getActiveSessions(me))
        activeNotifications?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        RescueLog.i("notification listener disconnected")
        getSystemService(MediaSessionManager::class.java)
            .removeOnActiveSessionsChangedListener(sessionsListener)
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        if (instance === this) instance = null
    }

    /** `adb shell dumpsys activity service dev.nish.aarescue/.MediaWatcher` prints the trace log. */
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.print(RescueLog.readTrace())
    }

    private fun track(list: List<MediaController>) {
        callbacks.keys.filter { old -> list.none { it.sessionToken == old.sessionToken } }.forEach {
            it.unregisterCallback(callbacks.remove(it)!!)
        }
        // A session that disappears while "playing" (e.g. a closed browser tab)
        // never sends a final state; forget it so it can't shadow the real player.
        val alive = list.map { it.packageName }.toSet()
        playingNow.filter { it !in alive }.forEach {
            playingNow -= it
            RescueLog.i("media: $it session gone")
        }
        for (c in list) {
            if (callbacks.keys.any { it.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    noteState(c.packageName, state)
            }
            c.registerCallback(cb, main)
            callbacks[c] = cb
            noteState(c.packageName, c.playbackState)
        }
    }

    private fun noteState(pkg: String, state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING
        val was = pkg in playingNow
        if (playing) playingNow += pkg else playingNow -= pkg
        // A drop pauses the music; remember when, so a pause caused by the drop
        // can be told apart from one the user made earlier.
        if (was && !playing) stoppedAt[pkg] = SystemClock.elapsedRealtime()
        if (playing != was) RescueLog.i("media: $pkg ${if (playing) "playing" else "stopped (${state?.state})"}")
    }

    private fun controllerFor(pkg: String): MediaController? =
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, MediaWatcher::class.java))
            .firstOrNull { it.packageName == pkg }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != Rescue.MAPS) return
        if (seenMapsKeys.add(sbn.key)) {
            RescueLog.i("maps notification: category=${sbn.notification.category} ongoing=${sbn.isOngoing}")
        }
        if (!isNavNotification(sbn.notification)) return
        if (navKey == null) {
            navFirstSeenAt = SystemClock.elapsedRealtime()
            val e = sbn.notification.extras
            RescueLog.i(
                "nav notification seen: title=${e.getCharSequence(Notification.EXTRA_TITLE)} " +
                    "text=${e.getCharSequence(Notification.EXTRA_TEXT)} " +
                    "sub=${e.getCharSequence(Notification.EXTRA_SUB_TEXT)} keys=${e.keySet()}"
            )
        }
        navKey = sbn.key
        navLastSeenAt = SystemClock.elapsedRealtime()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key != navKey) return
        RescueLog.i("nav notification removed")
        navKey = null
        navLastSeenAt = SystemClock.elapsedRealtime()
    }

    private fun isNavNotification(n: Notification) = n.category == Notification.CATEGORY_NAVIGATION

    companion object {
        @Volatile var instance: MediaWatcher? = null
            private set

        private val playingNow = mutableSetOf<String>()
        private val stoppedAt = mutableMapOf<String, Long>()
        @Volatile private var navKey: String? = null
        @Volatile private var navLastSeenAt = 0L
        @Volatile private var navFirstSeenAt = 0L
        private val seenMapsKeys = mutableSetOf<String>()

        fun isPlaying(pkg: String) = pkg in playingNow

        /** Apps whose playback stopped at/after [since], most recent first. */
        fun stoppedSince(since: Long): List<String> =
            stoppedAt.filter { it.value >= since && it.key !in playingNow }
                .entries.sortedByDescending { it.value }.map { it.key }

        fun stoppedAtOrAfter(pkg: String, since: Long) = (stoppedAt[pkg] ?: 0L) >= since

        fun playingPackages(): List<String> = playingNow.toList()

        fun playingPackage(): String? = playingNow.firstOrNull()

        fun pauseAll() {
            val svc = instance ?: return
            for (pkg in playingNow.toList()) svc.controllerFor(pkg)?.transportControls?.pause()
        }

        fun wasNavigatingSince(since: Long) = navKey != null || navLastSeenAt >= since

        /**
         * Maps has had a trip notification up continuously since at/after [since],
         * for at least [stableMs]. Right after a drop Maps flashes one briefly,
         * so a short-lived one doesn't count.
         */
        fun navigatingNowSince(since: Long, stableMs: Long = 3_000) =
            navKey != null && navFirstSeenAt >= since &&
                SystemClock.elapsedRealtime() - navFirstSeenAt >= stableMs

        /** Press play on [pkg]'s session; falls back to a media key if it has none. */
        fun play(pkg: String): Boolean {
            val svc = instance ?: return false
            val c = svc.controllerFor(pkg)
            if (c != null) {
                c.transportControls.play()
                return true
            }
            val am = svc.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            return true
        }
    }
}
