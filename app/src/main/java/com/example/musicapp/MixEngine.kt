package com.example.musicapp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// ───────────────────────── Mix songs: the real audio ─────────────────────────
//
// A mix is ONE new song file: a part of every picked song (chosen automatically), levelled to the same
// loudness and joined with equal-power cross-fades. It is saved in Music/Mixes, so it is a normal song
// of the library. Its cover is a collage of the picked songs' covers.

/** Remembers which songs are mixes and where their collage cover is. */
object MixStore {
    @Volatile private var dir: File? = null
    private val ids = HashSet<Long>()
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        dir = context.filesDir
        val p = context.getSharedPreferences("mixes", Context.MODE_PRIVATE)
        prefs = p
        p.getStringSet("ids", emptySet())?.mapNotNullTo(ids) { it.toLongOrNull() }
    }

    @Synchronized
    fun register(id: Long) {
        ids.add(id)
        prefs?.edit()?.putStringSet("ids", ids.map { it.toString() }.toSet())?.apply()
    }

    @Synchronized
    fun isMix(id: Long): Boolean = ids.contains(id)

    /** Same file name the cover loader already looks for (auto_cover_<id>.jpg). */
    fun coverFile(id: Long): File? = dir?.let { File(it, "auto_cover_$id.jpg") }

    fun coverUri(id: Long): Uri? =
        if (isMix(id)) coverFile(id)?.takeIf { it.exists() }?.let { Uri.fromFile(it) } else null
}

/** Collage of up to 4 covers: 2 songs = two halves, 3 = one big + two small, 4 = a 2x2 grid. */
object MixCover {
    suspend fun build(context: Context, songs: List<Song>, size: Int = 640): Bitmap = withContext(Dispatchers.Default) {
        val list = songs.take(4)
        val bmps = list.map { s -> try { loadArt(context, s, size / 2)?.asAndroidBitmap() } catch (_: Exception) { null } }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF1C1C1E.toInt())
        val s = size.toFloat()
        val h = s / 2f
        val cells: List<RectF> = when (list.size) {
            2 -> listOf(RectF(0f, 0f, h, s), RectF(h, 0f, s, s))
            3 -> listOf(RectF(0f, 0f, h, s), RectF(h, 0f, s, h), RectF(h, h, s, s))
            else -> listOf(RectF(0f, 0f, h, h), RectF(h, 0f, s, h), RectF(0f, h, h, s), RectF(h, h, s, s))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        cells.forEachIndexed { i, r -> drawCrop(c, bmps.getOrNull(i), r, paint) }
        // thin dark seams between the covers
        val seam = Paint().apply { color = 0xFF000000.toInt() }
        val t = 3f
        c.drawRect(h - t / 2, 0f, h + t / 2, s, seam)
        if (list.size >= 3) c.drawRect(if (list.size == 3) h else 0f, h - t / 2, s, h + t / 2, seam)
        out
    }

    private fun drawCrop(c: Canvas, b: Bitmap?, dst: RectF, paint: Paint) {
        if (b == null || b.width <= 0 || b.height <= 0) {
            val fill = Paint().apply { color = 0xFF3A3A3C.toInt() }
            c.drawRect(dst, fill)
            return
        }
        val side = min(b.width, b.height)
        val aspect = dst.width() / dst.height()
        var sw = side
        var sh = side
        if (aspect > 1f) sh = (side / aspect).toInt().coerceAtLeast(1) else if (aspect < 1f) sw = (side * aspect).toInt().coerceAtLeast(1)
        val l = b.width / 2 - sw / 2
        val t = b.height / 2 - sh / 2
        c.drawBitmap(b, Rect(l, t, l + sw, t + sh), dst, paint)
    }
}

class MixOutput(val file: File, val ext: String, val mime: String, val durationMs: Long)

object MixEngine {
    private const val RATE = 44100

    /** Length of the part taken from each song. */
    private fun segmentMs(n: Int): Long = when (n) { 2 -> 36_000L; 3 -> 28_000L; else -> 24_000L }

    /** The part of a song to use: it starts a third into the song (usually the first chorus), never past the end. */
    private fun pickStart(durationMs: Long, segMs: Long): Long {
        if (durationMs <= segMs + 3000L) return 0L
        return (durationMs * 0.33).toLong().coerceAtMost(durationMs - segMs - 2000L).coerceAtLeast(0L)
    }

