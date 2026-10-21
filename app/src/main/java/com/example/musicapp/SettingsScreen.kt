package com.example.musicapp

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

// ───────────────────────── Settings screen ─────────────────────────

@Composable
fun SettingsCard(title: String, p: Palette, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .padding(16.dp)
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, p: Palette, onSelect: (Int) -> Unit) {
    val n = options.size.coerceAtLeast(1)
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    // like iOS: while a finger is on the control the pill grows, lifts (shadow) and follows the finger
    val lift by animateFloatAsState(
        if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "segLift"
    )
    val selectedS by rememberUpdatedState(selected)
    val onSelectS by rememberUpdatedState(onSelect)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(p.selectedPill, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .pointerInput(n) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val segPx = size.width.toFloat() / n
                    var last = (down.position.x / segPx).toInt().coerceIn(0, n - 1)
                    pressed = true
                    try {
                        if (last != selectedS) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectS(last)
                        }
                        do {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull() ?: break
                            if (c.isConsumed) break
                            if (c.pressed) {
                                val i = (c.position.x / segPx).toInt().coerceIn(0, n - 1)
                                if (i != last) {
                                    last = i
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectS(i)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        pressed = false
                    }
                }
            }
    ) {
        val segW = maxWidth / n
        // the highlighted pill slides to the chosen option with a small spring
        val x by animateDpAsState(
            segW * selected.coerceIn(0, n - 1),
            spring(dampingRatio = 0.75f, stiffness = 500f), label = "segPill"
        )
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .height(36.dp)
                .graphicsLayer {
                    val sc = 1f + 0.08f * lift
                    scaleX = sc
                    scaleY = sc
                    shadowElevation = 10.dp.toPx() * lift
                    shape = RoundedCornerShape(10.dp)
                    clip = false
                }
                .background(p.segment, RoundedCornerShape(10.dp))
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = p.text
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    settings: Settings,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    profile: ProfileStore,
    onPickPhoto: () -> Unit,
    onPickAvatarVideo: () -> Unit,
    onPickBannerVideo: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar("Profile", p, onBack) }
        item { LargeTitle("Settings", "Make it yours", p) }
        item {
            SettingsCard("Profile media", p) {
                Text("Profile picture and animated banner are managed here. MP4 videos up to 50 MB loop automatically.", fontSize = 13.sp, color = p.secondary)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (profile.avatarVideoPath.isNotBlank()) {
                        LoopingProfileVideo(profile.avatarVideoPath, Modifier.size(58.dp).clip(CircleShape))
                    } else {
                        Avatar(profile.photo, profile.name, p, Modifier.size(58.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Profile picture", color = p.text, fontWeight = FontWeight.SemiBold)
                        Text(if (profile.avatarVideoPath.isNotBlank()) "Animated MP4 active" else if (profile.photo != null) "Photo selected" else "Initials / photo", color = p.secondary, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickPhoto, modifier = Modifier.weight(1f)) { Text("Choose photo") }
                    Button(onClick = onPickAvatarVideo, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Choose MP4", color = Color.White) }
                }
                Spacer(Modifier.height(12.dp))
                if (profile.bannerVideoPath.isNotBlank()) {
                    LoopingProfileVideo(profile.bannerVideoPath, Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    Box(Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(14.dp)).background(p.selectedPill), contentAlignment = Alignment.Center) {
                        Text("No animated banner selected", color = p.secondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPickBannerVideo, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = p.accent)) {
                    Text("Choose animated banner (MP4)", color = Color.White)
                }
            }
        }

        item {
            var showLogin by remember { mutableStateOf(false) }
            SettingsCard("YouTube account", p) {
                Text(
                    "YouTube sometimes says \"confirm you're not a bot\" and refuses to play. Signing in once with your own " +
                        "account usually fixes it. You type your password on Google's own page; only the sign-in cookies " +
                        "are kept, on this phone.",
                    fontSize = 13.sp, color = p.secondary
                )
                Spacer(Modifier.height(12.dp))
                if (YoutubeAuth.signedIn) {
                    Text("Signed in", color = p.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { YoutubeAuth.signOut(context) }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                } else {
                    Button(
                        onClick = { showLogin = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                    ) { Text("Sign in to YouTube", color = Color.White) }
                }
            }
            if (showLogin) {
                YoutubeAuth.LoginDialog(p) { ok ->
                    showLogin = false
                    if (ok) android.widget.Toast.makeText(context, "Signed in to YouTube", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        item {
            SettingsCard("Appearance", p) {
                Segmented(ThemeLabels, settings.theme, p) { settings.changeTheme(it) }
                if (settings.theme == 3) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Background, buttons and highlights take their colour from the cover of the song that is playing.",
                        fontSize = 13.sp, color = p.secondary
                    )
                }
            }
        }
        item {
            SettingsCard("Dynamic Island player", p) {
                Text(
                    "A fixed-size Dynamic Island at the top. Tap it to open playback controls, hold it for the quick menu (favorite, playlist, edit info, share).",
                    fontSize = 13.sp, color = p.secondary
                )
                    Spacer(Modifier.height(14.dp))
                    Text("Dynamic Island width: ${settings.islandWidth} dp", fontSize = 13.sp, color = p.text)
                    Slider(
                        value = settings.islandWidth.toFloat(),
                        onValueChange = { settings.changeIslandWidth(it.toInt()); },
                        valueRange = 180f..360f,
                        steps = 17,
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent)
                    )
                    Text("Dynamic Island height: ${settings.islandHeight} dp", fontSize = 13.sp, color = p.text)
                    Slider(
                        value = settings.islandHeight.toFloat(),
                        onValueChange = { settings.changeIslandHeight(it.toInt()) },
                        valueRange = 30f..64f,
                        steps = 16,
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent)
                    )
                    Text("Dynamic Island position from the top: +${settings.islandOffsetY} dp", fontSize = 13.sp, color = p.text)
                    Slider(
                        value = settings.islandOffsetY.toFloat(),
                        onValueChange = { settings.changeIslandOffsetY(it.toInt()) },
                        valueRange = 0f..80f,
                        steps = 15,
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent)
                    )
                    Text("Adjust the width, height and position manually. Your choices are saved automatically.", fontSize = 12.sp, color = p.secondary)
                
            }
        }
        item {
            SettingsCard("Notification Dynamic Island", p) {
                val accessOn = isNoticeAccessEnabled(context)
                val connected = NoticeBus.listenerConnected
                Text(
                    when {
                        !accessOn -> "Status: Notification access is OFF"
                        connected -> "Status: Connected, notifications will appear"
                        else -> "Status: Access is on but not connected yet. Tap Reconnect."
                    },
                    color = if (accessOn && connected) p.accent else p.secondary,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                        }
                    }) { Text("Access", color = p.accent) }
                    TextButton(onClick = {
                        rebindNoticeListener(context, force = true)
                        android.widget.Toast.makeText(context, "Reconnecting…", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Reconnect", color = p.accent) }
                    TextButton(onClick = { requestNoBatteryLimit(context) }) { Text("Keep alive", color = p.accent) }
                }
                Text(
                    "The island shows while this app is open. If it stays empty, tap Reconnect, and on Xiaomi/Samsung also set Battery to \"No restrictions\" and allow Autostart.",
                    fontSize = 12.sp, color = p.secondary
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show notifications", color = p.text, fontWeight = FontWeight.Medium)
                        Text("Show a cover preview when a notification arrives", color = p.secondary, fontSize = 12.sp)
                    }
                    IosSwitch(p, checked = settings.noticeEnabled, onCheckedChange = { settings.changeNoticeEnabled(it); NoticeBus.showTest(if (it) "Notification previews enabled." else "Notification previews disabled.") })
                }
                Spacer(Modifier.height(12.dp))
                Text("Panel size", color = p.text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("Compact", "Balanced", "Wide"), settings.noticeSize, p) { settings.changeNoticeSize(it); NoticeBus.showTest("Notification panel size changed to ${listOf("Compact", "Balanced", "Wide")[it]}.") }
                Spacer(Modifier.height(12.dp))
                Text("Panel appearance", color = p.text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("Black", "Glass", "Accent"), settings.noticeTint, p) { settings.changeNoticeTint(it); NoticeBus.showTest("Notification appearance changed to ${listOf("Black", "Glass", "Accent")[it]}.") }
                Spacer(Modifier.height(8.dp))
                Text("Each notification stays for 5 seconds. Tap its cover to read the description; the title is hidden. A test preview appears whenever you change these settings.", fontSize = 12.sp, color = p.secondary)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { NoticeBus.showTest("This is a test notification from Music. Your current panel settings are applied.") }) {
                    Text("Send test notification", color = p.accent)
                }
            }
        }
        item {
            SettingsCard("Accent color", p) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AccentColors.forEachIndexed { i, c ->
                        val sel = i == settings.accent
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .clickable { settings.changeAccent(i) }
                                .then(if (sel) Modifier.border(2.dp, c, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(c))
                        }
                    }
                }
            }
        }
        item {
            SettingsCard("Playback", p) {
                Text(
                    if (settings.crossfade == 0) "Crossfade: off" else "Crossfade: ${settings.crossfade} s",
                    fontSize = 15.sp, color = p.text
                )
                Slider(
                    value = settings.crossfade.toFloat(),
                    onValueChange = { settings.changeCrossfade(it.toInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent)
                )
                Text(
                    "Songs overlap and blend into each other. Gapless playback is always on when crossfade is off.",
                    fontSize = 12.sp, color = p.secondary
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Skip silence", fontSize = 15.sp, color = p.text)
                        Text("Jumps over silent parts inside a song", fontSize = 12.sp, color = p.secondary)
                    }
                    IosSwitch(p, 
                        checked = settings.skipSilence,
                        onCheckedChange = { settings.changeSkipSilence(it) }
                    )
                }
            }
        }
        item {
            SettingsCard("Sort songs by", p) {
                Segmented(SortLabels, settings.sort, p) { settings.changeSort(it) }
            }
        }
        item {
            SettingsCard("Liquid glass opacity", p) {
                Slider(
                    value = settings.glass,
                    onValueChange = { settings.changeGlass(it) },
                    valueRange = 0.35f..0.95f,
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent,
                        activeTrackColor = p.accent
                    )
                )
                Text(
                    "${(settings.glass * 100).toInt()}% · lower is more see-through",
                    fontSize = 13.sp, color = p.secondary
                )
            }
        }
        item {
            SettingsCard("Cover corners", p) {
                Slider(
                    value = settings.corners,
                    onValueChange = { settings.changeCorners(it) },
                    valueRange = 0f..1.8f,
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent,
                        activeTrackColor = p.accent
                    )
                )
                Text(
                    if (settings.corners < 0.05f) "Square" else "${(settings.corners * 100).toInt()}% rounded",
                    fontSize = 13.sp, color = p.secondary
                )
            }
        }
        item {
            SettingsCard("Song list", p) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show song duration", Modifier.weight(1f), fontSize = 16.sp, color = p.text)
                    IosSwitch(p, 
                        checked = settings.showDuration,
                        onCheckedChange = { settings.changeShowDuration(it) }
                    )
                }
            }
        }
    }
}
