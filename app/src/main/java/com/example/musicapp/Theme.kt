package com.example.musicapp

import java.io.File
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// ───────────────────────── Theme / Liquid Glass ─────────────────────────

/**
 * Colours for the whole app.
 * When [tint] is given (the "Cover" theme) the background, cards, glass, accent and the
 * selected highlight are all derived from the colour of the playing song's cover.
 */
class Palette(
    val dark: Boolean,
    baseAccent: Color,
    glass: Float,
    val corner: Float,
    tint: Color? = null,
    val opaqueClassic: Boolean = false
) {
    val tinted = tint != null

    private val hsv = FloatArray(3).also {
        if (tint != null) android.graphics.Color.colorToHSV(tint.toArgb(), it)
    }
    private val hue = hsv[0]
    // keep grey covers grey, give coloured covers a strong, readable saturation
    private val sat = if (!tinted) 0f else if (hsv[1] < 0.08f) hsv[1] else hsv[1].coerceIn(0.4f, 0.9f)

    private fun tone(satScale: Float, value: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, (sat * satScale).coerceIn(0f, 1f), value)))

    val accent: Color = if (tinted) tone(0.8f, 0.97f) else baseAccent
    val bg: Color = if (tinted) tone(0.9f, 0.17f) else if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val bgTop: Color = if (tinted) tone(0.85f, 0.31f) else bg
    val bgBrush: Brush = Brush.verticalGradient(listOf(bgTop, bg))
    val card: Color = if (tinted) tone(0.75f, 0.29f) else if (dark) Color(0xFF1C1C1E) else Color.White
    val segment: Color = if (tinted) tone(0.7f, 0.42f) else if (dark) Color(0xFF636366) else Color.White
    val text: Color = if (dark) Color.White else Color.Black
    val secondary: Color = if (tinted) Color.White.copy(alpha = 0.62f) else Color(0xFF8E8E93)
    val glassTop: Color =
        if (opaqueClassic) card else if (tinted) tone(0.7f, 0.38f).copy(alpha = glass)
        else if (dark) Color(0xFF3A3A3C).copy(alpha = glass) else Color.White.copy(alpha = glass)
    val glassBottom: Color =
        if (opaqueClassic) card else (if (tinted) tone(0.8f, 0.22f) else if (dark) Color(0xFF1C1C1E) else Color(0xFFF7F7FA))
            .copy(alpha = (glass - 0.14f).coerceAtLeast(0.2f))
    val selectedPill: Color = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)
    val coverTop: Color = if (tinted) tone(0.6f, 0.40f) else if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
    val coverBottom: Color = if (tinted) tone(0.7f, 0.28f) else if (dark) Color(0xFF2C2C2E) else Color(0xFFD1D1D6)
}

/**
 * Finds the main colour of a song's cover (weighted towards vivid, bright pixels).
 * Returns null when the song has no cover.
 */
suspend fun songTint(context: Context, song: Song): Color? = withContext(Dispatchers.Default) {
    try {
        val img = loadArt(context, song, 96) ?: return@withContext null
        val src = img.asAndroidBitmap()
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val n = 24
        val small = Bitmap.createScaledBitmap(soft, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        val hsv = FloatArray(3)
        var wSum = 0f
        var cx = 0f
        var cy = 0f
        var sSum = 0f
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2]
            val rad = hsv[0] * PI.toFloat() / 180f
            cx += cos(rad) * w
            cy += sin(rad) * w
            sSum += hsv[1] * w
            wSum += w
        }
        if (wSum / px.size < 0.04f) {
            // black-and-white cover
            Color(android.graphics.Color.HSVToColor(floatArrayOf(0f, 0f, 0.6f)))
        } else {
            var h = atan2(cy, cx) * 180f / PI.toFloat()
            if (h < 0f) h += 360f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(h, (sSum / wSum).coerceIn(0f, 1f), 0.8f)))
        }
    } catch (e: Exception) {
        null
    }
}

