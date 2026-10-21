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
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withFrameNanos

// ───────────────────────── Mix songs: craft-style cube animation ─────────────────────────
//
// Pick up to 4 songs, tap Mix: the songs fly into a 3D cube, the cube tumbles in every direction,
// stops on the face of every picked song (in random order) and finally on an empty face.
// Then the combined cover (one slice per song) is revealed. ALL picked songs are mixed.

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
private val GLOWS = listOf(Color(0xFFFF6B81), Color(0xFF5CE1E6), Color(0xFFFFD166), Color(0xFFA74FE0), Color(0xFF7BD88F))

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

@Composable
fun MixPage(
    songs: List<Song>,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    onBack: () -> Unit,
    onPlay: (List<Song>) -> Unit,
    onSavePlaylist: (String, List<Song>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val slots = remember { mutableStateListOf<Song?>(null, null, null, null) }
    var picking by remember { mutableIntStateOf(-1) }
    var phase by remember { mutableStateOf(MixPhase.Idle) }
    var picked by remember { mutableStateOf<List<Song>>(emptyList()) }
    var faces by remember { mutableStateOf<List<Song?>>(List(6) { null }) }
    var saved by remember { mutableStateOf(false) }
    var glowColor by remember { mutableStateOf(Color.White) }
    var arena by remember { mutableStateOf(Size.Zero) }

    val ax = remember { Animatable(-18f) }
    val ay = remember { Animatable(-24f) }
    val progress = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    val glowA = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val cubeAlpha = remember { Animatable(1f) }
    val reveal = remember { Animatable(0f) }

    val sparks = remember { mutableListOf<Spark>() }
    var frame by remember { mutableIntStateOf(0) }

    // particle physics, one step per frame
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

    // slow idle spin while nothing is happening
    LaunchedEffect(phase) {
        if (phase == MixPhase.Idle) {
            while (true) ay.animateTo(ay.value + 360f, tween(16000, easing = LinearEasing))
        }
    }

    fun burst(colors: List<Color>, n: Int, speed: Float) {
        if (arena == Size.Zero) return
        repeat(n) { i ->
            val a = Random.nextFloat() * 6.2831f
            val v = speed * (0.4f + Random.nextFloat() * 0.9f)
            sparks.add(Spark(arena.width / 2f, arena.height / 2f, cos(a) * v, sin(a) * v - 120f, colors[i % colors.size], 0.8f + Random.nextFloat() * 0.5f))
        }
        frame++
    }

    suspend fun land(face: Int, ms: Int, easing: Easing) {
        val bx = FBASE[face][0]
        val by = FBASE[face][1]
        // extra full turns on random axes, then land exactly on the face
        fun extra() = if (Random.nextFloat() < 0.8f) (if (Random.nextBoolean()) 1 else -1) * (1 + Random.nextInt(2)) * 360f else 0f
        val tx = bx + ((ax.value - bx) / 360f).roundToInt() * 360f + extra()
        val ty = by + ((ay.value - by) / 360f).roundToInt() * 360f + extra()
        coroutineScope {
            launch { ax.animateTo(tx, tween(ms, easing = easing)) }
            launch { ay.animateTo(ty, tween(ms, easing = easing)) }
        }
    }

    fun edited() {
        if (phase == MixPhase.Done) {
            phase = MixPhase.Idle
            scope.launch { reveal.snapTo(0f); cubeAlpha.snapTo(1f); progress.snapTo(0f) }
        }
    }

    fun startMix() {
        val chosen = slots.filterNotNull()
        if (chosen.size < 2 || phase == MixPhase.Running) return
        scope.launch {
            phase = MixPhase.Running
            saved = false
            val order = chosen.shuffled()            // every picked song is mixed, only the order is random
            picked = order
            val idx = (0..5).shuffled()
            val arr = arrayOfNulls<Song>(6)
            order.forEachIndexed { i, s -> arr[idx[i]] = s }
            faces = arr.toList()
            val empties = idx.drop(order.size)

            reveal.snapTo(0f); cubeAlpha.snapTo(1f); progress.snapTo(0f); fly.snapTo(0f); glowA.snapTo(0f)
            ax.snapTo(-18f); ay.snapTo(-24f)

            val total = 700 + 1500 + order.size * 1450 + 1700 + 400
            launch { progress.animateTo(1f, tween(total, easing = LinearEasing)) }
            launch { fly.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }

            // charge up: shake and glow
            glowColor = p.accent
            launch { glowA.animateTo(0.7f, tween(700)) }
            repeat(8) { i -> shake.animateTo(if (i % 2 == 0) 7f else -7f, tween(45)) }
            shake.animateTo(0f, tween(60))
            delay(280)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

            // wild spin in every direction
            glowColor = Color.White
            launch { glowA.animateTo(0.9f, tween(300)) }
            val spec = tween<Float>(1500, easing = CubicBezierEasing(0.45f, 0f, 0.25f, 1f))
            val tx = ax.value + (if (Random.nextBoolean()) 1 else -1) * (2 + Random.nextInt(2)) * 360f
            val ty = ay.value + (if (Random.nextBoolean()) 1 else -1) * (2 + Random.nextInt(2)) * 360f
            coroutineScope {
                launch { ax.animateTo(tx, spec) }
                launch { ay.animateTo(ty, spec) }
            }

            // stop on the face of every song
            order.forEachIndexed { i, _ ->
                land(idx[i], 1000, CubicBezierEasing(0.5f, 0f, 0.15f, 1f))
                val col = GLOWS[i % GLOWS.size]
                glowColor = col
                glowA.snapTo(0.95f)
                burst(listOf(col, Color.White), 18, 650f)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                launch { pulse.animateTo(1.12f, tween(120)); pulse.animateTo(1f, tween(260)) }
                delay(450)
            }

            // slower last spin, stops on an empty face
            glowColor = Color.White
            launch { glowA.animateTo(0.5f, tween(300)) }
            land(empties.random(), 1700, CubicBezierEasing(0.3f, 0.6f, 0.2f, 1f))
            delay(150)

            // reveal the mixed cover
            burst(GLOWS + Color.White, 64, 1100f)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            launch { cubeAlpha.animateTo(0f, tween(250)) }
            launch { glowA.animateTo(0f, tween(500)) }
            launch { fly.animateTo(0f, tween(550, easing = FastOutSlowInEasing)) }
            phase = MixPhase.Done
            reveal.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
    }

    val count = slots.count { it != null }
    val shown: List<Song?> = if (phase == MixPhase.Idle) listOf(slots[0], slots[1], slots[2], slots[3], null, null) else faces

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = topPad, bottom = bottomPad + 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(p.card).clickable { onBack() },
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

            // the cube
            CubeCanvas(
                faces = shown, p = p, ax = ax, ay = ay, progress = progress, glowA = glowA,
                glowColor = glowColor,
                modifier = Modifier.align(Alignment.Center).size(w * 0.62f).graphicsLayer {
                    alpha = cubeAlpha.value
                    scaleX = pulse.value; scaleY = pulse.value
                    translationX = shake.value
                }
            )

            // mixed cover
            if (phase == MixPhase.Done) {
                Box(
                    Modifier.align(Alignment.Center).size(w * 0.5f).graphicsLayer {
                        val r = reveal.value
                        alpha = (r * 3f).coerceIn(0f, 1f)
                        val sc = 0.2f + 0.8f * r
                        scaleX = sc; scaleY = sc
                        rotationY = (1f - r) * 90f
                        cameraDistance = 12f * density.density
                    }
                ) { MixCover(picked, p, Modifier.fillMaxSize()) }
            }

            // the four slots (fly into the cube while mixing)
            for (i in 0 until 4) {
                val song = slots[i]
                val dx = wPx / 2f - (xs[i] + sPx / 2f)
                val dy = hPx / 2f - (ys[i] + sPx / 2f)
                MixSlot(
                    song = song, p = p,
                    modifier = Modifier
                        .offset { IntOffset(xs[i].roundToInt(), ys[i].roundToInt()) }
                        .size(s)
                        .graphicsLayer {
                            translationX = dx * fly.value
                            translationY = dy * fly.value
                            val sc = 1f - 0.65f * fly.value
                            scaleX = sc; scaleY = sc
                            alpha = if (song != null) 1f - 0.9f * fly.value else 1f
                        },
                    onClick = { if (phase != MixPhase.Running) picking = i },
                    onClear = { if (phase != MixPhase.Running) { slots[i] = null; edited() } }
                )
            }

            // sparks on top of everything
            Canvas(Modifier.fillMaxSize()) {
                val f = frame
                if (f >= 0) sparks.forEach {
                    val l = it.life.coerceIn(0f, 1f)
                    drawCircle(it.color.copy(alpha = l), radius = 4.dp.toPx() * (0.4f + 0.6f * l), center = Offset(it.x, it.y))
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (phase) {
                MixPhase.Idle -> Text(
                    if (count < 2) "Pick 2 to 4 songs to blend together" else "$count songs ready. All of them will be mixed.",
                    color = p.secondary, fontSize = 15.sp, textAlign = TextAlign.Center
                )
                MixPhase.Running -> Text("Mixing…", color = p.secondary, fontSize = 15.sp)
                MixPhase.Done -> {
                    Text(
                        picked.joinToString(" × ") { it.title }, color = p.text, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text("${picked.size}-song mix", color = p.secondary, fontSize = 14.sp)
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
                    MixPill("Play mix", p, filled = false, enabled = true, modifier = Modifier.weight(1f)) { onPlay(picked) }
                    MixPill(
                        if (saved) "Saved ✓" else "Save playlist", p, filled = false, enabled = !saved,
                        modifier = Modifier.weight(1f)
                    ) {
                        onSavePlaylist("Mix: " + picked.joinToString(" × ") { it.title }.take(40), picked)
                        saved = true
                    }
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

/** 3D cube drawn with plain projection: faces carry the song covers, hidden faces are skipped. */
@Composable
private fun CubeCanvas(
    faces: List<Song?>,
    p: Palette,
    ax: Animatable<Float, *>,
    ay: Animatable<Float, *>,
    progress: Animatable<Float, *>,
    glowA: Animatable<Float, *>,
    glowColor: Color,
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

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val h = size.minDimension * 0.2f
        val dist = h * 5.2f
        val rax = ax.value
        val ray = ay.value

        if (glowA.value > 0.01f) {
            val gr = h * 3.4f
            drawCircle(
                Brush.radialGradient(listOf(glowColor.copy(alpha = glowA.value * 0.6f), Color.Transparent), center = Offset(cx, cy), radius = gr),
                radius = gr, center = Offset(cx, cy)
            )
        }
        // progress ring
        val rr = size.minDimension * 0.47f
        val stroke = 5.dp.toPx()
        drawArc(p.secondary.copy(alpha = 0.25f), 0f, 360f, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(stroke))
        drawArc(p.accent, -90f, 360f * progress.value, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))

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
            val path = Path().apply {
                moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close()
            }
            val song = faces.getOrNull(f)
            val bmp = song?.let { bmps[it.id] }
            if (song == null) {
                drawPath(path, p.card)
                val mx = (pts[0] + pts[2] + pts[4] + pts[6]) / 4f
                val my = (pts[1] + pts[3] + pts[5] + pts[7]) / 4f
                val inset = Path().apply {
                    for (k in 0..3) {
                        val x = mx + (pts[k * 2] - mx) * 0.55f
                        val y = my + (pts[k * 2 + 1] - my) * 0.55f
                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(inset, p.secondary.copy(alpha = 0.35f), style = Stroke(2.dp.toPx()))
            } else if (bmp != null) {
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val ab = bmp.asAndroidBitmap()
                    val bw = ab.width.toFloat()
                    val bh = ab.height.toFloat()
                    val m = AMatrix()
                    m.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, pts, 0, 4)
                    nc.save()
                    nc.clipPath(path.asAndroidPath())
                    nc.concat(m)
                    nc.drawBitmap(ab, 0f, 0f, paint)
                    nc.restore()
                }
            } else {
                drawPath(path, Brush.linearGradient(listOf(p.coverTop, p.coverBottom)))
            }
            // shading by angle + soft edge
            drawPath(path, Color.Black.copy(alpha = (1f - n[2]).coerceIn(0f, 1f) * 0.5f))
            drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(1.5.dp.toPx()))
        }
    }
}

private fun slicePolygon(count: Int, i: Int): List<Pair<Float, Float>> = when (count) {
    2 -> if (i == 0) listOf(0f to 0f, .58f to 0f, .42f to 1f, 0f to 1f) else listOf(.58f to 0f, 1f to 0f, 1f to 1f, .42f to 1f)
    3 -> when (i) {
        0 -> listOf(0f to 0f, .40f to 0f, .25f to 1f, 0f to 1f)
        1 -> listOf(.40f to 0f, .75f to 0f, .60f to 1f, .25f to 1f)
        else -> listOf(.75f to 0f, 1f to 0f, 1f to 1f, .60f to 1f)
    }
    else -> when (i) {
        0 -> listOf(0f to 0f, .5f to 0f, .5f to .5f, 0f to .5f)
        1 -> listOf(.5f to 0f, 1f to 0f, 1f to .5f, .5f to .5f)
        2 -> listOf(0f to .5f, .5f to .5f, .5f to 1f, 0f to 1f)
        else -> listOf(.5f to .5f, 1f to .5f, 1f to 1f, .5f to 1f)
    }
}

/** Combined cover: one slice per mixed song (2 diagonal slices, 3 slices or 4 quarters). */
@Composable
fun MixCover(list: List<Song>, p: Palette, modifier: Modifier = Modifier) {
    val n = list.size.coerceIn(1, 4)
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        if (n == 1) {
            Cover(list[0], Modifier.fillMaxSize(), 0.dp, p, px = 400)
        } else {
            list.take(4).forEachIndexed { i, s ->
                val poly = slicePolygon(n, i)
                val shape = GenericShape { size, _ ->
                    poly.forEachIndexed { k, (x, y) ->
                        if (k == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height)
                    }
                    close()
                }
                Cover(s, Modifier.fillMaxSize().clip(shape), 0.dp, p, px = 400)
            }
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
