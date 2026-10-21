package com.example.musicapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small log kept on the phone (last [MAX] lines, also after a restart). Settings > Logs shows the
 * newest ten, so an error like "why did this song not play" can be read (and copied) right in the app.
 * Never put passwords or cookies in here.
 */
object AppLog {
    class Entry(val time: Long, val level: Char, val tag: String, val text: String)

    /** Oldest first. Only change it on the main thread (add() takes care of that). */
    val entries = mutableStateListOf<Entry>()

    private const val MAX = 100
    private const val PREFS = "app_log"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        try {
            val json = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return
            val arr = JSONArray(json)
            val old = ArrayList<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                old += Entry(o.getLong("t"), o.getString("l").first(), o.getString("g"), o.getString("x"))
            }
            main.post { entries.addAll(0, old) }
        } catch (_: Exception) {
        }
    }

    fun i(tag: String, text: String) = add('I', tag, text)
    fun w(tag: String, text: String) = add('W', tag, text)
    fun e(tag: String, text: String, t: Throwable? = null) =
        add('E', tag, if (t == null) text else text + "\n" + describe(t))

    /** "IOException: message  ←  Cause: message" (the first few causes). */
    fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.take(4)
            .joinToString("\n← ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(200)}" }

    private fun add(level: Char, tag: String, text: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, text.take(700))
        main.post {
            entries.add(entry)
            while (entries.size > MAX) entries.removeAt(0)
            save()
        }
    }

    fun clear() {
        main.post {
            entries.clear()
            save()
        }
    }

    private fun save() {
        val ctx = app ?: return
        try {
            val arr = JSONArray()
            entries.forEach {
                arr.put(JSONObject().put("t", it.time).put("l", it.level.toString()).put("g", it.tag).put("x", it.text))
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun timeOf(e: Entry): String = synchronized(clock) { clock.format(Date(e.time)) }

    /** The newest [n] lines, newest first, as plain text (for Copy). */
    fun asText(n: Int): String =
        entries.takeLast(n).reversed().joinToString("\n\n") { "${timeOf(it)} ${it.level} [${it.tag}] ${it.text}" }
}
