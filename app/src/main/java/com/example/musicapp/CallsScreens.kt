package com.example.musicapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── Data ─────────────────────────

data class CallEntry(
    val id: Long,
    val number: String,
    val cachedName: String?,
    val type: Int,
    val date: Long,
    val durationSec: Long
)

data class PhoneContact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val numbers: List<String>,
    val starred: Boolean
)

val CallsPermissions = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.CALL_PHONE
)

fun callsCanUse(context: Context): Boolean =
    CallsPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The last nine digits are enough to tell numbers apart (+98 912…, 0912…, 912…). */
private fun numKey(n: String): String {
    val d = n.filter { it.isDigit() }
    return if (d.length > 9) d.takeLast(9) else d
}

private fun loadContacts(context: Context): List<PhoneContact> {
    val out = LinkedHashMap<Long, PhoneContact>()
    try {
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.STARRED),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(2).orEmpty().trim()
                val num = c.getString(3).orEmpty().trim()
                if (name.isEmpty() || num.isEmpty()) continue
                val old = out[id]
                if (old == null) {
                    out[id] = PhoneContact(id, c.getString(1).orEmpty(), name, listOf(num), c.getInt(4) == 1)
                } else if (old.numbers.none { numKey(it) == numKey(num) }) {
                    out[id] = old.copy(numbers = old.numbers + num)
                }
            }
        }
    } catch (_: Exception) {
    }
    return out.values.sortedBy { it.name.lowercase() }
}

private fun loadCalls(context: Context): List<CallEntry> {
    val out = ArrayList<CallEntry>()
    try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
            ),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 200) {
                out += CallEntry(c.getLong(0), c.getString(1).orEmpty(), c.getString(2), c.getInt(3), c.getLong(4), c.getLong(5))
            }
        }
    } catch (_: Exception) {
    }
    return out
}

/** Several calls in a row with the same number show up as one line with a counter. */
private class CallGroup(val entry: CallEntry, val count: Int, val name: String?)

private fun groupCalls(calls: List<CallEntry>, nameByKey: Map<String, String>): List<CallGroup> {
    val out = ArrayList<CallGroup>()
    for (c in calls) {
        val key = numKey(c.number)
        val last = out.lastOrNull()
        if (last != null && numKey(last.entry.number) == key && key.isNotEmpty() && last.entry.type == c.type) {
            out[out.lastIndex] = CallGroup(last.entry, last.count + 1, last.name)
        } else {
            out += CallGroup(c, 1, nameByKey[key] ?: c.cachedName?.takeIf { it.isNotBlank() })
        }
    }
    return out
}

private fun isMissed(type: Int) = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE

private fun callTime(ms: Long): String = when {
    DateUtils.isToday(ms) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    System.currentTimeMillis() - ms < 6 * DateUtils.DAY_IN_MILLIS -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
    else -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(ms))
}

private fun durText(sec: Long): String = if (sec < 60) "${sec}s" else "${sec / 60} min"

// ───────────────────────── Actions (Android's own call screen takes over after dialing) ─────────────────────────

private fun placeCall(context: Context, number: String) {
    if (number.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number.trim()))))
    } catch (_: Exception) {
        Toast.makeText(context, "Can't place this call", Toast.LENGTH_SHORT).show()
    }
}

private fun sendText(context: Context, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number.trim()))))
    } catch (_: Exception) {
        Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
    }
}

private fun addToContacts(context: Context, number: String) {
    try {
        context.startActivity(
            Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            }
        )
    } catch (_: Exception) {
        Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}

private fun openInContacts(context: Context, c: PhoneContact) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(c.id, c.lookupKey)))
    } catch (_: Exception) {
        Toast.makeText(context, "Couldn't open this contact", Toast.LENGTH_SHORT).show()
    }
}

// ───────────────────────── Entry point ─────────────────────────

/** The Calls page: asks for the permissions first, then shows recents, contacts and the keypad. */
@Composable
fun CallsScreen(p: Palette, topPad: Dp, bottomPad: Dp) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(callsCanUse(context)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        granted = callsCanUse(context)
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = callsCanUse(context)
    }
    Box(Modifier.fillMaxSize().background(p.bgBrush)) {
        if (!granted) {
            CallsPermissionPage(p, topPad, bottomPad) {
                val act = context.findActivity()
                val blocked = asked && act != null && CallsPermissions.any {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(act, it)
                }
                if (blocked) {
                    settingsLauncher.launch(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                } else {
                    launcher.launch(CallsPermissions)
                }
            }
        } else {
            CallsApp(p, topPad, bottomPad)
        }
    }
}

