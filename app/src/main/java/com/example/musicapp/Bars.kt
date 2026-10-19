package com.example.musicapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ───────────────────────── Glass bars ─────────────────────────

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isFav: Boolean,
    p: Palette,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onFav: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .glass(shape, p)
            .clickable { onOpen() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song, Modifier.size(42.dp), 11.dp, p, 300)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text
            )
            Text(
                song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = p.secondary
            )
        }
        Box(
            Modifier.size(38.dp).clip(CircleShape).clickable { onFav() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(
                if (isFav) Glyph.Heart else Glyph.HeartOutline,
                if (isFav) p.accent else p.secondary,
                Modifier.size(19.dp)
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(if (isPlaying) Glyph.Pause else Glyph.Play, p.text, Modifier.size(20.dp))
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { onNext() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(Glyph.Next, p.text, Modifier.size(22.dp))
        }
    }
}

/**
 * Compact glass tab bar. Tap a tab to switch, or press and hold anywhere on the bar and
 * slide left/right: the highlight follows your finger (with a little "lift" effect) and
 * the tabs change live.
 */
@Composable
fun GlassTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    p: Palette,
    photo: ImageBitmap?,
    profileName: String
) {
    val tabs = listOf(
        "Songs" to Glyph.Note,
        "Albums" to Glyph.Album,
        "Artists" to Glyph.Artist,
        "Profile" to Glyph.Artist
    )
    val count = tabs.size
    val shape = RoundedCornerShape(28.dp)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val widthState = remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val itemW = if (widthState.intValue > 0) widthState.intValue.toFloat() / count else 0f

    val pillPos by animateFloatAsState(
        selected.toFloat(),
        spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "pillPos"
    )
    val pillScale by animateFloatAsState(
        if (dragging) 1.14f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 400f),
        label = "pillScale"
    )
    val glow by animateFloatAsState(if (dragging) 0.25f else 0f, label = "glow")

    fun indexAt(x: Float): Int {
        val w = widthState.intValue
        if (w <= 0) return 0
        return (x / (w.toFloat() / count)).toInt().coerceIn(0, count - 1)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 44.dp)
            .glass(shape, p)
            .padding(5.dp)
            .onSizeChanged { widthState.intValue = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val i = indexAt(offset.x)
                        if (i != currentSelected) currentOnSelect(i)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        dragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val i = indexAt(offset.x)
                        if (i != currentSelected) currentOnSelect(i)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val i = indexAt(change.position.x)
                        if (i != currentSelected) {
                            currentOnSelect(i)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                )
            }
    ) {
        // Sliding highlight
        Box(Modifier.matchParentSize()) {
            if (itemW > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset((pillPos * itemW).roundToInt(), 0) }
                        .width(with(density) { itemW.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = pillScale
                            scaleY = pillScale
                        }
                        .clip(RoundedCornerShape(24.dp))
                        .background(p.selectedPill)
                        .background(p.accent.copy(alpha = glow))
                )
            }
        }

        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (label, glyph) ->
                val sel = i == selected
                val tint by animateColorAsState(if (sel) p.accent else p.secondary, label = "tint")
                val iconScale by animateFloatAsState(
                    if (sel && dragging) 1.22f else 1f,
                    spring(dampingRatio = 0.5f),
                    label = "iconScale"
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    ) {
                        if (i == 3) {
                            // The Profile tab shows the user's own photo.
                            Avatar(
                                photo, profileName, p,
                                Modifier
                                    .fillMaxSize()
                                    .then(if (sel) Modifier.border(1.5.dp, p.accent, CircleShape) else Modifier),
                                10.sp
                            )
                        } else {
                            GlyphIcon(glyph, tint, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
