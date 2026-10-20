package com.example.musicapp

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DeviceVideo(val id: Long, val uri: Uri, val name: String, val duration: Long, val date: Long)

private suspend fun loadDeviceVideos(context: Context): List<DeviceVideo> = withContext(Dispatchers.IO) {
    val out = mutableListOf<DeviceVideo>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED)
    context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            out += DeviceVideo(id, ContentUris.withAppendedId(collection, id),
                c.getString(nameCol) ?: "Video", c.getLong(durationCol), c.getLong(dateCol))
        }
    }
    out
}

@Composable
fun VideoFeedScreen(p: Palette, mode: Int, topPad: androidx.compose.ui.unit.Dp, bottomPad: androidx.compose.ui.unit.Dp,
                    onIdentify: (String) -> Unit = {}) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<DeviceVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasPermission by remember { mutableStateOf(false) }
    var activeMode by remember(mode) { mutableIntStateOf(mode) }

    val requestedPermissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun mediaPermissionGranted(): Boolean = requestedPermissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = mediaPermissionGranted()
        if (hasPermission) {
            loading = true
        } else {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = mediaPermissionGranted()
        if (hasPermission) {
            videos = loadDeviceVideos(context)
            loading = false
        } else {
            loading = false
            permissionLauncher.launch(requestedPermissions)
        }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loading = true
            videos = loadDeviceVideos(context)
            loading = false
        }
    }
    val shown = videos
    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = topPad)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shorts", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Videos on this device", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            }
            Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .10f)).padding(3.dp)) {
                listOf("Reels", "Posts").forEachIndexed { i, label ->
                    TextButton(onClick = { activeMode = i }, colors = ButtonDefaults.textButtonColors(
                        contentColor = if (activeMode == i) Color.Black else Color.White.copy(alpha = .8f)),
                        modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = if (activeMode == i) Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(horizontal = 7.dp, vertical = 4.dp) else Modifier)
                    }
                }
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
            !hasPermission -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Photos and videos permission is needed to show your device media.", color = Color.White.copy(alpha = .78f))
                    Button(onClick = { permissionLauncher.launch(requestedPermissions) }) { Text("Allow photos and videos") }
                }
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Text("No videos found. Add videos to your phone and reopen this section.", color = Color.White.copy(alpha = .65f))
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = bottomPad + 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(shown, key = { it.id }) { video ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = if (activeMode == 0) 0.dp else 12.dp)) {
                        DeviceVideoCard(video, p)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(video.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatVideoDuration(video.duration), color = Color.White.copy(alpha = .55f), fontSize = 11.sp)
                            }
                            TextButton(onClick = { onIdentify(video.name) }) { Text("Identify music", color = p.accent, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceVideoCard(video: DeviceVideo, p: Palette) {
    val context = LocalContext.current
    val player = remember(video.uri) { ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(video.uri)); repeatMode = Player.REPEAT_MODE_ONE; playWhenReady = false; prepare()
    } }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF151519))) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; controllerAutoShow = true } },
            update = { it.player = player }, modifier = Modifier.fillMaxWidth().aspectRatio(9f / 14f))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("On-device video", color = Color.White.copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { playing = !playing; if (playing) player.play() else player.pause() }) {
                Text(if (playing) "Pause" else "Play", color = p.accent)
            }
        }
    }
}

private fun formatVideoDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
