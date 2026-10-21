package com.example.musicapp

import android.content.ContentValues
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory

/**
 * Saves an online song on the device (Music/musicapp), with title, artist and cover inside the file.
 * After it is saved it shows up in Songs like any other file on the phone.
 *
 * Progress of every download is in [progress]: 0..<1 while downloading, 1 once saved.
 * [version] goes up after each saved song so the song list reloads.
 */
object OnlineDownloader {

    val progress = mutableStateMapOf<Long, Float>()
    var version by mutableIntStateOf(0)

    private const val CHUNK = 8L * 1024L * 1024L
    private const val FOLDER = "musicapp"

    /** Set by the UI: asks for the storage permission (Android 9 and older) and then starts the download again. */
    var requestWrite: ((Song) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isBusy(id: Long): Boolean = (progress[id] ?: 1f) < 1f
    fun isDone(id: Long): Boolean = progress[id] == 1f

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    fun start(context: Context, song: Song) {
        val page = song.remoteId ?: return
        if (isBusy(song.id)) {
            toast(context, "Already downloading")
            return
        }
        if (isDone(song.id)) {
            toast(context, "Already saved in Music/musicapp")
            return
        }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            val ask = requestWrite
            if (ask != null) { ask(song); return }
        }
        progress[song.id] = 0f
        toast(app, "Downloading: ${song.title}")
        Thread {
            var cache: File? = null
            var remuxed: File? = null
            try {
                val source = try {
                    OnlineEngine.audioSource(page)
                } catch (e: IOException) {
                    // YouTube blocked this network: take the same song from SoundCloud / Bandcamp if it is there
                    if (!OnlineEngine.isBlocked(e.message)) throw e
                    val alt = OnlineEngine.findAlternative(song.title, song.artist, song.durationMs) ?: throw e
                    toast(app, "YouTube blocked it, downloading from ${sourceNameOf(alt.url)}")
                    OnlineEngine.audioSource(alt.url)
                }
                val file = File(app.cacheDir, "dl_${System.nanoTime()}.${source.ext}")
                cache = file

                val report = { f: Float ->
                    main.post { if (isBusy(song.id)) progress[song.id] = f.coerceIn(0f, 0.99f) }
                    Unit
                }
                if (source.hls) downloadHls(source.url, file, report) else download(source.url, file, report)
                // A playlist of pieces glued together has no total length inside it, so players show a
                // length that keeps growing while it plays. Re-pack the AAC into a proper .m4a first.
                var ready = file
                var ext = source.ext
                var mime = source.mime
                if (source.hls && source.mime != "audio/mpeg") {
                    remux(file)?.let { ready = it; remuxed = it; ext = "m4a"; mime = "audio/mp4" }
                }
                writeTags(app, song, ready)
                save(app, song, ready, ext, mime)

                main.post {
                    progress[song.id] = 1f
                    version++
                }
                toast(app, "Saved: ${song.title}")
            } catch (e: Exception) {
                main.post { progress.remove(song.id) }
                toast(app, "Download failed: ${e.message ?: "unknown error"}")
            } finally {
                try {
                    cache?.delete()
                    remuxed?.delete()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    // ───────────────────────── Download (in 8 MB pieces, which servers accept without slowing down) ─────────────────────────

    @Throws(IOException::class)
    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        target.outputStream().use { out ->
            var offset = 0L
            var total = -1L
            var lastReported = -1f
            while (true) {
                val req = Request.Builder().url(url).get()
                    .header("User-Agent", OnlineEngine.USER_AGENT)
                    .header("Range", "bytes=$offset-${offset + CHUNK - 1}")
                    .build()
                client.newCall(req).execute().use { r ->
                    when (r.code) {
                        206 -> {
                            // Content-Range: bytes 0-8388607/12345678
                            r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { total = it }
                            val got = copy(r.body?.byteStream(), out)
                            offset += got
                            // total unknown and a short piece: that was the end
                            if (total < 0L && got < CHUNK) total = offset
                        }
                        200 -> {
                            // the server ignores ranges: the whole file comes in one go
                            total = r.body?.contentLength() ?: -1L
                            offset = copy(r.body?.byteStream(), out)
                            if (total <= 0L) total = offset
                        }
                        416 -> total = offset // asked past the end: finished
                        else -> throw IOException("The server answered HTTP ${r.code}")
                    }
                    Unit
                }
                if (total > 0L) {
                    val f = offset.toFloat() / total.toFloat()
                    if (f - lastReported >= 0.02f) {
                        lastReported = f
                        onProgress(f)
                    }
                }
                if (total in 0..offset) break
                if (offset == 0L) throw IOException("Empty answer from the server")
            }
        }
        if (target.length() < 1024L) throw IOException("The downloaded file is empty")
    }

    /** A playlist of pieces (SoundCloud): download every piece and put them one after the other in one file. */
    @Throws(IOException::class)
    private fun downloadHls(playlistUrl: String, target: File, onProgress: (Float) -> Unit) {
        fun text(url: String): String {
            val req = Request.Builder().url(url).header("User-Agent", OnlineEngine.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("The server answered HTTP ${r.code}")
                return r.body?.string() ?: throw IOException("Empty playlist")
            }
        }

        var url = playlistUrl
        var body = text(url)
        if (body.contains("#EXT-X-STREAM-INF")) {
            // a list of qualities: take the first one
            val variant = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("Empty playlist")
            url = java.net.URI(url).resolve(variant.trim()).toString()
            body = text(url)
        }
        if (Regex("#EXT-X-KEY:.*METHOD=(?!NONE)").containsMatchIn(body)) {
            throw IOException("This stream is encrypted and cannot be saved")
        }
        val pieces = mutableListOf<String>()
        Regex("#EXT-X-MAP:.*URI=\"([^\"]+)\"").find(body)?.let {
            pieces += java.net.URI(url).resolve(it.groupValues[1]).toString()
        }
        body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { pieces += java.net.URI(url).resolve(it).toString() }
        if (pieces.isEmpty()) throw IOException("Empty playlist")

        target.outputStream().use { out ->
            pieces.forEachIndexed { i, piece ->
                val req = Request.Builder().url(piece).header("User-Agent", OnlineEngine.USER_AGENT).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("The server answered HTTP ${r.code}")
                    copy(r.body?.byteStream(), out)
                }
                onProgress((i + 1).toFloat() / pieces.size.toFloat())
            }
        }
        if (target.length() < 1024L) throw IOException("The downloaded file is empty")
    }

    private fun copy(input: java.io.InputStream?, out: OutputStream): Long {
        if (input == null) throw IOException("Empty answer from the server")
        var n = 0L
        val buf = ByteArray(64 * 1024)
        input.use {
            while (true) {
                val r = it.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                n += r
            }
        }
        return n
    }

    // ───────────────────────── Fix the length of glued HLS pieces ─────────────────────────

    /** Copies the AAC samples into a normal MPEG-4 audio file (real duration, seekable). Null if it cannot. */
    private fun remux(src: File): File? {
        val out = File(src.parentFile, "rm_${System.nanoTime()}.m4a")
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null || fmt.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") return null
            ex.selectTrack(track)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val mt = m.addTrack(fmt)
            m.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var first = -1L
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                if (first < 0L) first = t
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, (t - first).coerceAtLeast(0L), flags)
                m.writeSampleData(mt, buf, info)
                ex.advance()
            }
            m.stop()
            m.release()
            muxer = null
            return if (out.length() > 1024L) out else { out.delete(); null }
        } catch (e: Throwable) {
            try { muxer?.release() } catch (_: Throwable) { }
            out.delete()
            return null
        } finally {
            try { ex.release() } catch (_: Throwable) { }
        }
    }

    // ───────────────────────── Tags + cover (optional: a failure here never stops the download) ─────────────────────────

    private fun writeTags(context: Context, song: Song, file: File) {
        try {
            try {
                val instance = TagOptionSingleton.getInstance()
                instance.javaClass.getMethod("setAndroid", java.lang.Boolean.TYPE).invoke(instance, true)
            } catch (_: Throwable) {
            }
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, song.title)
            if (song.artist.isNotBlank()) tag.setField(FieldKey.ARTIST, song.artist)
            tag.setField(FieldKey.ALBUM, "Downloads")
            val art = song.artUrl?.let { fetchCover(context, it) }
            if (art != null) {
                try {
                    tag.deleteArtworkField()
                    tag.setField(ArtworkFactory.createArtworkFromFile(art))
                } catch (_: Throwable) {
                } finally {
                    art.delete()
                }
            }
            audio.commit()
        } catch (_: Throwable) {
            // some formats (webm, opus) cannot be tagged: the song is saved without tags
        }
    }

    private fun fetchCover(context: Context, url: String): File? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", OnlineEngine.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body?.bytes() ?: return null
                val f = File(context.cacheDir, "cover_${System.nanoTime()}.jpg")
                f.writeBytes(bytes)
                f
            }
        } catch (_: Exception) {
            null
        }
    }

    // ───────────────────────── Put the file in the music folder ─────────────────────────

    private fun safeName(song: Song): String {
        val raw = if (song.artist.isBlank()) song.title else "${song.artist} - ${song.title}"
        return raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(100).ifBlank { "song" }
    }

    @Throws(IOException::class)
    private fun save(context: Context, song: Song, file: File, ext: String, mime: String) {
        val name = "${safeName(song)}.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.TITLE, song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artist.ifBlank { "Unknown Artist" })
                put(MediaStore.Audio.Media.ALBUM, "Downloads")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Cannot create the file in Music")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot write the file")
                out.use { o -> file.inputStream().use { i -> i.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw if (e is IOException) e else IOException(e.message, e)
            }
        } else {
            // Android 9 and older: a normal file, then ask the media scanner to add it
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
            dir.mkdirs()
            val target = File(dir, name)
            try {
                file.copyTo(target, overwrite = true)
            } catch (e: Exception) {
                throw IOException("Cannot write to Music/$FOLDER (storage permission?)", e)
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        }
    }
}
