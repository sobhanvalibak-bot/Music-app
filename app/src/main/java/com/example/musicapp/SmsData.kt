package com.example.musicapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────── SMS data ─────────────────────────

/** One text message. [key] is "p<id>" for a message from the phone's SMS database, "l<n>" for one sent from this app. */
data class SmsMsg(
    val key: String,
    val address: String,
    val body: String,
    val date: Long,
    val sent: Boolean,
    val unread: Boolean = false,
    /** Bubble effect used when it was sent from this app: "", "gentle", "loud" or "slam" (only shown inside this app). */
    val effect: String = "",
    /** Text of the message this one answers (kept only inside this app, the SMS itself is plain text). */
    val replyTo: String = "",
    val failed: Boolean = false
)

data class SmsThread(
    val id: String,
    val address: String,
    val name: String?,
    val last: SmsMsg,
    val unread: Int,
    val count: Int
) {
    val title: String get() = name ?: address
}

class SmsSnapshot(val msgs: List<SmsMsg>, val names: Map<String, String>)

/** Same person or sender -> same id, whatever the number format (+98 912..., 0912...). */
fun smsThreadId(address: String): String {
    if (address.any { it.isLetter() }) return address.trim().lowercase()
    val d = address.filter { it.isDigit() }
    if (d.isEmpty()) return address.trim()
    return if (d.length > 10) d.takeLast(10) else d
}

/** Senders with letters in the name (banks, shops) cannot receive a reply. */
fun smsCanReply(address: String): Boolean = address.isNotBlank() && address.none { it.isLetter() }

/** Short numbers and named senders are treated as service / promo messages. */
fun smsIsPromo(address: String): Boolean {
    if (address.any { it.isLetter() }) return true
    val d = address.filter { it.isDigit() }
    return d.length in 1..6
}

val SmsPermissions = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.SEND_SMS
)