/** iOS-style "liquid glass": translucent fill, specular rim light, soft shadow. */
fun Modifier.glass(shape: Shape, p: Palette): Modifier =
    this
        .shadow(
            elevation = 18.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.25f),
            spotColor = Color.Black.copy(alpha = 0.25f)
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(p.glassTop, p.glassBottom)))
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (p.dark) 0.45f else 1f),
                    Color.White.copy(alpha = 0.06f),
                    Color.White.copy(alpha = if (p.dark) 0.22f else 0.7f)
                )
            ),
            shape = shape
        )

// ───────────────────────── Cover art ─────────────────────────

private val artCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

fun invalidateSongCover(songId: Long) { for (size in listOf(96, 300, 400, 700)) artCache.remove("${songId}_$size") }

/**
 * The cover embedded in the audio file itself is the original, full-resolution artwork.
 * It is decoded with the largest sample size that still keeps at least [px] pixels,
 * so small list covers stay light and the full player gets the real image.
 */
private fun loadEmbedded(context: Context, song: Song, px: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, song.uri)
        val data = retriever.embeddedPicture ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
        }
    }
}

private suspend fun loadArt(context: Context, song: Song, px: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            val customCover = File(context.filesDir, "auto_cover_${song.id}.jpg")
            val bmp = (if (customCover.exists()) BitmapFactory.decodeFile(customCover.absolutePath) else null)
                ?: loadEmbedded(context, song, px) ?: if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
            } else {
                val u = ContentUris.withAppendedId(albumArtBase, song.albumId)
                context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
            }
            bmp?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

@Composable
fun Cover(
    song: Song,
    modifier: Modifier,
    radius: Dp,
    p: Palette,
    px: Int = 300,
    round: Boolean = false
) {
    val context = LocalContext.current
    // Keyed by song + size so the image is reloaded whenever the song changes
    // (this is what keeps the mini player cover in sync with the playing track).
    val cacheKey = "${song.id}_$px"
    var art by remember(cacheKey) { mutableStateOf<ImageBitmap?>(artCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (art == null) {
            val loaded = loadArt(context, song, px)
            if (loaded != null) artCache.put(cacheKey, loaded)
            art = loaded
        }
    }
    val shape: Shape = if (round) CircleShape else RoundedCornerShape(radius * p.corner)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.coverTop, p.coverBottom))),
        contentAlignment = Alignment.Center
    ) {
        val img = art
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            GlyphIcon(Glyph.Note, p.secondary.copy(alpha = 0.7f), Modifier.fillMaxSize(0.4f))
        }
    }
}

// ───────────────────────── Icons (drawn, no extra deps) ─────────────────────────

