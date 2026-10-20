package com.example.musicapp

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── Incoming notifications → island ─────────────────────────

/** One notification of another app, ready to show in the island. */
class Notice(
    val key: String,
    val appName: String,
    val icon: ImageBitmap?,
    val title: String,
    val text: String
)

/** The service writes here, the UI reads from here (same process). */
object NoticeBus {
    var current by mutableStateOf<Notice?>(null)
        private set
    private var testCounter = 0

    fun publish(notice: Notice) { current = notice }
    fun dismiss() { current = null }

    /** Show an in-app preview after a notification setting is changed. */
    fun showTest(description: String) {
        testCounter++
        current = Notice(
            key = "settings-test-$testCounter",
            appName = "Music · Notification test",
            icon = null,
            title = "Notification test",
            text = description
        )
    }
}

/** Needs "Notification access" (Settings). Android does not allow a runtime dialog for it. */
class NoticeListenerService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        // skip the ongoing ones (music, downloads, calls...), group summaries and silent system stuff
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_PROGRESS ||
            n.category == Notification.CATEGORY_SERVICE
        ) return

        val extras = n.extras
        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        // chat apps often keep the real message inside MessagingStyle / inbox lines
        val lastMessage = try {
            @Suppress("DEPRECATION")
            (extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle)
                ?.getCharSequence("text")?.toString()
        } catch (e: Exception) { null }
        val lastLine = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.toString()
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence("android.conversationTitle"))?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: lastMessage ?: lastLine
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: n.tickerText)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        // The picture on the island is the icon of the app that sent the notification.
        val icon = try {
            pm.getApplicationIcon(sbn.packageName).toImageBitmap(128)
        } catch (e: Exception) {
            try {
                n.smallIcon?.loadDrawable(this)?.toImageBitmap(128)
            } catch (_: Exception) { null }
        }
        val notice = Notice(sbn.key, appName, icon, title, text)
        main.post { NoticeBus.publish(notice) }
    }

    // Do not dismiss the preview when Android removes/replaces the source notification.
    // The island owns its five-second timeout; many apps immediately update their notifications.
    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit
}

private fun Bitmap.toImageBitmap(size: Int): ImageBitmap {
    val scaled = if (width == size && height == size) this else Bitmap.createScaledBitmap(this, size, size, true)
    return scaled.asImageBitmap()
}

private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(c)
    return bmp.asImageBitmap()
}

fun isNoticeAccessEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** After an update/reinstall Android may leave the listener unbound: ask it to reconnect. */
fun rebindNoticeListener(context: Context) {
    try {
        if (isNoticeAccessEnabled(context)) {
            NotificationListenerService.requestRebind(
                android.content.ComponentName(context, NoticeListenerService::class.java)
            )
        }
    } catch (_: Exception) {
    }
}

// ───────────────────────── UI ─────────────────────────

/**
 * The notification island. It looks like the music Dynamic Island: a black pill with the icon of the
 * app that sent the notification on the left and the app name next to it.
 *  - Tap: the pill grows (springy) and shows the title and the description.
 *  - Drag it away (up or sideways) to dismiss. It closes by itself after five seconds.
 * The music island is laid out below it by the caller, so the two never overlap.
 */
