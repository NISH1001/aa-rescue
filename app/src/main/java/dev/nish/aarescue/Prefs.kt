package dev.nish.aarescue

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("enabled", on).apply()

    fun resumeMusic(c: Context) = sp(c).getBoolean("resume_music", true)
    fun setResumeMusic(c: Context, on: Boolean) = sp(c).edit().putBoolean("resume_music", on).apply()

    fun resumeNav(c: Context) = sp(c).getBoolean("resume_nav", true)
    fun setResumeNav(c: Context, on: Boolean) = sp(c).edit().putBoolean("resume_nav", on).apply()
}