enum class Glyph { Note, Album, Artist, Tune, Play, Pause, Next, Heart, HeartOutline, Back, Close, Plus, Prev, Shuffle, Repeat, ChevronDown, Edit, Search, Queue, Share, More, PlusCircle, Speaker }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (glyph) {
            Glyph.Note -> {
                val sw = w * 0.09f
                drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.3f, h * 0.78f))
                drawLine(color, Offset(w * 0.46f, h * 0.78f), Offset(w * 0.46f, h * 0.16f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.46f, h * 0.16f), Offset(w * 0.84f, h * 0.30f), sw * 1.4f, StrokeCap.Round)
            }
            Glyph.Album -> {
                val sw = w * 0.08f
                drawRoundRect(
                    color, Offset(w * 0.12f, h * 0.12f), GSize(w * 0.76f, h * 0.76f),
                    CornerRadius(w * 0.16f), style = Stroke(sw)
                )
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
            }
            Glyph.Artist -> {
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, h * 0.3f))
                drawArc(
                    color, 180f, 180f, true,
                    topLeft = Offset(w * 0.14f, h * 0.6f), size = GSize(w * 0.72f, h * 0.8f)
                )
            }
            Glyph.Tune -> {
                val sw = w * 0.08f
                drawLine(color, Offset(w * 0.12f, h * 0.25f), Offset(w * 0.88f, h * 0.25f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.50f), Offset(w * 0.88f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.75f), Offset(w * 0.88f, h * 0.75f), sw, StrokeCap.Round)
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.32f, h * 0.25f))
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.68f, h * 0.50f))
                drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.40f, h * 0.75f))
            }
            Glyph.Heart, Glyph.HeartOutline -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.88f)
                    cubicTo(w * 0.08f, h * 0.58f, w * 0.0f, h * 0.32f, w * 0.14f, h * 0.2f)
                    cubicTo(w * 0.27f, h * 0.08f, w * 0.45f, h * 0.14f, w * 0.5f, h * 0.3f)
                    cubicTo(w * 0.55f, h * 0.14f, w * 0.73f, h * 0.08f, w * 0.86f, h * 0.2f)
                    cubicTo(w * 1.0f, h * 0.32f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
                    close()
                }
                if (glyph == Glyph.Heart) drawPath(path, color)
                else drawPath(path, color, style = Stroke(w * 0.09f))
            }
            Glyph.Back -> {
                val sw = w * 0.11f
                drawLine(color, Offset(w * 0.65f, h * 0.15f), Offset(w * 0.30f, h * 0.5f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.65f, h * 0.85f), sw, StrokeCap.Round)
            }
            Glyph.Close -> {
                val sw = w * 0.09f
                drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round)
            }
            Glyph.Plus -> {
                val sw = w * 0.1f
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), sw, StrokeCap.Round)
            }
            Glyph.Play -> {
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.08f)
                    lineTo(w * 0.22f, h * 0.92f)
                    lineTo(w * 0.94f, h * 0.5f)
                    close()
                }
                drawPath(path, color)
            }
            Glyph.Pause -> {
                drawRoundRect(color, Offset(w * 0.16f, h * 0.08f), GSize(w * 0.24f, h * 0.84f), CornerRadius(w * 0.06f))
                drawRoundRect(color, Offset(w * 0.60f, h * 0.08f), GSize(w * 0.24f, h * 0.84f), CornerRadius(w * 0.06f))
            }
            Glyph.Prev -> {
                val path = Path().apply {
                    moveTo(w * 0.96f, h * 0.12f)
                    lineTo(w * 0.96f, h * 0.88f)
                    lineTo(w * 0.42f, h * 0.5f)
                    close()
                    moveTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.50f, h * 0.88f)
                    lineTo(w * 0.08f, h * 0.5f)
                    close()
                }
                drawPath(path, color)
                drawRoundRect(color, Offset(w * 0.04f, h * 0.12f), GSize(w * 0.10f, h * 0.76f), CornerRadius(w * 0.03f))
            }
            Glyph.Shuffle -> {
                val sw = w * 0.08f
                val a = Path().apply {
                    moveTo(w * 0.10f, h * 0.30f)
                    cubicTo(w * 0.40f, h * 0.30f, w * 0.50f, h * 0.70f, w * 0.76f, h * 0.70f)
                }
                val b = Path().apply {
                    moveTo(w * 0.10f, h * 0.70f)
                    cubicTo(w * 0.40f, h * 0.70f, w * 0.50f, h * 0.30f, w * 0.76f, h * 0.30f)
                }
                drawPath(a, color, style = Stroke(sw, cap = StrokeCap.Round))
                drawPath(b, color, style = Stroke(sw, cap = StrokeCap.Round))
                val t1 = Path().apply {
                    moveTo(w * 0.94f, h * 0.30f)
                    lineTo(w * 0.72f, h * 0.16f)
                    lineTo(w * 0.72f, h * 0.44f)
                    close()
                }
                val t2 = Path().apply {
                    moveTo(w * 0.94f, h * 0.70f)
                    lineTo(w * 0.72f, h * 0.56f)
                    lineTo(w * 0.72f, h * 0.84f)
                    close()
                }
                drawPath(t1, color)
                drawPath(t2, color)
            }
            Glyph.Repeat -> {
                val sw = w * 0.08f
                drawRoundRect(
                    color, Offset(w * 0.10f, h * 0.28f), GSize(w * 0.80f, h * 0.46f),
                    CornerRadius(w * 0.16f), style = Stroke(sw)
                )
                val t = Path().apply {
                    moveTo(w * 0.78f, h * 0.14f)
                    lineTo(w * 0.78f, h * 0.42f)
                    lineTo(w * 0.98f, h * 0.28f)
                    close()
                }
                drawPath(t, color)
            }
            Glyph.ChevronDown -> {
                val sw = w * 0.11f
                drawLine(color, Offset(w * 0.15f, h * 0.35f), Offset(w * 0.5f, h * 0.68f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.85f, h * 0.35f), sw, StrokeCap.Round)
            }
            Glyph.Edit -> {
                drawLine(color, Offset(w * 0.30f, h * 0.68f), Offset(w * 0.76f, h * 0.22f), w * 0.15f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, h * 0.90f), Offset(w * 0.86f, h * 0.90f), w * 0.07f, StrokeCap.Round)
            }
            Glyph.Search -> {
                val sw = w * 0.10f
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.44f, h * 0.44f), style = Stroke(sw))
                drawLine(color, Offset(w * 0.65f, h * 0.65f), Offset(w * 0.88f, h * 0.88f), sw, StrokeCap.Round)
            }
            Glyph.Queue -> {
                val sw = w * 0.09f
                drawLine(color, Offset(w * 0.12f, h * 0.24f), Offset(w * 0.88f, h * 0.24f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.50f), Offset(w * 0.88f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.76f), Offset(w * 0.46f, h * 0.76f), sw, StrokeCap.Round)
                val tri = Path().apply {
                    moveTo(w * 0.62f, h * 0.62f)
                    lineTo(w * 0.62f, h * 0.90f)
                    lineTo(w * 0.90f, h * 0.76f)
                    close()
                }
                drawPath(tri, color)
            }
            Glyph.Share -> {
                val sw = w * 0.07f
                val a = Offset(w * 0.78f, h * 0.20f)
                val b = Offset(w * 0.22f, h * 0.50f)
                val c = Offset(w * 0.78f, h * 0.80f)
                drawLine(color, b, a, sw, StrokeCap.Round)
                drawLine(color, b, c, sw, StrokeCap.Round)
                drawCircle(color, w * 0.13f, a, style = Stroke(sw))
                drawCircle(color, w * 0.13f, b, style = Stroke(sw))
                drawCircle(color, w * 0.13f, c, style = Stroke(sw))
            }
            Glyph.More -> {
                drawCircle(color, w * 0.075f, Offset(w * 0.5f, h * 0.2f))
                drawCircle(color, w * 0.075f, Offset(w * 0.5f, h * 0.5f))
                drawCircle(color, w * 0.075f, Offset(w * 0.5f, h * 0.8f))
            }
            Glyph.PlusCircle -> {
                val sw = w * 0.07f
                drawCircle(color, radius = w * 0.44f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
                drawLine(color, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.72f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.72f, h * 0.5f), sw, StrokeCap.Round)
            }
            Glyph.Speaker -> {
                val sw = w * 0.07f
                drawRoundRect(color, Offset(w * 0.22f, h * 0.08f), GSize(w * 0.56f, h * 0.84f), CornerRadius(w * 0.1f), style = Stroke(sw))
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.62f), style = Stroke(sw))
                drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.5f, h * 0.27f))
            }
            Glyph.Next -> {
                val path = Path().apply {
                    moveTo(w * 0.04f, h * 0.12f)
                    lineTo(w * 0.04f, h * 0.88f)
                    lineTo(w * 0.58f, h * 0.5f)
                    close()
                    moveTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.50f, h * 0.88f)
                    lineTo(w * 0.92f, h * 0.5f)
                    close()
                }
                drawPath(path, color)
                drawRoundRect(color, Offset(w * 0.86f, h * 0.12f), GSize(w * 0.10f, h * 0.76f), CornerRadius(w * 0.03f))
            }
        }
    }
}
