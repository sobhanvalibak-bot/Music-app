@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.musicapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ───────────────────────── Icons ─────────────────────────

enum class SmsIconKind {
    Grid, Send, Reply, Star, StarFilled, Pin, PinFilled, Copy, Trash, Eye, EyeOff,
    Message, Contacts, Check, ChevronUp, Dot, Bolt, Burst,
    Phone, Backspace, Keypad, Clock, ArrowIn, ArrowOut, Mic
}

@Composable
fun SmsIcon(kind: SmsIconKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            SmsIconKind.Grid -> {
                val s = w * 0.3f
                val r = CornerRadius(w * 0.08f)
                for (cx in listOf(0.14f, 0.56f)) for (cy in listOf(0.14f, 0.56f)) {
                    drawRoundRect(color, Offset(w * cx, h * cy), Size(s, s), r, style = st)
                }
            }
            SmsIconKind.Send -> {
                val sw2 = w * 0.11f
                drawLine(color, Offset(w * 0.5f, h * 0.84f), Offset(w * 0.5f, h * 0.2f), sw2, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.24f, h * 0.44f)
                    lineTo(w * 0.5f, h * 0.18f)
                    lineTo(w * 0.76f, h * 0.44f)
                }
                drawPath(head, color, style = Stroke(width = sw2, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            SmsIconKind.Reply -> {
                val head = Path().apply {
                    moveTo(w * 0.4f, h * 0.2f)
                    lineTo(w * 0.16f, h * 0.42f)
                    lineTo(w * 0.4f, h * 0.64f)
                }
                drawPath(head, color, style = st)
                val tail = Path().apply {
                    moveTo(w * 0.16f, h * 0.42f)
                    lineTo(w * 0.58f, h * 0.42f)
                    cubicTo(w * 0.82f, h * 0.42f, w * 0.86f, h * 0.6f, w * 0.86f, h * 0.8f)
                }
                drawPath(tail, color, style = st)
            }
            SmsIconKind.Star, SmsIconKind.StarFilled -> {
                val path = Path()
                for (i in 0 until 10) {
                    val ang = (-Math.PI / 2 + i * Math.PI / 5).toFloat()
                    val rad = if (i % 2 == 0) w * 0.42f else w * 0.18f
                    val x = w * 0.5f + cos(ang) * rad
                    val y = h * 0.54f + sin(ang) * rad
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                if (kind == SmsIconKind.StarFilled) drawPath(path, color) else drawPath(path, color, style = st)
            }
            SmsIconKind.Pin, SmsIconKind.PinFilled -> {
                val body = Path().apply {
                    moveTo(w * 0.36f, h * 0.12f)
                    lineTo(w * 0.64f, h * 0.12f)
                    lineTo(w * 0.60f, h * 0.46f)
                    lineTo(w * 0.76f, h * 0.58f)
                    lineTo(w * 0.24f, h * 0.58f)
                    lineTo(w * 0.40f, h * 0.46f)
                    close()
                }
                if (kind == SmsIconKind.PinFilled) drawPath(body, color) else drawPath(body, color, style = st)
                drawLine(color, Offset(w * 0.5f, h * 0.58f), Offset(w * 0.5f, h * 0.9f), sw, StrokeCap.Round)
            }
            SmsIconKind.Copy -> {
                drawRoundRect(color, Offset(w * 0.34f, h * 0.1f), Size(w * 0.54f, h * 0.56f), CornerRadius(w * 0.1f), style = st)
                drawRoundRect(color, Offset(w * 0.12f, h * 0.34f), Size(w * 0.54f, h * 0.56f), CornerRadius(w * 0.1f), style = st)
            }
            SmsIconKind.Trash -> {
                drawLine(color, Offset(w * 0.16f, h * 0.28f), Offset(w * 0.84f, h * 0.28f), sw, StrokeCap.Round)
                val handle = Path().apply {
                    moveTo(w * 0.4f, h * 0.28f)
                    lineTo(w * 0.4f, h * 0.14f)
                    lineTo(w * 0.6f, h * 0.14f)
                    lineTo(w * 0.6f, h * 0.28f)
                }
                drawPath(handle, color, style = st)
                val bin = Path().apply {
                    moveTo(w * 0.26f, h * 0.28f)
                    lineTo(w * 0.32f, h * 0.86f)
                    lineTo(w * 0.68f, h * 0.86f)
                    lineTo(w * 0.74f, h * 0.28f)
                }
                drawPath(bin, color, style = st)
            }
            SmsIconKind.Eye, SmsIconKind.EyeOff -> {
                drawOval(color, Offset(w * 0.08f, h * 0.26f), Size(w * 0.84f, h * 0.48f), style = st)
                drawCircle(color = color, radius = w * 0.12f, center = Offset(w * 0.5f, h * 0.5f), style = st)
                if (kind == SmsIconKind.EyeOff) {
                    drawLine(color, Offset(w * 0.18f, h * 0.86f), Offset(w * 0.82f, h * 0.14f), sw, StrokeCap.Round)
                }
            }
            SmsIconKind.Message -> {
                drawRoundRect(color, Offset(w * 0.1f, h * 0.14f), Size(w * 0.8f, h * 0.56f), CornerRadius(w * 0.18f), style = st)
                val tail = Path().apply {
                    moveTo(w * 0.3f, h * 0.7f)
                    lineTo(w * 0.24f, h * 0.88f)
                    lineTo(w * 0.5f, h * 0.7f)
                }
                drawPath(tail, color, style = st)
            }
            SmsIconKind.Contacts -> {
                drawCircle(color = color, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.32f), style = st)
                drawArc(color, 180f, 180f, false, topLeft = Offset(w * 0.18f, h * 0.58f), size = Size(w * 0.64f, h * 0.5f), style = st)
            }
            SmsIconKind.Check -> {
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.72f)
                    lineTo(w * 0.8f, h * 0.3f)
                }
                drawPath(path, color, style = st)
            }
            SmsIconKind.ChevronUp -> {
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.62f)
                    lineTo(w * 0.5f, h * 0.34f)
                    lineTo(w * 0.8f, h * 0.62f)
                }
                drawPath(path, color, style = st)
            }
            SmsIconKind.Dot -> drawCircle(color = color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            SmsIconKind.Bolt -> {
                val path = Path().apply {
                    moveTo(w * 0.56f, h * 0.1f)
                    lineTo(w * 0.26f, h * 0.56f)
                    lineTo(w * 0.48f, h * 0.56f)
                    lineTo(w * 0.42f, h * 0.9f)
                    lineTo(w * 0.74f, h * 0.4f)
                    lineTo(w * 0.52f, h * 0.4f)
                    close()
                }
                drawPath(path, color, style = st)
            }
            SmsIconKind.Phone -> {
                val path = Path().apply {
                    moveTo(w * 0.26f, h * 0.14f)
                    lineTo(w * 0.40f, h * 0.14f)
                    lineTo(w * 0.46f, h * 0.34f)
                    lineTo(w * 0.36f, h * 0.42f)
                    cubicTo(w * 0.42f, h * 0.56f, w * 0.50f, h * 0.62f, w * 0.62f, h * 0.68f)
                    lineTo(w * 0.70f, h * 0.58f)
                    lineTo(w * 0.90f, h * 0.64f)
                    lineTo(w * 0.90f, h * 0.78f)
                    cubicTo(w * 0.90f, h * 0.86f, w * 0.84f, h * 0.90f, w * 0.76f, h * 0.90f)
                    cubicTo(w * 0.44f, h * 0.86f, w * 0.14f, h * 0.56f, w * 0.10f, h * 0.24f)
                    cubicTo(w * 0.10f, h * 0.18f, w * 0.18f, h * 0.14f, w * 0.26f, h * 0.14f)
                    close()
                }
                drawPath(path, color, style = st)
            }
            SmsIconKind.Backspace -> {
                val path = Path().apply {
                    moveTo(w * 0.36f, h * 0.22f)
                    lineTo(w * 0.86f, h * 0.22f)
                    lineTo(w * 0.86f, h * 0.78f)
                    lineTo(w * 0.36f, h * 0.78f)
                    lineTo(w * 0.1f, h * 0.5f)
                    close()
                }
                drawPath(path, color, style = st)
                drawLine(color, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.7f, h * 0.62f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.7f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), sw, StrokeCap.Round)
            }
            SmsIconKind.Keypad -> {
                for (cx in listOf(0.2f, 0.5f, 0.8f)) for (cy in listOf(0.16f, 0.4f, 0.64f, 0.88f)) {
                    drawCircle(color, radius = w * 0.07f, center = Offset(w * cx, h * cy))
                }
            }
            SmsIconKind.Clock -> {
                drawCircle(color, radius = w * 0.4f, center = Offset(w * 0.5f, h * 0.5f), style = st)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.28f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.66f, h * 0.58f), sw, StrokeCap.Round)
            }
            SmsIconKind.ArrowIn -> {
                drawLine(color, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.24f, h * 0.76f), sw, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.24f, h * 0.38f)
                    lineTo(w * 0.24f, h * 0.76f)
                    lineTo(w * 0.62f, h * 0.76f)
                }
                drawPath(head, color, style = st)
            }
            SmsIconKind.ArrowOut -> {
                drawLine(color, Offset(w * 0.24f, h * 0.76f), Offset(w * 0.78f, h * 0.22f), sw, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.4f, h * 0.22f)
                    lineTo(w * 0.78f, h * 0.22f)
                    lineTo(w * 0.78f, h * 0.6f)
                }
                drawPath(head, color, style = st)
            }
            SmsIconKind.Mic -> {
                drawRoundRect(color, Offset(w * 0.36f, h * 0.1f), Size(w * 0.28f, h * 0.46f), CornerRadius(w * 0.14f), style = st)
                val arc = Path().apply {
                    moveTo(w * 0.22f, h * 0.46f)
                    cubicTo(w * 0.22f, h * 0.8f, w * 0.78f, h * 0.8f, w * 0.78f, h * 0.46f)
                }
                drawPath(arc, color, style = st)
                drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.9f), sw, StrokeCap.Round)
            }
            SmsIconKind.Burst -> {
                for (i in 0 until 8) {
                    val a = (i * Math.PI / 4).toFloat()
                    drawLine(
                        color,
                        Offset(w * 0.5f + cos(a) * w * 0.16f, h * 0.5f + sin(a) * h * 0.16f),
                        Offset(w * 0.5f + cos(a) * w * 0.42f, h * 0.5f + sin(a) * h * 0.42f),
                        sw, StrokeCap.Round
                    )
                }
            }
        }
    }
}

