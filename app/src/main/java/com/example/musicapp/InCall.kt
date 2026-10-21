package com.example.musicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ───────────────────────── Shared state ─────────────────────────

/** What the call screen shows. Call objects are not observable, so [tick] is bumped on every change. */
object InCallState {
    val calls = mutableStateListOf<Call>()
    var tick by mutableIntStateOf(0)
    var muted by mutableStateOf(false)
    var route by mutableIntStateOf(CallAudioState.ROUTE_EARPIECE)
    var service: MusicInCallService? = null
}

/** A number sent to the app by another app (a tel: link or "Dial"): the Calls keypad opens with it. */
object DialRequest {
    var number by mutableStateOf<String?>(null)
}

fun isDefaultDialer(context: Context): Boolean =
    try {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName
    } catch (_: Exception) {
        false
    }

/** The intent that asks the user to make this app their phone app. */
fun requestDialerIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER)
    } else {
        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
    }

// ───────────────────────── The service Android talks to ─────────────────────────

class MusicInCallService : InCallService() {
    private var ringtone: Ringtone? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed()
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed()
    }

    override fun onCreate() {
        super.onCreate()
        InCallState.service = this
    }

    override fun onDestroy() {
        stopRinging()
        InCallState.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        InCallState.calls.add(call)
        changed()
        openScreen()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        InCallState.calls.remove(call)
        changed()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        InCallState.muted = audioState.isMuted
        InCallState.route = audioState.route
    }

    private fun changed() {
        InCallState.tick++
        val ringing = InCallState.calls.any { callState(it) == Call.STATE_RINGING }
        if (ringing) startRinging() else stopRinging()
        updateNotification(ringing)
    }

    private fun openScreen() {
        try {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // the full-screen notification below still brings it up
        }
    }

    // ── ringing: when this app is the phone app, it has to ring by itself ──

    @Suppress("DEPRECATION")
    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.play()
                ringtone = r
            } catch (_: Exception) {
            }
        }
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val pattern = longArrayOf(0, 800, 800)
        try {
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else vib?.vibrate(pattern, 0)
        } catch (_: Exception) {
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) { }
        ringtone = null
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
    }

    // ── notification: brings the call screen back, and opens it over the lock screen for incoming calls ──

    private fun updateNotification(ringing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val call = InCallState.calls.firstOrNull { callState(it) == Call.STATE_RINGING } ?: InCallState.calls.lastOrNull()
        if (call == null) {
            nm.cancel(NOTE_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        val name = lookupName(this, number) ?: number.ifBlank { "Unknown" }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(name)
            .setContentText(if (ringing) "Incoming call" else "Ongoing call")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pi)
        if (ringing) b.setFullScreenIntent(pi, true)
        try { nm.notify(NOTE_ID, b.build()) } catch (_: Exception) { }
    }

    companion object {
        private const val CHANNEL = "calls"
        private const val NOTE_ID = 4711
    }
}

@Suppress("DEPRECATION")
private fun callState(call: Call): Int = call.state

private fun lookupName(context: Context, number: String): String? {
    if (number.isBlank()) return null
    return try {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}

// ───────────────────────── The call screen ─────────────────────────

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { InCallScreen(onFinish = { finish() }) }
    }
}

private val Green = Color(0xFF30D158)
private val Red = Color(0xFFFF453A)

private fun clock(sec: Long): String = "%02d:%02d".format(sec / 60, sec % 60)

