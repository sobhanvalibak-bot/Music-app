package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun SettingsTab(settings: Settings, p: Palette, topPad: Dp, bottomPad: Dp, onBack: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar("Profile", p, onBack) }
        item { LargeTitle("Settings", "Make it yours", p) }

        item {
            SettingsCard("Appearance", p) {
                Segmented(ThemeLabels, settings.theme, p) { settings.changeTheme(it) }
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
