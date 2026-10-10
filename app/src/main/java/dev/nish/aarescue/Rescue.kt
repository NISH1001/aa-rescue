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
 */
object Rescue {
    const val MAPS = "com.google.android.apps.maps"

    /** Maps may pull its trip notification just before the drop is reported. */
    private const val NAV_GRACE_MS = 5_000L
    private const val MUSIC_TICK_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var lastType = -1
    private var initialized = false

    @Volatile private var navClickDeadline = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
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
                stopWaitingForUnlock()
                navClickDeadline = 0L
                if (prev != -1) RescueLog.event("Android Auto reconnected")
            }
            prev == CarConnection.CONNECTION_TYPE_PROJECTION &&
                type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> onDropped()
        }
    }

    fun isProjecting() = lastType == CarConnection.CONNECTION_TYPE_PROJECTION

    fun onDropped(test: Boolean = false, playingAtTest: String? = null, forceNav: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Stopped right around the drop = stopped by the drop.
        val stopped = if (test) listOfNotNull(playingAtTest)
        else MediaWatcher.stoppedSince(now - Timing.MUSIC_GRACE.get(app))
        // Still playing at the drop: the drop's own pause may land a moment later,
        // so watch these too, but only act if they actually stop.
        val stillPlaying = if (test) emptyList() else MediaWatcher.playingPackages() - stopped.toSet()
        val navigating = forceNav || MediaWatcher.wasNavigatingSince(now - NAV_GRACE_MS)
        val screenOn = app.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (!Prefs.enabled(app)) {
            RescueLog.i("DROP ignored: AA Rescue is paused")
            RescueLog.event("Android Auto dropped — AA Rescue is paused, did nothing")
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

        val react = Timing.REACT.get(app)
        if (doMusic) {
            val watch = MusicWatch(stopped.toSet(), stillPlaying.toSet(), droppedAt = now)
            main.postDelayed({ resumeMusic(watch, attempt = 1) }, react)
        }
        if (doNav) main.postDelayed({ resumeNav() }, react)
    }

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
        for (pkg in w.pressed) {
            RescueLog.event(
                if (MediaWatcher.isPlaying(pkg)) "Resumed ${label(pkg)}" else "Couldn't resume ${label(pkg)}"
            )
        }
    }

    private fun resumeNav() {
        if (isProjecting()) return
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
                RescueLog.i("nav: no Start found; screen: ${MapsClicker.instance?.describeScreen()}")
                RescueLog.event("Opened Maps but couldn't find Start")
            }
        }, findMs)
    }

    @Volatile private var navResumeStartedAt = 0L

    fun navClickArmed() = SystemClock.elapsedRealtime() < navClickDeadline

    /** No Start button needed if Maps is already back in turn-by-turn on its own. */
    fun navAlreadyRunning() = MediaWatcher.navigatingNowSince(navResumeStartedAt)

    fun onNavStarted(button: String?) {
        navClickDeadline = 0L
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
        if (waitingForUnlock != null) return
        if (Timing.UNLOCK_WAIT.get(app) == 0L) {
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
        main.postDelayed({ stopWaitingForUnlock() }, Timing.UNLOCK_WAIT.get(app))
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
