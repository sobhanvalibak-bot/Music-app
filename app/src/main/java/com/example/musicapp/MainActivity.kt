package com.example.musicapp

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ───────────────────────── Data ─────────────────────────

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val dateAdded: Long,
    val uri: Uri
)

private val albumArtBase: Uri = Uri.parse("content://media/external/audio/albumart")

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(ContentUris.withAppendedId(albumArtBase, albumId))
                .build()
        )
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .build()

fun loadSongs(context: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

    context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "Unknown Artist"
            val album = c.getString(albumCol)?.takeIf { it != "<unknown>" } ?: "Unknown Album"
            songs += Song(
                id = id,
                title = c.getString(titleCol) ?: "Unknown",
                artist = artist,
                album = album,
                albumId = c.getLong(albumIdCol),
                durationMs = c.getLong(durCol),
                dateAdded = c.getLong(addedCol),
                uri = ContentUris.withAppendedId(collection, id)
            )
        }
    }
    return songs
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

// ───────────────────────── Settings ─────────────────────────

val AccentColors: List<Color> = listOf(
    0xFFFA2D48, 0xFFFF9500, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFF64D2FF
).map { Color(it) }

val ThemeLabels = listOf("System", "Light", "Dark")
val SortLabels = listOf("Newest", "Oldest", "A–Z")
private val SortSubtitles = listOf("Newest first", "Oldest first", "A to Z")

class Settings(private val prefs: SharedPreferences) {
    var theme by mutableIntStateOf(prefs.getInt("theme", 0)); private set
    var accent by mutableIntStateOf(prefs.getInt("accent", 0)); private set
    var sort by mutableIntStateOf(prefs.getInt("sort", 0)); private set
    var glass by mutableFloatStateOf(prefs.getFloat("glass", 0.78f)); private set
    var corners by mutableFloatStateOf(prefs.getFloat("corners", 1f)); private set
    var showDuration by mutableStateOf(prefs.getBoolean("duration", true)); private set

    fun changeTheme(v: Int) { theme = v; prefs.edit().putInt("theme", v).apply() }
    fun changeAccent(v: Int) { accent = v; prefs.edit().putInt("accent", v).apply() }
    fun changeSort(v: Int) { sort = v; prefs.edit().putInt("sort", v).apply() }
    fun changeGlass(v: Float) { glass = v; prefs.edit().putFloat("glass", v).apply() }
    fun changeCorners(v: Float) { corners = v; prefs.edit().putFloat("corners", v).apply() }
    fun changeShowDuration(v: Boolean) { showDuration = v; prefs.edit().putBoolean("duration", v).apply() }
}

// ───────────────────────── Profile (stored on device) ─────────────────────────

data class Playlist(val id: String, val name: String, val songIds: List<Long>)

