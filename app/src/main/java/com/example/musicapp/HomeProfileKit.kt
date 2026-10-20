package com.example.musicapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

/** Click that shrinks the item while the finger is down and springs back, like iOS cards. */
fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "bounceClick"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * Parallax for the first item of a list: it moves at [speed] of the scroll distance and,
 * when [shrink] > 0, gets smaller and fades while the page scrolls away.
 */
fun Modifier.scrollParallax(state: LazyListState, speed: Float, shrink: Float = 0f): Modifier =
    this.graphicsLayer {
        val off = if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else 2000f
        val pr = (off / 300f).coerceIn(0f, 1f)
        translationY = off.coerceAtMost(600f) * speed
        val s = 1f - shrink * pr
        scaleX = s
        scaleY = s
        if (shrink > 0f) alpha = 1f - 0.7f * pr
    }

/** Counts from 0 up to [target] when it first appears. */
@Composable
fun rememberCountUp(target: Int): Int {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    return a.value.roundToInt()
}

/** "Good morning" / "Good afternoon" / ... for the Home title. */
fun greetingNow(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Good night"
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}