@Composable
private fun CallsPermissionPage(p: Palette, topPad: Dp, bottomPad: Dp, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.staggerIn(0).size(84.dp).clip(RoundedCornerShape(26.dp)).background(p.accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) { SmsIcon(SmsIconKind.Phone, p.accent, Modifier.size(42.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Calls", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.staggerIn(1))
        Spacer(Modifier.height(6.dp))
        Text(
            "To show your calls and contacts and to place calls, the app needs these permissions.",
            color = p.secondary, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
            modifier = Modifier.staggerIn(2)
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            Triple(SmsIconKind.Clock, "Call history", "Show your recent calls"),
            Triple(SmsIconKind.Contacts, "Contacts", "Show names instead of numbers"),
            Triple(SmsIconKind.Phone, "Phone calls", "Call straight from the app")
        ).forEachIndexed { i, (icon, title, sub) ->
            Row(
                Modifier.staggerIn(3 + i).fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmsIcon(icon, p.accent, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(sub, color = p.secondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.staggerIn(6).fillMaxWidth().bounceClick { onAllow() }
                .clip(RoundedCornerShape(16.dp)).background(p.accent).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) { Text("Allow", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ───────────────────────── The phone app ─────────────────────────

private class DetailTarget(val contact: PhoneContact?, val number: String?)

@Composable
private fun CallsApp(p: Palette, topPad: Dp, bottomPad: Dp) {
    val context = LocalContext.current
    var calls by remember { mutableStateOf<List<CallEntry>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        val (c, k) = withContext(Dispatchers.IO) { loadCalls(context) to loadContacts(context) }
        calls = c
        contacts = k
    }
    // coming back from the call screen: the new call should show up
    val owner = context.findActivity() as? LifecycleOwner
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    var section by remember { mutableIntStateOf(0) }
    var dial by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DetailTarget?>(null) }

    val nameByKey = remember(contacts) {
        val m = HashMap<String, String>()
        contacts.forEach { c -> c.numbers.forEach { n -> m.putIfAbsent(numKey(n), c.name) } }
        m
    }
    val contactByKey = remember(contacts) {
        val m = HashMap<String, PhoneContact>()
        contacts.forEach { c -> c.numbers.forEach { n -> m.putIfAbsent(numKey(n), c) } }
        m
    }
    val groups = remember(calls, nameByKey) { groupCalls(calls, nameByKey) }

    Column(Modifier.fillMaxSize().padding(top = topPad)) {
        Text(
            "Calls", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp)
        )
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Segmented(listOf("Recents", "Contacts", "Keypad"), section, p) { section = it }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                0 -> RecentsList(
                    p, groups, bottomPad,
                    onCall = { placeCall(context, it) },
                    onInfo = { g ->
                        val key = numKey(g.entry.number)
                        detail = DetailTarget(contactByKey[key], g.entry.number)
                    }
                )
                1 -> ContactsList(
                    p, contacts, bottomPad,
                    onOpen = { detail = DetailTarget(it, null) }
                )
                else -> Keypad(
                    p, dial, bottomPad,
                    matchName = if (dial.length >= 3) nameByKey[numKey(dial)] else null,
                    onChange = { dial = it },
                    onCall = {
                        placeCall(context, dial)
                    },
                    onAdd = { addToContacts(context, dial) }
                )
            }
        }
    }

    detail?.let { d ->
        CallDetailDialog(
            p = p,
            contact = d.contact,
            number = d.number,
            onClose = { detail = null },
            onCall = { placeCall(context, it) },
            onText = { sendText(context, it) },
            onAdd = { addToContacts(context, it) },
            onOpenContact = { openInContacts(context, it) }
        )
    }
}

// ───────────────────────── Recents ─────────────────────────

@Composable
private fun RecentsList(
    p: Palette,
    groups: List<CallGroup>,
    bottomPad: Dp,
    onCall: (String) -> Unit,
    onInfo: (CallGroup) -> Unit
) {
    if (groups.isEmpty()) {
        EmptyNote(p, "No recent calls", "Calls you make and receive will show up here.")
        return
    }
    val red = Color(0xFFFF453A)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPad + 8.dp)) {
        items(groups, key = { it.entry.id }) { g ->
            val e = g.entry
            val missed = isMissed(e.type)
            val title = g.name ?: e.number.ifBlank { "Unknown" }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .clickable { onCall(e.number) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmsAvatar(title, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (g.count > 1) "$title (${g.count})" else title,
                        color = if (missed) red else p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmsIcon(
                            if (e.type == CallLog.Calls.OUTGOING_TYPE) SmsIconKind.ArrowOut else SmsIconKind.ArrowIn,
                            if (missed) red else p.secondary, Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        val label = when (e.type) {
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.REJECTED_TYPE -> "Declined"
                            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                            else -> "Incoming"
                        }
                        Text(
                            if (!missed && e.durationSec > 0) "$label · ${durText(e.durationSec)}" else label,
                            color = p.secondary, fontSize = 13.sp, maxLines = 1
                        )
                    }
                }
                Text(callTime(e.date), color = p.secondary, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onInfo(g) },
                    contentAlignment = Alignment.Center
                ) { SmsIcon(SmsIconKind.Contacts, p.accent, Modifier.size(22.dp)) }
            }
        }
    }
}

// ───────────────────────── Contacts ─────────────────────────

private fun contactMatches(c: PhoneContact, q: String): Boolean {
    if (q.isBlank()) return true
    if (c.name.contains(q.trim(), ignoreCase = true)) return true
    val digits = q.filter { it.isDigit() }
    return digits.isNotEmpty() && c.numbers.any { n -> n.filter { it.isDigit() }.contains(digits) }
}

private fun initialOf(name: String): String {
    val ch = name.trim().firstOrNull() ?: return "#"
    return if (ch.isLetter()) ch.uppercase() else "#"
}

@Composable
private fun ContactsList(
    p: Palette,
    contacts: List<PhoneContact>,
    bottomPad: Dp,
    onOpen: (PhoneContact) -> Unit
) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(contacts, q) { contacts.filter { contactMatches(it, q) } }
    val favorites = remember(contacts) { contacts.filter { it.starred } }
    val grouped = remember(filtered) {
        filtered.groupBy { initialOf(it.name) }.toSortedMap(compareBy<String> { if (it == "#") "~" else it })
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(p.selectedPill).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphIcon(Glyph.Search, p.secondary, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (q.isEmpty()) Text("Search contacts", color = p.secondary, fontSize = 16.sp)
                    inner()
                }
            )
        }
        if (contacts.isEmpty()) {
            EmptyNote(p, "No contacts", "Contacts saved on this phone will show up here.")
            return@Column
        }
        if (filtered.isEmpty()) {
            EmptyNote(p, "No results", "Nothing matches \"${q.trim()}\".")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPad + 8.dp)) {
            if (q.isBlank() && favorites.isNotEmpty()) {
                item(key = "fav") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Text("Favorites", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(favorites, key = { it.id }) { c ->
                                Column(
                                    Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(c) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    SmsAvatar(c.name, 56.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        c.name, color = p.text, fontSize = 12.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
            grouped.forEach { (letter, list) ->
                item(key = "h$letter") {
                    Text(letter, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                items(list, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(c) }
                            .padding(horizontal = 4.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmsAvatar(c.name, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.numbers.first(), color = p.secondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Keypad ─────────────────────────

private val KeypadRows = listOf(
    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    listOf("*" to "", "0" to "+", "#" to "")
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyCap(
    p: Palette,
    size: Dp,
    fill: Color,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (pressed) p.accent.copy(alpha = 0.4f) else fill)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    p: Palette,
    dial: String,
    bottomPad: Dp,
    matchName: String?,
    onChange: (String) -> Unit,
    onCall: () -> Unit,
    onAdd: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val keySize = 70.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPad + 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                dial, color = p.text, fontSize = if (dial.length > 12) 26.sp else 34.sp,
                fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
        }
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            if (matchName != null) {
                Text(matchName, color = p.secondary, fontSize = 15.sp, maxLines = 1)
            } else if (dial.isNotEmpty()) {
                Text(
                    "Add to contacts", color = p.accent, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAdd() }.padding(horizontal = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        KeypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                row.forEach { (digit, letters) ->
                    KeyCap(
                        p, keySize, p.card,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(dial + digit)
                        },
                        onLong = if (digit == "0") ({
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onChange(dial + "+")
                        }) else null
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(digit, color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Normal)
                            Text(letters, color = p.secondary, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(keySize))
            KeyCap(p, keySize, Color(0xFF30D158), onClick = { onCall() }) {
                SmsIcon(SmsIconKind.Phone, Color.White, Modifier.size(32.dp))
            }
            if (dial.isNotEmpty()) {
                KeyCap(
                    p, keySize, Color.Transparent,
                    onClick = { onChange(dial.dropLast(1)) },
                    onLong = { onChange("") }
                ) { SmsIcon(SmsIconKind.Backspace, p.secondary, Modifier.size(28.dp)) }
            } else {
                Spacer(Modifier.size(keySize))
            }
        }
    }
}

// ───────────────────────── Details ─────────────────────────

@Composable
private fun RoundAction(p: Palette, icon: SmsIconKind, tint: Color, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { SmsIcon(icon, tint, Modifier.size(20.dp)) }
}

@Composable
private fun CallDetailDialog(
    p: Palette,
    contact: PhoneContact?,
    number: String?,
    onClose: () -> Unit,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onAdd: (String) -> Unit,
    onOpenContact: (PhoneContact) -> Unit
) {
    val numbers = contact?.numbers ?: listOfNotNull(number?.takeIf { it.isNotBlank() })
    val title = contact?.name ?: numbers.firstOrNull() ?: "Unknown"
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.card).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SmsAvatar(title, 76.dp)
            Spacer(Modifier.height(10.dp))
            Text(title, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            numbers.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RoundAction(p, SmsIconKind.Message, p.accent, p.accent.copy(alpha = 0.18f)) { onText(n) }
                    Spacer(Modifier.width(8.dp))
                    RoundAction(p, SmsIconKind.Phone, Color.White, Color(0xFF30D158)) { onClose(); onCall(n) }
                }
            }
            Spacer(Modifier.height(12.dp))
            val label = if (contact != null) "Open in Contacts" else "Add to contacts"
            Text(
                label, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    if (contact != null) onOpenContact(contact) else numbers.firstOrNull()?.let { onAdd(it) }
                }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyNote(p: Palette, title: String, sub: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(sub, color = p.secondary, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