class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val photoFile = File(context.filesDir, "profile_photo.jpg")

    var name by mutableStateOf(prefs.getString("name", "") ?: ""); private set
    var username by mutableStateOf(prefs.getString("username", "") ?: ""); private set
    var bio by mutableStateOf(prefs.getString("bio", "") ?: ""); private set
    var photo by mutableStateOf<ImageBitmap?>(readPhoto()); private set
    val favorites = mutableStateListOf<Long>()
    val playlists = mutableStateListOf<Playlist>()

    init {
        prefs.getString("fav", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.let { favorites.addAll(it) }
        val json = prefs.getString("playlists", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ids = o.getJSONArray("songs")
                    playlists += Playlist(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        songIds = (0 until ids.length()).map { ids.getLong(it) }
                    )
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun readPhoto(): ImageBitmap? =
        try {
            if (photoFile.exists()) BitmapFactory.decodeFile(photoFile.absolutePath)?.asImageBitmap() else null
        } catch (e: Exception) {
            null
        }

    fun updateInfo(newName: String, newUsername: String, newBio: String) {
        name = newName.trim()
        username = newUsername.trim().removePrefix("@")
        bio = newBio.trim()
        prefs.edit().putString("name", name).putString("username", username).putString("bio", bio).apply()
    }

    suspend fun savePhoto(uri: Uri) {
        val result: ImageBitmap? = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext null
                val side = minOf(bmp.width, bmp.height)
                val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val out = Bitmap.createScaledBitmap(square, minOf(side, 512), minOf(side, 512), true)
                FileOutputStream(photoFile).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                out.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        if (result != null) photo = result
    }

    fun removePhoto() {
        photoFile.delete()
        photo = null
    }

    fun toggleFavorite(id: Long) {
        if (favorites.contains(id)) favorites.remove(id) else favorites.add(id)
        prefs.edit().putString("fav", favorites.joinToString(",")).apply()
    }

    private fun savePlaylists() {
        val arr = JSONArray()
        playlists.forEach { pl ->
            arr.put(
                JSONObject()
                    .put("id", pl.id)
                    .put("name", pl.name)
                    .put("songs", JSONArray(pl.songIds))
            )
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun createPlaylist(playlistName: String, firstSongId: Long? = null): Playlist {
        val pl = Playlist(UUID.randomUUID().toString(), playlistName, listOfNotNull(firstSongId))
        playlists.add(pl)
        savePlaylists()
        return pl
    }

    fun addToPlaylist(playlistId: String, songId: Long) {
        val i = playlists.indexOfFirst { it.id == playlistId }
        if (i >= 0 && !playlists[i].songIds.contains(songId)) {
            playlists[i] = playlists[i].copy(songIds = playlists[i].songIds + songId)
            savePlaylists()
        }
    }

    fun removeFromPlaylist(playlistId: String, songId: Long) {
        val i = playlists.indexOfFirst { it.id == playlistId }
        if (i >= 0) {
            playlists[i] = playlists[i].copy(songIds = playlists[i].songIds - songId)
            savePlaylists()
        }
    }

    fun deletePlaylist(playlistId: String) {
        playlists.removeAll { it.id == playlistId }
        savePlaylists()
    }
}

sealed interface Sub {
    data object None : Sub
    data object Settings : Sub
    data object Favorites : Sub
    data class PlaylistPage(val id: String) : Sub
}

// ───────────────────────── Theme / Liquid Glass ─────────────────────────

class Palette(val dark: Boolean, val accent: Color, glass: Float, val corner: Float) {
    val bg = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val card = if (dark) Color(0xFF1C1C1E) else Color.White
    val segment = if (dark) Color(0xFF636366) else Color.White
    val text = if (dark) Color.White else Color.Black
    val secondary = Color(0xFF8E8E93)
    val glassTop = if (dark) Color(0xFF3A3A3C).copy(alpha = glass) else Color.White.copy(alpha = glass)
    val glassBottom = (if (dark) Color(0xFF1C1C1E) else Color(0xFFF7F7FA))
        .copy(alpha = (glass - 0.14f).coerceAtLeast(0.2f))
    val selectedPill = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)
    val coverTop = if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
    val coverBottom = if (dark) Color(0xFF2C2C2E) else Color(0xFFD1D1D6)
}

/** iOS-style "liquid glass": translucent fill, specular rim light, soft shadow. */
fun Modifier.glass(shape: Shape, p: Palette): Modifier =
    this
        .shadow(
            elevation = 18.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.25f),
            spotColor = Color.Black.copy(alpha = 0.25f)
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(p.glassTop, p.glassBottom)))
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (p.dark) 0.45f else 1f),
                    Color.White.copy(alpha = 0.06f),
                    Color.White.copy(alpha = if (p.dark) 0.22f else 0.7f)
                )
            ),
            shape = shape
        )

// ───────────────────────── Cover art ─────────────────────────

private val artCache = LruCache<Long, ImageBitmap>(300)

private suspend fun loadArt(context: Context, song: Song, px: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            val bmp = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
            } else {
                val u = ContentUris.withAppendedId(albumArtBase, song.albumId)
                context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
            }
            bmp?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

@Composable
fun Cover(
    song: Song,
    modifier: Modifier,
    radius: Dp,
    p: Palette,
    px: Int = 300,
    round: Boolean = false
) {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(artCache.get(song.id), song.id) {
        if (value == null) {
            val loaded = loadArt(context, song, px)
            if (loaded != null) artCache.put(song.id, loaded)
            value = loaded
        }
    }
    val shape: Shape = if (round) CircleShape else RoundedCornerShape(radius * p.corner)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.coverTop, p.coverBottom))),
        contentAlignment = Alignment.Center
    ) {
        val img = art
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            GlyphIcon(Glyph.Note, p.secondary.copy(alpha = 0.7f), Modifier.fillMaxSize(0.4f))
        }
    }
}

// ───────────────────────── Icons (drawn, no extra deps) ─────────────────────────