/** Round glass Misc button that sits next to the tab bar. */
@Composable
fun MiscButton(active: Boolean, p: Palette, onClick: () -> Unit, songs: Boolean = false) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else if (active) 1.08f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 420f),
        label = "miscScale"
    )
    val tint by animateColorAsState(if (active) p.accent else Color.White, label = "miscTint")
    val onClickS by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(BarHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .iosGlass(CircleShape, null)
            .background(if (active) p.accent.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClickS() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (songs) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon(Glyph.Note, tint, Modifier.size(22.dp))
                Spacer(Modifier.height(1.dp))
                Text("Songs", color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        } else {
            SmsIcon(SmsIconKind.Grid, tint, Modifier.size(24.dp))
        }
    }
}

// ───────────────────────── Small helpers ─────────────────────────

private val SmsAvatarColors = listOf(
    Color(0xFFFF375F), Color(0xFF0A84FF), Color(0xFF30D158),
    Color(0xFFFF9F0A), Color(0xFFBF5AF2), Color(0xFF64D2FF)
)

@Composable
internal fun SmsAvatar(title: String, size: Dp, modifier: Modifier = Modifier) {
    val c = SmsAvatarColors[(title.hashCode() and 0x7fffffff) % SmsAvatarColors.size]
    val first = title.trim().firstOrNull()
    Box(
        modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        if (first == null || first == '+' || first.isDigit()) {
            SmsIcon(SmsIconKind.Contacts, c, Modifier.size(size * 0.5f))
        } else {
            Text(first.uppercase(), color = c, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp)
        }
    }
}

