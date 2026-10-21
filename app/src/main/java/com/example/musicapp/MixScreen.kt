package com.example.musicapp

import android.graphics.Matrix as AMatrix
import android.graphics.Paint as APaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── Mix songs: craft-style cube animation ─────────────────────────
//
// Same flow as Telegram's gift crafting:
//  1. idle: a flat card with an anvil, a ring that fills as you pick songs, 4 slots around it
//  2. tap Mix: the rest of the screen slowly blurs out, "Mixing" appears, the card turns into a cube
//  3. one by one the song covers float into the cube and the cube slowly turns to show each of them
//  4. the cube keeps turning until the real audio is ready, then settles on the result face and the
//     four covers appear together as the cover of ONE new song (saved in the "Mixes" playlist)
// A short part of every picked song is cut out automatically; only the order is random.

private enum class MixPhase { Idle, Running, Done }

private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Color, var life: Float)

// Face order: 0 front, 1 right, 2 back, 3 left, 4 top, 5 bottom.
private val FN = arrayOf(
    floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f),
    floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 1f, 0f)
)
private val FU = arrayOf(
    floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f),
    floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f)
)
private val FV = arrayOf(
    floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
    floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
)
/** (rotationX, rotationY) in degrees that bring each face to the front. */
private val FBASE = arrayOf(
    floatArrayOf(0f, 0f), floatArrayOf(0f, -90f), floatArrayOf(0f, 180f),
    floatArrayOf(0f, 90f), floatArrayOf(-90f, 0f), floatArrayOf(90f, 0f)
)
private val CONFETTI = listOf(Color(0xFFFF6B81), Color(0xFF5CE1E6), Color(0xFFFFD166), Color(0xFFA74FE0), Color(0xFF7BD88F), Color.White)

/** Rounded-square outline of a cube face in unit (0..1) coordinates. */
private val ROUND_FACE: FloatArray = run {
    val inset = 0.035f
    val r = 0.22f
    val seg = 6
    val centers = arrayOf(
        floatArrayOf(inset + r, inset + r, 180f), floatArrayOf(1f - inset - r, inset + r, 270f),
        floatArrayOf(1f - inset - r, 1f - inset - r, 0f), floatArrayOf(inset + r, 1f - inset - r, 90f)
    )
    val out = ArrayList<Float>()
    for (c in centers) for (j in 0..seg) {
        val a = Math.toRadians((c[2] + 90f * j / seg).toDouble())
        out.add(c[0] + r * cos(a).toFloat())
        out.add(c[1] + r * sin(a).toFloat())
    }
    out.toFloatArray()
}

/** Anvil silhouette in unit coordinates (the glyph on empty faces). */
private val ANVIL = floatArrayOf(
    0.18f, 0.40f, 0.34f, 0.35f, 0.78f, 0.35f, 0.78f, 0.46f, 0.64f, 0.50f, 0.60f, 0.60f,
    0.70f, 0.68f, 0.70f, 0.75f, 0.32f, 0.75f, 0.32f, 0.68f, 0.42f, 0.60f, 0.38f, 0.50f, 0.30f, 0.46f
)

/** Rotate by Y then X (same order as CSS rotateX(ax) rotateY(ay)). */
private fun rot(x: Float, y: Float, z: Float, ax: Float, ay: Float): FloatArray {
    val a = Math.toRadians(ay.toDouble())
    val x1 = (x * cos(a) + z * sin(a)).toFloat()
    val z1 = (-x * sin(a) + z * cos(a)).toFloat()
    val b = Math.toRadians(ax.toDouble())
    val y2 = (y * cos(b) - z1 * sin(b)).toFloat()
    val z2 = (y * sin(b) + z1 * cos(b)).toFloat()
    return floatArrayOf(x1, y2, z2)
}

private fun polyPath(unit: FloatArray, m: AMatrix): Path {
    val out = FloatArray(unit.size)
    m.mapPoints(out, unit)
    val path = Path()
    path.moveTo(out[0], out[1])
    var i = 2
    while (i < out.size) {
        path.lineTo(out[i], out[i + 1])
        i += 2
    }
    path.close()
    return path
}

