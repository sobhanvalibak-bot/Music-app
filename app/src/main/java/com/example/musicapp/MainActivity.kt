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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {
    // Bumped every time the user taps the playback notification.
    private var openPlayerSignal by mutableIntStateOf(0)

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
            val p = remember(dark, accent, settings.glass, settings.corners, tintNow, settings.opaqueClassic) {
                Palette(dark, accent, settings.glass, settings.corners, tintNow, settings.opaqueClassic)
            }

            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent)
                )
                onDispose { }
            }

            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = p.bg, contentColor = p.text) {
                    Box(Modifier.fillMaxSize().background(p.bgBrush)) {
                        MusicApp(p, settings, openPlayerSignal, onTint = { coverTint = it })
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        hideStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
    }
}
