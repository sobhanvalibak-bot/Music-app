@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.musicapp

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ───────────────────────── Queue model ─────────────────────────

/** One row of the playback queue. [index] is the position in the player's playlist. */
data class QueueEntry(val index: Int, val song: Song)

/** The queue in the order it will actually be played (follows shuffle). */
fun buildQueue(c: Player, byId: Map<Long, Song>): List<QueueEntry> {
    val t = c.currentTimeline
    if (t.isEmpty) return emptyList()
    val shuffled = c.shuffleModeEnabled
    val out = ArrayList<QueueEntry>()
    var i = t.getFirstWindowIndex(shuffled)
    while (i != androidx.media3.common.C.INDEX_UNSET && out.size < t.windowCount) {
        val song = c.getMediaItemAt(i).mediaId.toLongOrNull()?.let { byId[it] }
        if (song != null) out.add(QueueEntry(i, song))
        i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffled)
    }
    return out
}

// ───────────────────────── Full player ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullPlayer(
    song: Song,
    isPlaying: Boolean,
    isFav: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    position: Long,
    p: Palette,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFav: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onArtist: (String) -> Unit,
    albumSongs: List<Song>,
    onPlayAlbumSong: (Int) -> Unit,
    onEdit: () -> Unit,
    settings: Settings,
    speed: Float,
    onSpeed: (Float) -> Unit,
    audioInfo: AudioInfo?,
    queue: List<QueueEntry>,
    currentWindow: Int,
    onPlayQueueItem: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onClearUpcoming: () -> Unit
) {
    val context = LocalContext.current
    var showQueue by remember { mutableStateOf(false) }
    var showAlbum by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    // swiping down must not close the player while one of the panels is open
    val showAlbumNow by rememberUpdatedState(showAlbum || showLyrics || showOptions || showQueue)
    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = showAlbum) { showAlbum = false }
    BackHandler(enabled = showLyrics) { showLyrics = false }
    BackHandler(enabled = showOptions) { showOptions = false }

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = song.durationMs
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragging) (dragValue * duration).toLong() else position

    val coverScale by animateFloatAsState(
        if (isPlaying) 1f else 0.9f,
        spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "coverScale"
    )

    // Spotify-like look: always dark, tinted by the cover colour at the top
    val fg = Color.White
    val sub = Color.White.copy(alpha = 0.66f)
    val topTint = lerp(Color(0xFF0B0B0B), p.accent, if (p.tinted) 0.30f else 0.34f)
    val tintAnim by animateColorAsState(topTint, tween(600), label = "playerTint")

    // synced lyrics for the little lyric line and the "Lyrics preview" card
    var lyricLines by remember(song.id) { mutableStateOf<List<LrcLine>>(emptyList()) }
    LaunchedEffect(song.id) {
        var found = withContext(Dispatchers.IO) { LyricsStore.load(context, song.id) }
        if (found.isEmpty() && LyricsStore.shouldTryEmbedded(song.id)) {
            val text = TagEditor.readLyrics(context, song)
            if (text.isNotBlank()) {
                LyricsStore.saveText(context, song.id, text)
                found = parseLrc(text)
            }
        }
        lyricLines = found
    }
    val lyricSynced = lyricLines.isNotEmpty() && lyricLines.first().timeMs >= 0L
    val activeLyric = if (lyricSynced) lyricLines.indexOfLast { it.timeMs <= position + 120L } else -1
    val currentLine = if (activeLyric >= 0) lyricLines[activeLyric].text else if (!lyricSynced && lyricLines.isNotEmpty()) lyricLines.first().text else ""

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tintAnim, Color(0xFF0E0E0E), Color(0xFF0A0A0A))))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 220f && !showAlbumNow) onClose() },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { _, d -> total += d }
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar: chevron · "Playing from" · more ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerIconButton(Glyph.ChevronDown, fg, 44.dp, 26.dp, onClick = onClose)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showAlbum = true }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Playing from Album", fontSize = 12.sp, color = sub, maxLines = 1)
                    Text(
                        song.album.ifBlank { song.title }, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                PlayerIconButton(Glyph.More, fg, 44.dp, 22.dp, onClick = onEdit)
            }

            // ── Cover: wide, rounded, fits whatever space is left ──
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Cover(
                    song,
                    Modifier
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                            shadowElevation = 28.dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                            clip = false
                            ambientShadowColor = Color.Black
                            spotShadowColor = Color.Black
                        },
                    16.dp, p, 1600
                )
            }

            // ── Current lyric line (like Spotify) ──
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).heightIn(min = 30.dp)) {
                AnimatedContent(
                    targetState = currentLine,
                    transitionSpec = {
                        (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 3 })
                    },
                    label = "lyricLine"
                ) { line ->
                    Text(
                        line, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Title · artists · add / like ──
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 25.sp, fontWeight = FontWeight.Bold, color = fg
                    )
                    FlowRow {
                        song.artists.forEachIndexed { i, name ->
                            Row {
                                Text(
                                    name, fontSize = 18.sp, color = sub,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onArtist(name) }
                                        .padding(vertical = 2.dp)
                                )
                                if (i < song.artists.lastIndex) {
                                    Text(", ", fontSize = 18.sp, color = sub, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
                PlayerIconButton(
                    if (isFav) Glyph.Heart else Glyph.HeartOutline,
                    if (isFav) p.accent else fg, 46.dp, 26.dp, onClick = onFav, bounce = isFav
                )
                PlayerIconButton(Glyph.PlusCircle, fg, 46.dp, 30.dp, onClick = onAddToPlaylist)
            }

            Spacer(Modifier.height(14.dp))

            // ── Seek bar: thin line, small thumb ──
            SpotifySeekBar(
                value = if (dragging) dragValue else frac,
                onChange = { dragging = true; dragValue = it },
                onFinish = { onSeek((dragValue * duration).toLong()); dragging = false },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                Text(formatTime(shownMs), fontSize = 12.sp, color = sub)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), fontSize = 12.sp, color = sub)
            }

            Spacer(Modifier.height(8.dp))

            // ── Controls: shuffle · prev · PLAY · next · repeat ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerIconButton(Glyph.Shuffle, if (shuffle) p.accent else fg, 50.dp, 26.dp, onClick = onShuffle)
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (shuffle) p.accent else Color.Transparent))
                }
                PlayerIconButton(Glyph.Prev, fg, 60.dp, 32.dp, onClick = onPrev)
                PlayerIconButton(
                    if (isPlaying) Glyph.Pause else Glyph.Play, Color.Black, 76.dp, 32.dp,
                    onClick = onToggle, filled = Color.White, pressScale = 0.9f
                )
                PlayerIconButton(Glyph.Next, fg, 60.dp, 32.dp, onClick = onNext)
                val repeatOn = repeatMode != Player.REPEAT_MODE_OFF
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        PlayerIconButton(Glyph.Repeat, if (repeatOn) p.accent else fg, 50.dp, 26.dp, onClick = onRepeat)
                        if (repeatMode == Player.REPEAT_MODE_ONE) {
                            Text("1", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = p.accent)
                        }
                    }
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (repeatOn) p.accent else Color.Transparent))
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Bottom row: playback options (left) · share · queue (right) ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerIconButton(Glyph.Tune, if (kotlin.math.abs(speed - 1f) < 0.01f) fg else p.accent, 46.dp, 24.dp, onClick = { showOptions = true })
                Spacer(Modifier.weight(1f))
                PlayerIconButton(Glyph.Share, fg, 46.dp, 24.dp, onClick = {
                    try {
                        val shareText = "${song.title} — ${song.artist}" + if (song.album.isNotBlank()) "\n${song.album}" else ""
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, song.title)
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(send, "Share song"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Unable to share this song", Toast.LENGTH_SHORT).show()
                    }
                })
                PlayerIconButton(Glyph.Queue, fg, 46.dp, 24.dp, onClick = { showQueue = true })
            }

            Spacer(Modifier.height(8.dp))

            // ── Lyrics preview card ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(lerp(Color(0xFF101010), p.accent, 0.28f))
                    .clickable { showLyrics = true }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Text("Lyrics preview", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
                Spacer(Modifier.height(6.dp))
                val preview = when {
                    lyricLines.isEmpty() -> listOf("Tap to open lyrics")
                    activeLyric >= 0 -> lyricLines.drop(activeLyric).take(2).map { it.text }
                    else -> lyricLines.take(2).map { it.text }
                }
                preview.forEachIndexed { i, l ->
                    Text(
                        l.ifBlank { "♪" }, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = fg.copy(alpha = if (i == 0) 0.95f else 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Album page that slides up over the player
        AnimatedVisibility(
            visible = showAlbum,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            AlbumPanel(
                song = song,
                songs = albumSongs,
                p = p,
                onClose = { showAlbum = false },
                onPlay = onPlayAlbumSong
            )
        }

        AnimatedVisibility(
            visible = showQueue,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            QueuePanel(
                queue = queue,
                currentWindow = currentWindow,
                shuffle = shuffle,
                p = p,
                onClose = { showQueue = false },
                onPlay = onPlayQueueItem,
                onRemove = onRemoveQueueItem,
                onMove = onMoveQueueItem,
                onClearUpcoming = onClearUpcoming
            )
        }

        AnimatedVisibility(
            visible = showLyrics,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            LyricsPanel(
                song = song,
                positionMs = position,
                p = p,
                onClose = { showLyrics = false },
                onSeek = onSeek
            )
        }

        AnimatedVisibility(
            visible = showOptions,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PlaybackOptionsPanel(
                song = song,
                p = p,
                settings = settings,
                speed = speed,
                onSpeed = onSpeed,
                info = audioInfo,
                onClose = { showOptions = false }
            )
        }
    }
}

// ───────────────────────── Player widgets ─────────────────────────

/** Round icon button that shrinks while pressed and springs back (optionally filled, optionally bouncing). */
@Composable
private fun PlayerIconButton(
    glyph: Glyph,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    filled: Color? = null,
    pressScale: Float = 0.82f,
    bounce: Boolean = false
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) pressScale else 1f,
        spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "btnScale"
    )
    // little pop when something turns on (e.g. the heart)
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(bounce) {
        if (bounce) {
            pop.snapTo(1.35f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 420f))
        }
    }
    val onClickS by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale * pop.value; scaleY = scale * pop.value }
            .clip(CircleShape)
            .then(if (filled != null) Modifier.background(filled) else Modifier)
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
    ) { GlyphIcon(glyph, color, Modifier.size(iconSize)) }
}

