package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The menu that opens from the three dots of a song and from a long press.
 * An iOS-style bottom sheet: song header on top, grouped rounded actions below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Song,
    fav: Boolean,
    p: Palette,
    onDismiss: () -> Unit,
    onFav: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onEditInfo: (() -> Unit)?,
    onRemove: (() -> Unit)?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // close the sheet with its slide-down animation, then run the action
    fun act(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            action()
            onDismiss()
        }
    }

    class Item(val glyph: Glyph, val label: String, val tint: Color, val run: () -> Unit)

    val items = buildList {
        if (onPlayNext != null) add(Item(Glyph.Next, "Play next", p.accent) { onPlayNext() })
        if (onAddToPlaylist != null) add(Item(Glyph.Plus, "Add to playlist", p.accent) { onAddToPlaylist() })
        add(
            Item(
                if (fav) Glyph.Heart else Glyph.HeartOutline,
                if (fav) "Remove from favorites" else "Add to favorites",
                p.accent
            ) { onFav() }
        )
        if (onEditInfo != null) add(Item(Glyph.Edit, "Edit song info", p.accent) { onEditInfo() })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.bg,
        contentColor = p.text,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 38.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(p.secondary.copy(alpha = 0.45f))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            // Header: cover + title + artist + album
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(song, Modifier.size(68.dp), 14.dp, p, 300)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.text
                    )
                    Text(
                        song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, color = p.accent
                    )
                    Text(
                        song.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, color = p.secondary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Grouped actions
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(p.card)
            ) {
                items.forEachIndexed { i, item ->
                    SheetRow(item.glyph, item.label, item.tint, p) { act(item.run) }
                    if (i < items.lastIndex) SheetDivider(p)
                }
            }

            if (onRemove != null) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.card)
                ) {
                    SheetRow(Glyph.Close, "Remove from this list", Color(0xFFFF453A), p, labelColor = Color(0xFFFF453A)) {
                        act { onRemove() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    glyph: Glyph,
    label: String,
    tint: Color,
    p: Palette,
    labelColor: Color = p.text,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { GlyphIcon(glyph, tint, Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = labelColor)
    }
}

@Composable
private fun SheetDivider(p: Palette) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 62.dp)
            .height(0.5.dp)
            .background(p.secondary.copy(alpha = 0.25f))
    )
}
