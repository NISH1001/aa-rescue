package dev.nish.aarescue

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * Is the car moving? A disconnect looks the same whether the screen glitched at
 * a red light or the car was switched off; the difference is that after a
 * glitch the car drives on. GPS speed is read only around an Android Auto
 * session: from connect until the drop is dealt with.
 */
object Motion {
    /** A fix older than this says nothing about now. */
    private const val FRESH_MS = 5_000L

    private lateinit var app: Context
    private var listening = false
    @Volatile private var lastKmh = 0f
    @Volatile private var lastAt = 0L
    @Volatile private var fixes = 0
    /** adb only: stands in for GPS so the rule can be tested at a desk. */
    @Volatile private var fakeKmh: Float? = null

    /** Called (main thread) on every reading at or above the moving threshold. */
    var onMoving: (() -> Unit)? = null

    private val listener = LocationListener { loc ->
        lastKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        lastAt = SystemClock.elapsedRealtime()
        fixes++
        check()
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lm() = app.getSystemService(LocationManager::class.java)

    /** Speed can be known at all: permission granted and GPS switched on. */
    fun usable() = fakeKmh != null ||
        (hasPermission(app) && runCatching { lm().isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false))

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !hasPermission(app)) return
        runCatching {
            lm().requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            listening = true
            fixes = 0
            RescueLog.i("motion: watching speed")
        }.onFailure { RescueLog.i("motion: can't watch speed: $it") }
    }

    fun stop() {
        onMoving = null
        if (!listening) return
        runCatching { lm().removeUpdates(listener) }
        listening = false
        RescueLog.i("motion: stopped watching ($fixes readings)")
    }

    /** Current speed, or null if there is no recent reading. */
    fun kmh(): Float? = fakeKmh
        ?: lastKmh.takeIf { lastAt != 0L && SystemClock.elapsedRealtime() - lastAt <= FRESH_MS }

    /** No recent reading counts as not moving. */
    fun moving() = (kmh() ?: 0f) >= Timing.SPEED.get(app)

    fun describe() = "speed=${kmh()?.let { "%.0f".format(it) } ?: "unknown"} readings=$fixes watching=$listening"

    /** adb only: negative clears. */
    fun setFake(kmh: Float) {
        fakeKmh = kmh.takeIf { it >= 0f }
        RescueLog.i("motion: fake speed ${fakeKmh ?: "cleared"}")
        check()
    }

    private fun check() {
        if (moving()) onMoving?.invoke()
    }
}
