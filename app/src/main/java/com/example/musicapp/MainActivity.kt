package com.example.musicapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {
    // Bumped every time the user taps the playback notification.
    private var openPlayerSignal by mutableIntStateOf(0)

    // Volume keys: the app draws its own thin iOS-style bar instead of the system panel.
    private var volumeLevel by mutableFloatStateOf(0f)
    private var volumeSignal by mutableIntStateOf(0)   // bumped on every key press
    private var volumeEdge by mutableIntStateOf(0)     // bumped when a press hits the min/max
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }

    private fun musicLevel(): Float {
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val before = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            try {
                audio.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE
                    else android.media.AudioManager.ADJUST_LOWER,
                    0 // no system volume panel
                )
            } catch (_: Exception) {
                return super.onKeyDown(keyCode, event) // e.g. Do Not Disturb: let the system handle it
            }
            volumeLevel = musicLevel()
            if (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == before) volumeEdge += 1
            volumeSignal += 1
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideStatusBar()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings = remember {
                Settings(getSharedPreferences("settings", Context.MODE_PRIVATE))
            }
            // "Cover" theme: colour of the playing song's cover (null when nothing is playing)
            var coverTint by remember { mutableStateOf<Color?>(null) }
            val coverMode = settings.theme == 3
            val animTint by animateColorAsState(
                coverTint ?: Color(0xFF3A3A3C),
                animationSpec = tween(durationMillis = 1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "coverTint"
            )
            val tintNow: Color? = if (coverMode && coverTint != null) animTint else null

            val dark = when (settings.theme) {
                1 -> false
                2 -> true
                3 -> true
                else -> isSystemInDarkTheme()
            }
            val accent = AccentColors[settings.accent.coerceIn(0, AccentColors.lastIndex)]
            val p = remember(dark, accent, settings.glass, settings.corners, tintNow) {
                Palette(dark, accent, settings.glass, settings.corners, tintNow)
            }

            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent)
                )
                // enableEdgeToEdge can bring the bar back: hide it again
                hideStatusBar()
                onDispose { }
            }

            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = p.bg, contentColor = p.text) {
                    Box(Modifier.fillMaxSize().background(p.bgBrush)) {
                        MusicApp(p, settings, openPlayerSignal, onTint = { coverTint = it })
                        VolumeHud(
                            signal = volumeSignal,
                            edge = volumeEdge,
                            level = volumeLevel,
                            p = p,
                            onLevel = { f ->
                                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                try {
                                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(f * max), 0)
                                } catch (_: Exception) {
                                }
                                volumeLevel = musicLevel()
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        hideStatusBar()
        // "Notification access" may have just been switched on, or the binding was lost
        rebindNoticeListener(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // draw into the camera cut-out area too, otherwise a black strip stays at the top
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) openPlayerSignal += 1
        // another app asked to dial a number (a tel: link or "Dial"): open the Calls keypad with it
        if (intent?.action == Intent.ACTION_DIAL || intent?.action == Intent.ACTION_VIEW) {
            val d = intent.data
            if (d?.scheme == "tel") DialRequest.number = android.net.Uri.decode(d.schemeSpecificPart).orEmpty()
            else if (intent.action == Intent.ACTION_DIAL) DialRequest.number = ""
        }
    }
}
