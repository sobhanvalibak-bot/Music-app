@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.musicapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateDpAsState
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
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── Glass bars ─────────────────────────

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
    profileName: String,
    navy: Boolean = false   // compact icon-only pill in the theme colour
) {
    val tabs = listOf(
        "Songs" to Glyph.Note,
        "Albums" to Glyph.Album,
        "Artists" to Glyph.Artist,
        "Profile" to Glyph.Artist
    )
    val count = tabs.size
    val barH = if (navy) 56.dp else BarHeight
    val barShape = RoundedCornerShape(if (navy) 28.dp else 32.dp)
    val lensShape = RoundedCornerShape(if (navy) 24.dp else 28.dp)
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
            .height(barH)
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
        // 1) iOS style floating capsule: soft dark glass, light rim, soft shadow.
        Box(Modifier.matchParentSize().iosGlass(barShape, if (navy) p.tabBarColor else null))

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
                                            p.accent.copy(alpha = 0.16f * lift),
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
                            if (selected >= 0) Color.White.copy(alpha = 0.13f + 0.07f * lift)
                            else Color.Transparent
                        )
                )
            }
        }

        // 3) iOS tab items: icon on top, small label below, selected one tinted.
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            tabs.forEachIndexed { i, (label, glyph) ->
                val sel = i == selected
                val tint by animateColorAsState(if (navy) (if (sel) p.onTabBar else p.onTabBar.copy(alpha = 0.50f)) else if (sel) p.accent else Color(0xFF9A9AA0), tween(200), label = "tint")
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .graphicsLayer {
                                // dock-style magnification: the closer to the lens, the bigger
                                val centre = i * itemW
                                val d = if (itemW > 0f) abs(centre - lensX) / itemW else 1f
                                val near = (1f - d).coerceIn(0f, 1f) * lensAlpha
                                val sc = 1f + (0.05f + 0.20f * lift) * near
                                scaleX = sc
                                scaleY = sc
                                translationY = -3.dp.toPx() * near * lift
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (i == 3) {
                            // Profile tab: the user's own photo
                            Avatar(
                                photo, profileName, p,
                                Modifier
                                    .fillMaxSize()
                                    .then(if (sel) Modifier.border(1.5.dp, p.accent, CircleShape) else Modifier),
                                14.sp
                            )
                        } else {
                            GlyphIcon(glyph, tint, Modifier.size(if (navy) 24.dp else 22.dp))
                        }
                    }
                    if (!navy) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label, fontSize = 10.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint, maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** iOS-like dark glass: translucent fill, light rim on the edge, soft shadow. */
internal fun Modifier.iosGlass(shape: Shape, solid: Color? = null): Modifier =
    this
        .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
        .clip(shape)
        .background(
            if (solid != null) Brush.verticalGradient(listOf(solid, solid))
            else Brush.verticalGradient(listOf(Color(0xFF2A2A2D).copy(alpha = 0.94f), Color(0xFF18181A).copy(alpha = 0.94f)))
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.16f))),
            shape
        )

val BarHeight = 64.dp
/** Total height of the white mini player card, and how much of its bottom the tab bar covers. */
val MiniCardHeight = 96.dp
val MiniCardHidden = 34.dp
private val BarPad = 6.dp

