package com.example.musicapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import java.io.FileOutputStream

/**
 * Home-screen widget: cover, title, artist and previous / play-pause / next.
 * The widget takes its colour from the playing cover (a translucent, glass-like tint).
 * Android widgets cannot blur what is behind them, so the glass look is translucency + a light rim.
 */
object MusicWidget {
    const val ACTION_PLAY_PAUSE = "com.example.musicapp.WIDGET_PLAY_PAUSE"
    const val ACTION_NEXT = "com.example.musicapp.WIDGET_NEXT"
    const val ACTION_PREV = "com.example.musicapp.WIDGET_PREV"

    private const val PREFS = "widget"
    private const val COVER_FILE = "widget_cover.jpg"
    private const val DEFAULT_TINT = 0xFF2C2C2E.toInt()

    /** Called by the playback service whenever the song or the play state changes. */
    fun pushFromPlayer(context: Context, p: Player) {
        val app = context.applicationContext
        val ids = AppWidgetManager.getInstance(app)
            .getAppWidgetIds(ComponentName(app, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val item = p.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
        val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
        val mediaId = item?.mediaId.orEmpty()
        val songUri = item?.localConfiguration?.uri
        val artUri = item?.mediaMetadata?.artworkUri
        val playing = p.isPlaying
        Thread {
            try {
                val st = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val songChanged = st.getString("id", "") != mediaId
                var tint = st.getInt("tint", DEFAULT_TINT)
                if (songChanged) {
                    val bmp = loadCover(app, mediaId, songUri, artUri)
                    if (bmp != null) {
                        FileOutputStream(File(app.filesDir, COVER_FILE)).use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
                        }
                        tint = tintFrom(bmp)
                    } else {
                        File(app.filesDir, COVER_FILE).delete()
                        tint = DEFAULT_TINT
                    }
                }
                st.edit()
                    .putString("id", mediaId)
                    .putString("title", title)
                    .putString("artist", artist)
                    .putBoolean("playing", playing)
                    .putInt("tint", tint)
                    .apply()
                render(app)
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun rid(context: Context, name: String, type: String): Int =
        context.resources.getIdentifier(name, type, context.packageName)

    /** Draws the widget from the last saved state (also used when the app is not running). */
    fun render(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val st = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = st.getString("title", "").orEmpty()
        val artist = st.getString("artist", "").orEmpty()
        val playing = st.getBoolean("playing", false)
        val tint = st.getInt("tint", DEFAULT_TINT)

        // Resources are looked up by name, so a missing widget layout can never break the build.
        val layoutId = rid(context, "widget_player", "layout")
        if (layoutId == 0) return
        val v = RemoteViews(context.packageName, layoutId)
        v.setTextViewText(rid(context, "w_title", "id"), title.ifBlank { "Music App" })
        v.setTextViewText(rid(context, "w_artist", "id"), artist.ifBlank { "Tap to open" })
        v.setImageViewResource(
            rid(context, "w_play", "id"),
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        v.setInt(rid(context, "w_tint", "id"), "setColorFilter", tint)

        val coverFile = File(context.filesDir, COVER_FILE)
        val cover = if (coverFile.exists()) BitmapFactory.decodeFile(coverFile.absolutePath) else null
        if (cover != null) v.setImageViewBitmap(rid(context, "w_cover", "id"), cover)
        else v.setImageViewResource(rid(context, "w_cover", "id"), rid(context, "ic_launcher", "mipmap"))

        v.setOnClickPendingIntent(rid(context, "w_play", "id"), broadcast(context, ACTION_PLAY_PAUSE, 1))
        v.setOnClickPendingIntent(rid(context, "w_next", "id"), broadcast(context, ACTION_NEXT, 2))
        v.setOnClickPendingIntent(rid(context, "w_prev", "id"), broadcast(context, ACTION_PREV, 3))
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPi = PendingIntent.getActivity(
            context, 10, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(rid(context, "w_cover", "id"), openPi)
        v.setOnClickPendingIntent(rid(context, "w_title", "id"), openPi)
        v.setOnClickPendingIntent(rid(context, "w_artist", "id"), openPi)
        mgr.updateAppWidget(ids, v)
    }

    /** Sends a command to the playback session without opening the app. */
    fun control(context: Context, block: (MediaController) -> Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                block(future.get())
            } catch (e: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed({ MediaController.releaseFuture(future) }, 1500L)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent {
        val i = Intent(context, MusicWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun loadCover(context: Context, mediaId: String, songUri: Uri?, artUri: Uri?): Bitmap? {
        val custom = File(context.filesDir, "auto_cover_$mediaId.jpg")
        if (custom.exists()) {
            BitmapFactory.decodeFile(custom.absolutePath)?.let { return it }
        }
        if (songUri != null && songUri.scheme != ONLINE_SCHEME) {
            try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, songUri)
                    val bytes = r.embeddedPicture
                    if (bytes != null) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
                    }
                } finally {
                    r.release()
                }
            } catch (e: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    return context.contentResolver.loadThumbnail(songUri, Size(320, 320), null)
                } catch (e: Exception) {
                }
            }
        }
        if (artUri != null && (artUri.scheme == "http" || artUri.scheme == "https")) {
            // online song: its thumbnail
            try {
                val c = java.net.URL(artUri.toString()).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                try {
                    c.inputStream.use { BitmapFactory.decodeStream(it)?.let { b -> return b } }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
            }
            return null
        }
        if (artUri != null) {
            try {
                context.contentResolver.openInputStream(artUri)?.use {
                    BitmapFactory.decodeStream(it)?.let { b -> return b }
                }
            } catch (e: Exception) {
            }
        }
        return null
    }

    /** Average cover colour, darkened so white text stays readable. */
    private fun tintFrom(b: Bitmap): Int {
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val k = 0.5f
        return Color.rgb(
            (Color.red(px) * k).toInt(),
            (Color.green(px) * k).toInt(),
            (Color.blue(px) * k).toInt()
        )
    }
}

class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        Thread { try { MusicWidget.render(app) } catch (e: Exception) { } }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext
        when (intent.action) {
            MusicWidget.ACTION_PLAY_PAUSE ->
                MusicWidget.control(app) { if (it.isPlaying) it.pause() else it.play() }
            MusicWidget.ACTION_NEXT ->
                MusicWidget.control(app) { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
            MusicWidget.ACTION_PREV ->
                MusicWidget.control(app) {
                    if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0)
                    else it.seekToPreviousMediaItem()
                }
        }
    }
}
