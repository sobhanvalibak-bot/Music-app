package com.example.musicapp

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Online lookups are deliberately opt-in: they require an active internet connection. */
data class OnlineSongInfo(
    val title: String, val artist: String, val album: String,
    val genre: String, val year: String, val artworkUrl: String
)
data class LyricsLine(val timeMs: Long, val text: String)

object AutoMusicLookup {
    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "MusicApp/1.2 (Android; music metadata lookup)")
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("Online service returned HTTP $code")
            body
        } finally { c.disconnect() }
    }

    suspend fun songInfo(title: String, artist: String): OnlineSongInfo? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val root = JSONObject(get("https://itunes.apple.com/search?term=$q&entity=song&limit=5"))
            val arr = root.optJSONArray("results") ?: return@withContext null
            if (arr.length() == 0) return@withContext null
            val target = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull {
                it.optString("trackName").contains(title, true) || it.optString("artistName").contains(artist, true)
            } ?: arr.getJSONObject(0)
            OnlineSongInfo(
                target.optString("trackName", title), target.optString("artistName", artist),
                target.optString("collectionName", ""), target.optString("primaryGenreName", ""),
                target.optString("releaseDate", "").take(4), target.optString("artworkUrl100", "").replace("100x100", "600x600")
            )
        } catch (e: Exception) {
            throw e
        }
    }


    suspend fun downloadCover(context: Context, songId: Long, artworkUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (artworkUrl.isBlank()) return@withContext false
        try {
            val connection = (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MusicApp/1.2 (Android)")
            }
            val bytes = try {
                if (connection.responseCode !in 200..299) return@withContext false
                connection.inputStream.use { it.readBytes() }
            } finally { connection.disconnect() }
            if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) return@withContext false
            File(context.filesDir, "auto_cover_$songId.jpg").writeBytes(bytes)
            true
        } catch (_: Exception) { false }
    }

    suspend fun lyrics(title: String, artist: String): List<LyricsLine> = withContext(Dispatchers.IO) {
        try {
            val t = URLEncoder.encode(title.trim(), "UTF-8")
            val a = URLEncoder.encode(artist.trim(), "UTF-8")
            // First try the exact LRCLIB endpoint, then search results; some tracks are indexed
            // with slightly different album/version metadata and are only found through search.
            val exact = runCatching { JSONObject(get("https://lrclib.net/api/get?track_name=$t&artist_name=$a")) }.getOrNull()
            var synced = exact?.optString("syncedLyrics", "").orEmpty()
            if (synced.isBlank()) {
                val search = JSONArray(get("https://lrclib.net/api/search?track_name=$t&artist_name=$a"))
                for (i in 0 until search.length()) {
                    val candidate = search.optJSONObject(i) ?: continue
                    val candidateLyrics = candidate.optString("syncedLyrics", "")
                    if (candidateLyrics.isNotBlank()) { synced = candidateLyrics; break }
                    val id = candidate.optLong("id", -1L)
                    if (id > 0) {
                        val detail = runCatching { JSONObject(get("https://lrclib.net/api/get/$id")) }.getOrNull()
                        synced = detail?.optString("syncedLyrics", "").orEmpty()
                        if (synced.isNotBlank()) break
                    }
                }
            }
            if (synced.isBlank()) return@withContext emptyList()
            val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]([^\\r\\n]*)")
            regex.findAll(synced).map { m ->
                val fraction = m.groupValues[3].ifBlank { "0" }.padEnd(3, '0').take(3).toLong()
                val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fraction
                LyricsLine(ms, m.groupValues[4].trim())
            }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
        } catch (_: Exception) { emptyList() }
    }
}
