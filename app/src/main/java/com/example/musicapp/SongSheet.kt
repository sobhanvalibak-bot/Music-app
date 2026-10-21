package com.example.musicapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Lets the song menus open the sleep timer sheet that lives in MusicApp. */
object SleepMenuBridge {
    var open: (() -> Unit)? = null
    var active: Boolean = false
    var label: String = "Sleep timer"
}

/** Opens the system share sheet with the song's title, artist and album. */
fun shareSong(context: Context, song: Song) {
    try {
        val shareText = "${song.title} — ${song.artist}" +
            if (song.remoteId != null) "\n${song.remoteId}"
            else if (song.album.isNotBlank()) "\n${song.album}" else ""
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(send, "Share song"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share this song", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Generic iOS style context menu overlay: the pressed item lifts, the app behind it blurs and the
 * frosted menu springs open next to it (under the item, or above it when there is no room below).
 *
 * [anchor] = bounds of the pressed item on the screen (null: the menu opens near the top).
 * [preview] draws the lifted copy of the item. [buildGroups] gets a `close` function: call
 * `close { action }` to close with a short animation and then run the action.
 */
@Composable
fun ContextMenuOverlay(
    p: Palette,
    anchor: Rect?,
    onDismiss: () -> Unit,
    previewRadius: Dp = 16.dp,
    preview: @Composable () -> Unit,
    buildGroups: (close: ((() -> Unit)?) -> Unit) -> List<List<MenuAction>>
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    fun close(action: (() -> Unit)? = null) {
        scope.launch {
            shown = false
            delay(150)
            action?.invoke()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        // real blur of the app behind the dialog (Android 12+), plus our own soft dim below
        SideEffect {
            val w = (view.parent as? DialogWindowProvider)?.window
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                if (Build.VERSION.SDK_INT >= 31) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val lp = w.attributes
                    lp.blurBehindRadius = 28
                    w.attributes = lp
                }
            }
        }

        var originX by remember { mutableFloatStateOf(0f) }
        var originY by remember { mutableFloatStateOf(0f) }
        var rootW by remember { mutableIntStateOf(0) }
        var rootH by remember { mutableIntStateOf(0) }
        var menuH by remember { mutableIntStateOf(0) }
        val scrim by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "menuScrim")
        // the lifted item grows with a spring, like the iPhone
        val lift by animateFloatAsState(
            if (shown) 1.04f else 1f,
            spring(dampingRatio = 0.55f, stiffness = 380f), label = "menuLift"
        )

        val fallbackTop = rootH * 0.22f
        val rowH = anchor?.height ?: with(density) { 70.dp.toPx() }
        val rowTop = if (anchor != null) anchor.top - originY else fallbackTop
        val rowLeft = if (anchor != null) anchor.left - originX else 0f
        val rowW = if (anchor != null) anchor.width else rootW.toFloat()
        val gap = with(density) { 10.dp.toPx() }
        val margin = with(density) { 20.dp.toPx() }
        val menuW = with(density) { 240.dp.toPx() }
        val bottomLimit = rootH - with(density) { 28.dp.toPx() }
        val below = rowTop + rowH + gap + menuH <= bottomLimit
        val menuTop = if (below) rowTop + rowH + gap else (rowTop - gap - menuH).coerceAtLeast(with(density) { 8.dp.toPx() })
        val menuLeft = rowLeft.coerceIn(margin, (rootW - menuW - margin).coerceAtLeast(margin))
        // small items (album, artist card) grow the menu from the item, wide rows from the left
        val originFx = if (rowW > menuW * 1.5f) 0.1f
        else (((rowLeft + rowW / 2f) - menuLeft) / menuW).coerceIn(0.1f, 0.9f)
        val origin = TransformOrigin(originFx, if (below) 0f else 1f)

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    originX = loc[0].toFloat()
                    originY = loc[1].toFloat()
                    rootW = it.size.width
                    rootH = it.size.height
                }
                .background(Color.Black.copy(alpha = (if (p.dark) 0.30f else 0.12f) * scrim))
                .pointerInput(Unit) { detectTapGestures { close() } }
        ) {
            // the pressed item, lifted
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.offset { IntOffset(rowLeft.roundToInt(), rowTop.roundToInt()) },
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(100))
            ) {
                Box(
                    Modifier
                        .width(with(density) { rowW.toDp() })
                        .height(with(density) { rowH.toDp() })
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .clip(RoundedCornerShape(previewRadius))
                        .background(p.card)
                ) { preview() }
            }

            val groups = buildGroups { a -> close(a) }.map { g -> g }.filter { it.isNotEmpty() }

            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.offset { IntOffset(menuLeft.roundToInt(), menuTop.roundToInt()) },
                enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.82f, transformOrigin = origin),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.92f, transformOrigin = origin)
            ) {
                // hidden for the very first frame, until its height is known (to choose below/above)
                Box(Modifier.graphicsLayer { alpha = if (menuH > 0) 1f else 0f }.onSizeChanged { menuH = it.height }) {
                    IslandContextMenu(groups = groups, p = p)
                }
            }
        }
    }
}

