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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

// ───────────────────────── Online songs (YouTube search, listen and watch) ─────────────────────────
//
// An online song is a normal [Song] with a negative id and a [Song.remoteId] (the video id).
// Its media item uses the private address  npyt:///VIDEO_ID?v=0|1  and the player's data source
// ([OnlineResolver]) turns that into a fresh stream address right when it is needed. That means
// queues, favorites, playlists and crossfade keep working even after the old address has expired.

/** One row of the online search results. */
data class OnlineTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artUrl: String?
)

const val ONLINE_SCHEME = "npyt"

val Song.isOnline: Boolean get() = remoteId != null

/** Stable negative id for a video, so it never collides with MediaStore ids (always positive). */
fun onlineSongId(videoId: String): Long =
    (UUID.nameUUIDFromBytes(videoId.toByteArray()).mostSignificantBits and Long.MAX_VALUE).inv()

fun OnlineTrack.toSong(): Song = Song(
    id = onlineSongId(videoId),
    title = title,
    artist = artist,
    album = "YouTube",
    albumId = -1L,
    durationMs = durationMs,
    dateAdded = System.currentTimeMillis() / 1000L,
    uri = Uri.parse("https://youtu.be/$videoId"),
    remoteId = videoId,
    artUrl = artUrl
)

/** The private address the player resolves into a real stream ([video] = picture and sound). */
fun onlineMediaUri(videoId: String, video: Boolean): Uri =
    Uri.Builder().scheme(ONLINE_SCHEME).authority("stream").appendPath(videoId)
        .appendQueryParameter("v", if (video) "1" else "0").build()

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

    /** Blocking: call from a background thread. */
    fun search(query: String): List<OnlineTrack> {
        ensureInit()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(yt, handler)
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = Uri.parse(item.url).getQueryParameter("v") ?: return@mapNotNull null
                OnlineTrack(
                    videoId = id,
                    title = item.name.orEmpty(),
                    artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
                    durationMs = if (item.duration > 0) item.duration * 1000L else 0L,
                    artUrl = item.thumbnails.maxByOrNull { it.height }?.url
                        ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                )
            }
            .distinctBy { it.videoId }
    }

    /** Blocking: a playable address for the video (audio only, or picture + sound with [video]). */
    @Throws(IOException::class)
    fun streamUrl(videoId: String, video: Boolean): String {
        val key = "$videoId|$video"
        val now = System.currentTimeMillis()
        urlCache[key]?.let { if (it.second > now) return it.first }
        try {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            var url: String? = null
            if (video) {
                // a stream that already has sound in it (usually 360p; sometimes 720p)
                url = info.videoStreams
                    .filter { !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxByOrNull { it.height }?.content
            }
            if (url == null) {
                val audio = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                // m4a first: it seeks reliably
                url = (audio.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
                    ?: audio.maxByOrNull { it.averageBitrate })?.content
            }
            if (url.isNullOrBlank()) throw IOException("No playable stream found for this video")
            urlCache[key] = url to (now + URL_TTL_MS)
            return url
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Could not load this video", e)
        }
    }
}

/** Runs inside the player's data source: npyt:///VIDEO_ID?v=1 → the real stream address. */
object OnlineResolver : ResolvingDataSource.Resolver {
    @Throws(IOException::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != ONLINE_SCHEME) return dataSpec
        val id = uri.lastPathSegment ?: throw IOException("Bad online address")
        val video = uri.getQueryParameter("v") == "1"
        return dataSpec.withUri(Uri.parse(OnlineEngine.streamUrl(id, video)))
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
