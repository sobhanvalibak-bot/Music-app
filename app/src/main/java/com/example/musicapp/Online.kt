@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.musicapp

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

// ───────────────────────── Online songs (YouTube, YouTube Music, SoundCloud, Bandcamp) ─────────────────────────
//
// An online song is a normal [Song] with a negative id and a [Song.remoteId] (the page address of the track).
// Its media item uses the private address  npyt://stream?u=PAGE_URL&v=0|1  and the player's data source
// ([OnlineResolver]) turns that into a fresh stream address right when it is needed. That means
// queues, favorites, playlists and crossfade keep working even after the old address has expired.

/** One row of the online search results. */
data class OnlineTrack(
    /** Page address of the track (YouTube video, SoundCloud track, Bandcamp track ...). */
    val url: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artUrl: String?
)

const val ONLINE_SCHEME = "npyt"

val Song.isOnline: Boolean get() = remoteId != null

/** Stable negative id for a video, so it never collides with MediaStore ids (always positive). */
fun onlineSongId(url: String): Long =
    (UUID.nameUUIDFromBytes(url.toByteArray()).mostSignificantBits and Long.MAX_VALUE).inv()

fun OnlineTrack.toSong(): Song = Song(
    id = onlineSongId(url),
    title = title,
    artist = artist,
    album = sourceNameOf(url),
    albumId = -1L,
    durationMs = durationMs,
    dateAdded = System.currentTimeMillis() / 1000L,
    uri = Uri.parse(url),
    remoteId = url,
    artUrl = artUrl
)

/** The private address the player resolves into a real stream ([video] = picture and sound). */
fun onlineMediaUri(url: String, video: Boolean): Uri =
    Uri.Builder().scheme(ONLINE_SCHEME).authority("stream")
        .appendQueryParameter("u", url).appendQueryParameter("v", if (video) "1" else "0").build()

/** Search sources shown in the Online tab (same order as [OnlineEngine.search]'s `source`). */
val OnlineSources = listOf("YouTube", "YT Music", "SoundCloud", "Bandcamp")

fun sourceNameOf(url: String): String = when {
    url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
    url.contains("soundcloud.com") -> "SoundCloud"
    url.contains("bandcamp.com") -> "Bandcamp"
    else -> "Online"
}

/** Only YouTube has a video to watch. */
val Song.canWatch: Boolean get() = remoteId?.let { it.contains("youtube.com") || it.contains("youtu.be") } == true
val Song.sourceName: String get() = remoteId?.let { sourceNameOf(it) } ?: ""

/** Online songs the app has seen (played, queued, favorited ...). Kept on the device. */
object OnlineRegistry {
    private const val PREFS = "online_songs"
    private const val MAX = 1000
    private val map = LinkedHashMap<Long, Song>()
    private var app: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        try {
            val json = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val song = OnlineTrack(
                    o.getString("v"), o.getString("t"), o.optString("a"),
                    o.optLong("d"), o.optString("u").ifBlank { null }
                ).toSong()
                map[song.id] = song
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun get(id: Long): Song? = if (id < 0) map[id] else null

    @Synchronized
    fun remember(song: Song) {
        if (song.remoteId == null) return
        if (map.containsKey(song.id)) return
        map[song.id] = song
        while (map.size > MAX) map.remove(map.keys.first())
        val arr = JSONArray()
        map.values.forEach {
            arr.put(
                JSONObject().put("v", it.remoteId).put("t", it.title).put("a", it.artist)
                    .put("d", it.durationMs).put("u", it.artUrl ?: "")
            )
        }
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("items", arr.toString())?.apply()
    }
}

/** Lets `byId[id]` find online songs too. */
class SongIndex(private val local: Map<Long, Song>) : Map<Long, Song> by local {
    override fun get(key: Long): Song? = local[key] ?: OnlineRegistry.get(key)
    override fun containsKey(key: Long): Boolean = get(key) != null
}

// ───────────────────────── Network: search and stream addresses ─────────────────────────

private class NpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()
        val body: RequestBody? = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder().method(method, body).url(url)
            .header("User-Agent", OnlineEngine.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        // signed in to YouTube (Settings): send the account cookies with YouTube's own requests
        val account = YoutubeAuth.cookie
        if (account != null) {
            val host = android.net.Uri.parse(url).host.orEmpty()
            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                val existing = request.headers().entries
                    .firstOrNull { it.key.equals("Cookie", true) }?.value?.joinToString("; ")
                builder.header("Cookie", listOfNotNull(existing, account).joinToString("; "))
            }
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", url)
            val text = resp.body?.string()
            return NpResponse(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
        }
    }
}

object OnlineEngine {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val URL_TTL_MS = 4L * 60L * 60L * 1000L

