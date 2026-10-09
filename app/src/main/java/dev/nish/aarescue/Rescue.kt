package dev.nish.aarescue

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
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

    /**
     * Music that stopped this close to the drop counts as stopped *by* the drop
     * (the pause and the disconnect arrive within a few hundred ms of each other).
     * Kept short so a pause you made yourself just before isn't undone.
     */
    private const val MUSIC_GRACE_MS = 1_000L
    /** Maps may pull its trip notification just before the drop is reported. */
    private const val NAV_GRACE_MS = 5_000L
    private const val MUSIC_TICKS = 8
    private const val MUSIC_TICK_MS = 500L
    private const val NAV_CLICK_WINDOW_MS = 25_000L

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
    }

    private fun onCarConnection(type: Int) {
        val prev = lastType
        lastType = type
        RescueLog.i("car connection: ${name(prev)} -> ${name(type)}")
        when {
            type == CarConnection.CONNECTION_TYPE_PROJECTION -> {
                main.removeCallbacksAndMessages(null)
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
        val musicPkg = if (test) playingAtTest else MediaWatcher.musicStoppedByDrop(now - MUSIC_GRACE_MS)
        val navigating = forceNav || MediaWatcher.wasNavigatingSince(now - NAV_GRACE_MS)
        if (!Prefs.enabled(app)) {
            RescueLog.i("DROP ignored: AA Rescue is paused")
            RescueLog.event("Android Auto dropped — AA Rescue is paused, did nothing")
            return
        }
        RescueLog.i("DROP test=$test music=$musicPkg navigating=$navigating bt=${connectedBluetooth()}")

        val doMusic = musicPkg != null && Prefs.resumeMusic(app)
        val doNav = navigating && Prefs.resumeNav(app)
        val what = listOfNotNull(
            musicPkg?.let { "${label(it)} was playing" + if (!doMusic) " (resume is off)" else "" },
            "a trip was in progress".takeIf { navigating }?.let { it + if (!doNav) " (resume is off)" else "" },
        )
        RescueLog.event(
            (if (test) "Test drop" else "Android Auto dropped") + " — " +
                what.ifEmpty { listOf("nothing was playing, left as is") }.joinToString(", ")
        )

        // Play almost immediately; if the drop's own "audio unplugged" pause lands
        // after ours, the retry loop presses play again.
        if (doMusic) main.postDelayed({ resumeMusic(musicPkg!!, attempt = 1) }, 300)
        if (doNav) main.postDelayed({ resumeNav() }, 300)
    }

    /** "Test a drop" button: note what's playing *now*, pause it, then run a fake drop. */
    fun simulateDrop() {
        val playing = MediaWatcher.playingPackage()
        MediaWatcher.pauseAll()
        main.postDelayed({ onDropped(test = true, playingAtTest = playing) }, 500)
    }

    /**
     * Keeps music playing for a short window after the drop. Android's own
     * "audio unplugged" pause can land after our first play(), so we keep
     * checking and press play again if it stops.
     */
    private fun resumeMusic(pkg: String, attempt: Int) {
        if (isProjecting()) return
        if (!MediaWatcher.isPlaying(pkg)) {
            RescueLog.i("music: play() on $pkg attempt $attempt sent=${MediaWatcher.play(pkg)}")
        }
        if (attempt < MUSIC_TICKS) {
            main.postDelayed({ resumeMusic(pkg, attempt + 1) }, MUSIC_TICK_MS)
            return
        }
        RescueLog.event(
            if (MediaWatcher.isPlaying(pkg)) "Resumed ${label(pkg)}" else "Couldn't resume ${label(pkg)}"
        )
    }

    private fun resumeNav() {
        if (isProjecting()) return
        navClickDeadline = SystemClock.elapsedRealtime() + NAV_CLICK_WINDOW_MS
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
                RescueLog.event("Opened Maps but couldn't find Start")
            }
        }, NAV_CLICK_WINDOW_MS)
    }

    fun navClickArmed() = SystemClock.elapsedRealtime() < navClickDeadline

    fun onNavStarted(button: String) {
        navClickDeadline = 0L
        RescueLog.event("Restarted navigation (tapped $button)")
    }

    fun onNavBlocked(why: String) {
        navClickDeadline = 0L
        RescueLog.event("Couldn't open Maps — phone is locked ($why)")
    }

    private fun label(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun connectedBluetooth(): String {
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return "?"
        val bm = app.getSystemService(BluetoothManager::class.java) ?: return "?"
        return listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).flatMap { p ->
            runCatching { bm.getConnectedDevices(p) }.getOrDefault(emptyList())
                .map { "${it.name}(${if (p == BluetoothProfile.A2DP) "a2dp" else "hfp"})" }
        }.joinToString()
    }

    private fun name(t: Int) = when (t) {
        CarConnection.CONNECTION_TYPE_PROJECTION -> "PROJECTION"
        CarConnection.CONNECTION_TYPE_NATIVE -> "NATIVE"
        CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "NOT_CONNECTED"
        else -> "UNKNOWN($t)"
    }
}
