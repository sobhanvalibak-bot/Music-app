package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlin.random.Random

// ───────────────────────── Categories ─────────────────────────
//
// Home > "Categories": the library is grouped (random mix, favorites, recently added, every album,
// every artist). Every time the app is opened the categories come in a new random order and the songs
// inside each one are shuffled too (press the dice to shuffle again).
// Inside the category that holds the playing song you get Shuffle and Repeat (album / all / one).
// Tapping a song plays that category as the queue. "Play next" never depends on any of this.

enum class CatKind { Mix, Favorites, Recent, Album, Artist }

class SongCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: CatKind,
    val songs: List<Song>
)

fun buildCategories(songs: List<Song>, favIds: Set<Long>, seed: Long): List<SongCategory> {
    if (songs.isEmpty()) return emptyList()
    val rnd = Random(seed)
    val mix = SongCategory("mix", "Random mix", "A fresh shuffle of your library", CatKind.Mix, songs.shuffled(rnd).take(30))
    val rest = ArrayList<SongCategory>()

    val favs = songs.filter { it.id in favIds }
    if (favs.isNotEmpty()) rest += SongCategory("fav", "Favorites", "${favs.size} songs", CatKind.Favorites, favs.shuffled(rnd))

    val recent = songs.sortedByDescending { it.dateAdded }.take(20)
    if (recent.size >= 2) rest += SongCategory("new", "Recently added", "Newest in your library", CatKind.Recent, recent.shuffled(rnd))

    songs.groupBy { it.albumId }.values
        .filter { it.size >= 2 && it.first().album != "Unknown Album" }
        .forEach { list ->
            val first = list.first()
            rest += SongCategory("album-${first.albumId}", first.album, "Album · ${list.size} songs", CatKind.Album, list.shuffled(rnd))
        }

    val byArtist = LinkedHashMap<String, Pair<String, MutableList<Song>>>()
    songs.forEach { s ->
        s.artists.forEach { name -> byArtist.getOrPut(name.lowercase()) { name to mutableListOf() }.second.add(s) }
    }
    byArtist.values
        .filter { it.second.size >= 3 && it.first != "Unknown Artist" }
        .forEach { (name, list) ->
            rest += SongCategory("artist-${name.lowercase()}", name, "Artist · ${list.size} songs", CatKind.Artist, list.shuffled(rnd))
        }

    // the order of the categories is random too (the random mix stays on top)
    return listOf(mix) + rest.shuffled(rnd).take(14)
}

/** The "All tracks | Categories" switch above the list. */
@Composable
fun CategorySwitch(categoryMode: Boolean, p: Palette, onChange: (Boolean) -> Unit, onReroll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(false to "All tracks", true to "Categories").forEach { (mode, label) ->
            val on = categoryMode == mode
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.card)
                    .clickable { onChange(mode) }.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(label, color = if (on) Color.White else p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        if (categoryMode) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(p.card).clickable { onReroll() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("Shuffle again", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

fun LazyListScope.categoryItems(
    categories: List<SongCategory>,
    current: Song?,
    p: Palette,
    shuffle: Boolean,
    repeatMode: Int,
    onPlay: (SongCategory, Int) -> Unit,
    onShuffle: (SongCategory) -> Unit,
    onRepeat: (SongCategory) -> Unit
) {
    items(categories, key = { "cat-${it.id}" }) { cat ->
        val hasCurrent = current != null && cat.songs.any { it.id == current.id }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cat.title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cat.subtitle, color = p.secondary, fontSize = 12.sp)
                }
            }
            if (hasCurrent) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatChip(if (shuffle) "Shuffle on" else "Shuffle", shuffle, p) { onShuffle(cat) }
                    val base = if (cat.kind == CatKind.Album) "album" else "all"
                    val label = when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Repeat $base on"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat $base"
                    }
                    CatChip(label, repeatMode != Player.REPEAT_MODE_OFF, p) { onRepeat(cat) }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
                items(cat.songs, key = { "cat-${cat.id}-${it.id}" }) { song ->
                    val playing = current?.id == song.id
                    Column(Modifier.width(116.dp).clickable { onPlay(cat, cat.songs.indexOf(song)) }) {
                        Cover(song, Modifier.size(116.dp), 16.dp, p, 300)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            song.title, color = if (playing) p.accent else p.text, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(song.artist, color = p.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatChip(label: String, on: Boolean, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(label, color = if (on) Color.White else p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}
