package com.example.musicapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LargeTitle(title: String, subtitle: String, p: Palette) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.text)
        Text(subtitle, fontSize = 14.sp, color = p.secondary)
    }
}

@Composable
fun BackBar(label: String, p: Palette, onBack: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { onBack() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.Back, p.accent, Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = p.accent, fontSize = 17.sp)
    }
}

@Composable
fun CircleButton(glyph: Glyph, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .glass(CircleShape, p)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(glyph, p.text, Modifier.size(22.dp))
    }
}

@Composable
fun Avatar(photo: ImageBitmap?, name: String, p: Palette, modifier: Modifier, textSize: TextUnit = 14.sp) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(p.accent, p.accent.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (name.isNotBlank()) {
            Text(
                name.trim().first().uppercase(),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = textSize
            )
        } else {
            GlyphIcon(Glyph.Artist, Color.White, Modifier.fillMaxSize(0.5f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    playing: Boolean,
    p: Palette,
    showDuration: Boolean,
    fav: Boolean,
    onClick: () -> Unit,
    onFav: () -> Unit,
    onLong: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onEditInfo: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onWatch: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    dragModifier: Modifier? = null
) {
    var sheetOpen by remember(song.id) { mutableStateOf(false) }
    var anchor by remember(song.id) { mutableStateOf<Rect?>(null) }
    val rowCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    // remember where the row is on the screen, so the menu can open right under it
    fun openMenu() {
        val c = rowCoords[0]
        anchor = if (c != null && c.isAttached) {
            val b = c.boundsInWindow()
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            Rect(b.left + loc[0], b.top + loc[1], b.right + loc[0], b.bottom + loc[1])
        } else null
        sheetOpen = true
    }
    SwipeActionBox(
        p = p, enabled = dragModifier == null, fav = fav,
        onFav = onFav, onNext = onPlayNext
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowCoords[0] = it }
            .then(
                if (dragModifier != null) {
                    // long-press + drag reorders the song instead of opening the menu
                    Modifier.clickable { onClick() }.then(dragModifier)
                } else Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openMenu()
                    }
                )
            )
            .padding(start = 20.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp)) {
            Cover(song, Modifier.size(56.dp), 10.dp, p, 300)
            if (playing) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(10.dp * p.corner))
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerBars(Color.White, PlaybackBridge.isPlayingNow, Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = if (playing) p.accent else p.text
            )
            Text(
                song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, color = p.secondary
            )
        }
        if (showDuration) {
            Spacer(Modifier.width(8.dp))
            Text(formatTime(song.durationMs), fontSize = 13.sp, color = p.secondary)
        }
        if (onWatch != null) {
            // online songs: watch the video instead of only listening
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.accent.copy(alpha = 0.16f))
                    .clickable { onWatch() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) { Text("Video", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.accent) }
        }
        if (onDownload != null) {
            // online songs: save the song on the phone (round progress while it downloads)
            val dl = OnlineDownloader.progress[song.id]
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onDownload() },
                contentAlignment = Alignment.Center
            ) {
                when {
                    dl == null -> GlyphIcon(Glyph.Download, p.secondary, Modifier.size(20.dp))
                    dl >= 1f -> Text("\u2713", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.accent)
                    else -> androidx.compose.material3.CircularProgressIndicator(
                        progress = { dl.coerceAtLeast(0.04f) },
                        modifier = Modifier.size(20.dp),
                        color = p.accent,
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { openMenu() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Song options", tint = p.secondary)
            }
            if (sheetOpen) {
                SongActionSheet(
                    song = song,
                    fav = fav,
                    p = p,
                    onDismiss = { sheetOpen = false },
                    onFav = onFav,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onWatch = onWatch,
                    onDownload = onDownload,
                    onAddToPlaylist = onLong,
                    onEditInfo = if (song.isOnline) null else onEditInfo,
                    onRemove = onRemove,
                    anchor = anchor
                )
            }
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { onFav() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(
                if (fav) Glyph.Heart else Glyph.HeartOutline,
                if (fav) p.accent else p.secondary,
                Modifier.size(20.dp)
            )
        }
        if (onRemove != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                GlyphIcon(Glyph.Close, p.secondary, Modifier.size(18.dp))
            }
        }
    }
    }
}

