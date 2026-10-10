package dev.nish.aarescue

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Two streams: [i] is the technical trace (logcat + file, for debugging a
 * drive), [event] is the plain-English history shown on the main screen.
 */
object RescueLog {
    private const val TAG = "AARescue"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_EVENTS = 50
    private var trace: File? = null
    private var events: File? = null

    data class Event(val time: Long, val text: String)

    fun init(context: Context) {
        trace = File(context.filesDir, "rescue.log")
        events = File(context.filesDir, "events.log")
    }

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        val f = trace ?: return
        if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
        f.appendText("${System.currentTimeMillis()} $msg\n")
    }

    @Synchronized
    fun event(text: String) {
        i("EVENT: $text")
        val f = events ?: return
        val kept = readEvents().take(MAX_EVENTS - 1).reversed()
        f.writeText((kept + Event(System.currentTimeMillis(), text))
            .joinToString("") { "${it.time}\t${it.text}\n" })
    }

    @Synchronized
    fun readTrace(): String = trace?.takeIf { it.exists() }?.readText().orEmpty()

    /** Newest first. */
    @Synchronized
    fun readEvents(): List<Event> =
        events?.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            val (t, text) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            t.toLongOrNull()?.let { Event(it, text) }
        }.reversed()

    @Synchronized
    fun clear() {
        trace?.delete()
        events?.delete()
    }
}
