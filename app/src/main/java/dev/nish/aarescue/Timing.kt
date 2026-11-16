package dev.nish.aarescue

import android.content.Context

/** User-adjustable timings. Defaults are the values the app shipped with. */
enum class Timing(
    val key: String,
    val title: String,
    val help: String,
    val defaultMs: Long,
    val minMs: Long,
    val maxMs: Long,
    val stepMs: Long,
) {
    REACT(
        "t_react", "Reaction delay",
        "Wait after a drop before pressing play / opening Maps",
        300, 0, 3_000, 100,
    ),
    MUSIC_HOLD(
        "t_music_hold", "Keep music playing for",
        "Presses play again if Android pauses it late",
        4_000, 1_000, 15_000, 500,
    ),
    MUSIC_GRACE(
        "t_music_grace", "Music stopped before drop",
        "Music that stopped this long before the drop still counts as playing",
        1_000, 0, 30_000, 500,
    ),
    NAV_FIND(
        "t_nav_find", "Look for Start in Maps for",
        "How long to keep trying to tap Start",
        25_000, 5_000, 60_000, 5_000,
    ),
    UNLOCK_WAIT(
        "t_unlock_wait", "Wait for unlock",
        "If the phone is locked, resume navigation when you unlock within this time",
        5 * 60_000, 0, 15 * 60_000, 60_000,
    ),
    AUTOPLAY(
        "t_autoplay", "Auto-start window",
        "Music that starts this soon after Android Auto connects counts as auto-started",
        6_000, 2_000, 20_000, 1_000,
    ),
    MOVE_WAIT(
        "t_move_wait", "Wait for the car to move",
        "After a drop while stopped, rescue if the car moves again within this time",
        5 * 60_000, 60_000, 15 * 60_000, 60_000,
    ),
    /** Not a time: km/h. Walking pace must stay below it. */
    SPEED(
        "t_speed", "Moving means faster than",
        "Below this speed the car counts as stopped",
        15, 10, 40, 5,
    );

    fun get(c: Context): Long = sp(c).getLong(key, defaultMs).coerceIn(minMs, maxMs)
    fun set(c: Context, ms: Long) = sp(c).edit().putLong(key, ms.coerceIn(minMs, maxMs)).apply()

    fun format(ms: Long): String = when {
        this == SPEED -> "$ms km/h"
        ms >= 60_000 -> "${ms / 60_000} min"
        ms % 1000 == 0L -> "${ms / 1000} s"
        else -> "%.1f s".format(ms / 1000.0)
    }

    companion object {
        private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        fun resetAll(c: Context) = entries.forEach { sp(c).edit().remove(it.key).apply() }
    }
}
