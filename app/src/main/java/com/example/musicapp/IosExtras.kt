package com.example.musicapp

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── iOS switch ─────────────────────────

/** Round green iOS toggle: the knob stretches while pressed and springs to the other side. */
@Composable
fun IosSwitch(p: Palette, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val knobW by animateDpAsState(if (pressed) 33.dp else 27.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "knobW")
    val knobX by animateDpAsState(
        if (checked) 51.dp - 2.dp - knobW else 2.dp,
        spring(dampingRatio = 0.65f, stiffness = 520f), label = "knobX"
    )
    val track by animateColorAsState(
        if (checked) Color(0xFF34C759) else if (p.dark) Color(0xFF39393D) else Color(0xFFE9E9EA),
        tween(220), label = "switchTrack"
    )
    val changeS by rememberUpdatedState(onCheckedChange)
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .pointerInput(checked) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        changeS(!checked)
                    }
                )
            }
            .pointerInput(checked) {
                // drag the knob to the other side, like iOS
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; pressed = true },
                    onHorizontalDrag = { change, dx -> change.consume(); total += dx },
                    onDragEnd = {
                        pressed = false
                        if (total > 10f && !checked) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            changeS(true)
                        } else if (total < -10f && checked) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            changeS(false)
                        }
                    },
                    onDragCancel = { pressed = false }
                )
            }
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 2.dp)
                .size(width = knobW, height = 27.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

// ───────────────────────── Swipe from the left edge to go back ─────────────────────────

/**
 * Put this on a full-screen page: dragging from the left edge pulls the page to the right,
 * and letting go past a third of the screen (or with a flick) calls [onBack].
 * Add it before `.background(...)` so the background moves with the page.
 */
fun Modifier.swipeBack(onBack: () -> Unit): Modifier = composed {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val onBackS by rememberUpdatedState(onBack)
    val edge = with(LocalDensity.current) { 36.dp.toPx() }
    this
        .graphicsLayer { translationX = offset.value }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x > edge) return@awaitEachGesture
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dragging = false
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed && !dragging) break
                    val up = change.changedToUpIgnoreConsumed()
                    val d = change.position - down.position
                    if (!dragging) {
                        if (abs(d.y) > viewConfiguration.touchSlop && abs(d.y) > abs(d.x)) break
                        if (d.x > viewConfiguration.touchSlop) dragging = true
                    }
                    if (dragging && !up) {
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val x = d.x.coerceAtLeast(0f)
                        scope.launch { offset.snapTo(x) }
                    }
                    if (up) finished = true
                }
                if (dragging) {
                    val vx = tracker.calculateVelocity().x
                    val w = size.width.toFloat()
                    scope.launch {
                        if (offset.value > w * 0.35f || vx > 1000f) {
                            offset.animateTo(w, tween(180))
                            onBackS()
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                        }
                    }
                }
            }
        }
}

// ───────────────────────── Pull down to reveal search ─────────────────────────

class PullToSearchState(
    val pull: Animatable<Float, AnimationVector1D>,
    val connection: NestedScrollConnection
)

/**
 * Pulling the list down while it is already at the top moves it down with a little resistance.
 * Letting go past [thresholdPx] calls [onTrigger]; otherwise it springs back.
 */
