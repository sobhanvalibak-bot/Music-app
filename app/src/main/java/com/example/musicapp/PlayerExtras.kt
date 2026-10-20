@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.musicapp

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.getValue
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ───────────────────────── Small pieces ─────────────────────────

@Composable
fun OptionPill(text: String, selected: Boolean, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) p.accent else p.selectedPill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else p.text
        )
    }
}

private fun speedLabel(s: Float): String =
    if (abs(s - s.toInt()) < 0.001f) "${s.toInt()}×" else "${s}×"

// ───────────────────────── Lyrics ─────────────────────────

private fun shiftHue(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + deg) % 360f
    hsv[1] = hsv[1].coerceAtLeast(0.5f)
    hsv[2] = hsv[2].coerceAtLeast(0.8f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** The line being sung: the part already sung is bright, the rest is dim (karaoke wipe). */
private fun karaokeText(line: LrcLine, nextMs: Long, pos: Long): AnnotatedString {
    val on = Color.White
    val off = Color.White.copy(alpha = 0.38f)
    return buildAnnotatedString {
        if (line.words.isNotEmpty()) {
            for (w in line.words) {
                withStyle(SpanStyle(color = if (pos >= w.timeMs) on else off)) { append(w.text) }
            }
        } else {
            // only line timing: spread the wipe over the length of the line
            val span = ((nextMs - line.timeMs) * 0.85f).toLong().coerceIn(600L, 8000L)
            val frac = ((pos - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
            val n = (line.text.length * frac).toInt().coerceIn(0, line.text.length)
            withStyle(SpanStyle(color = on)) { append(line.text.substring(0, n)) }
            withStyle(SpanStyle(color = off)) { append(line.text.substring(n)) }
        }
    }
}

/**
 * Full-screen lyrics in the Apple Music style: a soft glow in the colours of the cover,
 * big bold left-aligned lines, the current line bright (filling up as it is sung) and
 * the other lines dimmer and more blurred the further they are from it. Tap a line to jump there.
 * Lyrics come from the device only (a saved .lrc, the audio file's tag, or an imported .lrc).
 */
@Composable
fun LyricsPanel(
    song: Song,
    positionMs: Long,
    p: Palette,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var lines by remember(song.id, version) { mutableStateOf<List<LrcLine>>(emptyList()) }
    var loading by remember(song.id, version) { mutableStateOf(true) }

    LaunchedEffect(song.id, version) {
        loading = true
        var found = withContext(Dispatchers.IO) { LyricsStore.load(context, song.id) }
        if (found.isEmpty() && LyricsStore.shouldTryEmbedded(song.id)) {
            val text = TagEditor.readLyrics(context, song)
            if (text.isNotBlank()) {
                LyricsStore.saveText(context, song.id, text)
                found = parseLrc(text)
            }
        }
        lines = found
        loading = false
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (parseLrc(text).isEmpty()) {
                    Toast.makeText(context, "That file has no lyrics in it", Toast.LENGTH_SHORT).show()
                } else {
                    LyricsStore.saveText(context, song.id, text)
                    version++
                }
            }
        }
    }

    val synced = lines.isNotEmpty() && lines.first().timeMs >= 0L
    val active = if (synced) lines.indexOfLast { it.timeMs <= positionMs + 120L } else -1
    val listState = rememberLazyListState()

    LaunchedEffect(active, lines) {
        if (active >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(active, -(listState.layoutInfo.viewportSize.height / 5))
        }
    }

    // colours of the glow: the cover colour plus a neighbour hue, like the Apple Music background
    val glowA = p.accent
    val glowB = remember(p.accent) { shiftHue(p.accent, 45f) }
    val base = remember(p.accent) { lerp(Color.Black, p.accent, 0.10f) }
    val drift by rememberInfiniteTransition(label = "lyricGlow").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "lyricGlowDrift"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.radialGradient(
                        listOf(glowA.copy(alpha = 0.32f), Color.Transparent),
                        center = Offset(w * (0.30f + 0.3f * drift), h * 0.10f), radius = w * 0.95f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(glowA.copy(alpha = 0.80f), Color.Transparent),
                        center = Offset(w * (0.40f + 0.15f * drift), h * 0.66f), radius = w * 0.80f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(glowB.copy(alpha = 0.65f), Color.Transparent),
                        center = Offset(w * (0.80f - 0.2f * drift), h * 0.84f), radius = w * 0.70f
                    )
                )
                // keep the top calm and dark so the title stays readable
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), endY = h * 0.25f))
            }
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)) {
            // header: small cover, title + artist, round "+" (import .lrc)
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.ChevronDown, Color.White, Modifier.size(24.dp)) }
                Spacer(Modifier.width(4.dp))
                Cover(song, Modifier.size(56.dp), 12.dp, p, px = 200)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Text(
                        song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp, color = Color.White.copy(alpha = 0.62f)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                        .clickable { importer.launch("*/*") },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.Plus, Color.White, Modifier.size(18.dp)) }
            }

            if (lines.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (loading) "Looking in the song file…"
                        else "No lyrics for this song yet.\n\nTap + to import a .lrc file (line or word timing both work). " +
                            "It is saved on this device and works offline from then on.",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, lineHeight = 22.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // fade the lines out at the top and bottom edge
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.05f to Color.Black,
                                    0.90f to Color.Black,
                                    1f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    contentPadding = PaddingValues(top = 16.dp, bottom = 280.dp)
                ) {
                    items(lines.size) { i ->
                        val line = lines[i]
                        val isActive = synced && i == active
                        val dist = abs(i - active)
                        val alpha by animateFloatAsState(
                            targetValue = when {
                                !synced -> 0.92f
                                isActive -> 1f
                                dist == 1 -> 0.50f
                                dist == 2 -> 0.40f
                                else -> 0.32f
                            },
                            animationSpec = tween(350), label = "lyricAlpha"
                        )
                        val blurDp by animateDpAsState(
                            targetValue = when {
                                !synced || isActive -> 0.dp
                                dist == 1 -> 1.5.dp
                                dist == 2 -> 3.5.dp
                                else -> 6.dp
                            },
                            animationSpec = tween(350), label = "lyricBlur"
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (isActive || !synced) 1f else 0.96f,
                            animationSpec = tween(350), label = "lyricScale"
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = synced
                                ) { onSeek(line.timeMs) }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                        ) {
                            val textMod = if (blurDp > 0.dp) Modifier.blur(blurDp) else Modifier
                            if (isActive) {
                                val next = if (i + 1 < lines.size) lines[i + 1].timeMs else line.timeMs + 4000L
                                Text(
                                    karaokeText(line, next, positionMs),
                                    fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp,
                                    modifier = textMod
                                )
                            } else {
                                Text(
                                    line.text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                                    lineHeight = 37.sp, color = Color.White.copy(alpha = alpha),
                                    modifier = textMod
                                )
                            }
                        }
                    }
                }
            }
        }

        // thin progress line at the bottom
        val frac = if (song.durationMs > 0) (positionMs.toFloat() / song.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 22.dp)
                .height(10.dp)
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(3.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.25f))
            )
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(3.dp)
                    .clip(CircleShape).background(Color.White)
            )
            Row(Modifier.align(Alignment.CenterStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(frac.coerceAtLeast(0.0001f)))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.0001f)))
            }
        }
    }
}