@Composable
fun SongsTab(
    songs: List<Song>,
    recentlyPlayed: List<Song> = emptyList(),
    current: Song?,
    favSet: Set<Long>,
    p: Palette,
    showDuration: Boolean,
    sortSubtitle: String,
    topPad: Dp,
    bottomPad: Dp,
    onPlay: (Int) -> Unit,
    onToggleFav: (Song) -> Unit,
    onLong: (Song) -> Unit,
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: ((Song) -> Unit)? = null,
    onEditInfo: (Song) -> Unit = {},
    showIndex: Boolean = false,
    onOpenSearch: () -> Unit = {}
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pullPx = with(density) { 90.dp.toPx() }
    val fadePx = with(density) { 64.dp.toPx() }
    val pull = rememberPullToSearch(pullPx, onOpenSearch)
    // pulling up past the end of the list opens the search sheet too
    val pullUp = rememberPullUpToSearch(pullPx, onOpenSearch)
    val titleProg by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
        }
    }
    Box(Modifier.fillMaxSize().nestedScroll(pull.connection).nestedScroll(pullUp.connection)) {
    PullSearchPill(pull, pullPx, topPad, p)
    LazyColumn(
        state = listState,
        modifier = Modifier.graphicsLayer { translationY = pull.pull.value - pullUp.pull.value },
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad)
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().staggerIn(0), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greetingNow().uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.accent, letterSpacing = 1.5.sp)
                        Text("Home", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                        Text("${songs.size} tracks · $sortSubtitle", fontSize = 13.sp, color = p.secondary)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(p.selectedPill), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.Note, p.accent, Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                val featured = current ?: songs.firstOrNull()
                if (featured != null) {
                    val isNow = current != null && current.id == featured.id
                    Box(
                        Modifier.fillMaxWidth().height(214.dp)
                            .staggerIn(1)
                            .bounceClick { onPlay(songs.indexOfFirst { it.id == featured.id }.coerceAtLeast(0)) }
                            .clip(RoundedCornerShape(28.dp))
                    ) {
                        // the cover fills the card and drifts a little slower than the list (parallax)
                        Cover(
                            featured,
                            Modifier.fillMaxSize().graphicsLayer {
                                val off = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                                scaleX = 1.15f
                                scaleY = 1.15f
                                translationY = off * 0.18f
                            },
                            0.dp, p, 700
                        )
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)), startY = 120f)
                            )
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 80.dp, bottom = 16.dp)) {
                            Text(
                                if (isNow) "NOW PLAYING" else "TODAY’S PICK",
                                fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
                            )
                            Text(
                                featured.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White
                            )
                            Text(
                                featured.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp)
                                .clip(CircleShape).background(Color.White.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center
                        ) {
                            GlyphIcon(
                                if (isNow && PlaybackBridge.isPlayingNow) Glyph.Pause else Glyph.Play,
                                Color.Black, Modifier.size(22.dp)
                            )
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Quick picks", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text)
                        Text("From your library", fontSize = 12.sp, color = p.secondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    // Magazine-style discovery grid using real local tracks, not fabricated online recommendations.
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        songs.take(4).chunked(2).forEachIndexed { pairIdx, pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                pair.forEachIndexed { k, pick ->
                                    Row(
                                        Modifier.weight(1f).height(66.dp)
                                            .staggerIn(2 + pairIdx * 2 + k)
                                            .bounceClick { onPlay(songs.indexOfFirst { it.id == pick.id }.coerceAtLeast(0)) }
                                            .clip(RoundedCornerShape(15.dp))
                                            .background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.20f), p.card)))
                                            .padding(7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Cover(pick, Modifier.size(50.dp), 10.dp, p, 250)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(pick.title, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(pick.artist, color = p.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (recentlyPlayed.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Recently Played", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text)
                        Text("${recentlyPlayed.size} tracks", fontSize = 12.sp, color = p.secondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
                        items(recentlyPlayed.take(12), key = { "recent-${it.id}" }) { recent ->
                            Column(Modifier.width(116.dp).bounceClick { onPlay(songs.indexOfFirst { it.id == recent.id }.coerceAtLeast(0)) }) {
                                Cover(recent, Modifier.size(116.dp), 16.dp, p, 300)
                                Spacer(Modifier.height(7.dp))
                                Text(recent.title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(recent.artist, color = p.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All tracks" to "${songs.size}", "Favorites" to "${favSet.size}", "Playlists" to "Library").forEach { (label, value) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 10.dp, vertical = 11.dp)) {
                            Text(value, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(label, color = p.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            SectionHeader("All tracks", "", p) { }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                playing = current?.id == song.id,
                p = p,
                showDuration = showDuration,
                fav = favSet.contains(song.id),
                onClick = { onPlay(index) },
                onFav = { onToggleFav(song) },
                onLong = { onLong(song) },
                onPlayNext = { onPlayNext(song) },
                onAddToQueue = onAddToQueue?.let { q -> { q(song) } },
                onEditInfo = { onEditInfo(song) }
            )
        }
        // end of the list: pull up from here to search
        item {
            val armed = pullUp.pull.value >= pullPx
            Column(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmsIcon(
                    SmsIconKind.ChevronUp, if (armed) p.accent else p.secondary,
                    Modifier.size(20.dp).graphicsLayer { translationY = -pullUp.pull.value * 0.12f }
                )
                Text(
                    if (armed) "Release to search" else "Pull up to search",
                    color = if (armed) p.accent else p.secondary, fontSize = 12.sp
                )
            }
        }
    }
    CollapsingBar("Home", titleProg, p, topPad, Modifier.align(Alignment.TopStart))
    if (showIndex && songs.size > 20) {
        AlphabetIndex(
            p = p,
            onLetter = { letter ->
                val idx = songs.indexOfFirst { indexKey(it.title) >= letter }
                    .let { if (it < 0) songs.lastIndex else it }
                scope.launch { listState.scrollToItem(idx + 1) }
            },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.62f).padding(end = 2.dp)
        )
    }
    }
}

@Composable
fun SongListPage(
    title: String,
    subtitle: String,
    songs: List<Song>,
    current: Song?,
    favSet: Set<Long>,
    p: Palette,
    showDuration: Boolean,
    topPad: Dp,
    bottomPad: Dp,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onToggleFav: (Song) -> Unit,
    onLong: (Song) -> Unit,
    onRemove: ((Song) -> Unit)?,
    backLabel: String = "Profile",
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: ((Song) -> Unit)? = null,
    onEditInfo: (Song) -> Unit = {},
    stretchHeader: Boolean = false,
    /** Play / Shuffle buttons under the title. Rows play only the song you tap; these play the whole list. */
    onPlayAll: (() -> Unit)? = null,
    onShuffleAll: (() -> Unit)? = null
) {
    val stretch = rememberStretchState()
    LazyColumn(
        modifier = Modifier.nestedScroll(stretch.connection),
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad)
    ) {
        item { BackBar(backLabel, p, onBack) }
        if (stretchHeader && songs.isNotEmpty()) {
            item {
                val extra = with(LocalDensity.current) { (stretch.pull * 0.5f).toDp() }
                Box(
                    Modifier.fillMaxWidth().height(226.dp + extra).padding(top = 6.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Cover(
                        songs.first(),
                        Modifier.size(210.dp).graphicsLayer {
                            val sc = 1f + stretch.pull / 420f
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        },
                        18.dp, p, 700
                    )
                }
            }
        }
        item { LargeTitle(title, subtitle, p) }
        if (songs.isNotEmpty() && (onPlayAll != null || onShuffleAll != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (onPlayAll != null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(p.accent)
                                .clickable { onPlayAll() }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                    if (onShuffleAll != null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(p.selectedPill)
                                .clickable { onShuffleAll() }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Shuffle", color = p.text, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    "Tap the heart on any song to add it here.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = p.secondary, fontSize = 15.sp
                )
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                playing = current?.id == song.id,
                p = p,
                showDuration = showDuration,
                fav = favSet.contains(song.id),
                onClick = { onPlay(index) },
                onFav = { onToggleFav(song) },
                onLong = { onLong(song) },
                onRemove = onRemove?.let { r -> { r(song) } },
                onPlayNext = { onPlayNext(song) },
                onAddToQueue = onAddToQueue?.let { q -> { q(song) } },
                onEditInfo = { onEditInfo(song) }
            )
        }
    }
}

@Composable
fun PlaylistArt(songs: List<Song>, modifier: Modifier, radius: Dp, p: Palette) {
    if (songs.isNotEmpty()) {
        Cover(songs.first(), modifier, radius, p, 700)
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(radius * p.corner))
                .background(Brush.verticalGradient(listOf(p.accent, p.accent.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(Glyph.Note, Color.White, Modifier.fillMaxSize(0.4f))
        }
    }
}

@Composable
fun PlaylistPage(
    playlist: Playlist,
    songs: List<Song>,
    current: Song?,
    favSet: Set<Long>,
    p: Palette,
    showDuration: Boolean,
    topPad: Dp,
    bottomPad: Dp,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onToggleFav: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onPlayNext: () -> Unit,
    onSongPlayNext: (Song) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val headerItems = 2 // BackBar + header
    // local order used while dragging; saved when the finger lifts
    var local by remember(songs.map { it.id }) { mutableStateOf(songs) }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var startIndex by remember { mutableStateOf(-1) }

    fun finishDrag(commit: Boolean) {
        val id = draggedId
        if (commit && id != null) {
            val end = local.indexOfFirst { it.id == id }
            if (startIndex >= 0 && end >= 0 && end != startIndex) onMove(startIndex, end)
        } else {
            local = songs
        }
        draggedId = null
        dragOffset = 0f
        startIndex = -1
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar("Profile", p, onBack) }
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlaylistArt(songs, Modifier.size(170.dp), 20.dp, p)
                Spacer(Modifier.height(14.dp))
                Text(
                    playlist.name, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Text("${songs.size} songs", fontSize = 14.sp, color = p.secondary)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.accent)
                            .clickable { onPlayAll() }
                            .padding(horizontal = 32.dp, vertical = 11.dp)
                    ) { Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.selectedPill)
                            .clickable { onPlayNext() }
                            .padding(horizontal = 20.dp, vertical = 11.dp)
                    ) { Text("Play next", color = p.text, fontWeight = FontWeight.Medium) }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.selectedPill)
                            .clickable { onDelete() }
                            .padding(horizontal = 24.dp, vertical = 11.dp)
                    ) { Text("Delete", color = p.text, fontWeight = FontWeight.Medium) }
                }
            }
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    "Long-press any song and choose this playlist to add it.",
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    color = p.secondary, fontSize = 15.sp
                )
            }
        }
        itemsIndexed(local, key = { _, s -> s.id }) { index, song ->
            val dragging = draggedId == song.id
            val dragMod = Modifier.pointerInput(song.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        draggedId = song.id
                        startIndex = local.indexOfFirst { it.id == song.id }
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val info = listState.layoutInfo
                        val cur = info.visibleItemsInfo.firstOrNull { it.key == song.id }
                        if (cur != null) {
                            val center = cur.offset + cur.size / 2f + dragOffset
                            val target = info.visibleItemsInfo.firstOrNull {
                                it.key != song.id && it.index >= headerItems &&
                                    center > it.offset && center < it.offset + it.size
                            }
                            if (target != null) {
                                val from = cur.index - headerItems
                                val to = target.index - headerItems
                                val list = local.toMutableList()
                                list.add(to, list.removeAt(from))
                                local = list
                                val newOffset = if (to > from) cur.offset + target.size else target.offset
                                dragOffset += cur.offset - newOffset
                            }
                            // auto-scroll near the edges
                            val top = cur.offset + dragOffset
                            val bottom = top + cur.size
                            when {
                                top < info.viewportStartOffset + 40 -> scope.launch { listState.scrollBy(-24f) }
                                bottom > info.viewportEndOffset - 40 -> scope.launch { listState.scrollBy(24f) }
                            }
                        }
                    },
                    onDragEnd = { finishDrag(true) },
                    onDragCancel = { finishDrag(false) }
                )
            }
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationY = dragOffset
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shadowElevation = 24f
                        }
                    }
                    .background(if (dragging) p.bg else Color.Transparent)
            ) {
                SongRow(
                    song = song,
                    playing = current?.id == song.id,
                    p = p,
                    showDuration = showDuration,
                    fav = favSet.contains(song.id),
                    onClick = { onPlay(index) },
                    onFav = { onToggleFav(song) },
                    onRemove = { onRemove(song) },
                    onPlayNext = { onSongPlayNext(song) },
                    dragModifier = dragMod
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String, p: Palette, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text)
        if (action.isNotBlank()) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) { Text(action, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun ProfileTab(
    profile: ProfileStore,
    byId: Map<Long, Song>,
    totalSongs: Int,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onFavorites: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onPlayFavorites: (List<Song>, Int) -> Unit,
    onPlayList: (List<Song>) -> Unit,
    onPlayNextList: (List<Song>) -> Unit,
    onShuffleList: (List<Song>) -> Unit,
    onSongPlayNext: (Song) -> Unit
) {
    val context = LocalContext.current
    val favSongs = profile.favorites.reversed().mapNotNull { byId[it] }

    val listState = rememberLazyListState()
    val profFadePx = with(LocalDensity.current) { 150.dp.toPx() }
    val barProg by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / profFadePx).coerceIn(0f, 1f)
        }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Profile", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.text)
                CircleButton(Glyph.Tune, p, onSettings)
            }
        }
        item {
            // Banner and profile picture are one block: the picture sits on the bottom edge of the banner.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).staggerIn(0),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (profile.bannerVideoPath.isNotBlank()) {
                        LoopingProfileVideo(profile.bannerVideoPath, Modifier.fillMaxWidth().height(150.dp).scrollParallax(listState, 0.35f).clip(RoundedCornerShape(18.dp)))
                    } else {
                        Box(Modifier.fillMaxWidth().height(110.dp).scrollParallax(listState, 0.35f).glass(RoundedCornerShape(18.dp), p), contentAlignment = Alignment.Center) {
                            Text("Profile banner", color = p.secondary)
                        }
                    }
                    // the ring in the page colour cuts the picture out of the banner
                    Box(
                        Modifier
                            .padding(top = if (profile.bannerVideoPath.isNotBlank()) 94.dp else 54.dp)
                            .size(120.dp)
                            .scrollParallax(listState, 0.35f, 0.4f)
                            .clip(CircleShape)
                            .background(p.bg)
                            .padding(5.dp)
                    ) {
                        if (profile.avatarVideoPath.isNotBlank()) {
                            LoopingProfileVideo(profile.avatarVideoPath, Modifier.fillMaxSize().clip(CircleShape))
                        } else {
                            Avatar(
                                photo = profile.photo,
                                name = profile.name,
                                p = p,
                                modifier = Modifier.fillMaxSize(),
                                textSize = 42.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    profile.name.ifBlank { "Your name" },
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (profile.name.isBlank()) p.secondary else p.text
                )
                if (profile.username.isNotBlank()) {
                    Text("@${profile.username}", fontSize = 15.sp, color = p.secondary)
                }
                Text(
                    profile.bio.ifBlank { "Your music space. Add a bio to tell listeners about yourself." },
                    fontSize = 14.sp, color = p.secondary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.selectedPill)
                        .bounceClick { onEdit() }
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                ) { Text("Edit profile", color = p.text, fontWeight = FontWeight.Medium) }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).staggerIn(1),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProfileAction("Settings", "Manage media", p, Modifier.weight(1f), onSettings)
                ProfileAction("Favorites", "Your liked tracks", p, Modifier.weight(1f), onFavorites)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).staggerIn(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    favSongs.size to "Favorites",
                    profile.playlists.size to "Playlists",
                    totalSongs to "Songs"
                ).forEach { (n, label) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .glass(RoundedCornerShape(16.dp), p)
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${rememberCountUp(n)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text)
                        Text(label, fontSize = 12.sp, color = p.secondary)
                    }
                }
            }
        }

        item { SectionHeader("Favorites", "See all", p, onFavorites) }
        item {
            if (favSongs.isEmpty()) {
                Text(
                    "Tap the heart on any song to save it here.",
                    Modifier.padding(horizontal = 20.dp), color = p.secondary, fontSize = 15.sp
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(favSongs.take(12), key = { _, s -> s.id }) { i, song ->
                        MenuTarget(
                            p = p,
                            onClick = { onPlayFavorites(favSongs, i) },
                            modifier = Modifier.width(118.dp),
                            groups = { close ->
                                listOf(
                                    listOf(
                                        MenuAction("Play next", Glyph.Next) { close { onSongPlayNext(song) } },
                                        MenuAction("Remove favorite", Glyph.Heart) { close { profile.toggleFavorite(song.id) } }
                                    ),
                                    listOf(MenuAction("Share", Glyph.Share) { close { shareSong(context, song) } })
                                )
                            }
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                Cover(song, Modifier.size(106.dp), 14.dp, p, 400)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.text
                                )
                                Text(
                                    song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp, color = p.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Playlists", "New playlist", p, onNewPlaylist) }
        if (profile.playlists.isEmpty()) {
            item {
                Text(
                    "Create a playlist, then long-press any song to add it.",
                    Modifier.padding(horizontal = 20.dp), color = p.secondary, fontSize = 15.sp
                )
            }
        }
        items(profile.playlists, key = { it.id }) { pl ->
            val plSongs = pl.songIds.mapNotNull { byId[it] }
            MenuTarget(
                p = p,
                onClick = { onOpenPlaylist(pl.id) },
                modifier = Modifier.fillMaxWidth(),
                groups = { close ->
                    listOf(
                        listOf(
                            MenuAction("Play", Glyph.Play) { close { onPlayList(plSongs) } },
                            MenuAction("Shuffle", Glyph.Shuffle) { close { onShuffleList(plSongs) } },
                            MenuAction("Play next", Glyph.Next) { close { onPlayNextList(plSongs) } }
                        ),
                        listOf(
                            MenuAction("Delete playlist", Glyph.Close, Color(0xFFFF3B30)) {
                                close { profile.deletePlaylist(pl.id) }
                            }
                        )
                    )
                }
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistArt(plSongs, Modifier.size(56.dp), 10.dp, p)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text
                        )
                        Text("${plSongs.size} songs", fontSize = 14.sp, color = p.secondary)
                    }
                }
            }
        }
    }
    CollapsingBar(profile.name.ifBlank { "Profile" }, barProg, p, topPad, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun ProfileAction(title: String, subtitle: String, p: Palette, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(p.card).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(subtitle, color = p.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── Albums / Artists ─────────────────────────

@Composable
fun AlbumsTab(
    albums: List<List<Song>>,
    p: Palette,
    sortSubtitle: String,
    topPad: Dp,
    bottomPad: Dp,
    onPlayAll: (List<Song>) -> Unit,
    onPlayNext: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    columns: Int = 2,
    onColumns: (Int) -> Unit = {},
    onPlay: (List<Song>) -> Unit
) {
    val gridState = rememberLazyGridState()
    val pinchHaptic = LocalHapticFeedback.current
    val columnsS by rememberUpdatedState(columns)
    val onColumnsS by rememberUpdatedState(onColumns)
    val fadePx = with(LocalDensity.current) { 64.dp.toPx() }
    val titleProg by remember {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex > 0) 1f
            else (gridState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
        }
    }
    val albumOrder = remember(albums) { albums.withIndex().associate { it.value.first().albumId to it.index } }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns.coerceIn(1, 4)),
        modifier = Modifier.pointerInput(Unit) {
            // Pinch with two fingers: spread = bigger covers (fewer columns), pinch = more columns.
            // Events are only watched (never consumed), so normal scrolling keeps working.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var startDist = 0f
                var changed = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val down = event.changes.filter { it.pressed }
                    if (down.size >= 2 && !changed) {
                        val d = (down[0].position - down[1].position).getDistance()
                        if (startDist == 0f) {
                            startDist = d
                        } else {
                            val ratio = d / startDist
                            if (ratio > 1.3f && columnsS > 1) {
                                onColumnsS(columnsS - 1); changed = true
                                pinchHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else if (ratio < 0.75f && columnsS < 4) {
                                onColumnsS(columnsS + 1); changed = true
                                pinchHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad, bottom = bottomPad),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Albums", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.text)
                Text("${albums.size} albums · $sortSubtitle", fontSize = 14.sp, color = p.secondary)
            }
        }
        gridItems(albums, key = { it.first().albumId }) { list ->
            val first = list.first()
            val order = albumOrder[first.albumId] ?: 0
            MenuTarget(
                p = p,
                onClick = { onPlay(list) },
                previewRadius = 20.dp,
                groups = { close ->
                    listOf(
                        listOf(
                            MenuAction("Play", Glyph.Play) { close { onPlayAll(list) } },
                            MenuAction("Shuffle", Glyph.Shuffle) { close { onShuffle(list) } },
                            MenuAction("Play next", Glyph.Next) { close { onPlayNext(list) } }
                        ),
                        listOf(MenuAction("Open album", Glyph.Album) { close { onPlay(list) } })
                    )
                }
            ) {
                Column(Modifier.staggerIn(order, order < 12).padding(6.dp)) {
                    Cover(first, Modifier.fillMaxWidth().aspectRatio(1f), 16.dp, p, 700)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        first.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text
                    )
                    Text(
                        first.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp, color = p.secondary
                    )
                }
            }
        }
    }
    CollapsingBar("Albums", titleProg, p, topPad, Modifier.align(Alignment.TopStart))
    }
}

@Composable
fun ArtistsTab(
    artists: List<ArtistGroup>,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    onPlayAll: (List<Song>) -> Unit,
    onPlayNext: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onOpen: (ArtistGroup) -> Unit
) {
    val listState = rememberLazyListState()
    val fadePx = with(LocalDensity.current) { 64.dp.toPx() }
    val titleProg by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
        }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { LargeTitle("Artists", "${artists.size} artists", p) }
        items(artists, key = { it.name.lowercase() }) { entry ->
            MenuTarget(
                p = p,
                onClick = { onOpen(entry) },
                modifier = Modifier.fillMaxWidth(),
                groups = { close ->
                    listOf(
                        listOf(
                            MenuAction("Play", Glyph.Play) { close { onPlayAll(entry.songs) } },
                            MenuAction("Shuffle", Glyph.Shuffle) { close { onShuffle(entry.songs) } },
                            MenuAction("Play next", Glyph.Next) { close { onPlayNext(entry.songs) } }
                        ),
                        listOf(MenuAction("Open artist", Glyph.Artist) { close { onOpen(entry) } })
                    )
                }
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(entry.songs.first(), Modifier.size(56.dp), 28.dp, p, 300, round = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text
                        )
                        Text("${entry.songs.size} songs", fontSize = 14.sp, color = p.secondary)
                    }
                }
            }
        }
    }
    CollapsingBar("Artists", titleProg, p, topPad, Modifier.align(Alignment.TopStart))
    }
}