enum class Glyph { Note, Album, Artist, Tune, Play, Pause, Next, Heart, HeartOutline, Back, Close, Plus }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (glyph) {
            Glyph.Note -> {
                val sw = w * 0.09f
                drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.3f, h * 0.78f))
                drawLine(color, Offset(w * 0.46f, h * 0.78f), Offset(w * 0.46f, h * 0.16f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.46f, h * 0.16f), Offset(w * 0.84f, h * 0.30f), sw * 1.4f, StrokeCap.Round)
            }
            Glyph.Album -> {
                val sw = w * 0.08f
                drawRoundRect(
                    color, Offset(w * 0.12f, h * 0.12f), GSize(w * 0.76f, h * 0.76f),
                    CornerRadius(w * 0.16f), style = Stroke(sw)
                )
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
            }
            Glyph.Artist -> {
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, h * 0.3f))
                drawArc(
                    color, 180f, 180f, true,
                    topLeft = Offset(w * 0.14f, h * 0.6f), size = GSize(w * 0.72f, h * 0.8f)
                )
            }
            Glyph.Tune -> {
                val sw = w * 0.08f
                drawLine(color, Offset(w * 0.12f, h * 0.25f), Offset(w * 0.88f, h * 0.25f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.50f), Offset(w * 0.88f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.75f), Offset(w * 0.88f, h * 0.75f), sw, StrokeCap.Round)
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.32f, h * 0.25f))
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.68f, h * 0.50f))
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.40f, h * 0.75f))
            }
            Glyph.Heart, Glyph.HeartOutline -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.88f)
                    cubicTo(w * 0.08f, h * 0.58f, w * 0.0f, h * 0.32f, w * 0.14f, h * 0.2f)
                    cubicTo(w * 0.27f, h * 0.08f, w * 0.45f, h * 0.14f, w * 0.5f, h * 0.3f)
                    cubicTo(w * 0.55f, h * 0.14f, w * 0.73f, h * 0.08f, w * 0.86f, h * 0.2f)
                    cubicTo(w * 1.0f, h * 0.32f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
                    close()
                }
                if (glyph == Glyph.Heart) drawPath(path, color)
                else drawPath(path, color, style = Stroke(w * 0.09f))
            }
            Glyph.Back -> {
                val sw = w * 0.11f
                drawLine(color, Offset(w * 0.65f, h * 0.15f), Offset(w * 0.30f, h * 0.5f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.65f, h * 0.85f), sw, StrokeCap.Round)
            }
            Glyph.Close -> {
                val sw = w * 0.09f
                drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round)
            }
            Glyph.Plus -> {
                val sw = w * 0.1f
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), sw, StrokeCap.Round)
            }
            Glyph.Play -> {
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.08f)
                    lineTo(w * 0.22f, h * 0.92f)
                    lineTo(w * 0.94f, h * 0.5f)
                    close()
                }
                drawPath(path, color)
            }
            Glyph.Pause -> {
                drawRoundRect(color, Offset(w * 0.16f, h * 0.08f), GSize(w * 0.24f, h * 0.84f), CornerRadius(w * 0.06f))
                drawRoundRect(color, Offset(w * 0.60f, h * 0.08f), GSize(w * 0.24f, h * 0.84f), CornerRadius(w * 0.06f))
            }
            Glyph.Next -> {
                val path = Path().apply {
                    moveTo(w * 0.04f, h * 0.12f)
                    lineTo(w * 0.04f, h * 0.88f)
                    lineTo(w * 0.58f, h * 0.5f)
                    close()
                    moveTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.50f, h * 0.88f)
                    lineTo(w * 0.92f, h * 0.5f)
                    close()
                }
                drawPath(path, color)
                drawRoundRect(color, Offset(w * 0.86f, h * 0.12f), GSize(w * 0.10f, h * 0.76f), CornerRadius(w * 0.03f))
            }
        }
    }
}

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = remember {
                Settings(getSharedPreferences("settings", Context.MODE_PRIVATE))
            }
            val dark = when (settings.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val accent = AccentColors[settings.accent.coerceIn(0, AccentColors.lastIndex)]
            val p = remember(dark, accent, settings.glass, settings.corners) {
                Palette(dark, accent, settings.glass, settings.corners)
            }

            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent)
                )
                onDispose { }
            }

            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = p.bg, contentColor = p.text) {
                    MusicApp(p, settings)
                }
            }
        }
    }
}

// ───────────────────────── Screens ─────────────────────────