/** Thin progress line with a small round thumb that grows while dragging, like Spotify. */
@Composable
private fun SpotifySeekBar(
    value: Float,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var active by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "seekGrow")
    val onChangeS by rememberUpdatedState(onChange)
    val onFinishS by rememberUpdatedState(onFinish)
    Box(
        modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onChangeS((off.x / widthPx).coerceIn(0f, 1f))
                    onFinishS()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> active = true; onChangeS((off.x / widthPx).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onChangeS((change.position.x / widthPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = { active = false; onFinishS() },
                    onDragCancel = { active = false; onFinishS() }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackH = 4.dp + 2.dp * grow
        Box(
            Modifier.fillMaxWidth().height(trackH).clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.28f))
        )
        Box(
            Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(trackH).clip(RoundedCornerShape(50))
                .background(Color.White)
        )
        val thumb = 12.dp + 4.dp * grow
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((value.coerceIn(0f, 1f) * widthPx - thumb.toPx() / 2f).toInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun QueuePanel(
    queue: List<QueueEntry>,
    currentWindow: Int,
    shuffle: Boolean,
    p: Palette,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearUpcoming: () -> Unit
) {
    val curPos = queue.indexOfFirst { it.index == currentWindow }
    val upcoming = if (curPos >= 0) queue.size - curPos - 1 else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (curPos > 0) curPos - 1 else 0
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(p.bgBrush)
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { onClose() },
                contentAlignment = Alignment.Center
            ) { GlyphIcon(Glyph.ChevronDown, p.text, Modifier.size(24.dp)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Queue", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text)
                Text(
                    if (shuffle) "${queue.size} songs · Shuffle on" else "${queue.size} songs",
                    fontSize = 12.sp, color = p.secondary
                )
            }
            if (upcoming > 0) {
                Text(
                    "Clear up next", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onClearUpcoming() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("The queue is empty", fontSize = 15.sp, color = p.secondary)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                )
            ) {
                itemsIndexed(queue, key = { _, e -> "${e.index}-${e.song.id}" }) { pos, e ->
                    val playing = e.index == currentWindow
                    val played = curPos >= 0 && pos < curPos
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(e.index) }
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                            .graphicsLayer { alpha = if (played) 0.5f else 1f },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(e.song, Modifier.size(48.dp), 8.dp, p, 200)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                color = if (playing) p.accent else p.text
                            )
                            Text(
                                e.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp, color = p.secondary
                            )
                        }
                        if (playing) {
                            GlyphIcon(Glyph.Play, p.accent, Modifier.padding(end = 12.dp).size(14.dp))
                        } else {
                            if (!shuffle) {
                                // up / down (only while shuffle is off, because then the order is the playlist order)
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape)
                                        .clickable(enabled = pos > 0) { onMove(e.index, queue[pos - 1].index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    GlyphIcon(
                                        Glyph.ChevronDown, p.secondary.copy(alpha = if (pos > 0) 1f else 0.25f),
                                        Modifier.size(18.dp).graphicsLayer { rotationZ = 180f }
                                    )
                                }
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape)
                                        .clickable(enabled = pos < queue.lastIndex) { onMove(e.index, queue[pos + 1].index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    GlyphIcon(
                                        Glyph.ChevronDown, p.secondary.copy(alpha = if (pos < queue.lastIndex) 1f else 0.25f),
                                        Modifier.size(18.dp)
                                    )
                                }
                            }
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).clickable { onRemove(e.index) },
                                contentAlignment = Alignment.Center
                            ) { GlyphIcon(Glyph.Close, p.secondary, Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPanel(
    song: Song,
    songs: List<Song>,
    p: Palette,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit
) {
    val first = songs.firstOrNull() ?: song
    val totalMs = songs.sumOf { it.durationMs }
    Column(
        Modifier
            .fillMaxSize()
            .background(p.bgBrush)
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { onClose() },
                contentAlignment = Alignment.Center
            ) { GlyphIcon(Glyph.ChevronDown, p.text, Modifier.size(24.dp)) }
            Spacer(Modifier.width(6.dp))
            Cover(first, Modifier.size(64.dp), 12.dp, p, 300)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.album, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text
                )
                Text(
                    song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, color = p.accent
                )
                Text(
                    "${songs.size} songs · ${formatTime(totalMs)}",
                    fontSize = 12.sp, color = p.secondary
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            )
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, s ->
                val playing = s.id == song.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(index) }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                        if (playing) {
                            GlyphIcon(Glyph.Play, p.accent, Modifier.size(14.dp))
                        } else {
                            Text("${index + 1}", fontSize = 14.sp, color = p.secondary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            color = if (playing) p.accent else p.text
                        )
                        Text(
                            s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp, color = p.secondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(formatTime(s.durationMs), fontSize = 13.sp, color = p.secondary)
                }
            }
        }
    }
}
