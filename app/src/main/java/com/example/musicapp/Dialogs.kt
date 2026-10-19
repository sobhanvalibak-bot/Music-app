package com.example.musicapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    onDismiss: () -> Unit
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