@Composable
fun InCallScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = InCallState.tick   // read on purpose: a call changed, draw again
    val calls = InCallState.calls
    val call = calls.firstOrNull { callState(it) == Call.STATE_RINGING } ?: calls.lastOrNull()

    val number = call?.details?.handle?.schemeSpecificPart.orEmpty()
    var name by remember(number) { mutableStateOf<String?>(null) }
    LaunchedEffect(number) {
        if (number.isNotBlank()) name = withContext(Dispatchers.IO) { lookupName(context, number) }
    }
    val title = name ?: number.ifBlank { "Unknown" }
    var lastTitle by remember { mutableStateOf("") }
    LaunchedEffect(call != null, title) { if (call != null) lastTitle = title }

    // the call is over: say so for a moment, then close
    LaunchedEffect(call == null) {
        if (call == null) {
            delay(900)
            onFinish()
        }
    }

    val state = if (call == null) Call.STATE_DISCONNECTED else callState(call)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state == Call.STATE_ACTIVE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val connectedAt = call?.details?.connectTimeMillis ?: 0L
    val elapsed = if (connectedAt > 0) ((now - connectedAt) / 1000).coerceAtLeast(0) else 0L
    val status = when (state) {
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_ACTIVE -> clock(elapsed)
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
        else -> "Calling…"
    }
    val ringing = state == Call.STATE_RINGING
    val live = state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING
    val waiting = state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || ringing

    var showKeys by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ring"
    )

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF26263A), Color(0xFF07070B))))
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                if (waiting) {
                    Box(
                        Modifier.size(130.dp).graphicsLayer {
                            val sc = 1f + 0.45f * ring
                            scaleX = sc; scaleY = sc; alpha = 0.35f * (1f - ring)
                        }.clip(CircleShape).background(Color.White)
                    )
                }
                SmsAvatar(if (call == null) lastTitle else title, 130.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (call == null) lastTitle else title,
                color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            if (name != null && number.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(number, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp)

            Spacer(Modifier.weight(1f))

            if (call != null && live && showKeys) {
                // keypad: sends tones to the other side
                Text(
                    typed.takeLast(16), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light,
                    maxLines = 1, modifier = Modifier.height(40.dp)
                )
                listOf("123", "456", "789", "*0#").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        row.forEach { ch ->
                            CircleBtn(66, Color.White.copy(alpha = 0.14f), {
                                typed += ch
                                try {
                                    call.playDtmfTone(ch)
                                    Handler(Looper.getMainLooper()).postDelayed(
                                        { try { call.stopDtmfTone() } catch (_: Exception) { } }, 150
                                    )
                                } catch (_: Exception) {
                                }
                            }) { Text(ch.toString(), color = Color.White, fontSize = 28.sp) }
                        }
                    }
                }
                Text(
                    "Hide", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp,
                    modifier = Modifier.clip(CircleShape).clickable { showKeys = false }.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            } else if (call != null && live) {
                val speaker = InCallState.route == CallAudioState.ROUTE_SPEAKER
                val holding = state == Call.STATE_HOLDING
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ControlBtn("Mute", InCallState.muted, { InCallState.service?.setMuted(!InCallState.muted) }) { c ->
                        SmsIcon(SmsIconKind.Mic, c, Modifier.size(28.dp))
                    }
                    ControlBtn("Keypad", false, { showKeys = true }) { c ->
                        SmsIcon(SmsIconKind.Keypad, c, Modifier.size(26.dp))
                    }
                    ControlBtn(
                        "Speaker", speaker,
                        { InCallState.service?.setAudioRoute(if (speaker) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER) }
                    ) { c -> GlyphIcon(Glyph.Speaker, c, Modifier.size(28.dp)) }
                    ControlBtn(if (holding) "Resume" else "Hold", holding, { if (holding) call.unhold() else call.hold() }) { c ->
                        GlyphIcon(Glyph.Pause, c, Modifier.size(26.dp))
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            if (call != null && ringing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircleBtn(76, Red, { call.reject(false, null) }) {
                            SmsIcon(SmsIconKind.Phone, Color.White, Modifier.size(34.dp).graphicsLayer { rotationZ = 135f })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Decline", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircleBtn(76, Green, { call.answer(VideoProfile.STATE_AUDIO_ONLY) }) {
                            SmsIcon(SmsIconKind.Phone, Color.White, Modifier.size(34.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Answer", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    }
                }
            } else if (call != null) {
                CircleBtn(76, Red, { call.disconnect() }) {
                    SmsIcon(SmsIconKind.Phone, Color.White, Modifier.size(34.dp).graphicsLayer { rotationZ = 135f })
                }
            } else {
                Spacer(Modifier.height(76.dp))
            }
        }
    }
}

@Composable
private fun CircleBtn(size: Int, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun ControlBtn(label: String, active: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleBtn(64, if (active) Color.White else Color.White.copy(alpha = 0.14f), onClick) {
            icon(if (active) Color.Black else Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}
