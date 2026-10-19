package com.example.musicapp

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────── Data ─────────────────────────

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val dateAdded: Long,
    val uri: Uri,
    /** Artist names split on ',', '&' and '_' so every artist gets their own page. */
    val artists: List<String> = splitArtists(artist),
    /** Track number from the file's tag (disc * 1000 + track on some files), 0 if unknown. */
    val track: Int = 0
)

fun splitArtists(raw: String): List<String> =
    raw.split(',', '&', '_')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .ifEmpty { listOf("Unknown Artist") }

class ArtistGroup(val name: String, val songs: List<Song>)

val albumArtBase: Uri = Uri.parse("content://media/external/audio/albumart")

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
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.TRACK
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
        val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
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
                uri = ContentUris.withAppendedId(collection, id),
                track = c.getInt(trackCol)
            )
        }
    }
    return songs
}

val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

// ───────────────────────── Settings ─────────────────────────

val AccentColors: List<Color> = listOf(
    0xFFFA2D48, 0xFFFF9500, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFF64D2FF
).map { Color(it) }

val ThemeLabels = listOf("System", "Light", "Dark", "Cover")
val SortLabels = listOf("Newest", "Oldest", "A–Z")
val SortSubtitles = listOf("Newest first", "Oldest first", "A to Z")

class Settings(private val prefs: SharedPreferences) {
    var theme by mutableIntStateOf(prefs.getInt("theme", 0)); private set
    var accent by mutableIntStateOf(prefs.getInt("accent", 0)); private set
    var sort by mutableIntStateOf(prefs.getInt("sort", 0)); private set
    var glass by mutableFloatStateOf(prefs.getFloat("glass", 0.78f)); private set
    var opaqueClassic by mutableStateOf(prefs.getBoolean("opaque_classic", false)); private set
    var corners by mutableFloatStateOf(prefs.getFloat("corners", 1f)); private set
    var showDuration by mutableStateOf(prefs.getBoolean("duration", true)); private set

    fun changeTheme(v: Int) { theme = v; prefs.edit().putInt("theme", v).apply() }
    fun changeAccent(v: Int) { accent = v; prefs.edit().putInt("accent", v).apply() }
    fun changeSort(v: Int) { sort = v; prefs.edit().putInt("sort", v).apply() }
    fun changeGlass(v: Float) { glass = v; prefs.edit().putFloat("glass", v).apply() }
    fun changeOpaqueClassic(v: Boolean) { opaqueClassic = v; prefs.edit().putBoolean("opaque_classic", v).apply() }
    fun changeCorners(v: Float) { corners = v; prefs.edit().putFloat("corners", v).apply() }
    fun changeShowDuration(v: Boolean) { showDuration = v; prefs.edit().putBoolean("duration", v).apply() }
}

// ───────────────────────── Profile (stored on device) ─────────────────────────

data class Playlist(val id: String, val name: String, val songIds: List<Long>)

class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val photoFile = File(context.filesDir, "profile_photo.jpg")
    private val avatarVideoFile = File(context.filesDir, "profile_avatar.mp4")
    private val bannerVideoFile = File(context.filesDir, "profile_banner.mp4")
    var avatarVideoPath by mutableStateOf(if (avatarVideoFile.exists()) avatarVideoFile.absolutePath else ""); private set
    var bannerVideoPath by mutableStateOf(if (bannerVideoFile.exists()) bannerVideoFile.absolutePath else ""); private set

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

    suspend fun saveVideo(uri: Uri, banner: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > 50L * 1024L * 1024L) return@withContext false
            val dest = if (banner) bannerVideoFile else avatarVideoFile
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { output -> input.copyTo(output) } } ?: return@withContext false
            if (dest.length() > 50L * 1024L * 1024L) { dest.delete(); return@withContext false }
            withContext(Dispatchers.Main) {
                if (banner) bannerVideoPath = dest.absolutePath else avatarVideoPath = dest.absolutePath
            }
            true
        } catch (_: Exception) { false }
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
