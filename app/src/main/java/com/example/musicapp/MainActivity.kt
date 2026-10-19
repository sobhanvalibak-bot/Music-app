package com.example.musicapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings = remember {
                Settings(getSharedPreferences("settings", Context.MODE_PRIVATE))
            }
            val dark = when (settings.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val accent = AccentColors[settings.accent.coerceIn(0, AccentColors.lastIndex)]
            val p = remember(dark, accent, settings.glass, settings.corners) {
                Palette(dark, accent, settings.glass, settings.corners)
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
                    MusicApp(p, settings, openPlayerSignal)
                }
            }
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