fun smsCanRead(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

fun smsCanSend(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

fun smsCanReadContacts(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ───────────────────────── Local state: stars, pins, hidden, messages sent from the app ─────────────────────────

/**
 * Everything the app remembers about messages itself. Android only lets the default SMS app change the
 * phone's SMS database, so "delete" and "hide" here only apply inside this app.
 */
class SmsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sms_store", Context.MODE_PRIVATE)

    /** Changes whenever stars, pins, hidden chats or read marks change. */
    var rev by mutableIntStateOf(0); private set
    /** Changes whenever a message sent from this app is added. */
    var localRev by mutableIntStateOf(0); private set

    private fun track() { rev.hashCode() }
    private fun setOf(k: String): Set<String> = prefs.getStringSet(k, emptySet()) ?: emptySet()
    private fun flip(k: String, v: String): Boolean {
        val s = HashSet(setOf(k))
        val now = if (s.contains(v)) { s.remove(v); false } else { s.add(v); true }
        prefs.edit().putStringSet(k, s).apply()
        rev++
        return now
    }
    private fun put(k: String, v: String) {
        val s = HashSet(setOf(k))
        s.add(v)
        prefs.edit().putStringSet(k, s).apply()
        rev++
    }

    fun isStarred(key: String): Boolean { track(); return setOf("stars").contains(key) }
    fun toggleStar(key: String): Boolean = flip("stars", key)
    fun starredKeys(): Set<String> { track(); return setOf("stars") }

    fun isPinned(key: String): Boolean { track(); return setOf("pins").contains(key) }
    fun togglePin(key: String): Boolean = flip("pins", key)

    fun isGone(key: String): Boolean { track(); return setOf("gone").contains(key) }
    fun goneKeys(): Set<String> { track(); return setOf("gone") }
    fun remove(key: String) = put("gone", key)
    fun removeAll(keys: Collection<String>) {
        val s = HashSet(setOf("gone"))
        s.addAll(keys)
        prefs.edit().putStringSet("gone", s).apply()
        rev++
    }

    fun isThreadPinned(id: String): Boolean { track(); return setOf("tpins").contains(id) }
    fun toggleThreadPin(id: String): Boolean = flip("tpins", id)

    fun isThreadHidden(id: String): Boolean { track(); return setOf("thidden").contains(id) }
    fun toggleThreadHidden(id: String): Boolean = flip("thidden", id)

    fun lastRead(id: String): Long { track(); return prefs.getLong("read_$id", 0L) }
    fun markRead(id: String) {
        prefs.edit().putLong("read_$id", System.currentTimeMillis()).apply()
        rev++
    }

    fun locals(): List<SmsMsg> {
        localRev.hashCode()
        val raw = prefs.getString("local", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SmsMsg(
                    key = o.getString("k"), address = o.getString("a"), body = o.getString("b"),
                    date = o.getLong("d"), sent = true, effect = o.optString("e"),
                    replyTo = o.optString("r"), failed = o.optBoolean("f")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addLocal(address: String, body: String, effect: String, replyTo: String, failed: Boolean): SmsMsg {
        val n = prefs.getLong("local_n", 0L) + 1
        val msg = SmsMsg("l$n", address, body, System.currentTimeMillis(), true, false, effect, replyTo, failed)
        val arr = try { JSONArray(prefs.getString("local", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("k", msg.key); put("a", address); put("b", body); put("d", msg.date)
            put("e", effect); put("r", replyTo); put("f", failed)
        })
        prefs.edit().putString("local", arr.toString()).putLong("local_n", n).apply()
        localRev++
        return msg
    }
}

// ───────────────────────── Reading and sending ─────────────────────────

object SmsRepo {
    private val nameCache = HashMap<String, String?>()

    private fun contactName(context: Context, address: String): String? {
        if (address.any { it.isLetter() } || address.isBlank()) return null
        if (!smsCanReadContacts(context)) return null
        synchronized(nameCache) {
            if (nameCache.containsKey(address)) return nameCache[address]
        }
        var name: String? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {
        }
        synchronized(nameCache) { nameCache[address] = name }
        return name
    }

    /** Reads the newest messages from the phone (blocking: call from a background thread). */
    fun load(context: Context): SmsSnapshot {
        val msgs = ArrayList<SmsMsg>()
        val names = HashMap<String, String>()
        if (!smsCanRead(context)) return SmsSnapshot(msgs, names)
        try {
            val uri = Uri.parse("content://sms")
            context.contentResolver.query(
                uri, arrayOf("_id", "address", "body", "date", "type", "read"),
                null, null, "date DESC LIMIT 4000"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow("_id")
                val iAd = c.getColumnIndexOrThrow("address")
                val iBo = c.getColumnIndexOrThrow("body")
                val iDa = c.getColumnIndexOrThrow("date")
                val iTy = c.getColumnIndexOrThrow("type")
                val iRe = c.getColumnIndexOrThrow("read")
                while (c.moveToNext()) {
                    val type = c.getInt(iTy)
                    if (type == 3) continue // draft
                    val address = c.getString(iAd)?.trim().orEmpty()
                    if (address.isEmpty()) continue
                    val sent = type != 1
                    msgs.add(
                        SmsMsg(
                            key = "p" + c.getLong(iId),
                            address = address,
                            body = c.getString(iBo).orEmpty(),
                            date = c.getLong(iDa),
                            sent = sent,
                            unread = !sent && c.getInt(iRe) == 0,
                            failed = type == 5
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        for (m in msgs) {
            val id = smsThreadId(m.address)
            if (!names.containsKey(id)) {
                val n = contactName(context, m.address)
                if (n != null) names[id] = n
            }
        }
        return SmsSnapshot(msgs, names)
    }

    /** Sends the text. Returns false when Android refused it. */
    fun send(context: Context, address: String, text: String): Boolean {
        return try {
            val sm: SmsManager =
                if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(text)
            if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, null, null)
            else sm.sendTextMessage(address, null, text, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** All messages of the phone plus the ones sent from the app, without the ones removed inside the app. */
    fun merge(snapshot: SmsSnapshot, store: SmsStore): List<SmsMsg> {
        val gone = store.goneKeys()
        val base = snapshot.msgs.filter { !gone.contains(it.key) }
        val sentByThread = HashMap<String, MutableList<SmsMsg>>()
        for (m in base) if (m.sent) sentByThread.getOrPut(smsThreadId(m.address)) { ArrayList() }.add(m)
        val extra = store.locals().filter { l ->
            if (gone.contains(l.key)) return@filter false
            // the phone already lists it (default SMS app wrote it): do not show it twice
            val same = sentByThread[smsThreadId(l.address)]
            same == null || same.none { it.body == l.body && Math.abs(it.date - l.date) < 120_000L }
        }
        return (base + extra).sortedBy { it.date }
    }

    fun threads(all: List<SmsMsg>, names: Map<String, String>, store: SmsStore): List<SmsThread> {
        val groups = LinkedHashMap<String, MutableList<SmsMsg>>()
        for (m in all) groups.getOrPut(smsThreadId(m.address)) { ArrayList() }.add(m)
        val out = ArrayList<SmsThread>()
        for ((id, list) in groups) {
            val last = list.last()
            val read = store.lastRead(id)
            val unread = list.count { !it.sent && it.unread && it.date > read }
            out.add(SmsThread(id, last.address, names[id], last, unread, list.size))
        }
        return out.sortedWith(
            compareByDescending<SmsThread> { store.isThreadPinned(it.id) }.thenByDescending { it.last.date }
        )
    }
}

/** A 4-8 digit code inside a message (bank one-time codes), or null. */
fun smsFindCode(body: String): String? {
    if (body.length > 220) return null
    return Regex("(?<![\\d+])\\d{4,8}(?!\\d)").find(body)?.value
}