@Composable
fun rememberPullToSearch(thresholdPx: Float, onTrigger: () -> Unit): PullToSearchState {
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val trigger by rememberUpdatedState(onTrigger)
    val haptic = LocalHapticFeedback.current
    val connection = remember(thresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && pull.value > 0f && available.y < 0f) {
                    val nv = (pull.value + available.y).coerceAtLeast(0f)
                    val used = nv - pull.value
                    scope.launch { pull.snapTo(nv) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    val nv = (pull.value + available.y * 0.5f).coerceAtMost(thresholdPx * 1.8f)
                    scope.launch { pull.snapTo(nv) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value > 0f) {
                    if (pull.value >= thresholdPx) trigger()
                    pull.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 380f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(pull, thresholdPx) {
        snapshotFlow { pull.value >= thresholdPx }.collect {
            if (it) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    return remember(pull, connection) { PullToSearchState(pull, connection) }
}

/** The search pill that is revealed behind the list while it is pulled down. */
@Composable
fun PullSearchPill(state: PullToSearchState, thresholdPx: Float, topPad: Dp, p: Palette) {
    val pillH = 44.dp
    val pillHPx = with(LocalDensity.current) { pillH.toPx() }
    val armed = state.pull.value >= thresholdPx
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = topPad, start = 20.dp, end = 20.dp)
            .height(pillH)
            .graphicsLayer {
                val prog = (state.pull.value / thresholdPx).coerceIn(0f, 1f)
                alpha = prog
                translationY = (state.pull.value - pillHPx) / 2f
                scaleX = 0.9f + 0.1f * prog
                scaleY = 0.9f + 0.1f * prog
            }
            .clip(RoundedCornerShape(14.dp))
            .background(p.selectedPill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.Search, if (armed) p.accent else p.secondary, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (armed) "Release to search" else "Search",
            color = if (armed) p.accent else p.secondary, fontSize = 16.sp
        )
    }
}

// ───────────────────────── Large title that collapses into the top bar ─────────────────────────

/** Small centered title bar that fades in ([progress] 0..1) once the large title has scrolled away. */
@Composable
fun CollapsingBar(title: String, progress: Float, p: Palette, topPad: Dp, modifier: Modifier = Modifier) {
    if (progress <= 0.01f) return
    Box(
        modifier
            .fillMaxWidth()
            .height(topPad + 34.dp)
            .graphicsLayer { alpha = progress }
            .background(p.bg.copy(alpha = 0.94f))
    ) {
        Text(
            title, Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.5.dp)
                .background(p.secondary.copy(alpha = 0.25f))
        )
    }
}

// ───────────────────────── A–Z index ─────────────────────────

private val IndexLetters = listOf('#') + ('A'..'Z').toList()

/** First letter used by the index: letters as they are, everything else is '#'. */
fun indexKey(title: String): Char {
    val c = title.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (c.isLetter()) c else '#'
}

/** Letter strip on the right edge. Touch or slide on it; [onLetter] gets every new letter. */
@Composable
fun AlphabetIndex(p: Palette, onLetter: (Char) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableIntStateOf(-1) }
    var heightPx by remember { mutableIntStateOf(1) }
    val haptic = LocalHapticFeedback.current
    val onLetterS by rememberUpdatedState(onLetter)
    fun pick(y: Float) {
        val i = ((y / heightPx) * IndexLetters.size).toInt().coerceIn(0, IndexLetters.size - 1)
        if (i != active) {
            active = i
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onLetterS(IndexLetters[i])
        }
    }
    Box(modifier) {
        if (active >= 0) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 40.dp)
                    .offset { IntOffset(0, (((active + 0.5f) / IndexLetters.size) * heightPx - heightPx / 2f).roundToInt()) }
                    .size(58.dp)
                    .clip(RoundedCornerShape(topStart = 29.dp, topEnd = 29.dp, bottomStart = 29.dp, bottomEnd = 8.dp))
                    .background(p.accent),
                contentAlignment = Alignment.Center
            ) {
                Text(IndexLetters[active].toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(26.dp)
                .fillMaxHeight()
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { o ->
                        pick(o.y)
                        tryAwaitRelease()
                        active = -1
                    })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            pick(change.position.y)
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IndexLetters.forEachIndexed { i, c ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        c.toString(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = if (i == active) p.text else p.accent
                    )
                }
            }
        }
    }
}

// ───────────────────────── Swipe actions on a row ─────────────────────────

/**
 * iOS style swipe actions: drag the row to the right = favorite (pink), to the left = play next
 * (accent). Let go past the threshold to run the action; the row springs back.
 */
