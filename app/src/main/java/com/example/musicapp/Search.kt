package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ───────────────────────── Search ─────────────────────────

/** Songs whose title, artist or album contains [query]. Title matches that start with it come first. */
fun searchSongs(songs: List<Song>, query: String): List<Song> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return songs
        .filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
        .sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }
}

/** filter: 0 = everything, 1 = titles, 2 = artists, 3 = albums. */
fun searchSongsBy(songs: List<Song>, query: String, filter: Int): List<Song> {
    if (filter == 0) return searchSongs(songs, query)
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return songs
        .filter {
            when (filter) {
                1 -> it.title.lowercase().contains(q)
                2 -> it.artist.lowercase().contains(q)
                else -> it.album.lowercase().contains(q)
            }
        }
        .sortedBy {
            val field = when (filter) { 1 -> it.title; 2 -> it.artist; else -> it.album }.lowercase()
            if (field.startsWith(q)) 0 else 1
        }
}

private const val RECENT_PREFS = "search_history"

fun loadRecentSearches(context: android.content.Context): List<String> =
    context.getSharedPreferences(RECENT_PREFS, android.content.Context.MODE_PRIVATE)
        .getString("items", "").orEmpty()
        .split('\n').filter { it.isNotBlank() }

fun saveRecentSearch(context: android.content.Context, query: String): List<String> {
    val q = query.trim()
    if (q.length < 2) return loadRecentSearches(context)
    val list = (listOf(q) + loadRecentSearches(context).filter { !it.equals(q, ignoreCase = true) }).take(8)
    context.getSharedPreferences(RECENT_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString("items", list.joinToString("\n")).apply()
    return list
}

fun clearRecentSearches(context: android.content.Context) {
    context.getSharedPreferences(RECENT_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().remove("items").apply()
}

@Composable
fun SearchScreen(
    songs: List<Song>,
    current: Song?,
    favSet: Set<Long>,
    p: Palette,
    showDuration: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    topPad: Dp,
    bottomPad: Dp,
    onClose: () -> Unit,
    /** Plays just this song (second value: with the video, online songs only). Never queues the whole result list. */
    onPlay: (Song, Boolean) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onEditInfo: (Song) -> Unit,
    onToggleFav: (Song) -> Unit,
    onLong: (Song) -> Unit
) {
    val context = LocalContext.current
    var filter by remember { mutableIntStateOf(0) }
    // online search is switched off: the search page only looks at the songs on this device
    val online = false
    var source by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf(loadRecentSearches(context)) }
    val results = remember(query, songs, filter) { searchSongsBy(songs, query, filter) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // ── online search (YouTube): runs a moment after you stop typing, or at once on the keyboard's search key ──
    var onlineResults by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var submitTick by remember { mutableIntStateOf(0) }
    // set when the text in the box is a Spotify link: what that link is (title and artist)
    var spotifyLabel by remember { mutableStateOf<String?>(null) }
    val handledTick = remember { intArrayOf(0) }
    LaunchedEffect(online, source, query, submitTick) {
        if (!online) return@LaunchedEffect
        val q = query.trim()
        if (q.length < 2) {
            onlineResults = emptyList()
            onlineError = null
            onlineLoading = false
            return@LaunchedEffect
        }
        val spotifyId = OnlineEngine.spotifyTrackId(q)
        if (spotifyId == null && submitTick == handledTick[0]) delay(700) else handledTick[0] = submitTick
        onlineLoading = true
        onlineError = null
        try {
            onlineResults = withContext(Dispatchers.IO) {
                if (spotifyId != null) {
                    // Spotify link: find the song by its name and artist in the chosen source
                    val (title, artist) = OnlineEngine.spotifyInfo(spotifyId)
                    spotifyLabel = if (artist.isBlank()) title else "$title — $artist"
                    OnlineEngine.search("$artist $title".trim(), source)
                } else {
                    spotifyLabel = null
                    OnlineEngine.search(q, source)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onlineResults = emptyList()
            AppLog.e("search", "online search failed (${OnlineSources[source]}): $q", e)
            onlineError = if (spotifyId != null) "Could not read this Spotify link. Check your internet connection."
            else "Online search failed. Check your internet connection."
        }
        onlineLoading = false
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bgBrush)
            .pointerInput(Unit) { detectTapGestures { } } // keep touches from reaching the page below
            .imePadding()
    ) {
        Spacer(Modifier.height(topPad))
        Text(
            "Search", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.selectedPill)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphIcon(Glyph.Search, p.secondary, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        recents = saveRecentSearch(context, query)
                        if (online) submitTick++
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    if (online) "Search online or paste a Spotify link" else "Songs, artists, albums",
                                    color = p.secondary, fontSize = 17.sp
                                )
                            }
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).clickable { onQuery("") },
                        contentAlignment = Alignment.Center
                    ) { GlyphIcon(Glyph.Close, p.secondary, Modifier.size(14.dp)) }
                }
            }
            Text(
                "Cancel", color = p.accent, fontSize = 16.sp,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        onClose()
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }

        if (!online) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Songs", "Artists", "Albums").forEachIndexed { i, label ->
                    OptionPill(label, filter == i, p) { filter = i }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(top = 6.dp, bottom = bottomPad)
        ) {
            when {
                query.isBlank() -> {
                    if (recents.isEmpty()) {
                        item {
                            Text(
                                if (online) "Pick a source (YouTube, YT Music, SoundCloud, Bandcamp) and search."
                                else "Search your songs, artists and albums.",
                                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                color = p.secondary, fontSize = 15.sp
                            )
                        }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Recent searches", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.weight(1f))
                                Text(
                                    "Clear", fontSize = 14.sp, color = p.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { clearRecentSearches(context); recents = emptyList() }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                        items(recents.size) { i ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onQuery(recents[i]) }
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlyphIcon(Glyph.Search, p.secondary, Modifier.size(16.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(recents[i], fontSize = 16.sp, color = p.text, maxLines = 1)
                            }
                        }
                    }
                }

                online -> {
                    when {
                        onlineLoading && onlineResults.isEmpty() -> item {
                            Text(
                                "Searching online…",
                                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                color = p.secondary, fontSize = 15.sp
                            )
                        }
                        onlineError != null -> item {
                            Text(
                                onlineError.orEmpty(),
                                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                color = p.secondary, fontSize = 15.sp
                            )
                        }
                        onlineResults.isEmpty() -> item {
                            Text(
                                if (query.trim().length < 2) "Type at least 2 letters."
                                else "No online results for \"${query.trim()}\"",
                                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                color = p.secondary, fontSize = 15.sp
                            )
                        }
                        else -> {
                            item {
                                Text(
                                    (spotifyLabel?.let { "Spotify: $it\n" } ?: "") +
                                        "${onlineResults.size} results from ${OnlineSources[source]} · tap to listen",
                                    fontSize = 13.sp, color = p.secondary,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                            }
                            itemsIndexed(onlineResults, key = { _, t -> t.url }) { _, track ->
                                val song = remember(track.url) { track.toSong() }
                                SongRow(
                                    song = song,
                                    playing = current?.id == song.id,
                                    p = p,
                                    showDuration = showDuration,
                                    fav = favSet.contains(song.id),
                                    onClick = {
                                        recents = saveRecentSearch(context, query)
                                        focusManager.clearFocus()
                                        onPlay(song, false)
                                    },
                                    onFav = { onToggleFav(song) },
                                    onLong = { onLong(song) },
                                    onPlayNext = { onPlayNext(song) },
                                    onAddToQueue = { onAddToQueue(song) },
                                    onDownload = { OnlineDownloader.start(context, song) },
                                    onWatch = if (!song.canWatch) null else ({
                                        recents = saveRecentSearch(context, query)
                                        focusManager.clearFocus()
                                        onPlay(song, true)
                                    })
                                )
                            }
                        }
                    }
                }

                results.isEmpty() -> {
                    item {
                        Text(
                            "No results for \"${query.trim()}\"",
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            color = p.secondary, fontSize = 15.sp
                        )
                    }
                }

                else -> {
                    item {
                        Text(
                            "${results.size} songs", fontSize = 13.sp, color = p.secondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                    itemsIndexed(results, key = { _, s -> s.id }) { _, song ->
                        SongRow(
                            song = song,
                            playing = current?.id == song.id,
                            p = p,
                            showDuration = showDuration,
                            fav = favSet.contains(song.id),
                            onClick = {
                                recents = saveRecentSearch(context, query)
                                focusManager.clearFocus()
                                onPlay(song, false)
                            },
                            onFav = { onToggleFav(song) },
                            onLong = { onLong(song) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToQueue = { onAddToQueue(song) },
                            onEditInfo = { onEditInfo(song) }
                        )
                    }
                }
            }
        }
    }
}
