package com.example.musicapp

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Draws a "now playing" picture (cover, title, artist, small sound-wave) in the colour of the
 * cover and opens the share sheet with it.
 */
fun shareSongCard(context: Context, song: Song) {
    val app = context.applicationContext
    Thread {
        try {
            val cover = MusicWidget.loadCover(
                app, song.id.toString(),
                if (song.isOnline) null else song.uri,
                if (song.isOnline) song.artUrl?.let { android.net.Uri.parse(it) }
                else ContentUris.withAppendedId(albumArtBase, song.albumId)
            )
            val card = drawNowPlayingCard(song, cover)
            val dir = File(app.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "now_playing.png")
            FileOutputStream(file).use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val text = "${song.title} — ${song.artist}" +
                if (song.remoteId != null) "\n${song.remoteId}"
                else if (song.album.isNotBlank()) "\n${song.album}" else ""
            Handler(Looper.getMainLooper()).post {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "Share song")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Toast.makeText(context, "Unable to share this song", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Unable to share this song", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

private fun drawNowPlayingCard(song: Song, cover: Bitmap?): Bitmap {
    val w = 1080
    val h = 1350
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)

    // background: the cover's average colour, darkened
    var base = Color.rgb(44, 44, 46)
    if (cover != null) {
        val px = Bitmap.createScaledBitmap(cover, 1, 1, true).getPixel(0, 0)
        base = Color.rgb((Color.red(px) * 0.5f).toInt(), (Color.green(px) * 0.5f).toInt(), (Color.blue(px) * 0.5f).toInt())
    }
    c.drawColor(base)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // soft big circle for depth (flat, no gradient)
    fill.color = Color.argb(28, 255, 255, 255)
    c.drawCircle(w * 0.88f, h * 0.12f, 360f, fill)
    c.drawCircle(w * 0.08f, h * 0.9f, 300f, fill)

    // cover with rounded corners and a thin light rim
    val size = 760f
    val left = (w - size) / 2f
    val top = 150f
    val rect = RectF(left, top, left + size, top + size)
    val radius = 56f
    if (cover != null) {
        c.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawBitmap(cover, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        c.restore()
    } else {
        fill.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(rect, radius, radius, fill)
    }
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(70, 255, 255, 255)
    }
    c.drawRoundRect(rect, radius, radius, rim)

    // title and artist
    val maxTextW = 840f
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 44f
        textAlign = Paint.Align.CENTER
    }
    val title = TextUtils.ellipsize(song.title, titlePaint, maxTextW, TextUtils.TruncateAt.END).toString()
    val artist = TextUtils.ellipsize(song.artist, artistPaint, maxTextW, TextUtils.TruncateAt.END).toString()
    c.drawText(title, w / 2f, top + size + 110f, titlePaint)
    c.drawText(artist, w / 2f, top + size + 176f, artistPaint)

    // small sound-wave, different for every song
    val bars = 31
    val barW = 14f
    val gap = 14f
    val totalW = bars * barW + (bars - 1) * gap
    var x = (w - totalW) / 2f
    val midY = top + size + 290f
    val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }
    var seed = abs(song.id.hashCode()) + 7
    for (i in 0 until bars) {
        seed = (seed * 1103515245 + 12345) and 0x7fffffff
        val env = 1f - abs(i - bars / 2f) / (bars / 2f) * 0.55f
        val hh = (18f + (seed % 100) / 100f * 70f) * env
        c.drawRoundRect(RectF(x, midY - hh, x + barW, midY + hh), 7f, 7f, wave)
        x += barW + gap
    }

    // footer
    val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    c.drawText("Now playing  ·  Music App", w / 2f, h - 70f, foot)
    return out
}