    /**
     * Builds the mix, saves it in Music/Mixes, writes its collage cover and registers it.
     * [collage] is the cover to use (built by the animation), it is made here if null.
     */
    suspend fun create(context: Context, songs: List<Song>, collage: Bitmap?): Song = withContext(Dispatchers.IO) {
        val out = render(context, songs)
        val title = songs.joinToString(" × ") { it.title }.take(60)
        val saved = saveToMusic(context, out, "Mix - $title", title)
        try { out.file.delete() } catch (_: Exception) { }
        val cover = collage ?: MixCover.build(context, songs)
        MixStore.coverFile(saved.second)?.let { f ->
            try { f.outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 92, it) } } catch (_: Exception) { }
        }
        MixStore.register(saved.second)
        Song(
            id = saved.second, title = title, artist = "Mix", album = "Mixes", albumId = 0L,
            durationMs = out.durationMs, dateAdded = System.currentTimeMillis() / 1000L, uri = saved.first
        )
    }

    // ───────── decode ─────────

    private class Pcm(var data: ShortArray = ShortArray(1 shl 16), var size: Int = 0) {
        fun add(src: java.nio.ShortBuffer, count: Int) {
            if (size + count > data.size) data = data.copyOf(max(data.size * 2, size + count))
            src.get(data, size, count)
            size += count
        }
    }

    /** [startMs, startMs + lenMs) of the song as 44.1 kHz stereo 16-bit (interleaved). */
    private fun decodeSegment(context: Context, uri: Uri, startMs: Long, lenMs: Long): ShortArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IOException("No audio in this file")
            ex.selectTrack(track)
            ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val pcm = Pcm()
            val info = MediaCodec.BufferInfo()
            val startUs = startMs * 1000L
            val endUs = (startMs + lenMs) * 1000L
            var firstPts = -1L
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 90_000L
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(10_000L)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t > endUs + 200_000L) {
                            dec.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, t, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000L)
                if (oi >= 0) {
                    if (info.size > 0) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        val ob = dec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        pcm.add(sb, sb.remaining())
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (pcm.size == 0) throw IOException("Could not read this song")
            val skipFrames = if (firstPts in 0 until startUs) ((startUs - firstPts) * rate / 1_000_000L).toInt() else 0
            val takeFrames = (lenMs * rate / 1000L).toInt()
            return toStereo44(pcm.data, pcm.size, max(ch, 1), max(rate, 8000), skipFrames, takeFrames)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }

    private fun toStereo44(src: ShortArray, size: Int, ch: Int, rate: Int, skipFrames: Int, takeFrames: Int): ShortArray {
        val totalFrames = size / ch
        val start = skipFrames.coerceIn(0, totalFrames)
        val n = min(totalFrames - start, takeFrames)
        if (n <= 0) return ShortArray(0)
        val outFrames = (n.toLong() * RATE / rate).toInt()
        val out = ShortArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val pos = i.toDouble() * rate / RATE
            val i0 = pos.toInt().coerceAtMost(n - 1)
            val frac = (pos - i0).toFloat()
            val i1 = min(i0 + 1, n - 1)
            for (c in 0..1) {
                val sc = if (ch == 1) 0 else min(c, ch - 1)
                val a = src[(start + i0) * ch + sc].toInt()
                val b = src[(start + i1) * ch + sc].toInt()
                out[i * 2 + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }

    // ───────── join ─────────

    private fun render(context: Context, songs: List<Song>): MixOutput {
        val segMs = segmentMs(songs.size)
        val segs = ArrayList<ShortArray>()
        for (s in songs) {
            val len = if (s.durationMs in 1 until segMs) s.durationMs else segMs
            val start = pickStart(s.durationMs, len)
            val seg = decodeSegment(context, s.uri, start, len)
            if (seg.size < RATE) throw IOException("\"${s.title}\" is too short or cannot be read")
            segs.add(seg)
        }
        // same loudness for every part
        val gains = segs.map { seg ->
            var sum = 0.0
            for (v in seg) { val f = v / 32768.0; sum += f * f }
            val rms = sqrt(sum / max(seg.size, 1)).coerceAtLeast(1e-4)
            (0.11 / rms).coerceIn(0.4, 3.0).toFloat()
        }
        val minFrames = segs.minOf { it.size / 2 }
        val xf = min((2.5 * RATE).toInt(), minFrames / 3).coerceAtLeast(1)
        val fadeIn = min((0.4 * RATE).toInt(), minFrames / 4).coerceAtLeast(1)
        val tail = min((3.0 * RATE).toInt(), minFrames / 2).coerceAtLeast(1)
        val total = segs.sumOf { it.size / 2 } - xf * (segs.size - 1)
        val acc = FloatArray(total * 2)
        var pos = 0
        for ((k, seg) in segs.withIndex()) {
            val frames = seg.size / 2
            val g = gains[k]
            for (f in 0 until frames) {
                var w = g
                if (k > 0 && f < xf) w *= sin(0.5 * PI * f / xf).toFloat()
                else if (k == 0 && f < fadeIn) w *= f.toFloat() / fadeIn
                val fromEnd = frames - 1 - f
                if (k < segs.size - 1 && fromEnd < xf) w *= sin(0.5 * PI * fromEnd / xf).toFloat()
                else if (k == segs.size - 1 && fromEnd < tail) w *= fromEnd.toFloat() / tail
                val o = (pos + f) * 2
                acc[o] += seg[f * 2] * w
                acc[o + 1] += seg[f * 2 + 1] * w
            }
            pos += frames - xf
        }
        var peak = 1f
        for (v in acc) peak = max(peak, abs(v))
        val scale = if (peak > 32000f) 32000f / peak else 1f
        val pcm = ShortArray(acc.size) { (acc[it] * scale).toInt().coerceIn(-32767, 32767).toShort() }
        val durationMs = total * 1000L / RATE
        val dir = File(context.cacheDir, "mix").apply { mkdirs() }
        val base = "mix_${System.currentTimeMillis()}"
        return try {
            val f = File(dir, "$base.m4a")
            encodeAac(pcm, f)
            MixOutput(f, "m4a", "audio/mp4", durationMs)
        } catch (e: Exception) {
            // no AAC encoder on this phone: plain WAV always works
            val f = File(dir, "$base.wav")
            writeWav(pcm, f)
            MixOutput(f, "wav", "audio/x-wav", durationMs)
        }
    }

    private fun encodeAac(pcm: ShortArray, outFile: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var started = false
            var fed = 0                       // shorts fed so far
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 120_000L
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = enc.dequeueInputBuffer(10_000L)
                    if (ii >= 0) {
                        val buf: ByteBuffer = enc.getInputBuffer(ii)!!
                        buf.clear()
                        val ptsUs = (fed / 2).toLong() * 1_000_000L / RATE
                        val remaining = pcm.size - fed
                        if (remaining <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            var n = min(buf.remaining() / 2, remaining)
                            n -= n % 2
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, fed, n)
                            enc.queueInputBuffer(ii, 0, n * 2, ptsUs, 0)
                            fed += n
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000L)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    started = true
                } else if (oi >= 0) {
                    val ob = enc.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (!outputDone || !started) throw IOException("AAC encoder did not finish")
        } finally {
            try { enc.stop() } catch (_: Exception) { }
            try { enc.release() } catch (_: Exception) { }
            try { muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
        }
    }

    private fun writeWav(pcm: ShortArray, f: File) {
        val dataLen = pcm.size * 2
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
            h.putInt(RATE); h.putInt(RATE * 4); h.putShort(4); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataLen)
            raf.write(h.array())
            val chunk = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < pcm.size) {
                chunk.clear()
                val n = min(chunk.capacity() / 2, pcm.size - i)
                chunk.asShortBuffer().put(pcm, i, n)
                raf.write(chunk.array(), 0, n * 2)
                i += n
            }
        }
    }

    // ───────── save in Music/Mixes ─────────

    private const val FOLDER = "Mixes"

    private suspend fun saveToMusic(context: Context, out: MixOutput, fileBase: String, title: String): Pair<Uri, Long> {
        val name = fileBase.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(100).ifBlank { "Mix" } + "." + out.ext
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, out.mime)
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(android.provider.MediaStore.Audio.Media.TITLE, title)
                put(android.provider.MediaStore.Audio.Media.ARTIST, "Mix")
                put(android.provider.MediaStore.Audio.Media.ALBUM, "Mixes")
                put(android.provider.MediaStore.Audio.Media.IS_MUSIC, 1)
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Cannot create the file in Music")
            try {
                val o = resolver.openOutputStream(uri) ?: throw IOException("Cannot write the file")
                o.use { os -> out.file.inputStream().use { it.copyTo(os) } }
                val done = ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                throw if (e is IOException) e else IOException(e.message, e)
            }
            return uri to ContentUris.parseId(uri)
        }
        // Android 9 and older: normal file, then the media scanner adds it
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        dir.mkdirs()
        val target = File(dir, name)
        try { out.file.copyTo(target, overwrite = true) } catch (e: Exception) { throw IOException("Cannot write to Music/$FOLDER (storage permission?)", e) }
        val uri: Uri? = suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(out.mime)) { _, u -> if (cont.isActive) cont.resume(u) }
        }
        val u = uri ?: throw IOException("The mix was saved but the library did not see it yet")
        return u to ContentUris.parseId(u)
    }
}