/** Round glass search button that sits next to the tab bar (like the iOS tab bar). */
@Composable
fun SearchButton(active: Boolean, p: Palette, onClick: () -> Unit, navy: Boolean = false) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "searchScale"
    )
    val tint by animateColorAsState(if (active) p.accent else if (navy) p.onTabBar else Color.White, label = "searchTint")
    val onClickS by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(if (navy) 56.dp else BarHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .iosGlass(CircleShape, if (navy) p.tabBarColor else null)
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


/**
 * Dynamic Island: pure black pill (like the real iPhone one).
 *  - Tap the pill: it grows with a springy, staggered animation into a control panel.
 *  - The expanded panel shows cover, title, a live progress bar and round, press-reactive buttons.
 *  - Content fades/slides in after the shape has started growing, and fades out before it shrinks.
 */
@Composable
fun TopIsland(
    song: Song,
    p: Palette,
    isPlaying: Boolean,
    isFav: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onFav: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    islandWidthDp: Int = 250,
    islandHeightDp: Int = 36,
    position: Long = 0L,
    reserveTop: Boolean = true,
    onSeek: (Long) -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    onLongPress: () -> Unit = {},
    sleepActive: Boolean = false
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val statusH = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(density).toDp() }
    val pillH = islandHeightDp.dp
    val top = ((statusH - pillH) / 2).coerceAtLeast(4.dp)
    val pop = remember { Animatable(1f) }
    val longPressS by rememberUpdatedState(onLongPress)

    // 0f = compact pill, 1f = expanded panel. One spring drives size, corner, shadow and content.
    val t by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 300f),
        label = "islandT"
    )
    // when the song changes the compact pill briefly swells, like the real Dynamic Island
    val bump = remember { Animatable(0f) }
    var firstSong by remember { mutableStateOf(true) }
    LaunchedEffect(song.id) {
        if (firstSong) {
            firstSong = false
        } else {
            bump.snapTo(0f)
            bump.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f))
            kotlinx.coroutines.delay(900)
            bump.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 220f))
        }
    }
    val bumpNow = (bump.value * (1f - t)).coerceAtLeast(0f)
    val width = androidx.compose.ui.unit.lerp(islandWidthDp.dp, 340.dp, t) + 44.dp * bumpNow
    val height = androidx.compose.ui.unit.lerp(pillH, 168.dp, t) + 8.dp * bumpNow
    val corner = androidx.compose.ui.unit.lerp(pillH / 2, 34.dp, t.coerceIn(0f, 1f))
    val shape = RoundedCornerShape(corner)

    // the content of the panel appears a bit after the shape starts to grow
    val contentAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (expanded) tween(260, delayMillis = 110, easing = FastOutSlowInEasing) else tween(90),
        label = "islandContentAlpha"
    )
    val compactAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else 1f,
        animationSpec = if (expanded) tween(90) else tween(220, delayMillis = 140),
        label = "islandCompactAlpha"
    )

    LaunchedEffect(song.id) {
        pop.snapTo(1.05f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
    }

    val wave = rememberInfiniteTransition(label = "islandWave")
    val wavePhase by wave.animateFloat(
        initialValue = 0.55f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse),
        label = "wavePhase"
    )

    // press feedback on the whole pill
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "islandPress"
    )

    Box(modifier.fillMaxWidth().padding(top = if (reserveTop) top else 0.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .graphicsLayer {
                    val s = pop.value * (if (expanded) 1f else press)
                    scaleX = s; scaleY = s
                    // the big panel drops lower on the screen while it grows
                    translationY = 56.dp.toPx() * t
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                }
                .width(width)
                .height(height)
                .shadow(
                    elevation = 6.dp + 18.dp * t, shape = shape,
                    ambientColor = Color.Black, spotColor = Color.Black
                )
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.08f + 0.05f * t), shape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onExpandedChange(!expanded)
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPressS()
                        }
                    )
                }
        ) {
            // ───────── compact pill ─────────
            if (compactAlpha > 0.01f) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(pillH)
                        .padding(horizontal = 5.dp)
                        .graphicsLayer { alpha = compactAlpha },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(song, Modifier.size((islandHeightDp - 8).coerceAtLeast(20).dp), 0.dp, p, 200, round = true)
                    // only the cover and the wave: the title shows when the island is expanded
                    Spacer(Modifier.weight(1f))
                    // small blue moon while the sleep timer is running
                    if (sleepActive) {
                        GlyphIcon(Glyph.Moon, Color(0xFF0A84FF), Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    IslandWave(isPlaying, wavePhase, Color.White, 0.85f)
                    Spacer(Modifier.width(6.dp))
                }
            }

            // ───────── expanded panel ─────────
            if (contentAlpha > 0.01f) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .graphicsLayer {
                            alpha = contentAlpha
                            translationY = (1f - contentAlpha) * -10.dp.toPx()
                        },
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // header: cover · title/artist · wave
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Cover(song, Modifier.size(46.dp), 12.dp, p, 300)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title, color = Color.White, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IslandWave(isPlaying, wavePhase, Color.White, 1f)
                        Spacer(Modifier.width(10.dp))
                        // opens the full player
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.16f))
                                .clickable {
                                    onExpandedChange(false)
                                    onOpenPlayer()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            GlyphIcon(Glyph.ChevronDown, Color.White, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f })
                        }
                    }

                    // progress: drag or tap to change the time of the song
                    val dur = song.durationMs
                    var dragFrac by remember { mutableStateOf<Float?>(null) }
                    val liveFrac = if (dur > 0) (position.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val frac = dragFrac ?: liveFrac
                    val shownMs = if (dragFrac != null) (frac * dur).toLong() else position
                    val seekS by rememberUpdatedState(onSeek)
                    Column {
                        Box(
                            Modifier.fillMaxWidth().height(24.dp)
                                .pointerInput(dur) {
                                    detectTapGestures { o ->
                                        if (dur > 0 && size.width > 0) {
                                            seekS((o.x / size.width).coerceIn(0f, 1f).times(dur).toLong())
                                        }
                                    }
                                }
                                .pointerInput(dur) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { o ->
                                            if (size.width > 0) dragFrac = (o.x / size.width).coerceIn(0f, 1f)
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            if (size.width > 0) dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                                        },
                                        onDragEnd = {
                                            dragFrac?.let { if (dur > 0) seekS((it * dur).toLong()) }
                                            dragFrac = null
                                        },
                                        onDragCancel = { dragFrac = null }
                                    )
                                },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            val barH by animateDpAsState(if (dragFrac != null) 8.dp else 4.dp, tween(150), label = "islandBarH")
                            Box(
                                Modifier.fillMaxWidth().height(barH)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.18f))
                            ) {
                                Box(
                                    Modifier.fillMaxHeight().fillMaxWidth(frac)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White)
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatTime(shownMs), color = Color.White.copy(alpha = if (dragFrac != null) 1f else 0.5f), fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            Text("-" + formatTime((dur - shownMs).coerceAtLeast(0L)), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                    }

                    // controls
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IslandButton(Glyph.Shuffle, if (shuffle) p.accent else Color.White.copy(alpha = 0.55f), 38.dp, 18.dp, onShuffle)
                        IslandButton(Glyph.Prev, Color.White, 42.dp, 21.dp, onPrev)
                        IslandButton(
                            if (isPlaying) Glyph.Pause else Glyph.Play, Color.Black, 54.dp, 24.dp, onToggle,
                            filled = Color.White
                        )
                        IslandButton(Glyph.Next, Color.White, 42.dp, 21.dp, onNext)
                        IslandButton(
                            Glyph.Repeat,
                            if (repeatMode != Player.REPEAT_MODE_OFF) p.accent else Color.White.copy(alpha = 0.55f),
                            38.dp, 18.dp, onRepeat
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IslandWave(playing: Boolean, phase: Float, color: Color, alpha: Float) {
    val rest by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "islandWaveRest")
    Row(
        Modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { i ->
            val base = listOf(5, 11, 7, 15, 8)[i].toFloat()
            val h = 3f + (base * phase * (if (i % 2 == 0) 1f else 0.82f) - 3f) * rest
            Box(
                Modifier.width(2.5.dp).height(h.coerceAtLeast(3f).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = alpha * (0.5f + 0.5f * rest)))
            )
        }
    }
}

/** Round island button: shrinks while pressed and springs back, with a soft pressed highlight. */
@Composable
private fun IslandButton(
    glyph: Glyph,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    filled: Color? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.82f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "islandBtnScale"
    )
    val glow by animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "islandBtnGlow")
    val onClickS by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(filled ?: Color.White.copy(alpha = 0.14f * glow))
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
        GlyphIcon(glyph, color, Modifier.size(iconSize))
    }
}