@Composable
fun SwipeActionBox(
    p: Palette,
    enabled: Boolean,
    fav: Boolean,
    onFav: () -> Unit,
    onNext: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    val swipe = remember { Animatable(0f) }
    val raw = remember { floatArrayOf(0f) }
    val scope = rememberCoroutineScope()
    val thr = with(LocalDensity.current) { 84.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val favS by rememberUpdatedState(onFav)
    val nextS by rememberUpdatedState(onNext)
    var crossed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        val off = swipe.value
        if (off != 0f) {
            val right = off > 0f
            val prog = (abs(off) / thr).coerceIn(0f, 1f)
            Box(
                Modifier
                    .matchParentSize()
                    .background(if (right) Color(0xFFFF2D55) else p.accent)
                    .padding(horizontal = 26.dp),
                contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                GlyphIcon(
                    if (right) (if (fav) Glyph.Heart else Glyph.HeartOutline) else Glyph.Next,
                    Color.White,
                    Modifier.size(24.dp).graphicsLayer {
                        scaleX = 0.6f + 0.4f * prog
                        scaleY = 0.6f + 0.4f * prog
                        alpha = prog
                    }
                )
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = swipe.value }
                .then(if (off != 0f) Modifier.background(p.bg) else Modifier)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            raw[0] = swipe.value
                            crossed = false
                        },
                        onDragEnd = {
                            val v = raw[0]
                            if (v >= thr) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                favS()
                            } else if (v <= -thr) {
                                nextS?.let {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    it()
                                }
                            }
                            scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 380f)) }
                        },
                        onDragCancel = {
                            scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 380f)) }
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            val cur = raw[0]
                            val minV = if (nextS != null) -thr * 1.6f else 0f
                            val factor = if (abs(cur) > thr) 0.4f else 1f
                            val nv = (cur + dx * factor).coerceIn(minV, thr * 1.6f)
                            raw[0] = nv
                            scope.launch { swipe.snapTo(nv) }
                            val c = abs(nv) >= thr
                            if (c != crossed) {
                                crossed = c
                                if (c) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    )
                }
        ) { content() }
    }
}

// ───────────────────────── Big pill volume slider ─────────────────────────

/** Control Center style vertical pill: slide up and down to change the music volume. */
@Composable
fun VolumePill(onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    var dragging by remember { mutableStateOf(false) }
    var heightPx by remember { mutableIntStateOf(1) }
    val haptic = LocalHapticFeedback.current
    val onTouchS by rememberUpdatedState(onTouch)
    val grow by animateFloatAsState(if (dragging) 1.07f else 1f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "volGrow")

    // follows the hardware volume keys too
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) level = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            delay(300)
        }
    }

    fun setFromY(y: Float) {
        val v = (1f - y / heightPx).coerceIn(0f, 1f)
        level = v
        val step = (v * max).roundToInt()
        if (step != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0) } catch (_: Exception) { }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onTouchS()
    }

    Box(
        modifier
            .size(width = 58.dp, height = 190.dp)
            .graphicsLayer { scaleX = grow; scaleY = grow }
            .clip(RoundedCornerShape(29.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o ->
                    dragging = true
                    setFromY(o.y)
                    tryAwaitRelease()
                    dragging = false
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; setFromY(it.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        setFromY(change.position.y)
                    }
                )
            }
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(level.coerceIn(0f, 1f))
                .background(Color.White.copy(alpha = 0.94f))
        )
        GlyphIcon(
            Glyph.Volume,
            if (level > 0.12f) Color.Black else Color.White,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(24.dp)
        )
    }
}

// ───────────────────────── Sleep timer sheet ─────────────────────────

private val SleepOptions = listOf(15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", -1 to "End of this song")

/**
 * Bottom card to set the sleep timer. [onPick]: minutes, -1 = stop after this song, 0 = turn off.
 */
@Composable
fun SleepTimerSheet(
    active: Boolean,
    endOfSong: Boolean,
    leftMs: Long,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown = true }
    fun close(then: (() -> Unit)? = null) {
        scope.launch {
            shown = false
            delay(190)
            then?.invoke()
            onDismiss()
        }
    }
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val scrim by animateFloatAsState(if (shown) 0.45f else 0f, tween(200), label = "sleepScrim")
        val fg = Color.White
        val sub = Color.White.copy(alpha = 0.6f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .pointerInput(Unit) { detectTapGestures { close() } }
        ) {
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it } + fadeIn(tween(120)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF1C1C1E))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(vertical = 8.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sleep timer", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                endOfSong -> "Music stops after this song"
                                active -> "Music stops in ${formatTime(leftMs)}"
                                else -> "Music fades out, then stops"
                            },
                            fontSize = 13.sp, color = sub
                        )
                    }
                    SleepOptions.forEach { (value, label) ->
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { close { onPick(value) } }
                                .padding(horizontal = 20.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = fg)
                            if (value == -1 && endOfSong) GlyphIcon(Glyph.Moon, Color(0xFF0A84FF), Modifier.size(20.dp))
                        }
                    }
                    if (active || endOfSong) {
                        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.Black.copy(alpha = 0.35f)))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { close { onPick(0) } }
                                .padding(horizontal = 20.dp, vertical = 15.dp)
                        ) {
                            Text("Turn off timer", Modifier.weight(1f), fontSize = 16.sp, color = Color(0xFFFF453A))
                        }
                    }
                }
            }
        }
    }
}
