package com.example.musicapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * iPhone style volume indicator on the left edge.
 *
 * Every time it appears (volume key or touch) it is BIG first: a wide glass pill you can touch and drag
 * to change the volume. When you stop, it shrinks into the thin capsule it always was, and then slides away.
 * The capsule sits on liquid glass: translucent body, bright rim, soft highlight, shadow.
 *
 * [signal] changes on every key press, [edge] changes when a press hits min or max, [level] is 0..1,
 * [onLevel] is called while you drag (0..1).
 */
@Composable
fun VolumeHud(
    signal: Int,
    edge: Int,
    level: Float,
    p: Palette,
    onLevel: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var touchTick by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(1) }
    val onLevelS by rememberUpdatedState(onLevel)

    // big for a moment (longer while a finger is on it), then small, then gone
    LaunchedEffect(signal, touchTick) {
        if (signal == 0 && touchTick == 0) return@LaunchedEffect
        visible = true
        expanded = true
        delay(1900)
        while (touching) delay(100)
        expanded = false
        delay(1300)
        visible = false
    }

    val fill by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 500f), label = "volFill")
    // swells a little while the volume is changing (small state only)
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        active = true
        delay(450)
        active = false
    }
    val width by animateDpAsState(
        if (expanded) 68.dp else if (active) 13.dp else 9.dp,
        spring(dampingRatio = 0.7f, stiffness = 380f), label = "volW"
    )
    val height by animateDpAsState(
        if (expanded) 240.dp else 150.dp,
        spring(dampingRatio = 0.75f, stiffness = 380f), label = "volH"
    )
    val swell by animateFloatAsState(if (active && !expanded) 1.03f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "volS")
    val iconAlpha by animateFloatAsState(if (expanded) 1f else 0f, tween(180), label = "volIcon")

    // rubber band at the ends
    val stretch = remember { Animatable(1f) }
    LaunchedEffect(edge) {
        if (edge == 0) return@LaunchedEffect
        stretch.snapTo(1f)
        stretch.animateTo(1.07f, tween(70))
        stretch.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }

    fun setFromY(y: Float) {
        onLevelS((1f - y / heightPx).coerceIn(0f, 1f))
        touchTick++
    }

    // liquid glass body: light glass on dark themes, smoky glass on light ones, so it is always visible
    val glassBody = if (p.dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.11f)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF5A5A5E).copy(alpha = 0.46f), Color(0xFF2C2C2E).copy(alpha = 0.34f)))
    }
    val rim = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.50f))
    )

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(start = 12.dp),
        enter = slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 420f)) { -it * 3 } + fadeIn(tween(120)),
        exit = slideOutHorizontally(tween(220)) { -it * 3 } + fadeOut(tween(200))
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleY = swell * stretch.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .size(width = width, height = height)
                .shadow(14.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(CircleShape)
                .background(glassBody)
                .border(if (expanded) 1.2.dp else 0.8.dp, rim, CircleShape)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .then(
                    if (expanded) Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { o ->
                                touching = true
                                setFromY(o.y)
                                tryAwaitRelease()
                                touching = false
                            })
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { touching = true; setFromY(it.y) },
                                onDragEnd = { touching = false },
                                onDragCancel = { touching = false },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    setFromY(change.position.y)
                                }
                            )
                        }
                    else Modifier
                )
        ) {
            // soft highlight on the upper left, like light on glass
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.55f)
                    .fillMaxHeight(0.5f)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent)))
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fill)
                    .background(Color.White.copy(alpha = 0.95f))
            )
            GlyphIcon(
                Glyph.Volume,
                if (fill > 0.12f) Color.Black else Color.White,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .size(24.dp)
                    .graphicsLayer { alpha = iconAlpha }
            )
        }
    }
}
