package com.example.musicapp

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player

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
        if (isPlaying) 1f else 0.88f,
        spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "coverScale"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(p.bgBrush)
            .background(
                Brush.verticalGradient(
                    listOf(p.accent.copy(alpha = if (p.tinted) 0.20f else 0.32f), Color.Transparent)
                )
            )
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
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.ChevronDown, p.text, Modifier.size(24.dp)) }
                Spacer(Modifier.weight(1f))
                Text("Now Playing", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { onEdit() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.Edit, p.text, Modifier.size(20.dp)) }
            }

            Spacer(Modifier.weight(0.5f))

            Cover(
                song,
                Modifier
                    .fillMaxWidth(0.86f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                    },
                28.dp, p, 1600
            )

            Spacer(Modifier.weight(0.5f))

            // Title + artists + actions
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text
                    )
                    FlowRow {
                        song.artists.forEachIndexed { i, name ->
                            Row {
                                Text(
                                    name, fontSize = 17.sp, color = p.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onArtist(name) }
                                        .padding(vertical = 2.dp)
                                )
                                if (i < song.artists.lastIndex) {
                                    Text(", ", fontSize = 17.sp, color = p.secondary, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                    // Album: tap to see every song of this album
                    Row(
                        Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(p.selectedPill)
                            .clickable { showAlbum = true }
                            .padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlyphIcon(Glyph.Album, p.accent, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            song.album, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).clickable { onAddToPlaylist() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.Plus, p.text, Modifier.size(22.dp)) }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).clickable { onFav() },
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon(
                        if (isFav) Glyph.Heart else Glyph.HeartOutline,
                        if (isFav) p.accent else p.text,
                        Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Seek bar
            Slider(
                value = if (dragging) dragValue else frac,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = p.text,
                    activeTrackColor = p.text,
                    inactiveTrackColor = p.secondary.copy(alpha = 0.3f)
                )
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(formatTime(shownMs), fontSize = 12.sp, color = p.secondary)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), fontSize = 12.sp, color = p.secondary)
            }

            Spacer(Modifier.height(14.dp))

            // Controls
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { onShuffle() },
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon(Glyph.Shuffle, if (shuffle) p.accent else p.secondary, Modifier.size(22.dp))
                }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable { onPrev() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.Prev, p.text, Modifier.size(28.dp)) }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(p.text).clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon(if (isPlaying) Glyph.Pause else Glyph.Play, p.bg, Modifier.size(30.dp))
                }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable { onNext() },
                    contentAlignment = Alignment.Center
                ) { GlyphIcon(Glyph.Next, p.text, Modifier.size(28.dp)) }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { onRepeat() },
                    contentAlignment = Alignment.Center
                ) {
                    val on = repeatMode != Player.REPEAT_MODE_OFF
                    GlyphIcon(Glyph.Repeat, if (on) p.accent else p.secondary, Modifier.size(22.dp))
                    if (repeatMode == Player.REPEAT_MODE_ONE) {
                        Text("1", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = p.accent)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Lyrics + playback options (speed, sleep timer, crossfade, equalizer, stats)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.selectedPill)
                        .clickable { showLyrics = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(Glyph.Note, p.text, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lyrics", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.text)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.selectedPill)
                        .clickable { showQueue = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(Glyph.Queue, p.text, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Queue", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.text)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.selectedPill)
                        .clickable { showOptions = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(Glyph.Tune, p.text, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (kotlin.math.abs(speed - 1f) < 0.01f) "Playback" else "${speed}×",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.text
                    )
                }
            }

            Spacer(Modifier.weight(0.5f))
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
            .statusBarsPadding()
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
            .statusBarsPadding()
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