private fun smsDayKey(ms: Long): Int {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
}

private fun smsClock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun smsListTime(ms: Long): String {
    val d = smsDayKey(System.currentTimeMillis()) - smsDayKey(ms)
    return when {
        d == 0 -> smsClock(ms)
        d == 1 -> "Yesterday"
        d in 2..6 -> SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date(ms))
        else -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
    }
}

private fun smsDayLabel(ms: Long): String {
    val d = smsDayKey(System.currentTimeMillis()) - smsDayKey(ms)
    return when (d) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date(ms))
    }
}

private fun smsCopy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("sms", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** Press: shrinks a little under the finger and springs back; long press gives a haptic tick. */
private fun Modifier.smsPress(onClick: () -> Unit, onLong: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "smsPress"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLong()
            }
        )
}

// ───────────────────────── Hold menu (iOS style) ─────────────────────────

class SmsMenuItem(
    val label: String,
    val icon: SmsIconKind,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** Dark scrim, the held item lifted in the middle and a springy menu under it. */
@Composable
private fun SmsMenuOverlay(
    p: Palette,
    items: List<SmsMenuItem>,
    onDismiss: () -> Unit,
    footer: String? = null,
    preview: @Composable () -> Unit
) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f)) }
    val menuBg = if (p.dark) Color(0xFF2C2C2E).copy(alpha = 0.98f) else Color.White.copy(alpha = 0.98f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f * prog.value.coerceIn(0f, 1f)))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.graphicsLayer {
                    val s = 0.9f + 0.1f * prog.value
                    scaleX = s
                    scaleY = s
                    alpha = prog.value.coerceIn(0f, 1f)
                }
            ) { preview() }
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .width(248.dp)
                    .graphicsLayer {
                        val s = 0.5f + 0.5f * prog.value
                        scaleX = s
                        scaleY = s
                        alpha = prog.value.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .background(menuBg)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.text.copy(alpha = 0.12f)))
                    val tint = if (item.danger) Color(0xFFFF453A) else p.text
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { item.onClick() }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.label, color = tint, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        SmsIcon(item.icon, tint, Modifier.size(20.dp))
                    }
                }
            }
            if (footer != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    footer, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                    modifier = Modifier.width(248.dp).graphicsLayer { alpha = prog.value.coerceIn(0f, 1f) }
                )
            }
        }
    }
}

// ───────────────────────── Misc screen ─────────────────────────