@Composable
fun MusicApp(p: Palette, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { ProfileStore(context) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) scope.launch { profile.savePhoto(uri) } }

    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf<Sub>(Sub.None) }
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    BackHandler(enabled = tab == 3 && sub != Sub.None) { sub = Sub.None }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlyphIcon(Glyph.Note, p.accent, Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text("Allow access to your music to continue", color = p.text, fontSize = 17.sp)
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.accent)
                    .clickable { launcher.launch(audioPermission) }
                    .padding(horizontal = 36.dp, vertical = 14.dp)
            ) { Text("Allow", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
        return
    }

    val songs = remember { loadSongs(context) }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val sorted = remember(songs, settings.sort) {
        when (settings.sort) {
            1 -> songs.sortedBy { it.dateAdded }
            2 -> songs.sortedBy { it.title.lowercase() }
            else -> songs
        }
    }
    val albums = remember(sorted, settings.sort) {
        val groups = sorted.groupBy { it.albumId }.values
        when (settings.sort) {
            1 -> groups.sortedBy { l -> l.minOf { it.dateAdded } }
            2 -> groups.sortedBy { it.first().album.lowercase() }
            else -> groups.sortedByDescending { l -> l.maxOf { it.dateAdded } }
        }
    }
    val artists = remember(songs) {
        songs.groupBy { it.artist }.entries.sortedBy { it.key.lowercase() }
    }
    val favSet = profile.favorites.toSet()

    // Playback runs in PlaybackService (media session + notification + lock screen).
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var current by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
            } catch (e: Exception) {
                controller = null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    DisposableEffect(controller, byId) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            isPlaying = c.isPlaying
            current = c.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    current = mediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
                }
            }
            c.addListener(listener)
            onDispose { c.removeListener(listener) }
        }
    }

    fun playQueue(queue: List<Song>, index: Int) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        c.setMediaItems(queue.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (current != null) 170.dp else 110.dp)
    val sortSubtitle = SortSubtitles[settings.sort.coerceIn(0, 2)]
    val showDuration = settings.showDuration

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = tab, label = "tabs") { t ->
            if (t == 3) {
                when (val s = sub) {
                    is Sub.None -> ProfileTab(
                        profile = profile,
                        byId = byId,
                        totalSongs = songs.size,
                        p = p,
                        topPad = topPad,
                        bottomPad = bottomPad,
                        onPickPhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onEdit = { showEdit = true },
                        onSettings = { sub = Sub.Settings },
                        onFavorites = { sub = Sub.Favorites },
                        onOpenPlaylist = { sub = Sub.PlaylistPage(it) },
                        onNewPlaylist = { showNewPlaylist = true },
                        onPlayFavorites = { list, i -> playQueue(list, i) }
                    )
                    is Sub.Settings -> SettingsTab(settings, p, topPad, bottomPad) { sub = Sub.None }
                    is Sub.Favorites -> {
                        val favSongs = profile.favorites.reversed().mapNotNull { byId[it] }
                        SongListPage(
                            title = "Favorites",
                            subtitle = "${favSongs.size} songs",
                            songs = favSongs,
                            current = current,
                            favSet = favSet,
                            p = p,
                            showDuration = showDuration,
                            topPad = topPad,
                            bottomPad = bottomPad,
                            onBack = { sub = Sub.None },
                            onPlay = { i -> playQueue(favSongs, i) },
                            onToggleFav = { profile.toggleFavorite(it.id) },
                            onLong = { addTarget = it },
                            onRemove = null
                        )
                    }
                    is Sub.PlaylistPage -> {
                        val pl = profile.playlists.firstOrNull { it.id == s.id }
                        if (pl != null) {
                            val plSongs = pl.songIds.mapNotNull { byId[it] }
                            PlaylistPage(
                                playlist = pl,
                                songs = plSongs,
                                current = current,
                                favSet = favSet,
                                p = p,
                                showDuration = showDuration,
                                topPad = topPad,
                                bottomPad = bottomPad,
                                onBack = { sub = Sub.None },
                                onPlay = { i -> playQueue(plSongs, i) },
                                onToggleFav = { profile.toggleFavorite(it.id) },
                                onRemove = { profile.removeFromPlaylist(pl.id, it.id) },
                                onDelete = {
                                    sub = Sub.None
                                    profile.deletePlaylist(pl.id)
                                }
                            )
                        }
                    }
                }
            } else if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No songs found on this device", color = p.secondary)
                }
            } else when (t) {
                0 -> SongsTab(
                    songs = sorted,
                    current = current,
                    favSet = favSet,
                    p = p,
                    showDuration = showDuration,
                    sortSubtitle = sortSubtitle,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    onPlay = { i -> playQueue(sorted, i) },
                    onToggleFav = { profile.toggleFavorite(it.id) },
                    onLong = { addTarget = it }
                )
                1 -> AlbumsTab(albums, p, sortSubtitle, topPad, bottomPad) { list -> playQueue(list, 0) }
                else -> ArtistsTab(artists, p, topPad, bottomPad) { list -> playQueue(list, 0) }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            current?.let { song ->
                MiniPlayer(
                    song = song,
                    isPlaying = isPlaying,
                    isFav = favSet.contains(song.id),
                    p = p,
                    onToggle = {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onNext = {
                        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
                    },
                    onFav = { profile.toggleFavorite(song.id) }
                )
            }
            GlassTabBar(
                selected = tab,
                onSelect = { sub = Sub.None; tab = it },
                p = p,
                photo = profile.photo,
                profileName = profile.name
            )
        }
    }

    addTarget?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = profile.playlists,
            p = p,
            onPick = { pl ->
                profile.addToPlaylist(pl.id, song.id)
                Toast.makeText(context, "Added to ${pl.name}", Toast.LENGTH_SHORT).show()
                addTarget = null
            },
            onCreate = { name ->
                val pl = profile.createPlaylist(name, song.id)
                Toast.makeText(context, "Added to ${pl.name}", Toast.LENGTH_SHORT).show()
                addTarget = null
            },
            onDismiss = { addTarget = null }
        )
    }
    if (showNewPlaylist) {
        NewPlaylistDialog(
            p = p,
            onCreate = { name -> profile.createPlaylist(name); showNewPlaylist = false },
            onDismiss = { showNewPlaylist = false }
        )
    }
    if (showEdit) {
        EditProfileDialog(
            profile = profile,
            p = p,
            onDismiss = { showEdit = false }
        )
    }
}

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
    onRemove: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(start = 20.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song, Modifier.size(56.dp), 10.dp, p, 200)
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
    current: Song?,
    favSet: Set<Long>,
    p: Palette,
    showDuration: Boolean,
    sortSubtitle: String,
    topPad: Dp,
    bottomPad: Dp,
    onPlay: (Int) -> Unit,
    onToggleFav: (Song) -> Unit,
    onLong: (Song) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { LargeTitle("Songs", "${songs.size} songs · $sortSubtitle", p) }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                playing = current?.id == song.id,
                p = p,
                showDuration = showDuration,
                fav = favSet.contains(song.id),
                onClick = { onPlay(index) },
                onFav = { onToggleFav(song) },
                onLong = { onLong(song) }
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
    onRemove: ((Song) -> Unit)?
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar("Profile", p, onBack) }
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
                onRemove = onRemove?.let { r -> { r(song) } }
            )
        }
    }
}

