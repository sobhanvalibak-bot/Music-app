package com.example.musicapp

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ───────────────────────── Shared state between the UI and PlaybackService ─────────────────────────
// Both run in the same process, so plain singletons are enough.

object PlaybackBridge {
    @Volatile
    var audioSessionId: Int = 0
}

object SleepTimer {
    /** Wall-clock end time ([SystemClock.elapsedRealtime] based), 0 = no timer. */
    var endAtMs by mutableLongStateOf(0L)

    /** Stop when the current song ends. */
    var afterTrack by mutableStateOf(false)

    fun off() {
        endAtMs = 0L
        afterTrack = false
    }

    fun inMinutes(min: Int) {
        afterTrack = false
        endAtMs = SystemClock.elapsedRealtime() + min * 60_000L
    }

    fun endOfTrack() {
        endAtMs = 0L
        afterTrack = true
    }
}

// ───────────────────────── System equalizer ─────────────────────────

fun openSystemEqualizer(context: Context) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, PlaybackBridge.audioSessionId)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No system equalizer found on this device", Toast.LENGTH_SHORT).show()
    }
}

// ───────────────────────── Stats for nerds ─────────────────────────

data class AudioInfo(val codec: String, val sampleRate: Int, val channels: Int, val bitrate: Int)

private fun codecName(mime: String?): String = when {
    mime == null -> "Unknown"
    mime.contains("mpeg") -> "MP3"
    mime.contains("mp4a") -> "AAC"
    mime.contains("flac") -> "FLAC"
    mime.contains("opus") -> "Opus"
    mime.contains("vorbis") -> "Vorbis"
    mime.contains("alac") -> "ALAC"
    mime.contains("raw") -> "PCM / WAV"
    mime.contains("amr") -> "AMR"
    else -> mime.removePrefix("audio/")
}

fun audioInfoFrom(tracks: Tracks): AudioInfo? {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
    var index = 0
    for (i in 0 until group.length) {
        if (group.isTrackSelected(i)) {
            index = i
            break
        }
    }
    val f = group.getTrackFormat(index)
    val bitrate = if (f.averageBitrate != Format.NO_VALUE) f.averageBitrate else f.bitrate
    return AudioInfo(
        codec = codecName(f.sampleMimeType),
        sampleRate = f.sampleRate,
        channels = f.channelCount,
        bitrate = bitrate
    )
}

/** Bit depth of the file (Android 12+ only, 0 when unknown). */
suspend fun bitDepthOf(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 31) return@withContext 0
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
    } catch (t: Throwable) {
        0
    } finally {
        try {
            r.release()
        } catch (t: Throwable) {
        }
    }
}

// ───────────────────────── Lyrics (everything stays on the device) ─────────────────────────

/** One word of an "enhanced" LRC line: `<00:12.50>word`. */
data class LrcWord(val timeMs: Long, val text: String)

/** [timeMs] is -1 for lyrics without timing. [words] is empty unless the file has word timing. */
data class LrcLine(val timeMs: Long, val text: String, val words: List<LrcWord> = emptyList())

private val stampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val wordStampRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
private val offsetRegex = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
private val metaTagRegex = Regex("""^\[[A-Za-z#]+:.*]$""")

private fun stampToMs(min: String, sec: String, frac: String): Long {
    val f = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
    return min.toLong() * 60_000L + sec.toLong() * 1000L + f
}

/**
 * Reads .lrc text. Understands `[mm:ss.xx]` line times (several per line), `[offset:ms]`,
 * and word times `<mm:ss.xx>` for word-by-word highlighting. Text without any timing
 * comes back as plain lines with timeMs = -1.
 */
fun parseLrc(raw: String): List<LrcLine> {
    val offset = offsetRegex.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val timed = ArrayList<LrcLine>()
    val plain = ArrayList<LrcLine>()

    for (rawLine in raw.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        val stamps = ArrayList<Long>()
        var rest = line
        while (true) {
            val m = stampRegex.find(rest) ?: break
            if (m.range.first != 0) break
            stamps += stampToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) - offset
            rest = rest.substring(m.range.last + 1)
        }

        if (stamps.isEmpty()) {
            if (metaTagRegex.matches(line)) continue
            plain += LrcLine(-1L, line)
            continue
        }

        val words = ArrayList<LrcWord>()
        val tags = wordStampRegex.findAll(rest).toList()
        if (tags.isNotEmpty()) {
            val lead = rest.substring(0, tags[0].range.first)
            if (lead.isNotBlank()) words += LrcWord(stamps[0], lead)
            for (i in tags.indices) {
                val t = tags[i]
                val from = t.range.last + 1
                val to = if (i + 1 < tags.size) tags[i + 1].range.first else rest.length
                val piece = rest.substring(from, to)
                if (piece.isNotEmpty()) {
                    words += LrcWord(
                        stampToMs(t.groupValues[1], t.groupValues[2], t.groupValues[3]) - offset,
                        piece
                    )
                }
            }
        }
        val text = rest.replace(wordStampRegex, "").trim()
        if (text.isEmpty()) continue
        for (s in stamps) timed += LrcLine(s, text, words)
    }
    return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain
}

/** Lyrics saved inside the app's private storage, one .lrc file per song. */
object LyricsStore {
    private val triedEmbedded = HashSet<Long>()

    private fun file(context: Context, songId: Long): File {
        val dir = File(context.filesDir, "lyrics")
        dir.mkdirs()
        return File(dir, "$songId.lrc")
    }

    fun load(context: Context, songId: Long): List<LrcLine> =
        try {
            val f = file(context, songId)
            if (f.exists()) parseLrc(f.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    fun saveText(context: Context, songId: Long, text: String) {
        try {
            file(context, songId).writeText(text)
        } catch (e: Exception) {
        }
    }

    fun saveLines(context: Context, songId: Long, lines: List<LyricsLine>) {
        val sb = StringBuilder()
        for (l in lines) {
            val min = l.timeMs / 60_000L
            val sec = (l.timeMs % 60_000L) / 1000L
            val cs = (l.timeMs % 1000L) / 10L
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, cs)).append(l.text).append('\n')
        }
        saveText(context, songId, sb.toString())
    }

    fun delete(context: Context, songId: Long) {
        try {
            file(context, songId).delete()
        } catch (e: Exception) {
        }
    }

    /** True the first time it is asked for a song, so the file is only searched for tags once per run. */
    fun shouldTryEmbedded(songId: Long): Boolean = triedEmbedded.add(songId)
}