private fun slicePolygon(count: Int, i: Int): FloatArray = when (count) {
    2 -> if (i == 0) floatArrayOf(0f, 0f, .58f, 0f, .42f, 1f, 0f, 1f) else floatArrayOf(.58f, 0f, 1f, 0f, 1f, 1f, .42f, 1f)
    3 -> when (i) {
        0 -> floatArrayOf(0f, 0f, .40f, 0f, .25f, 1f, 0f, 1f)
        1 -> floatArrayOf(.40f, 0f, .75f, 0f, .60f, 1f, .25f, 1f)
        else -> floatArrayOf(.75f, 0f, 1f, 0f, 1f, 1f, .60f, 1f)
    }
    else -> when (i) {
        0 -> floatArrayOf(0f, 0f, .5f, 0f, .5f, .5f, 0f, .5f)
        1 -> floatArrayOf(.5f, 0f, 1f, 0f, 1f, .5f, .5f, .5f)
        2 -> floatArrayOf(0f, .5f, .5f, .5f, .5f, 1f, 0f, 1f)
        else -> floatArrayOf(.5f, .5f, 1f, .5f, 1f, 1f, .5f, 1f)
    }
}

@Composable
fun MixPage(
    songs: List<Song>,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    /** Builds the real mix (audio + cover), saves it and puts it in the Mixes playlist. Null = failed. */
    render: suspend (List<Song>, android.graphics.Bitmap?) -> Song?
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val context = LocalContext.current

    val slots = remember { mutableStateListOf<Song?>(null, null, null, null) }
    var picking by remember { mutableIntStateOf(-1) }
    var phase by remember { mutableStateOf(MixPhase.Idle) }
    var picked by remember { mutableStateOf<List<Song>>(emptyList()) }
    var faces by remember { mutableStateOf<List<Song?>>(List(6) { null }) }
    var resultFace by remember { mutableIntStateOf(0) }
    var mixSong by remember { mutableStateOf<Song?>(null) }
    var collage by remember { mutableStateOf<ImageBitmap?>(null) }
    var arena by remember { mutableStateOf(Size.Zero) }

    val ax = remember { Animatable(0f) }
    val ay = remember { Animatable(0f) }
    val uiHide = remember { Animatable(0f) }      // 1 = rest of the screen blurred out, "Mixing" shown
    val ringA = remember { Animatable(1f) }
    val reveal = remember { Animatable(0f) }      // result face: anvil -> combined cover
    val settle = remember { Animatable(1f) }
    val gone = remember { List(4) { Animatable(0f) } }

    val sparks = remember { mutableListOf<Spark>() }
    var frame by remember { mutableIntStateOf(0) }

    // confetti physics, one step per frame
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = t
                if (sparks.isNotEmpty()) {
                    sparks.forEach {
                        it.x += it.vx * dt; it.y += it.vy * dt
                        it.vy += 900f * dt; it.vx *= 0.985f
                        it.life -= dt * 1.1f
                    }
                    sparks.removeAll { it.life <= 0f }
                    frame++
                }
            }
        }
    }

    fun burst(n: Int, speed: Float) {
        if (arena == Size.Zero) return
        repeat(n) { i ->
            val a = Random.nextFloat() * 6.2831f
            val v = speed * (0.4f + Random.nextFloat() * 0.9f)
            sparks.add(Spark(arena.width / 2f, arena.height / 2f, cos(a) * v, sin(a) * v - 120f, CONFETTI[i % CONFETTI.size], 0.8f + Random.nextFloat() * 0.5f))
        }
        frame++
    }

    suspend fun land(face: Int, ms: Int, easing: Easing, turns: Int = 0) {
        val bx = FBASE[face][0]
        val by = FBASE[face][1]
        // shortest way to the face, plus [turns] full slow turns around the vertical axis
        val dir = if (Random.nextBoolean()) 1 else -1
        val tx = bx + ((ax.value - bx) / 360f).roundToInt() * 360f
        val ty = by + ((ay.value - by) / 360f).roundToInt() * 360f + dir * turns * 360f
        coroutineScope {
            launch { ax.animateTo(tx, tween(ms, easing = easing)) }
            launch { ay.animateTo(ty, tween(ms, easing = easing)) }
        }
    }

    fun edited() {
        if (phase == MixPhase.Done) {
            phase = MixPhase.Idle
            scope.launch {
                reveal.snapTo(0f); settle.snapTo(1f)
                faces = List(6) { null }
                launch { ringA.animateTo(1f, tween(300)) }
                coroutineScope {
                    launch { ax.animateTo((ax.value / 360f).roundToInt() * 360f, tween(500)) }
                    launch { ay.animateTo((ay.value / 360f).roundToInt() * 360f, tween(500)) }
                }
            }
        }
    }

    fun startMix() {
        val chosen = slots.filterNotNull()
        if (chosen.size < 2 || phase == MixPhase.Running) return
        scope.launch {
            phase = MixPhase.Running
            mixSong = null
            collage = null
            val order = chosen.shuffled()            // every picked song is mixed, only the order is random
            picked = order
            val all = (0..5).shuffled()
            val rf = all[0]                          // the face that ends up showing the result
            val songFaces = all.drop(1).take(order.size)
            faces = List(6) { null }                 // a face shows its cover only once that song has flown in
            resultFace = rf
            reveal.snapTo(0f); settle.snapTo(1f)
            gone.forEach { it.snapTo(0f) }

            // the real audio is built while the animation plays
            val job = async {
                try {
                    val cb = withContext(Dispatchers.Default) { MixCover.build(context, order) }
                    collage = cb.asImageBitmap()
                    render(order, cb)
                } catch (e: Exception) {
                    null
                }
            }

            // 1) the rest of the screen slowly blurs out, the ring disappears
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            launch { uiHide.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            launch { ringA.animateTo(0f, tween(700)) }
            delay(1300)

            // 2) song by song: the cover floats into the cube while the cube slowly turns to that face
            val glide = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
            order.forEachIndexed { i, s ->
                val face = songFaces[i]
                faces = faces.toMutableList().also { it[face] = s }
                val si = slots.indexOf(s)
                coroutineScope {
                    if (si >= 0) launch { gone[si].animateTo(1f, tween(1700, easing = CubicBezierEasing(0.5f, 0f, 0.3f, 1f))) }
                    launch { land(face, 2300, glide) }
                }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(500)
            }

            // 3) keep turning slowly until the audio is ready (at least a short while, so it never looks stuck)
            do {
                ay.animateTo(ay.value + 120f, tween(2600, easing = LinearEasing))
            } while (!job.isCompleted)
            val made = job.await()

            if (made == null) {
                // failed (the reason was shown by the caller): back to the start
                gone.forEach { g -> launch { g.animateTo(0f, tween(500)) } }
                faces = List(6) { null }
                picked = emptyList()
                phase = MixPhase.Idle
                launch { ringA.animateTo(1f, tween(400)) }
                uiHide.animateTo(0f, tween(600))
                return@launch
            }

            // 4) last, slow, long spin: it stops on the result face
            land(rf, 3600, CubicBezierEasing(0.25f, 0.6f, 0.2f, 1f), 1)
            delay(300)

            // 5) reveal the combined cover
            mixSong = made
            phase = MixPhase.Done
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            burst(36, 750f)
            launch { reveal.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
            launch { settle.animateTo(1.18f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow)) }
            delay(1700)
            gone.forEach { g -> launch { g.animateTo(0f, tween(500)) } }
            uiHide.animateTo(0f, tween(800))
        }
    }

    val count = slots.count { it != null }
    val shown: List<Song?> = if (phase == MixPhase.Idle) List(6) { null } else faces
    val mixCount = if (picked.isEmpty()) count else picked.size

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = topPad, bottom = bottomPad + 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .graphicsLayer { alpha = 1f - uiHide.value },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(p.card).clickable { if (phase != MixPhase.Running) onBack() },
                Alignment.Center
            ) { GlyphIcon(Glyph.Back, p.text, Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Text("Mix songs", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.text)
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(1f)
                .onSizeChanged { arena = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {
            val w = maxWidth
            val s = w * 0.24f
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val sPx = with(density) { s.toPx() }
            val gap = with(density) { 7.dp.toPx() }
            val xs = floatArrayOf(0f, 0f, wPx - sPx, wPx - sPx)
            val ys = floatArrayOf(hPx / 2f - sPx - gap, hPx / 2f + gap, hPx / 2f - sPx - gap, hPx / 2f + gap)

            CubeCanvas(
                faces = shown, resultFace = resultFace, picked = picked, collage = collage, reveal = reveal,
                ringFrac = count / 4f, ringA = ringA, p = p, ax = ax, ay = ay,
                modifier = Modifier.align(Alignment.Center).size(w * 0.62f).graphicsLayer {
                    scaleX = settle.value; scaleY = settle.value
                }
            )

            // the four slots; each one vanishes when the cube stops on its song
            for (i in 0 until 4) {
                val song = slots[i]
                MixSlot(
                    song = song, p = p,
                    modifier = Modifier
                        .offset { IntOffset(xs[i].roundToInt(), ys[i].roundToInt()) }
                        .size(s)
                        .graphicsLayer {
                            // the cover floats to the middle, shrinks and melts into the cube
                            val g = gone[i].value
                            translationX = (wPx / 2f - (xs[i] + sPx / 2f)) * g
                            translationY = (hPx / 2f - (ys[i] + sPx / 2f)) * g
                            val sc = 1f - 0.55f * g
                            scaleX = sc; scaleY = sc
                            alpha = 1f - ((g - 0.72f) / 0.28f).coerceIn(0f, 1f)
                        },
                    onClick = { if (phase != MixPhase.Running) picking = i },
                    onClear = { if (phase != MixPhase.Running) { slots[i] = null; edited() } }
                )
            }

            Canvas(Modifier.fillMaxSize()) {
                val f = frame
                if (f >= 0) sparks.forEach {
                    val l = it.life.coerceIn(0f, 1f)
                    drawCircle(it.color.copy(alpha = l), radius = 4.dp.toPx() * (0.4f + 0.6f * l), center = Offset(it.x, it.y))
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            // normal content: blurs out while mixing
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = 1f - uiHide.value
                    val b = uiHide.value * 14f
                    if (b > 0.5f && android.os.Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(b, b, TileMode.Decal)
                }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (phase) {
                        MixPhase.Idle -> Text(
                            if (count < 2) "Pick 2 to 4 songs. A part of each one is cut out and blended into one new song." else "$count songs ready. They become ONE song, saved in your Mixes playlist.",
                            color = p.secondary, fontSize = 15.sp, textAlign = TextAlign.Center
                        )
                        MixPhase.Running -> Spacer(Modifier.height(1.dp))
                        MixPhase.Done -> {
                            Text(
                                picked.joinToString(" × ") { it.title }, color = p.text, fontSize = 18.sp,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val ms = mixSong?.durationMs ?: 0L
                            Text(
                                "${picked.size}-song mix" + if (ms > 0) " · ${ms / 60000}:${"%02d".format(ms / 1000 % 60)}" else "",
                                color = p.secondary, fontSize = 14.sp
                            )
                            Text("Saved in the playlist “Mixes”", color = p.secondary, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val canMix = count >= 2 && phase != MixPhase.Running
                    MixPill(
                        text = when {
                            phase == MixPhase.Running -> "Mixing…"
                            phase == MixPhase.Done -> "Mix again"
                            count < 2 -> "Mix"
                            else -> "Mix $count songs"
                        },
                        p = p, filled = true, enabled = canMix, modifier = Modifier.fillMaxWidth()
                    ) { startMix() }
                    if (phase == MixPhase.Done) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MixPill("Play mix", p, filled = false, enabled = mixSong != null, modifier = Modifier.weight(1f)) { mixSong?.let(onPlay) }
                            MixPill("Add to playlist", p, filled = false, enabled = mixSong != null, modifier = Modifier.weight(1f)) { mixSong?.let(onAddToPlaylist) }
                        }
                    }
                }
            }

            // "Mixing" texts, shown while the rest is blurred out
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 16.dp).graphicsLayer { alpha = uiHide.value },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Mixing", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Cutting a part of each song", color = p.secondary, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("$mixCount songs become one", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (picking >= 0) {
        SongPickerDialog(
            songs = songs,
            exclude = slots.filterNotNull().map { it.id }.toSet(),
            p = p,
            onPick = { slots[picking] = it; picking = -1; edited() },
            onDismiss = { picking = -1 }
        )
    }
}

@Composable
private fun MixPill(
    text: String,
    p: Palette,
    filled: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val bg = if (filled) p.accent.copy(alpha = if (enabled) 1f else 0.4f) else p.card
    val fg = if (filled) Color.White else p.accent.copy(alpha = if (enabled) 1f else 0.5f)
    Box(
        modifier.clip(RoundedCornerShape(26.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 15.dp),
        Alignment.Center
    ) { Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun MixSlot(song: Song?, p: Palette, modifier: Modifier, onClick: () -> Unit, onClear: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onClick), Alignment.Center) {
        if (song != null) {
            Cover(song, Modifier.fillMaxSize(), 18.dp, p, px = 300)
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClear),
                Alignment.Center
            ) { GlyphIcon(Glyph.Close, Color.White, Modifier.size(10.dp)) }
        } else {
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.text), Alignment.Center) {
                GlyphIcon(Glyph.Plus, p.bg, Modifier.size(20.dp))
            }
        }
    }
}

/** 3D cube drawn with plain projection. Faces are rounded squares: song cover, anvil, or the combined result. */
@Composable
private fun CubeCanvas(
    faces: List<Song?>,
    resultFace: Int,
    picked: List<Song>,
    collage: ImageBitmap?,
    reveal: Animatable<Float, *>,
    ringFrac: Float,
    ringA: Animatable<Float, *>,
    p: Palette,
    ax: Animatable<Float, *>,
    ay: Animatable<Float, *>,
    modifier: Modifier
) {
    val context = LocalContext.current
    val bmps = remember { mutableStateMapOf<Long, androidx.compose.ui.graphics.ImageBitmap>() }
    LaunchedEffect(faces) {
        faces.filterNotNull().forEach { s ->
            if (!bmps.containsKey(s.id)) loadArt(context, s, 400)?.let { bmps[s.id] = it }
        }
    }
    val paint = remember { APaint().apply { isFilterBitmap = true; isAntiAlias = true } }
    val slate = if (p.dark) Color(0xFF3B475E) else Color(0xFFC9D1E2)
    val glyph = if (p.dark) Color.White else Color(0xFF2B3550)

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val h = size.minDimension * 0.24f
        val dist = h * 5.2f
        val rax = ax.value
        val ray = ay.value
        val rv = reveal.value

        // ring around the card (idle only): fills as songs are picked
        if (ringA.value > 0.01f) {
            val rr = size.minDimension * 0.47f
            val stroke = 5.dp.toPx()
            drawArc(p.secondary.copy(alpha = 0.25f * ringA.value), 0f, 360f, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(stroke))
            if (ringFrac > 0f) drawArc(p.accent.copy(alpha = ringA.value), -90f, 360f * ringFrac, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        }

        val sx = intArrayOf(-1, 1, 1, -1)
        val sy = intArrayOf(-1, -1, 1, 1)
        for (f in 0 until 6) {
            val n = rot(FN[f][0], FN[f][1], FN[f][2], rax, ray)
            if (n[2] <= 0.02f) continue                       // back face
            val pts = FloatArray(8)
            for (k in 0..3) {
                val px = FN[f][0] + sx[k] * FU[f][0] + sy[k] * FV[f][0]
                val py = FN[f][1] + sx[k] * FU[f][1] + sy[k] * FV[f][1]
                val pz = FN[f][2] + sx[k] * FU[f][2] + sy[k] * FV[f][2]
                val r = rot(px * h, py * h, pz * h, rax, ray)
                val sc = dist / (dist - r[2])
                pts[k * 2] = cx + r[0] * sc
                pts[k * 2 + 1] = cy + r[1] * sc
            }
            // unit square -> projected quad
            val m = AMatrix()
            m.setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0, pts, 0, 4)
            val facePath = polyPath(ROUND_FACE, m)
            val song = faces.getOrNull(f)
            val bmp = song?.let { bmps[it.id] }
            val isResult = song == null && f == resultFace && rv > 0.01f && picked.size >= 2 && collage != null

            if (song != null && bmp != null) {
                drawPath(facePath, p.coverBottom)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val ab = bmp.asAndroidBitmap()
                    val bw = ab.width.toFloat()
                    val bh = ab.height.toFloat()
                    val mb = AMatrix()
                    mb.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, pts, 0, 4)
                    paint.alpha = 255
                    nc.save()
                    nc.clipPath(facePath.asAndroidPath())
                    nc.concat(mb)
                    nc.drawBitmap(ab, 0f, 0f, paint)
                    nc.restore()
                }
            } else if (song != null) {
                drawPath(facePath, Brush.linearGradient(listOf(p.coverTop, p.coverBottom)))
            } else {
                // empty face: slate card with an anvil
                drawPath(facePath, slate)
                drawPath(polyPath(ANVIL, m), glyph.copy(alpha = 0.95f * (1f - rv)))
                if (isResult && collage != null) {
                    // the result face turns into the four covers together
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        val ab = collage.asAndroidBitmap()
                        val bw = ab.width.toFloat()
                        val bh = ab.height.toFloat()
                        val mb = AMatrix()
                        mb.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, pts, 0, 4)
                        paint.alpha = (rv * 255f).toInt().coerceIn(0, 255)
                        nc.save()
                        nc.clipPath(facePath.asAndroidPath())
                        nc.concat(mb)
                        nc.drawBitmap(ab, 0f, 0f, paint)
                        nc.restore()
                        paint.alpha = 255
                    }
                }
            }
            // shading by angle + soft edge
            drawPath(facePath, Color.Black.copy(alpha = (1f - n[2]).coerceIn(0f, 1f) * 0.45f))
            drawPath(facePath, Color.White.copy(alpha = 0.10f), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun SongPickerDialog(songs: List<Song>, exclude: Set<Long>, p: Palette, onPick: (Song) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, songs, exclude) {
        songs.filter { it.id !in exclude && (q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true)) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).clip(RoundedCornerShape(24.dp)).background(p.card)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Search songs") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.accent, cursorColor = p.accent,
                    focusedTextColor = p.text, unfocusedTextColor = p.text
                ),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(s, Modifier.size(46.dp), 8.dp, p, px = 96)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = p.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
