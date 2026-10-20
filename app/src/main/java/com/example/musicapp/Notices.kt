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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        // Prefer the actual notification artwork/cover, falling back to the app icon.
        val icon = try {
            val cover = (extras.get(Notification.EXTRA_PICTURE) as? Bitmap)
                ?: (extras.get(Notification.EXTRA_LARGE_ICON_BIG) as? Bitmap)
                ?: (extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)
            if (cover != null) cover.toImageBitmap(128)
            else pm.getApplicationIcon(sbn.packageName).toImageBitmap(96)
        } catch (e: Exception) {
            try { pm.getApplicationIcon(sbn.packageName).toImageBitmap(96) } catch (_: Exception) { null }
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

// ───────────────────────── UI ─────────────────────────

/**
 * The island that drops down when a notification arrives: app icon on the left, app name next to it.
 * Tap: shows the notification text. Drag it away (up or sideways) to dismiss. Closes by itself after a few seconds
 * unless it is opened.
 */
@Composable
fun NoticeIsland(
    notice: Notice,
    onDismiss: () -> Unit,
    panelSize: Int = 1,
    tint: Int = 0,
    accent: Color = Color(0xFF0A84FF),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val statusH = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    var expanded by remember(notice.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val offX = remember(notice.key) { Animatable(0f) }
    val offY = remember(notice.key) { Animatable(0f) }
    val onDismissS by rememberUpdatedState(onDismiss)
    val shape = RoundedCornerShape(if (expanded) 28.dp else 40.dp)

    // The timeout is always five seconds, even if the user opens the description.
    LaunchedEffect(notice.key) {
        delay(5000)
        onDismissS()
    }
    val panelWidth = when (panelSize) { 0 -> 0.66f; 2 -> 1f; else -> 0.84f }
    val panelColor = when (tint) {
        1 -> Color(0xFF202024).copy(alpha = 0.88f)
        2 -> accent.copy(alpha = 0.88f)
        else -> Color.Black
    }

    Box(
        modifier.fillMaxWidth().padding(top = statusH + 8.dp, start = 14.dp, end = 14.dp),
        contentAlignment = Alignment.TopCenter
    ) {
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
                }
                .widthIn(max = 420.dp)
                .fillMaxWidth(if (expanded) 1f else panelWidth)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 400f))
                .clip(shape)
                .background(panelColor)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(9.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().height(if (expanded) 72.dp else 48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (notice.icon != null) {
                    Image(
                        bitmap = notice.icon, contentDescription = "Notification cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(if (expanded) 64.dp else 44.dp).clip(RoundedCornerShape(if (expanded) 16.dp else 13.dp))
                    )
                } else {
                    Box(Modifier.size(if (expanded) 64.dp else 44.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.12f)))
                }
            }
            if (expanded && notice.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    notice.text, fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f),
                    maxLines = 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
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
