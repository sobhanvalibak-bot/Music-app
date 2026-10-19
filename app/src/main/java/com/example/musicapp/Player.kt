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
    onEdit: () -> Unit
) {
    var showAlbum by remember { mutableStateOf(false) }
    val showAlbumNow by rememberUpdatedState(showAlbum)
    BackHandler(enabled = showAlbum) { showAlbum = false }

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

            Spacer(Modifier.weight(0.6f))
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
