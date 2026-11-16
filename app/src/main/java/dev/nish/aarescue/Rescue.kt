package dev.nish.aarescue

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.connection.CarConnection

/**
 * Watches the Android Auto connection. When a projection session ends, it puts
 * things back the way they were: music that was playing plays again, and a
 * trip that was in progress gets restarted in Google Maps. Nothing that was
 * off gets turned on.
 *
 * It also watches the start of a session: Android Auto's "Start music
 * automatically" presses play on connect, and with no speaker or headphones
 * connected that comes out of the phone itself. That gets paused again.
 */
object Rescue {
    const val MAPS = "com.google.android.apps.maps"

    /** Maps may pull its trip notification just before the drop is reported. */
    private const val NAV_GRACE_MS = 5_000L
    private const val MUSIC_TICK_MS = 500L
    /** Android Auto's autoplay can land just before the connection is reported. */
    private const val AUTOPLAY_LOOKBACK_MS = 1_500L
    /** Reconnecting this soon after a drop that had music: it's the same drive, leave music alone. */
    private const val SAME_DRIVE_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var lastType = -1
    private var initialized = false

    @Volatile private var navClickDeadline = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        Motion.init(app)
        CarConnection(app).type.observeForever { type -> onCarConnection(type) }
        watchBluetooth()
    }

    /** BluetoothManager.getConnectedDevices only covers GATT; audio profiles need proxies. */
    private val btProxies = mutableMapOf<Int, BluetoothProfile>()

    private fun watchBluetooth() {
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                btProxies[profile] = proxy
            }
            override fun onServiceDisconnected(profile: Int) {
                btProxies.remove(profile)
            }
        }
        adapter.getProfileProxy(app, listener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(app, listener, BluetoothProfile.HEADSET)
    }

    private fun onCarConnection(type: Int) {
        val prev = lastType
        lastType = type
        RescueLog.i("car connection: ${name(prev)} -> ${name(type)}")
        when {
            type == CarConnection.CONNECTION_TYPE_PROJECTION -> {
                main.removeCallbacksAndMessages(null)
                pendingRescue = null
                Motion.onMoving = null
                Motion.start()
                stopWaitingForUnlock()
                navPending = false
                WakeActivity.current?.finish()
                navClickDeadline = 0L
                if (prev != -1) RescueLog.event("Android Auto reconnected")
                // prev == -1 is this process starting mid-session, not a new connection.
                if (prev == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) onConnected()
            }
            prev == CarConnection.CONNECTION_TYPE_PROJECTION &&
                type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> {
                connectedAt = 0L
                onDropped()
            }
        }
    }

    /** When the current session connected; 0 when not connected. */
    private var connectedAt = 0L
    private var lastMusicDropAt = 0L
    /** Apps paused by [onPlaybackStarted] that haven't played since; a drop must not resume them. */
    private val autoPaused = mutableSetOf<String>()

    fun onConnected() {
        val now = SystemClock.elapsedRealtime()
        connectedAt = now
        autoPaused.clear()
        RescueLog.i("CONNECT outputs=${audioOutputs()} playing=${MediaWatcher.playingPackages()}")
        MediaWatcher.startedSince(now - AUTOPLAY_LOOKBACK_MS).forEach { onPlaybackStarted(it) }
    }

    /**
     * An app just started playing. Right after Android Auto connects that's its
     * autoplay, not the user; if the sound has nowhere to go but the phone's
     * own speaker, pause it.
     */
    fun onPlaybackStarted(pkg: String) {
        if (!initialized || connectedAt == 0L) {
            autoPaused -= pkg
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - connectedAt > Timing.AUTOPLAY.get(app)) {
            autoPaused -= pkg
            return
        }
        if (!Prefs.enabled(app) || !Prefs.stopAutoplay(app)) return
        if (lastMusicDropAt != 0L && connectedAt - lastMusicDropAt < SAME_DRIVE_MS) {
            RescueLog.i("autoplay: $pkg left playing, music was on at a drop moments ago")
            return
        }
        val external = externalOutputs()
        if (external.isNotEmpty()) {
            RescueLog.i("autoplay: $pkg left playing, output available: $external")
            return
        }
        RescueLog.i("autoplay: pausing $pkg ${now - connectedAt} ms after connect, outputs=${audioOutputs()}")
        MediaWatcher.pause(pkg)
        if (autoPaused.add(pkg)) {
            RescueLog.event("Stopped ${label(pkg)} — it started by itself with no speaker connected")
        }
    }

    /** adb only: pretend Android Auto just connected, to try the autoplay stop without a car. */
    fun simulateConnect() {
        lastMusicDropAt = 0L
        Motion.start()
        onConnected()
    }

    private fun outputDevices() =
        app.getSystemService(android.media.AudioManager::class.java)
            .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)

    private fun audioOutputs() = outputDevices().map { "${it.type}:${it.productName}" }

    /** Somewhere other than the phone itself that media could play: speakers, headphones, the car screen. */
    private fun externalOutputs() = outputDevices().filter { it.type in EXTERNAL_OUTPUTS }.map { "${it.productName}" }

    private val EXTERNAL_OUTPUTS = run {
        setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
            android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST,
            android.media.AudioDeviceInfo.TYPE_HEARING_AID,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
            android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL,
            android.media.AudioDeviceInfo.TYPE_AUX_LINE,
            android.media.AudioDeviceInfo.TYPE_HDMI,
            android.media.AudioDeviceInfo.TYPE_DOCK,
            // Android Auto capturing media to play it through the car screen.
            android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        )
    }

    fun isProjecting() = lastType == CarConnection.CONNECTION_TYPE_PROJECTION

    fun onDropped(test: Boolean = false, playingAtTest: String? = null, forceNav: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Stopped right around the drop = stopped by the drop.
        // (Not what we paused ourselves at connect: that was never meant to play.)
        val stopped = if (test) listOfNotNull(playingAtTest)
        else MediaWatcher.stoppedSince(now - Timing.MUSIC_GRACE.get(app)) - autoPaused
        // Still playing at the drop: the drop's own pause may land a moment later,
        // so watch these too, but only act if they actually stop.
        val stillPlaying = if (test) emptyList() else MediaWatcher.playingPackages() - stopped.toSet()
        val navigating = forceNav || MediaWatcher.wasNavigatingSince(now - NAV_GRACE_MS)
        val screenOn = app.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (!test && (stopped.isNotEmpty() || stillPlaying.isNotEmpty())) lastMusicDropAt = now
        if (!Prefs.enabled(app)) {
            RescueLog.i("DROP ignored: AA Rescue is paused")
            RescueLog.event("Android Auto dropped — AA Rescue is paused, did nothing")
            Motion.stop()
            return
        }
        RescueLog.i(
            "DROP test=$test stopped=$stopped stillPlaying=$stillPlaying navigating=$navigating " +
                "screenOn=$screenOn bt=${connectedBluetooth()}"
        )

        val doMusic = (stopped.isNotEmpty() || stillPlaying.isNotEmpty()) && Prefs.resumeMusic(app)
        val doNav = navigating && Prefs.resumeNav(app)
        val what = listOfNotNull(
            stopped.takeIf { it.isNotEmpty() }?.let { pkgs ->
                pkgs.joinToString(" & ") { label(it) } + " was playing" +
                    if (!Prefs.resumeMusic(app)) " (resume is off)" else ""
            },
            "a trip was in progress".takeIf { navigating }?.let { it + if (!doNav) " (resume is off)" else "" },
        )
        RescueLog.event(
            (if (test) "Test drop" else "Android Auto dropped") + " — " +
                what.ifEmpty { listOf("nothing was playing, left as is") }.joinToString(", ")
        )

        if (!doMusic && !doNav) {
            Motion.stop()
            return
        }
        val watch = if (doMusic) MusicWatch(stopped.toSet(), stillPlaying.toSet(), droppedAt = now) else null
        val rescue = Runnable {
            if (watch != null) resumeMusic(watch, attempt = 1)
            if (doNav) resumeNav()
        }
        // Stopped at the drop: a red light and a switched-off car look the same.
        // Wait; only a car that drives on gets its music and map back.
        val wait = !test && Prefs.waitForMoving(app) && Motion.usable() && !Motion.moving()
        RescueLog.i("motion: ${Motion.describe()} usable=${Motion.usable()} wait=$wait")
        if (!wait) {
            Motion.stop()
            main.postDelayed(rescue, Timing.REACT.get(app))
            return
        }
        RescueLog.event("Car is stopped — will bring it back when the car moves")
        pendingRescue = rescue
        Motion.onMoving = {
            pendingRescue?.let {
                pendingRescue = null
                Motion.stop()
                RescueLog.event("Car is moving again")
                it.run()
            }
        }
        main.postDelayed({
            if (pendingRescue != null) {
                pendingRescue = null
                Motion.stop()
                RescueLog.event("Car didn't move — left as is")
            }
        }, Timing.MOVE_WAIT.get(app))
    }

    /** What to do once the car moves, after a drop while stopped. */
    private var pendingRescue: Runnable? = null

    fun waitingForMove() = pendingRescue != null

    /** What to keep playing after a drop, and what we actually had to press play on. */
    private class MusicWatch(val stopped: Set<String>, val stillPlaying: Set<String>, val droppedAt: Long) {
        val pressed = linkedSetOf<String>()
    }

    /** adb only: pause what's playing, then run the real drop logic as if Android Auto dropped. */
    fun simulateRealDrop(forceNav: Boolean) {
        MediaWatcher.pauseAll()
        main.postDelayed({ onDropped(forceNav = forceNav) }, 300)
    }

    /** "Test a drop" button: note what's playing *now*, pause it, then run a fake drop. */
    fun simulateDrop(forceNav: Boolean = false) {
        val playing = MediaWatcher.playingPackage()
        MediaWatcher.pauseAll()
        main.postDelayed({ onDropped(test = true, playingAtTest = playing, forceNav = forceNav) }, 500)
    }

    /**
     * Keeps music playing for a short window after the drop. Android's own
     * "audio unplugged" pause can land after our first play(), so we keep
     * checking and press play again if it stops.
     */
    private fun resumeMusic(w: MusicWatch, attempt: Int) {
        if (isProjecting()) return
        for (pkg in w.stopped + w.stillPlaying) {
            if (MediaWatcher.isPlaying(pkg)) continue
            // A still-playing app only counts once it has actually stopped since the drop.
            if (pkg !in w.stopped && !MediaWatcher.stoppedAtOrAfter(pkg, w.droppedAt)) continue
            w.pressed += pkg
            RescueLog.i("music: play() on $pkg attempt $attempt sent=${MediaWatcher.play(pkg)}")
        }
        if (attempt < Timing.MUSIC_HOLD.get(app) / MUSIC_TICK_MS) {
            main.postDelayed({ resumeMusic(w, attempt + 1) }, MUSIC_TICK_MS)
            return
        }
        // Give the last play() a moment to take effect before judging.
        main.postDelayed({ reportMusic(w) }, MUSIC_TICK_MS)
    }

    private fun reportMusic(w: MusicWatch) {
        for (pkg in w.pressed) {
            RescueLog.event(
                if (MediaWatcher.isPlaying(pkg)) "Resumed ${label(pkg)}" else "Couldn't resume ${label(pkg)}"
            )
        }
    }

    private fun resumeNav() {
        if (isProjecting()) return
        navPending = true
        navResumeStartedAt = SystemClock.elapsedRealtime()
        val findMs = Timing.NAV_FIND.get(app)
        navClickDeadline = navResumeStartedAt + findMs
        RescueLog.i("nav: bringing Maps forward, Start-tap armed")
        // WakeActivity turns the screen on, dismisses the keyguard if it can,
        // then opens Maps in its existing state (route preview with Start).
        app.startActivity(
            Intent(app, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val clicker = MapsClicker.instance
        if (clicker == null) {
            navClickDeadline = 0L
            RescueLog.event("Opened Maps — tap Start (Maps auto-Start isn't turned on)")
            return
        }
        clicker.poke()
        main.postDelayed({
            if (navClickDeadline != 0L) {
                navClickDeadline = 0L
                navPending = false
                RescueLog.i("nav: no Start found; screen: ${MapsClicker.instance?.describeScreen()}")
                RescueLog.event("Opened Maps but couldn't find Start")
            }
        }, findMs)
    }

    @Volatile private var navResumeStartedAt = 0L

    /** True from a drop until navigation is restored, given up on, or Android Auto is back. */
    @Volatile var navPending = false
        private set

    fun navClickArmed() = SystemClock.elapsedRealtime() < navClickDeadline

    /** No Start button needed if Maps is already back in turn-by-turn on its own. */
    fun navAlreadyRunning() = MediaWatcher.navigatingNowSince(navResumeStartedAt)

    fun onNavStarted(button: String?) {
        navClickDeadline = 0L
        navPending = false
        RescueLog.event(
            if (button != null) "Restarted navigation (tapped $button)" else "Navigation is back on (Maps resumed it)"
        )
    }

    /**
     * The phone needs a PIN/fingerprint. Wait (a few minutes) for the user to
     * unlock, then restore navigation straight away.
     */
    fun onNavBlocked() {
        navClickDeadline = 0L
        if (!navPending || isProjecting() || waitingForUnlock != null) return
        if (Timing.UNLOCK_WAIT.get(app) == 0L) {
            navPending = false
            RescueLog.event("Couldn't open Maps — phone is locked")
            return
        }
        RescueLog.event("Phone is locked — will restart navigation as soon as you unlock")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                stopWaitingForUnlock()
                if (isProjecting()) return
                RescueLog.i("nav: unlocked, resuming")
                resumeNav()
            }
        }
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        waitingForUnlock = receiver
        main.postDelayed({ stopWaitingForUnlock(); navPending = false }, Timing.UNLOCK_WAIT.get(app))
    }

    private var waitingForUnlock: BroadcastReceiver? = null

    private fun stopWaitingForUnlock() {
        waitingForUnlock?.let { runCatching { app.unregisterReceiver(it) } }
        waitingForUnlock = null
    }

    private fun label(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun connectedBluetooth(): String {
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return "no-permission"
        return btProxies.flatMap { (p, proxy) ->
            runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                .map { "${it.name}(${if (p == BluetoothProfile.A2DP) "a2dp" else "hfp"})" }
        }.joinToString().ifEmpty { "none" }
    }

    private fun name(t: Int) = when (t) {
        CarConnection.CONNECTION_TYPE_PROJECTION -> "PROJECTION"
        CarConnection.CONNECTION_TYPE_NATIVE -> "NATIVE"
        CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "NOT_CONNECTED"
        else -> "UNKNOWN($t)"
    }
}
