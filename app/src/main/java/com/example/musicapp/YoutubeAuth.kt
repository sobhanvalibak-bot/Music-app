package com.example.musicapp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Your own YouTube sign-in. YouTube often answers "confirm you're not a bot" to apps that are not
 * signed in (VPNs and shared networks make it worse). You sign in once inside a normal Google page;
 * the app keeps the resulting cookies on this phone only and sends them with its YouTube requests.
 * The password is typed into Google's page, the app never sees it.
 */
object YoutubeAuth {
    private const val PREFS = "youtube_auth"
    private const val LOGIN_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"

    // a normal Chrome on Android (Google refuses sign-in inside a page that says it is an app's web view)
    const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile
    var cookie: String? = null
        private set

    var signedIn by mutableStateOf(false)
        private set

    @Synchronized
    fun init(context: Context) {
        if (cookie != null) return
        val c = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cookie", null)
        if (!c.isNullOrBlank()) {
            cookie = c
            signedIn = true
        }
    }

    fun save(context: Context, value: String) {
        cookie = value
        signedIn = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("cookie", value).apply()
        OnlineEngine.clearCache()
    }

    fun signOut(context: Context) {
        cookie = null
        signedIn = false
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("cookie").apply()
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) {
        }
        OnlineEngine.clearCache()
    }

    /** Full-screen Google sign-in. [onDone] gets true once the sign-in worked. */
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun LoginDialog(p: Palette, onDone: (Boolean) -> Unit) {
        Dialog(
            onDismissRequest = { onDone(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "Cancel", color = p.accent, fontSize = 17.sp,
                        modifier = Modifier.clickable { onDone(false) }
                    )
                    Text(
                        "Sign in to YouTube", color = p.text, fontSize = 17.sp,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = WEB_UA
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    if (url == null || !url.contains("youtube.com")) return
                                    val c = cm.getCookie("https://www.youtube.com").orEmpty()
                                    if (c.contains("SAPISID") || c.contains("__Secure-3PSID")) {
                                        save(ctx, c)
                                        onDone(true)
                                    }
                                }
                            }
                            loadUrl(LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() }
                )
            }
        }
    }
}
