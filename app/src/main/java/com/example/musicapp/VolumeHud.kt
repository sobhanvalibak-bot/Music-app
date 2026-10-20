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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * iPhone style volume indicator: a very thin vertical capsule on the left edge.
 * It slides in when a volume key is pressed, the white fill rises and falls with the volume,
 * the capsule swells a little while you hold it, and it stretches like rubber at min / max.
 * Hides itself after a moment.
 *
 * [signal] changes on every key press, [edge] changes when a press hits min or max, [level] is 0..1.
 */
@Composable
fun VolumeHud(signal: Int, edge: Int, level: Float, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        visible = true
        delay(1600)
        visible = false
    }

    val fill by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 500f), label = "volFill")
    // swells while the volume is changing, relaxes a moment later
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        active = true
        delay(450)
        active = false
    }
    val width by animateDpAsState(if (active) 13.dp else 9.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "volW")
    val swell by animateFloatAsState(if (active) 1.03f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "volS")

    // rubber band at the ends
    val stretch = remember { Animatable(1f) }
    LaunchedEffect(edge) {
        if (edge == 0) return@LaunchedEffect
        stretch.snapTo(1f)
        stretch.animateTo(1.07f, tween(70))
        stretch.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }

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
                .size(width = width, height = 150.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(CircleShape)
                .background(Color(0xFF2C2C2E).copy(alpha = 0.62f))
                .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fill)
                    .background(Color.White.copy(alpha = 0.95f))
            )
        }
    }
}
