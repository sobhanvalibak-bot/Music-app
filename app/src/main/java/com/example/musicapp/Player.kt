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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
    onClearUpcoming: () -> Unit,
    sleepActive: Boolean = false,
    sleepEndOfSong: Boolean = false,
    sleepLeftMs: Long = 0L,
    onSleepPick: (Int) -> Unit = {},
    /** Online songs: the picture is showing, the player that draws it, and the audio/video switch. */
    hasVideo: Boolean = false,
    videoPlayer: Player? = null,
    onToggleVideo: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showSleep by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var volumeTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(showVolume, volumeTouch) {
        if (showVolume) {
            delay(3000)
            showVolume = false
        }
    }
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
        if (isPlaying) 1f else 0.82f,
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

    val tiltState = rememberDeviceTilt()
    // The cover flies up from the mini player (bottom-left) into place when the player opens.
    var coverIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { coverIn = true }
    val coverFly by animateFloatAsState(
        if (coverIn) 1f else 0f,
        spring(dampingRatio = 0.78f, stiffness = 220f),
        label = "coverFly"
    )

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
        AnimatedCoverBackground(p.accent, Modifier.fillMaxSize())
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
                        .clickable(enabled = !song.isOnline) { showAlbum = true }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(if (song.isOnline) "Playing from YouTube" else "Playing from Album", fontSize = 12.sp, color = sub, maxLines = 1)
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
              if (hasVideo && videoPlayer != null) {
                VideoSurface(
                    videoPlayer,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                )
              } else {
                Cover(
                    song,
                    Modifier
                        .aspectRatio(1f)
                        .graphicsLayer {
                            val fly = (0.28f + 0.72f * coverFly)
                            scaleX = coverScale * fly
                            scaleY = coverScale * fly
                            translationY = (1f - coverFly) * size.height * 1.5f
                            translationX = -(1f - coverFly) * size.width * 0.35f
                            cameraDistance = 14f * density
                            rotationY = tiltState.value.x * 26f
                            rotationX = -tiltState.value.y * 26f
                            // paused: the cover shrinks (above), loses its shadow and dims, like Apple Music
                            val playF = ((coverScale - 0.82f) / 0.18f).coerceIn(0f, 1f)
                            alpha = 0.72f + 0.28f * playF
                            shadowElevation = (10f + 18f * playF).dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                            clip = false
                            ambientShadowColor = Color.Black
                            spotShadowColor = Color.Black
                        },
                    16.dp, p, 1600
                )
              }
                if (onToggleVideo != null) {
                    // online songs: switch between sound only and the video
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { onToggleVideo() }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            if (hasVideo) "Audio only" else "Watch video",
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
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
                // time left, like the iPhone: -2:03
                Text("-" + formatTime((duration - shownMs).coerceAtLeast(0L)), fontSize = 12.sp, color = sub)
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
                PlayerIconButton(Glyph.Moon, if (sleepActive || sleepEndOfSong) p.accent else fg, 46.dp, 22.dp, onClick = { showSleep = true })
                PlayerIconButton(Glyph.Volume, if (showVolume) p.accent else fg, 46.dp, 24.dp, onClick = { showVolume = !showVolume })
                Spacer(Modifier.weight(1f))
                PlayerIconButton(Glyph.Share, fg, 46.dp, 24.dp, onClick = { shareSongCard(context, song) })
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

        // big pill volume slider on the right edge
        AnimatedVisibility(
            visible = showVolume,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            enter = fadeIn(tween(120)) + scaleIn(
                spring(dampingRatio = 0.65f, stiffness = 420f),
                initialScale = 0.7f, transformOrigin = TransformOrigin(1f, 0.5f)
            ),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.8f, transformOrigin = TransformOrigin(1f, 0.5f))
        ) {
            VolumePill(onTouch = { volumeTouch = System.nanoTime() })
        }

        if (showSleep) {
            SleepTimerSheet(
                active = sleepActive,
                endOfSong = sleepEndOfSong,
                leftMs = sleepLeftMs,
                onPick = onSleepPick,
                onDismiss = { showSleep = false }
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
    val ring = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bounce) {
        if (bounce) {
            ring.snapTo(0f)
            ring.animateTo(1f, androidx.compose.animation.core.tween(650))
        }
    }
    val onClickS by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val r = ring.value
                if (r > 0.001f && r < 0.999f) {
                    drawCircle(
                        color = color.copy(alpha = (1f - r) * 0.8f),
                        radius = this.size.minDimension / 2f * (0.7f + 1.1f * r),
                        style = Stroke(width = 3.dp.toPx() * (1f - r) + 1f)
                    )
                }
            }
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
    var curFrac by remember { mutableFloatStateOf(0f) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var scrubSpeed by remember { mutableFloatStateOf(1f) }
    val haptic = LocalHapticFeedback.current
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
                // Fine scrubbing like iOS: the further the finger is from the bar vertically,
                // the slower and more precise the seek becomes (1x, 1/2, 1/4, 1/10).
                detectDragGestures(
                    onDragStart = { off ->
                        active = true
                        curFrac = (off.x / widthPx).coerceIn(0f, 1f)
                        lastX = off.x
                        scrubSpeed = 1f
                        onChangeS(curFrac)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val dist = kotlin.math.abs(change.position.y - size.height / 2f)
                        val speed = when {
                            dist < 70.dp.toPx() -> 1f
                            dist < 140.dp.toPx() -> 0.5f
                            dist < 220.dp.toPx() -> 0.25f
                            else -> 0.1f
                        }
                        if (speed != scrubSpeed) {
                            scrubSpeed = speed
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        curFrac = (curFrac + (change.position.x - lastX) / widthPx * speed).coerceIn(0f, 1f)
                        lastX = change.position.x
                        onChangeS(curFrac)
                    },
                    onDragEnd = { active = false; scrubSpeed = 1f; onFinishS() },
                    onDragCancel = { active = false; scrubSpeed = 1f; onFinishS() }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (active && scrubSpeed < 1f) {
            Text(
                when (scrubSpeed) { 0.5f -> "Half speed"; 0.25f -> "Quarter speed"; else -> "Fine scrubbing" },
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-18).dp)
            )
        }
        val trackH = 4.dp + 6.dp * grow
        Box(
            Modifier.fillMaxWidth().height(trackH).clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.28f))
        )
        Box(
            Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(trackH).clip(RoundedCornerShape(50))
                .background(Color.White)
        )
        val thumb = 12.dp - 4.dp * grow
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
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Order shown on screen. While a song is dragged it changes live; it is saved when the finger lifts.
    var local by remember(queue.map { "${it.index}-${it.song.id}" }) { mutableStateOf(queue) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var startPos by remember { mutableStateOf(-1) }

    val curPos = local.indexOfFirst { it.index == currentWindow }
    val upcoming = if (curPos >= 0) local.size - curPos - 1 else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (curPos > 0) curPos - 1 else 0
    )

    fun finishDrag(commit: Boolean) {
        val key = draggedKey
        if (commit && key != null) {
            val end = local.indexOfFirst { "${it.index}-${it.song.id}" == key }
            if (startPos >= 0 && end >= 0 && end != startPos && startPos < queue.size && end < queue.size) {
                // playlist positions of the song's old and new place
                onMove(queue[startPos].index, queue[end].index)
            }
        } else {
            local = queue
        }
        draggedKey = null
        dragOffset = 0f
        startPos = -1
    }

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
                    if (shuffle) "${queue.size} songs · Shuffle on"
                    else "${queue.size} songs · Hold a song and drag to reorder",
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
                itemsIndexed(local, key = { _, e -> "${e.index}-${e.song.id}" }) { pos, e ->
                    val key = "${e.index}-${e.song.id}"
                    val playing = e.index == currentWindow
                    val played = curPos >= 0 && pos < curPos
                    val dragging = draggedKey == key

                    // hold the song, then move your finger up or down (only while shuffle is off,
                    // because then the order on screen is the real playlist order)
                    val dragMod = if (shuffle) Modifier else Modifier.pointerInput(key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggedKey = key
                                startPos = local.indexOfFirst { "${it.index}-${it.song.id}" == key }
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val info = listState.layoutInfo
                                val cur = info.visibleItemsInfo.firstOrNull { it.key == key }
                                if (cur != null) {
                                    val center = cur.offset + cur.size / 2f + dragOffset
                                    val target = info.visibleItemsInfo.firstOrNull {
                                        it.key != key && center > it.offset && center < it.offset + it.size
                                    }
                                    if (target != null) {
                                        val from = cur.index
                                        val to = target.index
                                        val list = local.toMutableList()
                                        list.add(to, list.removeAt(from))
                                        local = list
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val newOffset = if (to > from) cur.offset + target.size else target.offset
                                        dragOffset += cur.offset - newOffset
                                    }
                                    // scroll when the finger reaches the top or bottom edge
                                    val top = cur.offset + dragOffset
                                    val bottom = top + cur.size
                                    when {
                                        top < info.viewportStartOffset + 40 -> scope.launch { listState.scrollBy(-26f) }
                                        bottom > info.viewportEndOffset - 40 -> scope.launch { listState.scrollBy(26f) }
                                    }
                                }
                            },
                            onDragEnd = { finishDrag(true) },
                            onDragCancel = { finishDrag(false) }
                        )
                    }
                    val lift by animateFloatAsState(
                        if (dragging) 1f else 0f,
                        spring(dampingRatio = 0.6f, stiffness = 500f), label = "queueLift"
                    )
                    Row(
                        (if (dragging) Modifier else Modifier.animateItem())
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else 0f
                                val s = 1f + 0.04f * lift
                                scaleX = s
                                scaleY = s
                                alpha = if (played && !dragging) 0.5f else 1f
                                shadowElevation = 28f * lift
                                shape = RoundedCornerShape(16.dp)
                                clip = false
                            }
                            .background(
                                if (dragging) p.card else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            )
                            .then(dragMod)
                            .clickable { onPlay(e.index) }
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
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
                            // one tap: this song jumps to the top of "up next" and plays right after the current one
                            if (!shuffle && curPos >= 0 && pos > curPos + 1) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(p.accent.copy(alpha = 0.18f))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onMove(e.index, currentWindow + if (e.index > currentWindow) 1 else 0)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Play next", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.accent)
                                }
                                Spacer(Modifier.width(4.dp))
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
