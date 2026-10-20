package com.example.musicapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
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
    onFav: () -> Unit,
    style: Int = 0
) {
    val island = style == 1
    val shape: Shape = if (island) RoundedCornerShape(50) else RoundedCornerShape(24.dp)
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

    // Dynamic Island: the pill "pops" a little each time the song changes
    val pop = remember { Animatable(1f) }
    LaunchedEffect(song.id) {
        if (island) {
            pop.snapTo(1.07f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
        }
    }

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
                val sc = (1f - 0.035f * press - 0.05f * frac) * (if (island) pop.value else 1f)
                scaleX = sc
                scaleY = sc
            }
            .padding(horizontal = 14.dp)
            .then(
                if (island) Modifier
                    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(shape)
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 9.dp, vertical = 9.dp)
                else Modifier
                    .glass(shape, p)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (island) {
            IslandContent(song, isPlaying, isFav, p, onToggle, onNext, onFav)
            return@Row
        }
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

/** Content of the Dynamic Island style mini player (everything sits on a black pill). */
@Composable
private fun RowScope.IslandContent(
    song: Song,
    isPlaying: Boolean,
    isFav: Boolean,
    p: Palette,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onFav: () -> Unit
) {
    Cover(song, Modifier.size(42.dp), 0.dp, p, 300, round = true)
    Spacer(Modifier.width(11.dp))
    Column(Modifier.weight(1f)) {
        Text(
            song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White
        )
        Text(
            song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)
        )
    }
    SoundBars(isPlaying, p.accent)
    Spacer(Modifier.width(6.dp))
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable { onFav() },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(
            if (isFav) Glyph.Heart else Glyph.HeartOutline,
            if (isFav) p.accent else Color.White.copy(alpha = 0.7f),
            Modifier.size(18.dp)
        )
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(if (isPlaying) Glyph.Pause else Glyph.Play, Color.White, Modifier.size(19.dp))
    }
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable { onNext() },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(Glyph.Next, Color.White, Modifier.size(21.dp))
    }
}

/** Little animated equalizer, like the live activity bars on the iPhone. */
@Composable
private fun SoundBars(playing: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "bars")
    val periods = intArrayOf(520, 680, 450, 610)
    val h = periods.mapIndexed { i, ms ->
        t.animateFloat(
            initialValue = 0.25f + 0.1f * i,
            targetValue = 1f - 0.12f * i,
            animationSpec = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i"
        )
    }
    val rest by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "barsRest")
    Row(
        Modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        h.forEach { st ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .graphicsLayer {
                        val f = 0.22f + (st.value - 0.22f) * rest
                        scaleY = f.coerceAtLeast(0.18f)
                    }
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

/**
 * iOS style floating tab bar (liquid glass).
 *  - A glass capsule with a bigger profile photo.
 *  - Hold the bar: the selected spot lifts into a glass lens that wraps the icon,
 *    with a bright rim, a soft glow around it, and icons near it grow like a dock.
 *  - Swipe left or right: the lens follows your finger (liquid stretch) and the tab changes live.
 *  - [selected] = -1 means no tab is selected (the search page is open): the lens fades out.
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
    val barShape = RoundedCornerShape(40.dp)
    val lensShape = RoundedCornerShape(34.dp)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val padPx = with(density) { BarPad.toPx() }

    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val widthState = remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var fingerX by remember { mutableFloatStateOf(0f) }

    val innerW = (widthState.intValue - 2 * padPx).coerceAtLeast(0f)
    val itemW = if (innerW > 0f) innerW / count else 0f

    // where the lens wants to be, measured from the inner left edge
    val targetX = if (dragging && itemW > 0f)
        (fingerX - padPx - itemW / 2f).coerceIn(0f, itemW * (count - 1))
    else selected.coerceAtLeast(0) * itemW

    val lensX by animateFloatAsState(
        targetX,
        if (dragging) spring(dampingRatio = 0.8f, stiffness = 900f)
        else spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "lensX"
    )
    val lift by animateFloatAsState(
        if (dragging || pressed) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 330f),
        label = "lift"
    )
    val lensAlpha by animateFloatAsState(
        if (selected >= 0) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 500f),
        label = "lensAlpha"
    )

    fun indexAt(x: Float): Int {
        if (itemW <= 0f) return 0
        return ((x - padPx) / itemW).toInt().coerceIn(0, count - 1)
    }

    Box(
        modifier
            .height(BarHeight)
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
        // 1) the glass capsule itself
        Box(Modifier.matchParentSize().glass(barShape, p))

        // 2) the lens: sits inside the bar, and lifts out of it while held
        if (itemW > 0f) {
            Box(
                Modifier
                    .padding(BarPad)
                    .matchParentSize()
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(lensX.roundToInt(), 0) }
                        .width(with(density) { itemW.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = lensAlpha
                            val travel = (abs(targetX - lensX) / itemW).coerceAtMost(1f)
                            // lens grows past the bar edges when held, stretches when travelling
                            scaleX = 1f + 0.16f * lift + 0.30f * travel
                            scaleY = 1f + 0.30f * lift - 0.08f * travel
                            shadowElevation = 22.dp.toPx() * lift
                            shape = lensShape
                            clip = false
                            ambientShadowColor = Color.Black.copy(alpha = 0.45f)
                            spotShadowColor = Color.Black.copy(alpha = 0.45f)
                        }
                        .drawBehind {
                            // soft glow that wraps around the lens while it is held
                            if (lift > 0.01f) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        listOf(
                                            p.accent.copy(alpha = 0.40f * lift),
                                            Color.Transparent
                                        ),
                                        center = center,
                                        radius = size.width * 0.95f
                                    ),
                                    radius = size.width * 0.95f,
                                    center = center
                                )
                            }
                        }
                        .clip(lensShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = (if (p.dark) 0.20f else 0.55f) + 0.20f * lift),
                                    p.accent.copy(alpha = 0.10f + 0.10f * lift)
                                )
                            )
                        )
                        .border(
                            1.2.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.55f + 0.45f * lift),
                                    Color.White.copy(alpha = 0.08f),
                                    Color.White.copy(alpha = 0.30f + 0.40f * lift)
                                )
                            ),
                            lensShape
                        )
                )
            }
        }

        // 3) icons and labels
        Row(Modifier.fillMaxSize().padding(horizontal = BarPad)) {
            tabs.forEachIndexed { i, (label, glyph) ->
                val sel = i == selected
                val tint by animateColorAsState(if (sel) p.accent else p.secondary, label = "tint")
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .graphicsLayer {
                                // dock-style magnification: the closer to the lens, the bigger
                                val centre = i * itemW
                                val d = if (itemW > 0f) abs(centre - lensX) / itemW else 1f
                                val near = (1f - d).coerceIn(0f, 1f) * lensAlpha
                                val sc = 1f + (0.08f + 0.20f * lift) * near
                                scaleX = sc
                                scaleY = sc
                                translationY = -4.dp.toPx() * near * lift
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (i == 3) {
                            // Profile tab: the user's own photo, bigger than the other icons
                            Avatar(
                                photo, profileName, p,
                                Modifier
                                    .fillMaxSize()
                                    .then(if (sel) Modifier.border(2.dp, p.accent, CircleShape) else Modifier),
                                14.sp
                            )
                        } else {
                            GlyphIcon(glyph, tint, Modifier.size(26.dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label, fontSize = 10.5.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = tint, maxLines = 1
                    )
                }
            }
        }
    }
}

val BarHeight = 72.dp
private val BarPad = 6.dp

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
            .size(BarHeight)
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
        GlyphIcon(Glyph.Search, tint, Modifier.size(26.dp))
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