/** The "Misc" page: asks for the SMS / contacts permissions first, then shows the messages. */
@Composable
fun SmsMiscScreen(p: Palette, topPad: Dp, bottomPad: Dp, onThreadOpen: (Boolean) -> Unit) {
    val context = LocalContext.current
    var canRead by remember { mutableStateOf(smsCanRead(context)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        canRead = smsCanRead(context)
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canRead = smsCanRead(context)
    }
    run {
        Box(Modifier.fillMaxSize().background(p.bgBrush)) {
            if (!canRead) {
                SmsPermissionPage(p, topPad, bottomPad) {
                    val act = context.findActivity()
                    // asked before and Android no longer shows its dialog: send the user to the app settings
                    val blocked = asked && act != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.READ_SMS)
                    if (blocked) {
                        settingsLauncher.launch(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    } else {
                        launcher.launch(SmsPermissions)
                    }
                }
            } else {
                SmsInbox(p, topPad, bottomPad, onThreadOpen)
            }
        }
    }
}

@Composable
private fun SmsPermissionPage(p: Palette, topPad: Dp, bottomPad: Dp, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.staggerIn(0).size(84.dp).clip(RoundedCornerShape(26.dp)).background(p.accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) { SmsIcon(SmsIconKind.Message, p.accent, Modifier.size(42.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Messages", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.staggerIn(1))
        Spacer(Modifier.height(6.dp))
        Text(
            "To show and send text messages, the app needs these permissions.",
            color = p.secondary, fontSize = 14.sp, lineHeight = 22.sp,
            modifier = Modifier.staggerIn(2)
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            Triple(SmsIconKind.Message, "Read SMS", "Show your received messages"),
            Triple(SmsIconKind.Contacts, "Contacts", "Show the sender's name instead of the number"),
            Triple(SmsIconKind.Send, "Send SMS", "Reply from inside the app")
        ).forEachIndexed { i, (icon, title, sub) ->
            Row(
                Modifier.staggerIn(3 + i).fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmsIcon(icon, p.accent, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(sub, color = p.secondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.staggerIn(6).fillMaxWidth().bounceClick { onAllow() }
                .clip(RoundedCornerShape(16.dp)).background(p.accent).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) { Text("Allow", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ───────────────────────── Inbox ─────────────────────────

@Composable
private fun SmsInbox(p: Palette, topPad: Dp, bottomPad: Dp, onThreadOpen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { SmsStore(context) }
    var snap by remember { mutableStateOf(SmsSnapshot(emptyList(), emptyMap())) }
    var loaded by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    // a new message arrives (or one changes): read the list again
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { tick++ }
        }
        try {
            context.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, obs)
        } catch (_: Exception) {
        }
        onDispose { context.contentResolver.unregisterContentObserver(obs) }
    }
    LaunchedEffect(tick) {
        snap = withContext(Dispatchers.IO) { SmsRepo.load(context) }
        loaded = true
    }

    val all = remember(snap, store.localRev, store.rev) { SmsRepo.merge(snap, store) }
    val threads = remember(all, store.rev) { SmsRepo.threads(all, snap.names, store) }

    var filter by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var shownId by remember { mutableStateOf<String?>(null) }
    var showStarred by remember { mutableStateOf(false) }
    var menuThread by remember { mutableStateOf<SmsThread?>(null) }

    LaunchedEffect(open) {
        if (open != null) shownId = open
        onThreadOpen(open != null)
    }
    DisposableEffect(Unit) { onDispose { onThreadOpen(false) } }
    BackHandler(enabled = showStarred && open == null) { showStarred = false }
    BackHandler(enabled = menuThread != null) { menuThread = null }

    val visible = remember(threads, filter, store.rev) {
        threads.filter { t ->
            val hidden = store.isThreadHidden(t.id)
            if (filter == 3) hidden
            else !hidden && when (filter) {
                1 -> t.name != null
                2 -> smsIsPromo(t.address)
                else -> true
            }
        }
    }
    val unreadTotal = threads.sumOf { it.unread }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).staggerIn(0),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Messages", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                        Text(
                            if (unreadTotal > 0) "$unreadTotal unread" else "${threads.size} chats",
                            fontSize = 13.sp, color = p.secondary
                        )
                    }
                    Box(
                        Modifier.size(44.dp).bounceClick { showStarred = true }.clip(CircleShape).background(p.selectedPill),
                        contentAlignment = Alignment.Center
                    ) { SmsIcon(SmsIconKind.Star, p.accent, Modifier.size(22.dp)) }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).staggerIn(1),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Contacts", "Promotions", "Hidden").forEachIndexed { i, label ->
                        val on = filter == i
                        val bg by animateColorAsState(if (on) p.accent else p.selectedPill, label = "segBg")
                        Box(
                            Modifier.bounceClick { filter = i }.clip(RoundedCornerShape(16.dp)).background(bg)
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = if (on) Color.White else p.text
                            )
                        }
                    }
                }
            }
            if (loaded && visible.isEmpty()) {
                item {
                    Text(
                        if (filter == 3) "No hidden chats" else "No messages",
                        color = p.secondary, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            items(visible, key = { it.id }) { t ->
                SmsThreadRow(
                    t, p, store.isThreadPinned(t.id),
                    onClick = { open = t.id },
                    onLong = { menuThread = t }
                )
            }
        }

        // chat page slides in from the side like an iOS push
        AnimatedVisibility(
            visible = open != null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { -it } + fadeIn(tween(150)),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(200))
        ) {
            val id = shownId
            if (id != null) {
                val list = remember(all, id) { all.filter { smsThreadId(it.address) == id } }
                val first = list.lastOrNull()
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SmsThreadScreen(
                        p = p, store = store, threadId = id,
                        title = snap.names[id] ?: first?.address.orEmpty(),
                        address = first?.address.orEmpty(),
                        msgs = list, topPad = topPad,
                        onBack = { open = null }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showStarred && open == null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { -it } + fadeIn(tween(150)),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(200))
        ) {
            SmsStarredPage(
                p, store, all, snap.names, topPad, bottomPad,
                onBack = { showStarred = false },
                onOpen = { id -> showStarred = false; open = id }
            )
        }

        val mt = menuThread
        if (mt != null) {
            val pinned = store.isThreadPinned(mt.id)
            val hidden = store.isThreadHidden(mt.id)
            SmsMenuOverlay(
                p = p,
                onDismiss = { menuThread = null },
                items = buildList {
                    add(SmsMenuItem(if (pinned) "Unpin" else "Pin", if (pinned) SmsIconKind.PinFilled else SmsIconKind.Pin) {
                        store.toggleThreadPin(mt.id); menuThread = null
                    })
                    add(SmsMenuItem(if (hidden) "Unhide" else "Hide", if (hidden) SmsIconKind.Eye else SmsIconKind.EyeOff) {
                        store.toggleThreadHidden(mt.id); menuThread = null
                    })
                    if (mt.unread > 0) add(SmsMenuItem("Mark as read", SmsIconKind.Check) {
                        store.markRead(mt.id); menuThread = null
                    })
                    add(SmsMenuItem("Copy number", SmsIconKind.Copy) {
                        smsCopy(context, mt.address); menuThread = null
                    })
                    add(SmsMenuItem("Delete chat", SmsIconKind.Trash, danger = true) {
                        store.removeAll(all.filter { smsThreadId(it.address) == mt.id }.map { it.key })
                        menuThread = null
                    })
                },
                footer = "Deleting only happens inside this app. The messages stay on your phone."
            ) {
                Box(Modifier.width(300.dp).clip(RoundedCornerShape(16.dp)).background(p.card)) {
                    SmsThreadRow(mt, p, pinned, onClick = {}, onLong = {})
                }
            }
        }
    }
}