@Composable
fun PlaylistArt(songs: List<Song>, modifier: Modifier, radius: Dp, p: Palette) {
    if (songs.isNotEmpty()) {
        Cover(songs.first(), modifier, radius, p, 400)
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
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable { onAction() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) { Text(action, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
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
    onPickPhoto: () -> Unit,
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
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(
                    photo = profile.photo,
                    name = profile.name,
                    p = p,
                    modifier = Modifier.size(104.dp).clickable { onPickPhoto() },
                    textSize = 42.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (profile.photo == null) "Add photo" else "Change photo",
                    color = p.accent, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPickPhoto() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    profile.name.ifBlank { "Your name" },
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (profile.name.isBlank()) p.secondary else p.text
                )
                if (profile.username.isNotBlank()) {
                    Text("@${profile.username}", fontSize = 15.sp, color = p.secondary)
                }
                Text(
                    profile.bio.ifBlank { "Tap Edit profile to add a bio." },
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
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
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
                            .clip(RoundedCornerShape(16.dp))
                            .background(p.card)
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
                            Cover(song, Modifier.size(118.dp), 14.dp, p, 300)
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

// ───────────────────────── Settings screen ─────────────────────────

@Composable
fun SettingsCard(title: String, p: Palette, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .padding(16.dp)
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, p: Palette, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.selectedPill)
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) p.segment else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = p.text
                )
            }
        }
    }
}

