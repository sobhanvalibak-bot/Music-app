package com.example.musicapp

import android.os.SystemClock
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.getValue
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

/**
 * Lyrics from the device only: a .lrc saved for this song, lyrics found inside the audio file,
 * or a .lrc the user imports. Timed lyrics follow the song (line by line, or word by word when
 * the .lrc has word timing); tap a line to jump there.
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
            listState.animateScrollToItem(active, -(listState.layoutInfo.viewportSize.height / 4))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bgBrush)
            .background(Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.22f), Color.Transparent)))
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { onClose() },
                contentAlignment = Alignment.Center
            ) { GlyphIcon(Glyph.ChevronDown, p.text, Modifier.size(24.dp)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text
                )
                Text(
                    song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp, color = p.secondary
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.selectedPill)
                    .clickable { importer.launch("*/*") }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("Import .lrc", fontSize = 13.sp, color = p.text) }
        }

        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Looking in the song file…"
                    else "No lyrics for this song yet.\n\nImport a .lrc file (line or word timing both work). " +
                        "It is saved on this device and works offline from then on.",
                    color = p.secondary, fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 24.dp, bottom = 320.dp)
            ) {
                items(lines.size) { i ->
                    val line = lines[i]
                    val isActive = i == active
                    val alpha by animateFloatAsState(
                        targetValue = when {
                            !synced -> 0.9f
                            isActive -> 1f
                            i < active -> 0.32f
                            else -> 0.5f
                        },
                        animationSpec = tween(300), label = "lyricAlpha"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isActive || !synced) 1f else 0.93f,
                        animationSpec = tween(300), label = "lyricScale"
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = synced) { onSeek(line.timeMs) }
                            .padding(horizontal = 28.dp, vertical = 10.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                    ) {
                        if (isActive && line.words.isNotEmpty()) {
                            val styled = buildAnnotatedString {
                                for (w in line.words) {
                                    val reached = positionMs >= w.timeMs
                                    withStyle(
                                        SpanStyle(color = if (reached) p.text else p.text.copy(alpha = 0.35f))
                                    ) { append(w.text) }
                                }
                            }
                            Text(styled, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                        } else {
                            Text(
                                line.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                lineHeight = 32.sp, color = p.text.copy(alpha = alpha)
                            )
                        }
                    }
                }
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
                Switch(
                    checked = settings.skipSilence,
                    onCheckedChange = { settings.changeSkipSilence(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = p.accent)
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
