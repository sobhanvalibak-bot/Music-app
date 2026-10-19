package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────── Dialogs ─────────────────────────

@Composable
private fun fieldColors(p: Palette) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = p.accent,
    cursorColor = p.accent,
    focusedLabelColor = p.accent,
    focusedTextColor = p.text,
    unfocusedTextColor = p.text
)

@Composable
fun NewPlaylistDialog(p: Palette, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        titleContentColor = p.text,
        textContentColor = p.text,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true, colors = fieldColors(p)
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) {
                Text("Create", color = p.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.secondary) } }
    )
}

@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<Playlist>,
    p: Palette,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        titleContentColor = p.text,
        textContentColor = p.text,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(song.title, color = p.secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (onEdit != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.selectedPill)
                            .clickable { onEdit() }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlyphIcon(Glyph.Edit, p.accent, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Edit info", color = p.text, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                playlists.forEach { pl ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(pl) }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    ) {
                        Text(pl.name, Modifier.weight(1f), color = p.text, fontSize = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${pl.songIds.size}", color = p.secondary, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("New playlist") }, singleLine = true, colors = fieldColors(p)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) {
                Text("Create and add", color = p.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.secondary) } }
    )
}

@Composable
fun EditProfileDialog(profile: ProfileStore, p: Palette, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var username by remember { mutableStateOf(profile.username) }
    var bio by remember { mutableStateOf(profile.bio) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        titleContentColor = p.text,
        textContentColor = p.text,
        title = { Text("Edit profile") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, colors = fieldColors(p)
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true, colors = fieldColors(p)
                )
                OutlinedTextField(
                    value = bio, onValueChange = { bio = it },
                    label = { Text("Bio") }, maxLines = 3, colors = fieldColors(p)
                )
                if (profile.photo != null) {
                    TextButton(onClick = { profile.removePhoto() }) {
                        Text("Remove photo", color = p.secondary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                profile.updateInfo(name, username, bio)
                onDismiss()
            }) { Text("Save", color = p.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.secondary) } }
    )
}

/**
 * Edit the real tags of a song. What you save is written into the audio file itself
 * (so other music apps and file managers see it too), not just stored in this app.
 */
@Composable
fun EditSongDialog(
    song: Song,
    p: Palette,
    saving: Boolean,
    onSave: (TagEdit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var loaded by remember(song.id) { mutableStateOf(false) }
    var title by remember(song.id) { mutableStateOf(song.title) }
    var artist by remember(song.id) { mutableStateOf("") }
    var album by remember(song.id) { mutableStateOf("") }
    var genre by remember(song.id) { mutableStateOf("") }
    var year by remember(song.id) { mutableStateOf("") }
    var track by remember(song.id) { mutableStateOf("") }

    LaunchedEffect(song.id) {
        val t = TagEditor.readTags(context, song)
        title = t.title
        artist = t.artist
        album = t.album
        genre = t.genre
        year = t.year
        track = t.track
        loaded = true
    }

    val enabled = loaded && !saving
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = p.card,
        titleContentColor = p.text,
        textContentColor = p.text,
        title = { Text("Edit info") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (loaded) "Saved into the audio file itself, so every music app sees the change."
                    else "Reading the file…",
                    color = p.secondary, fontSize = 13.sp
                )
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, enabled = enabled,
                    label = { Text("Title") }, singleLine = true, colors = fieldColors(p)
                )
                OutlinedTextField(
                    value = artist, onValueChange = { artist = it }, enabled = enabled,
                    label = { Text("Artist") }, singleLine = true, colors = fieldColors(p)
                )
                OutlinedTextField(
                    value = album, onValueChange = { album = it }, enabled = enabled,
                    label = { Text("Album") }, singleLine = true, colors = fieldColors(p)
                )
                OutlinedTextField(
                    value = genre, onValueChange = { genre = it }, enabled = enabled,
                    label = { Text("Genre") }, singleLine = true, colors = fieldColors(p)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                        enabled = enabled, modifier = Modifier.weight(1f),
                        label = { Text("Year") }, singleLine = true, colors = fieldColors(p),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = track, onValueChange = { track = it.filter(Char::isDigit).take(3) },
                        enabled = enabled, modifier = Modifier.weight(1f),
                        label = { Text("Track") }, singleLine = true, colors = fieldColors(p),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && title.isNotBlank(),
                onClick = { onSave(TagEdit(title, artist, album, genre, year, track)) }
            ) { Text(if (saving) "Saving…" else "Save", color = p.accent) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel", color = p.secondary) }
        }
    )
}