    private var ready = false
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    @Synchronized
    private fun ensureInit() {
        if (!ready) {
            NewPipe.init(NpDownloader())
            ready = true
        }
    }

    /** Blocking: call from a background thread. [source] = index in [OnlineSources]. */
    fun search(query: String, source: Int = 0): List<OnlineTrack> {
        ensureInit()
        val (service, filter) = when (source) {
            1 -> ServiceList.YouTube to "music_songs"
            2 -> ServiceList.SoundCloud to "tracks"
            3 -> ServiceList.Bandcamp to "tracks"
            else -> ServiceList.YouTube to "videos"
        }
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val url = item.url ?: return@mapNotNull null
                val ytId = if (url.contains("youtube.com")) Uri.parse(url).getQueryParameter("v") else null
                OnlineTrack(
                    url = url,
                    title = item.name.orEmpty(),
                    artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
                    durationMs = if (item.duration > 0) item.duration * 1000L else 0L,
                    artUrl = item.thumbnails.maxByOrNull { it.height }?.url
                        ?: ytId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
                )
            }
            .distinctBy { it.url }
    }

    private val spotifyRe = Regex("""(?:open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?track/|spotify:track:)([A-Za-z0-9]{22})""")

    /** The track id when [text] is a Spotify track link (or spotify:track: uri), else null. */
    fun spotifyTrackId(text: String): String? = spotifyRe.find(text)?.groupValues?.get(1)

    private fun httpGet(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept-Language", "en")
        return try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun unescape(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    /**
     * Title and artist of a Spotify track, read from its public page (no Spotify account or API key).
     * Blocking: call from a background thread.
     */
    @Throws(IOException::class)
    fun spotifyInfo(trackId: String): Pair<String, String> {
        try {
            val html = httpGet("https://open.spotify.com/track/$trackId")
            val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            val desc = Regex("""<meta property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            if (!title.isNullOrBlank()) {
                // description looks like:  Artist · Album · Song · 2020   (artist first)
                val artist = desc?.let { unescape(it).split("·").firstOrNull()?.trim() }.orEmpty()
                return unescape(title) to artist
            }
        } catch (_: Exception) {
        }
        // fallback: Spotify's public oEmbed gives the title only
        try {
            val json = httpGet("https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode("https://open.spotify.com/track/$trackId", "UTF-8"))
            val title = JSONObject(json).optString("title")
            if (title.isNotBlank()) return title to ""
        } catch (_: Exception) {
        }
        throw IOException("Could not read this Spotify link")
    }

    /** One downloadable audio file: its address, file extension and mime type ([hls] = a playlist of pieces). */
    class AudioSource(val url: String, val ext: String, val mime: String, val hls: Boolean = false)

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Forget every saved address (after signing in or out). */
    fun clearCache() = urlCache.clear()

    /** Forget the saved address of a page (after a playback error), so the next try asks again. */
    fun forget(pageUrl: String) {
        urlCache.remove("$pageUrl|true")
        urlCache.remove("$pageUrl|false")
    }

    /** True when the message means "the site blocked this phone / network" (YouTube's bot check). */
    fun isBlocked(message: String?): Boolean =
        message != null && (message.contains("not a bot", true) || message.contains("Sign in to confirm", true))

    fun isSoundCloud(url: String): Boolean = url.contains("soundcloud.com")

    /**
     * Asks the server for the first two bytes. Returns null when the address works, else the problem.
     * An address that answers 403 (expired / blocked) is skipped instead of being handed to the player.
     */
    private fun probe(url: String): String? {
        return try {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-1")
                .build()
            probeClient.newCall(req).execute().use { r ->
                if (r.code == 200 || r.code == 206) null else "HTTP ${r.code}"
            }
        } catch (e: IOException) {
            e.message ?: "network error"
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Sign in", true) || m.contains("not a bot", true) ->
                "YouTube asked to confirm you are not a bot (it blocks this network)."
            m.contains("age", true) && m.contains("restrict", true) -> "This video is age restricted"
            m.contains("not available", true) || m.contains("unavailable", true) -> "This video is not available"
            m.contains("Unable to resolve host", true) || m.contains("timeout", true) -> "No internet connection"
            m.isBlank() -> "Could not load this song"
            else -> m.take(120)
        }
    }

    private fun audioCandidates(
        info: StreamInfo,
        method: DeliveryMethod,
        mp3First: Boolean = false
    ): List<org.schabi.newpipe.extractor.stream.AudioStream> {
        val audio = info.audioStreams.filter { it.deliveryMethod == method && !it.content.isNullOrBlank() }
        return audio.sortedWith(
            compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                if (mp3First) it.format == MediaFormat.MP3 else it.format == MediaFormat.M4A
            }.thenByDescending { it.averageBitrate }
        )
    }

    /**
     * Blocking: a playable address for the song (audio only, or picture + sound with [video]).
     * SoundCloud gives a playlist (HLS), the other sites a plain file.
     */
    @Throws(IOException::class)
    fun streamUrl(pageUrl: String, video: Boolean): String {
        val key = "$pageUrl|$video"
        val now = System.currentTimeMillis()
        urlCache[key]?.let { if (it.second > now) return it.first }
        try {
            ensureInit()
            val info = StreamInfo.getInfo(pageUrl)
            val tried = mutableListOf<String>()
            var found: String? = null

            fun accept(url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                val problem = probe(url)
                if (problem == null) {
                    found = url
                    return true
                }
                tried += problem
                return false
            }

            if (isSoundCloud(pageUrl)) {
                // the player is told "HLS" for SoundCloud items (see Song.toMediaItem), so only playlists fit here
                for (a in audioCandidates(info, DeliveryMethod.HLS).take(4)) if (accept(a.content)) break
            } else {
                if (video && pageUrl.contains("youtube.com")) {
                    // a stream that already has sound in it (usually 360p; sometimes 720p)
                    val withSound = info.videoStreams
                        .filter { !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                        .sortedByDescending { it.height }
                    for (v in withSound) if (accept(v.content)) break
                }
                if (found == null) {
                    for (a in audioCandidates(info, DeliveryMethod.PROGRESSIVE_HTTP).take(4)) if (accept(a.content)) break
                }
            }
            val url = found
            if (url.isNullOrBlank()) {
                throw IOException(
                    if (tried.isEmpty()) "No playable stream found for this song"
                    else "The server refused the stream (${tried.last()})"
                )
            }
            urlCache[key] = url to (now + URL_TTL_MS)
            return url
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(friendly(e), e)
        }
    }

    /** Blocking: the best audio file of a page, to save on the device. */
    @Throws(IOException::class)
    fun audioSource(pageUrl: String): AudioSource {
        try {
            ensureInit()
            val info = StreamInfo.getInfo(pageUrl)
            val tried = mutableListOf<String>()
            val list = audioCandidates(info, DeliveryMethod.PROGRESSIVE_HTTP).map { it to false } +
                audioCandidates(info, DeliveryMethod.HLS, mp3First = true).map { it to true }
            for ((a, hls) in list.take(6)) {
                val url = a.content ?: continue
                val problem = probe(url)
                if (problem != null) { tried += problem; continue }
                val fmt: MediaFormat? = a.format
                val ext: String
                val mime: String
                if (fmt == null || fmt == MediaFormat.M4A) { ext = "m4a"; mime = "audio/mp4" }
                else if (fmt == MediaFormat.MP3) { ext = "mp3"; mime = "audio/mpeg" }
                else { ext = fmt.suffix; mime = fmt.mimeType }
                return AudioSource(url, ext, mime, hls)
            }
            throw IOException(
                if (tried.isEmpty()) "No downloadable audio found for this song"
                else "The server refused the file (${tried.last()})"
            )
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(friendly(e), e)
        }
    }

    /**
     * When YouTube blocks a song, look for the same song on SoundCloud (then Bandcamp):
     * same title and a length within 10 seconds. Blocking: call from a background thread.
     */
    fun findAlternative(title: String, artist: String, durationMs: Long): OnlineTrack? {
        fun norm(s: String) = s.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?\]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
        val t = norm(title)
        if (t.isEmpty()) return null
        for (source in intArrayOf(2, 3)) {
            val list = try {
                search("$artist $title".trim(), source)
            } catch (_: Exception) {
                emptyList()
            }
            val hit = list.firstOrNull { r ->
                val rt = norm(r.title)
                val titleOk = rt.contains(t) || (rt.isNotEmpty() && t.contains(rt))
                val durOk = durationMs <= 0L || r.durationMs <= 0L ||
                    kotlin.math.abs(r.durationMs - durationMs) <= 10_000L
                titleOk && durOk
            }
            if (hit != null) return hit
        }
        return null
    }
}

/** Runs inside the player's data source: npyt:///VIDEO_ID?v=1 → the real stream address. */
object OnlineResolver : ResolvingDataSource.Resolver {
    @Throws(IOException::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != ONLINE_SCHEME) return dataSpec
        val page = uri.getQueryParameter("u") ?: throw IOException("Bad online address")
        val video = uri.getQueryParameter("v") == "1"
        return dataSpec.withUri(Uri.parse(OnlineEngine.streamUrl(page, video)))
    }
}

// ───────────────────────── Video ─────────────────────────

/**
 * Shows the picture of the playing video (the player has to be playing a video stream).
 * A TextureView is used so rounded corners, animations and panels drawn above it all work.
 */
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = modifier,
        onRelease = { view ->
            try {
                player.clearVideoTextureView(view)
            } catch (_: Exception) {
            }
        }
    )
}