// ───────────────────────── Playback options ─────────────────────────

private val SpeedChoices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SleepChoices = listOf(15, 30, 45, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackOptionsPanel(
    song: Song,
    p: Palette,
    settings: Settings,
    speed: Float,
    onSpeed: (Float) -> Unit,
    info: AudioInfo?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var bitDepth by remember(song.id) { mutableIntStateOf(0) }
    LaunchedEffect(song.id) { bitDepth = bitDepthOf(context, song.uri) }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val timerMinutesLeft = ((SleepTimer.endAtMs - now) / 60_000L).toInt() + 1
    val timerOn = SleepTimer.endAtMs > now
    val sleepOff = !timerOn && !SleepTimer.afterTrack

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(p.bg)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Playback", Modifier.weight(1f),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text
                )
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.ChevronDown, p.text, Modifier.size(22.dp)) }
            }

            Spacer(Modifier.height(14.dp))
            Text("Speed · ${speedLabel(speed)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedChoices.forEach { s ->
                    OptionPill(speedLabel(s), abs(speed - s) < 0.01f, p) { onSpeed(s) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                when {
                    timerOn -> "Sleep timer · stops in $timerMinutesLeft min"
                    SleepTimer.afterTrack -> "Sleep timer · stops after this song"
                    else -> "Sleep timer"
                },
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPill("Off", sleepOff, p) { SleepTimer.off() }
                val activePreset = SleepChoices.firstOrNull { timerMinutesLeft <= it }
                SleepChoices.forEach { m ->
                    OptionPill("$m min", timerOn && activePreset == m, p) { SleepTimer.inMinutes(m) }
                }
                OptionPill("End of song", SleepTimer.afterTrack, p) { SleepTimer.endOfTrack() }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                if (settings.crossfade == 0) "Crossfade · off" else "Crossfade · ${settings.crossfade} s",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary
            )
            Slider(
                value = settings.crossfade.toFloat(),
                onValueChange = { settings.changeCrossfade(it.toInt()) },
                valueRange = 0f..12f,
                steps = 11,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent)
            )

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skip silence", fontSize = 16.sp, color = p.text)
                    Text("Jumps over silent parts inside a song", fontSize = 12.sp, color = p.secondary)
                }
                IosSwitch(p, 
                    checked = settings.skipSilence,
                    onCheckedChange = { settings.changeSkipSilence(it) }
                )
            }

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.selectedPill)
                    .clickable { openSystemEqualizer(context) }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) { Text("Open equalizer", fontSize = 16.sp, color = p.accent, fontWeight = FontWeight.Medium) }

            Spacer(Modifier.height(18.dp))
            Text("Stats for nerds", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NerdRow("Format", info?.codec ?: "—", p)
                NerdRow(
                    "Sample rate",
                    if (info != null && info.sampleRate > 0) "${info.sampleRate} Hz" else "—", p
                )
                NerdRow("Bit depth", if (bitDepth > 0) "$bitDepth-bit" else "—", p)
                NerdRow(
                    "Channels",
                    if (info != null && info.channels > 0) info.channels.toString() else "—", p
                )
                NerdRow(
                    "Bitrate",
                    if (info != null && info.bitrate > 0) "${info.bitrate / 1000} kbps" else "—", p
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NerdRow(label: String, value: String, p: Palette) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = p.secondary)
        Text(value, fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium)
    }
}
