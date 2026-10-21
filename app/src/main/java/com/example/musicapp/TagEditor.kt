package com.example.musicapp

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.IOException

// ───────────────────────── Edit song info inside the audio file ─────────────────────────

/** The text tags of a song that can be edited. Empty text removes that tag from the file. */
data class TagEdit(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val year: String,
    val track: String
)

sealed interface SaveResult {
    data object Ok : SaveResult

    /** Android 10/11+: the user has to allow this app to modify the file (system dialog). */
    class NeedsGrant(val sender: IntentSender) : SaveResult

    /** Android 9 and older: needs the storage write permission. */
    data object NeedsStoragePermission : SaveResult

    /** Android 11+: "All files access" lets the tags be written straight into the song in its own folder. */
    class NeedsAllFilesAccess(val intent: Intent) : SaveResult

    class Error(val message: String) : SaveResult
}

/**
 * Reads and writes the real tags (ID3 for mp3, MP4 atoms for m4a, Vorbis comments for flac/ogg...)
 * with jaudiotagger. The library needs a normal file: whenever Android lets the app write to the song
 * where it lives (storage permission on Android 9-, "All files access" on Android 11+), the song is edited
 * in place in its own folder. Only when that is not allowed, a copy in the app cache is edited and
 * copied back over the original through MediaStore.
 */
object TagEditor {

    private fun prepareLibrary() {
        // The Android build of jaudiotagger has a switch that avoids java.awt classes.
        try {
            val instance = TagOptionSingleton.getInstance()
            instance.javaClass
                .getMethod("setAndroid", java.lang.Boolean.TYPE)
                .invoke(instance, true)
        } catch (t: Throwable) {
        }
    }