@Composable
fun NoticeIsland(
    notice: Notice,
    onDismiss: () -> Unit,
    panelSize: Int = 1,
    tint: Int = 0,
    accent: Color = Color(0xFF0A84FF),
    islandWidthDp: Int = 250,
    islandHeightDp: Int = 36,
    modifier: Modifier = Modifier
) {
    var expanded by remember(notice.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val offX = remember(notice.key) { Animatable(0f) }
    val offY = remember(notice.key) { Animatable(0f) }
    val pop = remember(notice.key) { Animatable(1f) }
    val onDismissS by rememberUpdatedState(onDismiss)
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp

    // 0f = compact pill, 1f = expanded panel
    val t by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = 300f), label = "noticeT"
    )
    val pillH = islandHeightDp.dp
    val expandedW = when (panelSize) { 0 -> 300.dp; 2 -> screenW - 28.dp; else -> 340.dp }.coerceAtMost(screenW - 28.dp)
    val width = androidx.compose.ui.unit.lerp(islandWidthDp.dp, expandedW, t)
    val corner = androidx.compose.ui.unit.lerp(pillH / 2, 30.dp, t.coerceIn(0f, 1f))
    val shape = RoundedCornerShape(corner)
    val padH = androidx.compose.ui.unit.lerp(5.dp, 16.dp, t.coerceIn(0f, 1f))
    val padV = androidx.compose.ui.unit.lerp(4.dp, 14.dp, t.coerceIn(0f, 1f))

    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 1f else 0f,
        if (expanded) androidx.compose.animation.core.tween(240, delayMillis = 100) else androidx.compose.animation.core.tween(80),
        label = "noticeContent"
    )

    // The timeout is always five seconds, even if the user opens the description.
    LaunchedEffect(notice.key) {
        pop.snapTo(1.06f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
    }
    LaunchedEffect(notice.key) {
        delay(5000)
        onDismissS()
    }
    val panelColor = when (tint) {
        1 -> Color(0xFF202024).copy(alpha = 0.92f)
        2 -> accent.copy(alpha = 0.92f)
        else -> Color.Black
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .pointerInput(notice.key) {
                    detectDragGestures(
                        onDrag = { change, d ->
                            change.consume()
                            scope.launch {
                                offX.snapTo(offX.value + d.x)
                                offY.snapTo((offY.value + d.y).coerceAtMost(40f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val gone = offY.value < -50.dp.toPx() || abs(offX.value) > 110.dp.toPx()
                                if (gone) {
                                    if (abs(offX.value) > -offY.value) {
                                        offX.animateTo(if (offX.value > 0) 1200f else -1200f, spring(stiffness = 400f))
                                    } else {
                                        offY.animateTo(-600f, spring(stiffness = 400f))
                                    }
                                    onDismissS()
                                } else {
                                    launch { offX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 450f)) }
                                    offY.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 450f))
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                launch { offX.animateTo(0f) }
                                offY.animateTo(0f)
                            }
                        }
                    )
                }
                .pointerInput(notice.key) { detectTapGestures(onTap = { expanded = !expanded }) }
                .offset { IntOffset(offX.value.roundToInt(), offY.value.roundToInt()) }
                .graphicsLayer {
                    val far = maxOf(abs(offX.value) / 400f, abs(offY.value) / 200f).coerceIn(0f, 1f)
                    alpha = 1f - far * 0.8f
                    scaleX = pop.value; scaleY = pop.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                }
                .width(width)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 380f))
                .shadow(6.dp + 16.dp * t, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(panelColor)
                .border(1.dp, Color.White.copy(alpha = 0.08f + 0.06f * t), shape)
                .padding(horizontal = padH, vertical = padV)
        ) {
            // header: app icon · app name · "new" dot
            Row(
                Modifier.fillMaxWidth().height(if (expanded) 46.dp else pillH - 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconSize = if (expanded) 44.dp else (islandHeightDp - 8).coerceAtLeast(20).dp
                val iconShape = if (expanded) RoundedCornerShape(13.dp) else CircleShape
                if (notice.icon != null) {
                    Image(
                        bitmap = notice.icon, contentDescription = notice.appName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(iconSize).clip(iconShape)
                    )
                } else {
                    Box(Modifier.size(iconSize).clip(iconShape).background(Color.White.copy(alpha = 0.14f)))
                }
                Spacer(Modifier.width(if (expanded) 12.dp else 8.dp))
                if (expanded) {
                    Column(Modifier.weight(1f).graphicsLayer { alpha = contentAlpha }) {
                        Text(
                            notice.appName, fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (notice.title.isNotBlank()) {
                            Text(
                                notice.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Text(
                        notice.appName, Modifier.weight(1f), color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "noticeDot")
                    val dotAlpha by blink.animateFloat(
                        0.35f, 1f,
                        androidx.compose.animation.core.infiniteRepeatable(
                            androidx.compose.animation.core.tween(700),
                            androidx.compose.animation.core.RepeatMode.Reverse
                        ), label = "dotAlpha"
                    )
                    Box(
                        Modifier.padding(end = 8.dp).size(7.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = dotAlpha))
                    )
                }
            }
            // description
            if (expanded && notice.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    notice.text, fontSize = 14.sp, lineHeight = 19.sp, color = Color.White.copy(alpha = 0.92f),
                    maxLines = 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                        .graphicsLayer { alpha = contentAlpha }
                )
            }
        }
    }
}

@Composable
fun NoticeAccessDialog(p: Palette, onOpenSettings: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = p.card,
        titleContentColor = p.text,
        textContentColor = p.text,
        title = { Text("Show notifications in the island") },
        text = {
            Text(
                "To show incoming notifications at the top, allow \"Notification access\" for this app on the next screen.",
                color = p.secondary
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open settings", color = p.accent) } },
        dismissButton = { TextButton(onClick = onLater) { Text("Not now", color = p.secondary) } }
    )
}
