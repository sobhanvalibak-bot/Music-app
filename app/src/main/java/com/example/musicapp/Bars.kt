package com.example.musicapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── Glass bars ─────────────────────────

/**
 * Glass mini player.
 *  - Tap: open the full player. Pressing it makes it shrink a little, like on iOS.
 *  - Swipe left: next song. Swipe right: previous song.
 *    The card follows your finger and tilts, flies off the side, and the new song springs back in.
 *    When there is no next song the card resists and bounces back.
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isFav: Boolean,
    p: Palette,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    canNext: () -> Boolean,
    onFav: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "miniPress"
    )

    val onOpenS by rememberUpdatedState(onOpen)
    val onNextS by rememberUpdatedState(onNext)
    val onPrevS by rememberUpdatedState(onPrev)
    val canNextS by rememberUpdatedState(canNext)

    Row(
        Modifier
            .fillMaxWidth()
            // gestures sit outside the moving layer, so finger positions stay stable while it moves
            .pointerInput(Unit) {
                var dragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        pressed = false
                        scope.launch { offsetX.stop() }
                        dragX = offsetX.value
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        // no next song: resist when pulling left
                        val factor = if (dx < 0f && dragX <= 0f && !canNextS()) 0.3f else 1f
                        dragX += dx * factor
                        val v = dragX
                        scope.launch { offsetX.snapTo(v) }
                    },
                    onDragEnd = {
                        val x = dragX
                        val threshold = 90.dp.toPx()
                        scope.launch {
                            if (x < -threshold && canNextS()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                offsetX.animateTo(-screenPx, tween(150))
                                onNextS()
                                offsetX.snapTo(screenPx * 0.6f)
                                offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 380f))
                            } else if (x > threshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                offsetX.animateTo(screenPx, tween(150))
                                onPrevS()
                                offsetX.snapTo(-screenPx * 0.6f)
                                offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 380f))
                            } else {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 450f))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 450f)) }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onOpenS() }
                )
            }
            .graphicsLayer {
                val frac = (abs(offsetX.value) / screenPx).coerceIn(0f, 1f)
                translationX = offsetX.value
                rotationZ = offsetX.value / screenPx * 5f
                alpha = 1f - frac * 0.85f
                val sc = 1f - 0.035f * press - 0.05f * frac
                scaleX = sc
                scaleY = sc
            }
            .padding(horizontal = 14.dp)
            .glass(shape, p)
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
 * Floating glass tab bar.
 *  - Tap a tab to switch. Pressing the bar lifts the highlight like iOS.
 *  - Just swipe left or right on the bar: the highlight follows your finger smoothly (liquid stretch),
 *    icons near it grow like a dock, and the tab changes live. Let go and it snaps in place.
 *  - [selected] = -1 means no tab is selected (the search page is open): the highlight fades out.
 */
@Composable
fun GlassTabBar(
    modifier: Modifier = Modifier,
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
    val shape = RoundedCornerShape(30.dp)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val widthState = remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var fingerX by remember { mutableFloatStateOf(0f) }

    val itemW = if (widthState.intValue > 0) widthState.intValue.toFloat() / count else 0f

    // Where the pill wants to be (in px): under the finger while dragging, else under the selected tab.
    val targetX = if (dragging && itemW > 0f)
        (fingerX - itemW / 2f).coerceIn(0f, itemW * (count - 1))
    else selected.coerceAtLeast(0) * itemW

    val pillX by animateFloatAsState(
        targetX,
        if (dragging) spring(dampingRatio = 0.8f, stiffness = 900f)
        else spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "pillX"
    )
    val lift by animateFloatAsState(
        if (dragging || pressed) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "lift"
    )
    val pillAlpha by animateFloatAsState(
        if (selected >= 0) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 500f),
        label = "pillAlpha"
    )

    fun indexAt(x: Float): Int {
        val w = widthState.intValue
        if (w <= 0) return 0
        return (x / (w.toFloat() / count)).toInt().coerceIn(0, count - 1)
    }

    Box(
        modifier
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .glass(shape, p)
            .padding(5.dp)
            .onSizeChanged { widthState.intValue = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        fingerX = offset.x
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { offset ->
                        val i = indexAt(offset.x)
                        if (i != currentSelected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnSelect(i)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        pressed = false
                        fingerX = offset.x
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val i = indexAt(offset.x)
                        if (i != currentSelected) currentOnSelect(i)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        fingerX = change.position.x
                        val i = indexAt(change.position.x)
                        if (i != currentSelected) {
                            currentOnSelect(i)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                )
            }
    ) {
        // Liquid highlight
        Box(Modifier.matchParentSize()) {
            if (itemW > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset(pillX.roundToInt(), 0) }
                        .width(with(density) { itemW.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = pillAlpha
                            // stretch a little while it is travelling, and lift when held
                            val travel = (abs(targetX - pillX) / itemW).coerceAtMost(1f)
                            scaleX = 1f + 0.10f * lift + 0.30f * travel
                            scaleY = 1f + 0.10f * lift - 0.08f * travel
                        }
                        .clip(RoundedCornerShape(25.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    p.accent.copy(alpha = 0.30f + 0.12f * lift),
                                    p.accent.copy(alpha = 0.14f + 0.10f * lift)
                                )
                            )
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(p.accent.copy(alpha = 0.7f), p.accent.copy(alpha = 0.15f))
                            ),
                            RoundedCornerShape(25.dp)
                        )
                )
            }
        }

        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (label, glyph) ->
                val sel = i == selected
                val tint by animateColorAsState(if (sel) p.accent else p.secondary, label = "tint")
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                // dock-style magnification: the closer to the pill, the bigger
                                val centre = i * itemW
                                val d = if (itemW > 0f) abs(centre - pillX) / itemW else 1f
                                val near = (1f - d).coerceIn(0f, 1f) * pillAlpha
                                val sc = 1f + (0.12f + 0.16f * lift) * near
                                scaleX = sc
                                scaleY = sc
                                translationY = -3.dp.toPx() * near * (0.4f + 0.6f * lift)
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
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label, fontSize = 10.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = tint, maxLines = 1
                    )
                }
            }
        }
    }
}

/** Round glass search button that sits next to the tab bar (like the iOS tab bar). */
@Composable
fun SearchButton(active: Boolean, p: Palette, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "searchScale"
    )
    val tint by animateColorAsState(if (active) p.accent else p.text, label = "searchTint")
    val onClickS by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(60.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glass(CircleShape, p)
            .background(if (active) p.accent.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClickS() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(Glyph.Search, tint, Modifier.size(24.dp))
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