    /** Display name (for the file extension) and the real path (for re-scanning). */
    private fun fileInfo(context: Context, song: Song): Pair<String, String?> {
        var name = ""
        var path: String? = null
        try {
            context.contentResolver.query(
                song.uri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: ""
                    path = c.getString(1)
                }
            }
        } catch (e: Exception) {
        }
        return name to path
    }

    private fun extensionOf(name: String, path: String?): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotEmpty()) return fromName
        val fromPath = path?.substringAfterLast('.', "") ?: ""
        return fromPath.ifEmpty { "mp3" }
    }

    private fun copyToCache(context: Context, song: Song, ext: String): File {
        val file = File(context.cacheDir, "tag_edit_${song.id}.$ext")
        val input = context.contentResolver.openInputStream(song.uri)
            ?: throw IOException("Cannot open the song file")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        return file
    }

    /** Current tags of the file. Falls back to what MediaStore knows if the file cannot be read. */
    suspend fun readTags(context: Context, song: Song): TagEdit = withContext(Dispatchers.IO) {
        var cache: File? = null
        try {
            prepareLibrary()
            val (name, path) = fileInfo(context, song)
            val file = copyToCache(context, song, extensionOf(name, path))
            cache = file
            val tag: Tag? = AudioFileIO.read(file).tag
            fun value(key: FieldKey): String =
                try {
                    tag?.getFirst(key) ?: ""
                } catch (e: Exception) {
                    ""
                }
            TagEdit(
                title = value(FieldKey.TITLE).ifBlank { song.title },
                artist = value(FieldKey.ARTIST),
                album = value(FieldKey.ALBUM),
                genre = value(FieldKey.GENRE),
                year = value(FieldKey.YEAR),
                track = value(FieldKey.TRACK)
            )
        } catch (t: Throwable) {
            TagEdit(
                title = song.title,
                artist = song.artist.takeUnless { it == "Unknown Artist" } ?: "",
                album = song.album.takeUnless { it == "Unknown Album" } ?: "",
                genre = "",
                year = "",
                track = if (song.track > 0) (song.track % 1000).toString() else ""
            )
        } finally {
            cache?.delete()
        }
    }

    /** Lyrics stored inside the audio file (ID3 USLT / Vorbis LYRICS...), or "" when there are none. */
    suspend fun readLyrics(context: Context, song: Song): String = withContext(Dispatchers.IO) {
        var cache: File? = null
        try {
            prepareLibrary()
            val (name, path) = fileInfo(context, song)
            val file = copyToCache(context, song, extensionOf(name, path))
            cache = file
            val tag: Tag? = AudioFileIO.read(file).tag
            try {
                tag?.getFirst(FieldKey.LYRICS) ?: ""
            } catch (e: Exception) {
                ""
            }
        } catch (t: Throwable) {
            ""
        } finally {
            cache?.delete()
        }
    }

    /** The song's real file when this app may write to it directly, else null. */
    private fun directFile(context: Context, path: String?): File? {
        if (path == null) return null
        val f = File(path)
        if (!f.isFile) return null
        val allowed = when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= 29 -> false
            else -> ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return if (allowed && f.canWrite()) f else null
    }

    /** Writes [edit] into the audio file itself and refreshes MediaStore. */
    suspend fun save(context: Context, song: Song, edit: TagEdit): SaveResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val (name, path) = fileInfo(context, song)
            val direct = directFile(context, path)

            if (direct == null) {
                // Android 11+: ask once for "All files access", so the song can be edited right in its folder.
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    if (!prefs.getBoolean("allfiles_asked", false)) {
                        prefs.edit().putBoolean("allfiles_asked", true).apply()
                        return@withContext SaveResult.NeedsAllFilesAccess(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
                // Not allowed to touch the file directly: ask for permission to modify this one file.
                if (Build.VERSION.SDK_INT >= 30) {
                    val granted = context.checkUriPermission(
                        song.uri, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        return@withContext SaveResult.NeedsGrant(
                            MediaStore.createWriteRequest(resolver, listOf(song.uri)).intentSender
                        )
                    }
                } else if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@withContext SaveResult.NeedsStoragePermission
                }
            }

            var cache: File? = null
            try {
                prepareLibrary()
                // in place when allowed; otherwise a copy in the app cache
                val file = direct ?: copyToCache(context, song, extensionOf(name, path)).also { cache = it }

                val audio = AudioFileIO.read(file)
                val tag = audio.tagOrCreateAndSetDefault
                fun put(key: FieldKey, raw: String) {
                    val v = raw.trim()
                    if (v.isEmpty()) {
                        try {
                            tag.deleteField(key)
                        } catch (e: Exception) {
                        }
                    } else {
                        tag.setField(key, v)
                    }
                }
                put(FieldKey.TITLE, edit.title)
                put(FieldKey.ARTIST, edit.artist)
                put(FieldKey.ALBUM, edit.album)
                put(FieldKey.GENRE, edit.genre)
                put(FieldKey.YEAR, edit.year)
                put(FieldKey.TRACK, edit.track)
                audio.commit()

                if (direct == null) {
                    // copy the edited file back over the original
                    val out = resolver.openOutputStream(song.uri, "wt")
                        ?: return@withContext SaveResult.Error("Cannot open the file for writing")
                    out.use { o -> file.inputStream().use { i -> i.copyTo(o) } }
                }

                // update MediaStore right away, then let the scanner re-read the file
                try {
                    val values = ContentValues()
                    if (edit.title.isNotBlank()) values.put(MediaStore.Audio.Media.TITLE, edit.title.trim())
                    if (edit.artist.isNotBlank()) values.put(MediaStore.Audio.Media.ARTIST, edit.artist.trim())
                    if (edit.album.isNotBlank()) values.put(MediaStore.Audio.Media.ALBUM, edit.album.trim())
                    edit.year.trim().toIntOrNull()?.let { values.put(MediaStore.Audio.Media.YEAR, it) }
                    edit.track.trim().toIntOrNull()?.let { values.put(MediaStore.Audio.Media.TRACK, it) }
                    if (values.size() > 0) resolver.update(song.uri, values, null, null)
                } catch (e: Exception) {
                }
                if (path != null) {
                    val done = CompletableDeferred<Unit>()
                    try {
                        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                            done.complete(Unit)
                        }
                        withTimeoutOrNull(3000) { done.await() }
                    } catch (e: Exception) {
                    }
                }
                SaveResult.Ok
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    SaveResult.NeedsGrant(e.userAction.actionIntent.intentSender)
                } else if (Build.VERSION.SDK_INT < 29) {
                    SaveResult.NeedsStoragePermission
                } else {
                    SaveResult.Error("Android did not allow changing this file")
                }
            } catch (t: Throwable) {
                SaveResult.Error(t.message ?: "Could not write the tags to this file")
            } finally {
                cache?.delete()
            }
        }
}
