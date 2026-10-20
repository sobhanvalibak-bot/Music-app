package com.example.musicapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
    onEditInfo: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(start = 20.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song, Modifier.size(56.dp), 10.dp, p, 300)
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
        var menuOpen by remember(song.id) { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Song options", tint = p.secondary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = { menuOpen = false; onPlayNext?.invoke() },
                    enabled = onPlayNext != null
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    onClick = { menuOpen = false; onLong?.invoke() },
                    enabled = onLong != null
                )
                DropdownMenuItem(
                    text = { Text("Edit song info") },
                    onClick = { menuOpen = false; onEditInfo?.invoke() },
                    enabled = onEditInfo != null
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
    onEditInfo: (Song) -> Unit = {}
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DISCOVER YOUR SOUND", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.accent, letterSpacing = 1.5.sp)
                        Text("Discover", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                        Text("${songs.size} tracks · $sortSubtitle", fontSize = 13.sp, color = p.secondary)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(p.selectedPill), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.Note, p.accent, Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                val featured = current ?: songs.firstOrNull()
                if (featured != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                            .background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.30f), p.card, p.card)))
                            .clickable { onPlay(songs.indexOfFirst { it.id == featured.id }.coerceAtLeast(0)) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(featured, Modifier.size(88.dp), 16.dp, p, 400)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (current != null) "NOW PLAYING" else "TODAY’S PICK", fontSize = 10.sp, color = p.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(featured.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text)
                            Text(featured.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = p.secondary)
                            Spacer(Modifier.height(8.dp))
                            Text(if (current != null && current.id == featured.id) "Open player  ›" else "Play track  ›", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                        songs.take(4).chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                pair.forEach { pick ->
                                    Row(
                                        Modifier.weight(1f).height(66.dp)
                                            .clip(RoundedCornerShape(15.dp))
                                            .background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.20f), p.card)))
                                            .clickable { onPlay(songs.indexOfFirst { it.id == pick.id }.coerceAtLeast(0)) }
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
                            Column(Modifier.width(116.dp).clickable { onPlay(songs.indexOfFirst { it.id == recent.id }.coerceAtLeast(0)) }) {
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
                onEditInfo = { onEditInfo(song) }
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
    onEditInfo: (Song) -> Unit = {}
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar(backLabel, p, onBack) }
        item { LargeTitle(title, subtitle, p) }
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
    onToggleFav: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onDelete: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
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
                            .clickable { onPlay(0) }
                            .padding(horizontal = 32.dp, vertical = 11.dp)
                    ) { Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold) }
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
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                playing = current?.id == song.id,
                p = p,
                showDuration = showDuration,
                fav = favSet.contains(song.id),
                onClick = { onPlay(index) },
                onFav = { onToggleFav(song) },
                onRemove = { onRemove(song) }
            )
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
    onPlayFavorites: (List<Song>, Int) -> Unit
) {
    val favSongs = profile.favorites.reversed().mapNotNull { byId[it] }

    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
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
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (profile.bannerVideoPath.isNotBlank()) {
                        LoopingProfileVideo(profile.bannerVideoPath, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)))
                    } else {
                        Box(Modifier.fillMaxWidth().height(110.dp).glass(RoundedCornerShape(18.dp), p), contentAlignment = Alignment.Center) {
                            Text("Profile banner", color = p.secondary)
                        }
                    }
                    // the ring in the page colour cuts the picture out of the banner
                    Box(
                        Modifier
                            .padding(top = if (profile.bannerVideoPath.isNotBlank()) 94.dp else 54.dp)
                            .size(120.dp)
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
                        .clickable { onEdit() }
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                ) { Text("Edit profile", color = p.text, fontWeight = FontWeight.Medium) }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProfileAction("Settings", "Manage media", p, Modifier.weight(1f), onSettings)
                ProfileAction("Favorites", "Your liked tracks", p, Modifier.weight(1f), onFavorites)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                        Text("$n", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text)
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
                        Column(
                            Modifier.width(118.dp).clickable { onPlayFavorites(favSongs, i) }
                        ) {
                            Cover(song, Modifier.size(118.dp), 14.dp, p, 400)
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPlaylist(pl.id) }
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
    onPlay: (List<Song>) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
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
            Column(Modifier.clickable { onPlay(list) }) {
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

@Composable
fun ArtistsTab(
    artists: List<ArtistGroup>,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    onOpen: (ArtistGroup) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { LargeTitle("Artists", "${artists.size} artists", p) }
        items(artists, key = { it.name.lowercase() }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry) }
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
