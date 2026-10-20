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

/** The phone itself (data-saver, per-app network switch, VPN lockdown...) refused the socket. */
class NetworkBlockedException(cause: Throwable?) : java.io.IOException("NETWORK_BLOCKED", cause)

object AutoMusicLookup {
    private fun isBlocked(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val m = t.message.orEmpty()
            if (t is SecurityException || m.contains("EPERM", true) || m.contains("Operation not permitted", true) ||
                m.contains("Permission denied", true) || m.contains("INTERNET permission", true)) return true
            t = t.cause; depth++
        }
        return false
    }

    private fun get(url: String): String {
        try {
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
        } catch (e: NetworkBlockedException) {
            throw e
        } catch (e: Exception) {
            if (isBlocked(e)) throw NetworkBlockedException(e) else throw e
        }
    }

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun artistParts(a: String) = a.split(',', '&', '_', ';').map { norm(it) }.filter { it.isNotBlank() }

    /** Best candidate: the title must match, and a matching artist wins over a matching title alone. */
    private fun score(trackName: String, artistName: String, title: String, artist: String): Int {
        val tn = norm(trackName); val tt = norm(title)
        if (tt.isBlank()) return 0
        var pts = when {
            tn == tt -> 4
            tn.contains(tt) || (tn.isNotBlank() && tt.contains(tn)) -> 2
            else -> return 0 // a different song by the same artist is not a match
        }
        val an = norm(artistName)
        if (an.isNotBlank() && artistParts(artist).any { an.contains(it) || it.contains(an) }) pts += 3
        return pts
    }

    suspend fun songInfo(title: String, artist: String): OnlineSongInfo? = withContext(Dispatchers.IO) {
        var firstError: Exception? = null
        val fromItunes = try { itunes(title, artist) } catch (e: NetworkBlockedException) { throw e } catch (e: Exception) { firstError = e; null }
        if (fromItunes != null) return@withContext fromItunes
        // fallback provider: some regions/networks block one catalog but not the other
        val fromDeezer = try { deezer(title, artist) } catch (e: NetworkBlockedException) { throw e } catch (e: Exception) { if (firstError == null) firstError = e; null }
        if (fromDeezer == null && firstError != null) throw firstError
        fromDeezer
    }

    private fun itunes(title: String, artist: String): OnlineSongInfo? {
        val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val root = JSONObject(get("https://itunes.apple.com/search?term=$q&entity=song&limit=10"))
        val arr = root.optJSONArray("results") ?: return null
        val best = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it to score(it.optString("trackName"), it.optString("artistName"), title, artist) }
            .filter { it.second >= 2 }.maxByOrNull { it.second }?.first ?: return null
        return OnlineSongInfo(
            best.optString("trackName", title), best.optString("artistName", artist),
            best.optString("collectionName", ""), best.optString("primaryGenreName", ""),
            best.optString("releaseDate", "").take(4), best.optString("artworkUrl100", "").replace("100x100", "600x600")
        )
    }

    private fun deezer(title: String, artist: String): OnlineSongInfo? {
        val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val arr = JSONObject(get("https://api.deezer.com/search?q=$q&limit=10")).optJSONArray("data") ?: return null
        val best = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it to score(it.optString("title"), it.optJSONObject("artist")?.optString("name").orEmpty(), title, artist) }
            .filter { it.second >= 2 }.maxByOrNull { it.second }?.first ?: return null
        val album = best.optJSONObject("album")
        var genre = ""; var year = ""
        val albumId = album?.optLong("id", -1L) ?: -1L
        if (albumId > 0) runCatching {
            val a = JSONObject(get("https://api.deezer.com/album/$albumId"))
            genre = a.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name").orEmpty()
            year = a.optString("release_date", "").take(4)
        }
        return OnlineSongInfo(
            best.optString("title", title), best.optJSONObject("artist")?.optString("name") ?: artist,
            album?.optString("title").orEmpty(), genre, year, album?.optString("cover_xl").orEmpty()
        )
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
        } catch (e: NetworkBlockedException) { throw e } catch (_: Exception) { emptyList() }
    }
}