@Composable
private fun SmsThreadRow(t: SmsThread, p: Palette, pinned: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().smsPress(onClick, onLong).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmsAvatar(t.title, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.title, color = p.text, fontSize = 16.sp,
                    fontWeight = if (t.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                )
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    SmsIcon(SmsIconKind.PinFilled, p.secondary, Modifier.size(13.dp))
                }
            }
            Text(
                (if (t.last.sent) "You: " else "") + t.last.body.replace('\n', ' '),
                color = p.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(smsListTime(t.last.date), color = p.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            if (t.unread > 0) Box(Modifier.size(10.dp).clip(CircleShape).background(p.accent))
            else Spacer(Modifier.size(10.dp))
        }
    }
}

// ───────────────────────── Starred messages ─────────────────────────

@Composable
private fun SmsStarredPage(
    p: Palette, store: SmsStore, all: List<SmsMsg>, names: Map<String, String>,
    topPad: Dp, bottomPad: Dp, onBack: () -> Unit, onOpen: (String) -> Unit
) {
    val starred = remember(all, store.rev) { all.filter { store.isStarred(it.key) }.sortedByDescending { it.date } }
    Box(Modifier.fillMaxSize().background(p.bgBrush).pointerInput(Unit) { detectTapGestures { } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).bounceClick { onBack() }.clip(CircleShape).background(p.selectedPill),
                        contentAlignment = Alignment.Center
                    ) { GlyphIcon(Glyph.Back, p.accent, Modifier.size(20.dp).graphicsLayer { scaleX = -1f }) }
                    Spacer(Modifier.width(12.dp))
                    Text("Starred", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                }
            }
            if (starred.isEmpty()) {
                item {
                    Text(
                        "You haven't starred any messages yet.\nHold a message and tap \"Star\".",
                        color = p.secondary, fontSize = 14.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp, start = 30.dp, end = 30.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            items(starred, key = { it.key }) { m ->
                val id = smsThreadId(m.address)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                        .bounceClick { onOpen(id) }
                        .clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmsIcon(SmsIconKind.StarFilled, Color(0xFFFFB340), Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            names[id] ?: m.address, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f), maxLines = 1,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                        )
                        Text(smsListTime(m.date), color = p.secondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        m.body, color = p.text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                    )
                }
            }
        }
    }
}

// ───────────────────────── Chat ─────────────────────────

private sealed class SmsRow(val id: String) {
    class Day(val label: String, key: Int) : SmsRow("d$key")
    class Bubble(val msg: SmsMsg, val tail: Boolean) : SmsRow(msg.key)
}