/**
 * Wraps any item: tap = [onClick], long press = the iOS style context menu with [groups].
 * [content] is drawn in the list and again (lifted) inside the menu overlay.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuTarget(
    p: Palette,
    onClick: () -> Unit,
    groups: (close: ((() -> Unit)?) -> Unit) -> List<List<MenuAction>>,
    modifier: Modifier = Modifier,
    previewRadius: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .onGloballyPositioned { coords[0] = it }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val c = coords[0]
                    anchor = if (c != null && c.isAttached) {
                        val b = c.boundsInWindow()
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        Rect(b.left + loc[0], b.top + loc[1], b.right + loc[0], b.bottom + loc[1])
                    } else null
                    open = true
                }
            )
    ) {
        content()
        if (open) {
            ContextMenuOverlay(
                p = p, anchor = anchor, onDismiss = { open = false },
                previewRadius = previewRadius, preview = content, buildGroups = groups
            )
        }
    }
}

/**
 * The menu that opens from the three dots of a song and from a long press.
 * [anchor] = bounds of the pressed row on the screen (null: the menu opens near the top).
 */
@Composable
fun SongActionSheet(
    song: Song,
    fav: Boolean,
    p: Palette,
    onDismiss: () -> Unit,
    onFav: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onEditInfo: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    anchor: Rect? = null,
    onAddToQueue: (() -> Unit)? = null,
    onWatch: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val red = Color(0xFFFF3B30)
    ContextMenuOverlay(
        p = p,
        anchor = anchor,
        onDismiss = onDismiss,
        preview = {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(song, Modifier.size(52.dp), 10.dp, p, 300)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text
                    )
                    Text(
                        song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, color = p.secondary
                    )
                }
            }
        },
        buildGroups = { close ->
            listOf(
                buildList<MenuAction> {
                    if (onWatch != null) add(MenuAction("Watch video", Glyph.Play) { close { onWatch() } })
                    if (onPlayNext != null) add(MenuAction("Play next", Glyph.Next) { close { onPlayNext() } })
                    if (onAddToQueue != null) add(MenuAction("Add to queue", Glyph.Queue) { close { onAddToQueue() } })
                    if (onAddToPlaylist != null) add(MenuAction("Add to playlist", Glyph.PlusCircle) { close { onAddToPlaylist() } })
                    add(
                        MenuAction(
                            if (fav) "Remove favorite" else "Favorite",
                            if (fav) Glyph.Heart else Glyph.HeartOutline
                        ) { close { onFav() } }
                    )
                },
                buildList<MenuAction> {
                    if (onDownload != null) add(MenuAction("Download", Glyph.Download) { close { onDownload() } })
                    if (onEditInfo != null) add(MenuAction("Edit info", Glyph.Edit) { close { onEditInfo() } })
                    add(MenuAction("Share", Glyph.Share) { close { shareSong(context, song) } })
                },
                buildList<MenuAction> {
                    SleepMenuBridge.open?.let { open ->
                        add(
                            MenuAction(
                                SleepMenuBridge.label, Glyph.Moon,
                                if (SleepMenuBridge.active) Color(0xFF0A84FF) else null
                            ) { close { open() } }
                        )
                    }
                },
                buildList<MenuAction> {
                    if (onRemove != null) add(MenuAction("Remove from this list", Glyph.Close, red) { close { onRemove() } })
                }
            )
        }
    )
}
