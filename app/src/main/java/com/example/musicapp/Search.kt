package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFav: (Song) -> Unit,
    onLong: (Song) -> Unit
) {
    val results = remember(query, songs) { searchSongs(songs, query) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

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
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("Songs, artists, albums", color = p.secondary, fontSize = 17.sp)
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

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(top = 6.dp, bottom = bottomPad)
        ) {
            when {
                query.isBlank() -> item {
                    Text(
                        "Search your songs, artists and albums.",
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        color = p.secondary, fontSize = 15.sp
                    )
                }
                results.isEmpty() -> item {
                    Text(
                        "No results for \"${query.trim()}\"",
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        color = p.secondary, fontSize = 15.sp
                    )
                }
                else -> {
                    item {
                        Text(
                            "${results.size} songs", fontSize = 13.sp, color = p.secondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                    itemsIndexed(results, key = { _, s -> s.id }) { index, song ->
                        SongRow(
                            song = song,
                            playing = current?.id == song.id,
                            p = p,
                            showDuration = showDuration,
                            fav = favSet.contains(song.id),
                            onClick = {
                                focusManager.clearFocus()
                                onPlay(results, index)
                            },
                            onFav = { onToggleFav(song) },
                            onLong = { onLong(song) }
                        )
                    }
                }
            }
        }
    }
}