@Composable
fun SettingsTab(settings: Settings, p: Palette, topPad: Dp, bottomPad: Dp, onBack: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar("Profile", p, onBack) }
        item { LargeTitle("Settings", "Make it yours", p) }

        item {
            SettingsCard("Appearance", p) {
                Segmented(ThemeLabels, settings.theme, p) { settings.changeTheme(it) }
            }
        }
        item {
            SettingsCard("Accent color", p) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AccentColors.forEachIndexed { i, c ->
                        val sel = i == settings.accent
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .clickable { settings.changeAccent(i) }
                                .then(if (sel) Modifier.border(2.dp, c, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(c))
                        }
                    }
                }
            }
        }
        item {
            SettingsCard("Sort songs by", p) {
                Segmented(SortLabels, settings.sort, p) { settings.changeSort(it) }
            }
        }
        item {
            SettingsCard("Liquid glass opacity", p) {
                Slider(
                    value = settings.glass,
                    onValueChange = { settings.changeGlass(it) },
                    valueRange = 0.35f..0.95f,
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent,
                        activeTrackColor = p.accent
                    )
                )
                Text(
                    "${(settings.glass * 100).toInt()}% · lower is more see-through",
                    fontSize = 13.sp, color = p.secondary
                )
            }
        }
        item {
            SettingsCard("Cover corners", p) {
                Slider(
                    value = settings.corners,
                    onValueChange = { settings.changeCorners(it) },
                    valueRange = 0f..1.8f,
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent,
                        activeTrackColor = p.accent
                    )
                )
                Text(
                    if (settings.corners < 0.05f) "Square" else "${(settings.corners * 100).toInt()}% rounded",
                    fontSize = 13.sp, color = p.secondary
                )
            }
        }
        item {
            SettingsCard("Song list", p) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show song duration", Modifier.weight(1f), fontSize = 16.sp, color = p.text)
                    Switch(
                        checked = settings.showDuration,
                        onCheckedChange = { settings.changeShowDuration(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent)
                    )
                }
            }
        }
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
                Cover(first, Modifier.fillMaxWidth().aspectRatio(1f), 16.dp, p, 500)
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
    artists: List<Map.Entry<String, List<Song>>>,
    p: Palette,
    topPad: Dp,
    bottomPad: Dp,
    onPlay: (List<Song>) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { LargeTitle("Artists", "${artists.size} artists", p) }
        items(artists, key = { it.key }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(entry.value) }
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(entry.value.first(), Modifier.size(56.dp), 28.dp, p, 200, round = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.key, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text
                    )
                    Text("${entry.value.size} songs", fontSize = 14.sp, color = p.secondary)
                }
            }
        }
    }
}

// ───────────────────────── Glass bars ─────────────────────────

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isFav: Boolean,
    p: Palette,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onFav: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .glass(shape, p)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song, Modifier.size(46.dp), 12.dp, p, 200)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text
            )
            Text(
                song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp, color = p.secondary
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { onFav() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(
                if (isFav) Glyph.Heart else Glyph.HeartOutline,
                if (isFav) p.accent else p.secondary,
                Modifier.size(20.dp)
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(if (isPlaying) Glyph.Pause else Glyph.Play, p.text, Modifier.size(22.dp))
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { onNext() },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(Glyph.Next, p.text, Modifier.size(24.dp))
        }
    }
}

@Composable
fun GlassTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    p: Palette,
    photo: ImageBitmap?,
    profileName: String
) {
    val tabs = listOf(
        "Songs" to Glyph.Note,
        "Albums" to Glyph.Album,
        "Artists" to Glyph.Artist,
        "Profile" to Glyph.Artist
    )
    val shape = RoundedCornerShape(40.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glass(shape, p)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        tabs.forEachIndexed { i, (label, glyph) ->
            val sel = i == selected
            val tint by animateColorAsState(if (sel) p.accent else p.secondary, label = "tint")
            val pill by animateColorAsState(
                if (sel) p.selectedPill else Color.Transparent, label = "pill"
            )
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(34.dp))
                    .background(pill)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (i == 3) {
                    // The Profile tab shows the user's own photo.
                    Box(
                        Modifier
                            .size(26.dp)
                            .then(if (sel) Modifier.border(2.dp, p.accent, CircleShape) else Modifier)
                            .padding(if (sel) 2.dp else 0.dp)
                    ) {
                        Avatar(photo, profileName, p, Modifier.fillMaxSize(), 12.sp)
                    }
                } else {
                    GlyphIcon(glyph, tint, Modifier.size(26.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
