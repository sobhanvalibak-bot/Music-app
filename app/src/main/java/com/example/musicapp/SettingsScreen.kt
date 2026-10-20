package com.example.musicapp

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.selectedPill)
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) p.segment else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
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
            SettingsCard("Player style", p) {
                Segmented(listOf("Classic", "Dynamic Island"), settings.miniStyle, p) { settings.changeMiniStyle(it); NoticeBus.showTest("Player style changed to ${if (it == 1) "Dynamic Island" else "Classic"}.") }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (settings.miniStyle == 1)
                        "A fixed-size Dynamic Island at the top. It shows only the cover; tap it to open playback controls."
                    else
                        "The classic glass mini player with cover and playback controls.",
                    fontSize = 13.sp, color = p.secondary
                )
                if (settings.miniStyle == 1) {
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
                    Text("Adjust the width and height manually. Your choices are saved automatically.", fontSize = 12.sp, color = p.secondary)
                }
            }
        }
        item {
            SettingsCard("Notification Dynamic Island", p) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show notifications", color = p.text, fontWeight = FontWeight.Medium)
                        Text("Show a cover preview when a notification arrives", color = p.secondary, fontSize = 12.sp)
                    }
                    Switch(checked = settings.noticeEnabled, onCheckedChange = { settings.changeNoticeEnabled(it); NoticeBus.showTest(if (it) "Notification previews enabled." else "Notification previews disabled.") })
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
                    Switch(
                        checked = settings.skipSilence,
                        onCheckedChange = { settings.changeSkipSilence(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent)
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
            SettingsCard("Tab bar style", p) {
                Segmented(listOf("Floating", "Classic"), settings.tabBarStyle, p) { settings.changeTabBarStyle(it) }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (settings.tabBarStyle == 0)
                        "Floating glass capsule with the current animations."
                    else
                        "A solid rectangular bar attached to the bottom edge. It stays visible, has no glass effect, and the mini player docks directly above it. ",
                    fontSize = 13.sp, color = p.secondary
                )
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
            SettingsCard("Liquid Glass · Classic mode", p) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Opaque player and navigation", fontSize = 15.sp, color = p.text)
                        Text("Use solid backgrounds instead of transparent glass. Scrolling still hides and reveals the navigation bar.", fontSize = 12.sp, color = p.secondary)
                    }
                    Switch(checked = settings.opaqueClassic, onCheckedChange = { settings.changeOpaqueClassic(it) })
                }
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
                    Switch(
                        checked = settings.showDuration,
                        onCheckedChange = { settings.changeShowDuration(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent)
                    )
                }
            }
        }
    }
}