@Composable
private fun SmsBubble(
    m: SmsMsg, tail: Boolean, p: Palette,
    starred: Boolean, pinned: Boolean, fresh: Boolean, flash: Boolean, status: String?,
    onLong: () -> Unit, onCode: (String) -> Unit
) {
    val appear = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (fresh) {
            when (m.effect) {
                "gentle" -> appear.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
                "loud", "slam" -> appear.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 260f))
                else -> appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f))
            }
        }
    }
    val flashA by animateFloatAsState(if (flash) 1f else 0f, tween(300), label = "bubbleFlash")
    val density = LocalDensity.current
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (!m.sent && tail) 6.dp else 20.dp,
        bottomEnd = if (m.sent && tail) 6.dp else 20.dp
    )
    val bg = if (m.sent) p.accent else if (p.dark) Color(0xFF26262A) else Color.White
    val fg = if (m.sent) Color.White else p.text
    val code = if (!m.sent) smsFindCode(m.body) else null

    Column(
        Modifier.fillMaxWidth().padding(bottom = if (tail) 5.dp else 0.dp),
        horizontalAlignment = if (m.sent) Alignment.End else Alignment.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 290.dp)
                .graphicsLayer {
                    val a = appear.value
                    transformOrigin = TransformOrigin(if (m.sent) 1f else 0f, 1f)
                    when (m.effect) {
                        "gentle" -> {
                            val s = 0.5f + 0.5f * a
                            scaleX = s; scaleY = s
                            translationY = (1f - a) * with(density) { 30.dp.toPx() }
                            alpha = (0.3f + 0.7f * a).coerceIn(0f, 1f)
                        }
                        "loud" -> {
                            val s = 1f + (1f - a) * 0.9f
                            scaleX = s; scaleY = s
                            rotationZ = (1f - a) * -5f
                        }
                        "slam" -> {
                            val s = 1f + (1f - a) * 1.6f
                            scaleX = s; scaleY = s
                            rotationZ = (1f - a) * -8f
                            alpha = (a * 3f).coerceIn(0f, 1f)
                        }
                        else -> {
                            val s = 0.88f + 0.12f * a
                            scaleX = s; scaleY = s
                            translationY = (1f - a) * with(density) { 46.dp.toPx() }
                            alpha = a.coerceIn(0f, 1f)
                        }
                    }
                }
                .smsPress(onClick = {}, onLong = onLong)
                .clip(shape)
                .background(bg)
                .then(if (flashA > 0.01f) Modifier.border(2.dp, p.accent.copy(alpha = flashA), shape) else Modifier)
                .padding(horizontal = 13.dp, vertical = 8.dp)
        ) {
            if (m.replyTo.isNotBlank()) {
                Row(
                    Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.14f))
                ) {
                    Box(Modifier.width(3.dp).height(30.dp).background(if (m.sent) Color.White.copy(alpha = 0.8f) else p.accent))
                    Text(
                        m.replyTo, color = fg.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                    )
                }
            }
            Text(
                m.body, color = fg, fontSize = 15.sp, lineHeight = 21.sp,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
            )
            if (code != null) {
                Row(
                    Modifier.padding(top = 6.dp).bounceClick { onCode(code) }
                        .clip(RoundedCornerShape(12.dp)).background(p.text.copy(alpha = 0.1f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmsIcon(SmsIconKind.Copy, fg, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Copy code", color = fg, fontSize = 12.sp)
                }
            }
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    SmsIcon(SmsIconKind.PinFilled, fg.copy(alpha = 0.7f), Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (starred) {
                    SmsIcon(SmsIconKind.StarFilled, Color(0xFFFFB340), Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(smsClock(m.date), color = fg.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }
        if (status != null) {
            Text(
                status, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, end = 6.dp),
                color = if (m.failed) Color(0xFFFF453A) else p.secondary
            )
        }
    }
}

@Composable
private fun SmsThreadScreen(
    p: Palette, store: SmsStore, threadId: String, title: String, address: String,
    msgs: List<SmsMsg>, topPad: Dp, onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val openedAt = remember { System.currentTimeMillis() }
    val canReply = smsCanReply(address)

    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<SmsMsg?>(null) }
    var menuMsg by remember { mutableStateOf<SmsMsg?>(null) }
    var effectMenu by remember { mutableStateOf(false) }
    var freshKey by remember { mutableStateOf<String?>(null) }
    var flashKey by remember { mutableStateOf<String?>(null) }
    var pinIndex by remember { mutableIntStateOf(0) }
    var showErr by remember { mutableStateOf(false) }
    var shakeTick by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var pendingFx by remember { mutableStateOf("") }
    var headerPx by remember { mutableIntStateOf(0) }

    BackHandler(enabled = menuMsg != null || effectMenu) { menuMsg = null; effectMenu = false }
    BackHandler(enabled = menuMsg == null && !effectMenu) { onBack() }

    // opening the chat (and every new message while it is open) marks it as read
    LaunchedEffect(msgs.lastOrNull()?.key) { store.markRead(threadId) }
    LaunchedEffect(showErr) { if (showErr) { delay(1600); showErr = false } }
    LaunchedEffect(flashKey) { if (flashKey != null) { delay(1300); flashKey = null } }
    LaunchedEffect(freshKey) { if (freshKey != null) { delay(1800); freshKey = null } }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            repeat(4) { shake.animateTo(if (it % 2 == 0) 10f else -10f, tween(50)) }
            shake.animateTo(0f, tween(70))
        }
    }

    val rows = remember(msgs) {
        val out = ArrayList<SmsRow>()
        var lastDay = -1
        msgs.forEachIndexed { i, m ->
            val day = smsDayKey(m.date)
            if (day != lastDay) {
                out.add(SmsRow.Day(smsDayLabel(m.date), day))
                lastDay = day
            }
            val next = msgs.getOrNull(i + 1)
            val tail = next == null || next.sent != m.sent || smsDayKey(next.date) != day || next.date - m.date > 5 * 60_000L
            out.add(SmsRow.Bubble(m, tail))
        }
        out.reversed()
    }
    val pinnedMsgs = msgs.filter { store.isPinned(it.key) }
    val lastSentKey = msgs.lastOrNull { it.sent }?.key

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { retry++ }

    fun doSend(fx: String) {
        val t = text.trim()
        if (t.isEmpty()) { showErr = true; return }
        if (!smsCanSend(context)) {
            pendingFx = fx
            permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
            return
        }
        val ok = SmsRepo.send(context, address, t)
        val m = store.addLocal(address, t, fx, replyTo?.body?.take(120).orEmpty(), !ok)
        freshKey = m.key
        if (fx == "slam" || fx == "loud") shakeTick++
        text = ""
        replyTo = null
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (!ok) Toast.makeText(context, "Not sent", Toast.LENGTH_SHORT).show()
        scope.launch { listState.animateScrollToItem(0) }
    }
    val sendNow by rememberUpdatedState<(String) -> Unit> { fx -> doSend(fx) }
    LaunchedEffect(retry) {
        if (retry > 0) {
            if (smsCanSend(context)) sendNow(pendingFx)
            else Toast.makeText(context, "Messages can't be sent without the SMS permission", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize().background(p.bgBrush).pointerInput(Unit) { detectTapGestures { } }) {
        val headerDp = with(density) { headerPx.toDp() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationX = shake.value },
                    contentPadding = PaddingValues(
                        top = headerDp + (if (pinnedMsgs.isNotEmpty()) 46.dp else 0.dp) + 8.dp,
                        bottom = 10.dp, start = 12.dp, end = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(rows, key = { it.id }) { row ->
                        when (row) {
                            is SmsRow.Day -> Text(
                                row.label, color = p.secondary, fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            is SmsRow.Bubble -> {
                                val m = row.msg
                                SmsBubble(
                                    m = m, tail = row.tail, p = p,
                                    starred = store.isStarred(m.key), pinned = store.isPinned(m.key),
                                    fresh = m.key == freshKey || (!m.sent && m.date > openedAt && System.currentTimeMillis() - m.date < 5000L),
                                    flash = flashKey == m.key,
                                    status = if (m.key == lastSentKey) (if (m.failed) "Not sent" else "Sent") else null,
                                    onLong = { menuMsg = m },
                                    onCode = { c -> smsCopy(context, c) }
                                )
                            }
                        }
                    }
                }

                // glass header: back, avatar, name
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(p.bg.copy(alpha = 0.92f))
                        .onSizeChanged { headerPx = it.height }
                        .padding(top = topPad, bottom = 8.dp)
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Box(
                            Modifier.align(Alignment.CenterStart).size(40.dp).bounceClick { onBack() }
                                .clip(CircleShape).background(p.selectedPill),
                            contentAlignment = Alignment.Center
                        ) { GlyphIcon(Glyph.Back, p.accent, Modifier.size(20.dp)) }
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            SmsAvatar(title, 38.dp)
                            Text(
                                title, color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp).padding(top = 2.dp),
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                            )
                        }
                    }
                }

                // pinned messages banner: tap to jump to the next pinned message
                BoxAnimatedVisibility(
                    visible = pinnedMsgs.isNotEmpty(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = headerDp + 6.dp),
                    enter = fadeIn() + expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val cur = pinnedMsgs.getOrNull(pinIndex % pinnedMsgs.size.coerceAtLeast(1))
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 34.dp)
                            .bounceClick {
                                if (pinnedMsgs.isNotEmpty()) {
                                    val target = pinnedMsgs[pinIndex % pinnedMsgs.size]
                                    pinIndex++
                                    val idx = rows.indexOfFirst { it is SmsRow.Bubble && it.msg.key == target.key }
                                    flashKey = target.key
                                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                                }
                            }
                            .clip(RoundedCornerShape(17.dp)).background(p.selectedPill)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmsIcon(SmsIconKind.PinFilled, p.accent, Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            cur?.body?.replace('\n', ' ').orEmpty(), color = p.text, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                        )
                        Text("${pinnedMsgs.size}", color = p.secondary, fontSize = 12.sp)
                    }
                }
            }

            // message box
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                AnimatedVisibility(
                    visible = showErr,
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        "Write a message first", color = Color(0xFFFF6B6B), fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                AnimatedVisibility(
                    visible = replyTo != null && canReply,
                    enter = fadeIn() + expandVertically(spring(dampingRatio = 0.8f, stiffness = 420f)),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val r = replyTo
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp))
                            .background(p.selectedPill).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmsIcon(SmsIconKind.Reply, p.accent, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Reply to", color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                r?.body?.replace('\n', ' ').orEmpty(), color = p.secondary, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                            )
                        }
                        Box(Modifier.size(28.dp).bounceClick { replyTo = null }, contentAlignment = Alignment.Center) {
                            GlyphIcon(Glyph.Close, p.secondary, Modifier.size(14.dp))
                        }
                    }
                }
                if (!canReply) {
                    Box(
                        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(p.selectedPill),
                        contentAlignment = Alignment.Center
                    ) { Text("This sender can't receive replies", color = p.secondary, fontSize = 13.sp) }
                } else {
                    val shape = RoundedCornerShape(23.dp)
                    val has = text.isNotBlank()
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
                            .background(p.selectedPill)
                            .border(1.dp, p.text.copy(alpha = 0.12f), shape)
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it; showErr = false },
                            textStyle = TextStyle(color = p.text, fontSize = 15.sp, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(p.accent),
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 52.dp, top = 13.dp, bottom = 13.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) Text("Message", color = p.secondary, fontSize = 15.sp)
                                    inner()
                                }
                            }
                        )
                        // send button: tap sends, hold opens the effects menu (no microphone)
                        var pressed by remember { mutableStateOf(false) }
                        val sc by animateFloatAsState(
                            if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "sendScale"
                        )
                        val btn by animateColorAsState(if (has) p.accent else p.text.copy(alpha = 0.1f), label = "sendBg")
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp).size(34.dp)
                                .graphicsLayer { scaleX = sc; scaleY = sc }
                                .clip(CircleShape).background(btn)
                                .pointerInput(has) {
                                    detectTapGestures(
                                        onPress = {
                                            pressed = true
                                            tryAwaitRelease()
                                            pressed = false
                                        },
                                        onTap = { sendNow("") },
                                        onLongPress = {
                                            if (has) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                effectMenu = true
                                            } else showErr = true
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) { SmsIcon(SmsIconKind.Send, if (has) Color.White else p.secondary, Modifier.size(18.dp)) }
                    }
                }
            }
        }

        // hold a message: reply / copy / star / pin / delete
        val mm = menuMsg
        if (mm != null) {
            val starred = store.isStarred(mm.key)
            val pinned = store.isPinned(mm.key)
            SmsMenuOverlay(
                p = p,
                onDismiss = { menuMsg = null },
                items = buildList {
                    if (canReply) add(SmsMenuItem("Reply", SmsIconKind.Reply) { replyTo = mm; menuMsg = null })
                    add(SmsMenuItem("Copy", SmsIconKind.Copy) { smsCopy(context, mm.body); menuMsg = null })
                    add(SmsMenuItem(if (starred) "Unstar" else "Star", if (starred) SmsIconKind.StarFilled else SmsIconKind.Star) {
                        store.toggleStar(mm.key); menuMsg = null
                    })
                    add(SmsMenuItem(if (pinned) "Unpin" else "Pin", if (pinned) SmsIconKind.PinFilled else SmsIconKind.Pin) {
                        store.togglePin(mm.key); menuMsg = null
                    })
                    add(SmsMenuItem("Delete", SmsIconKind.Trash, danger = true) { store.remove(mm.key); menuMsg = null })
                },
                footer = "Deleting only happens inside this app."
            ) {
                Box(Modifier.width(300.dp)) {
                    SmsBubble(
                        m = mm, tail = true, p = p, starred = starred, pinned = pinned,
                        fresh = false, flash = false, status = null, onLong = {}, onCode = {}
                    )
                }
            }
        }

        // hold send: bubble effects
        if (effectMenu) {
            val preview = text.trim()
            SmsMenuOverlay(
                p = p,
                onDismiss = { effectMenu = false },
                items = listOf(
                    SmsMenuItem("Send normally", SmsIconKind.Send) { effectMenu = false; sendNow("") },
                    SmsMenuItem("Gentle", SmsIconKind.Dot) { effectMenu = false; sendNow("gentle") },
                    SmsMenuItem("Loud", SmsIconKind.Bolt) { effectMenu = false; sendNow("loud") },
                    SmsMenuItem("Slam", SmsIconKind.Burst) { effectMenu = false; sendNow("slam") }
                ),
                footer = "The effect is only visible inside this app. The recipient gets a normal SMS."
            ) {
                Box(Modifier.width(300.dp)) {
                    SmsBubble(
                        m = SmsMsg("preview", address, preview, System.currentTimeMillis(), true),
                        tail = true, p = p, starred = false, pinned = false,
                        fresh = false, flash = false, status = null, onLong = {}, onCode = {}
                    )
                }
            }
        }
    }
}

// AnimatedVisibility called from a Box nested inside a Column hits the Compose
// scope-marker rule (outer ColumnScope receiver is blocked). Wrapping it in a
// BoxScope extension gives it a single clean receiver.
@Composable
private fun BoxScope.BoxAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = enter, exit = exit, content = content)
}
