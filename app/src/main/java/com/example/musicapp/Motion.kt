package com.example.musicapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ───────────────────────── 1. Living background from the cover colour ─────────────────────────

private fun motionShiftHue(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()),
        hsv
    )
    hsv[0] = (hsv[0] + deg + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Three soft colour blobs drifting slowly, like the Apple Music player background. */
@Composable
fun AnimatedCoverBackground(accent: Color, modifier: Modifier = Modifier) {
    val ac by animateColorAsState(accent, tween(800), label = "bgAccent")
    val t = rememberInfiniteTransition(label = "bgDrift")
    val a by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "bgA"
    )
    val b by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(12500, easing = LinearEasing), RepeatMode.Reverse), label = "bgB"
    )
    val c by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse), label = "bgC"
    )
    val c2 = motionShiftHue(ac, 35f)
    val c3 = motionShiftHue(ac, -35f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.6f
        fun blob(color: Color, cx: Float, cy: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy), radius = r
                ),
                radius = r, center = Offset(cx, cy)
            )
        }
        blob(ac, w * (0.10f + 0.55f * a), h * (0.12f + 0.22f * b), 0.50f)
        blob(c2, w * (0.95f - 0.60f * b), h * (0.45f + 0.20f * c), 0.34f)
        blob(c3, w * (0.20f + 0.50f * c), h * (0.85f - 0.25f * a), 0.30f)
    }
}

// ───────────────────────── 3. Cards fly in one after another ─────────────────────────

/** Fade + rise + small scale-up, delayed by [index] so a grid builds up item by item. */
fun Modifier.staggerIn(index: Int, enabled: Boolean = true): Modifier = composed {
    val a = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enabled) {
            delay((index.coerceIn(0, 12) * 55).toLong())
            a.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 220f))
        }
    }
    this.graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 36.dp.toPx()
        val s = 0.92f + 0.08f * v
        scaleX = s
        scaleY = s
    }
}

// ───────────────────────── 4. Stretchy header ─────────────────────────

/** Pull a list down at the top and [pull] grows; letting go springs it back. */
class StretchState {
    var pull by mutableFloatStateOf(0f)
        private set

    private val maxPull = 520f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (pull > 0f && available.y < 0f) {
                val used = maxOf(available.y, -pull)
                pull += used
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0f) {
                pull = (pull + available.y * 0.55f).coerceAtMost(maxPull)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pull > 0f) {
                release()
                return available
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (pull > 0f) release()
            return Velocity.Zero
        }
    }

    private suspend fun release() {
        animate(
            initialValue = pull, targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 260f)
        ) { v, _ -> pull = v.coerceAtLeast(0f) }
        pull = 0f
    }
}

@Composable
fun rememberStretchState(): StretchState = remember { StretchState() }

// ───────────────────────── 5. Equalizer bars for the playing song ─────────────────────────

/** Four little bars that dance while music plays and rest low when paused. */
@Composable
fun EqualizerBars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eqBars")
    val f0 by t.animateFloat(
        0.2f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "eq0"
    )
    val f1 by t.animateFloat(
        0.2f, 1f, infiniteRepeatable(tween(410, easing = LinearEasing), RepeatMode.Reverse), label = "eq1"
    )
    val f2 by t.animateFloat(
        0.2f, 1f, infiniteRepeatable(tween(640, easing = LinearEasing), RepeatMode.Reverse), label = "eq2"
    )
    val f3 by t.animateFloat(
        0.2f, 1f, infiniteRepeatable(tween(470, easing = LinearEasing), RepeatMode.Reverse), label = "eq3"
    )
    val fr = floatArrayOf(f0, f1, f2, f3)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0..3) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (playing) fr[i] else 0.2f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// ───────────────────────── 10. Shimmer while a cover loads ─────────────────────────

/** A soft light band that sweeps across its parent; put it on top while something loads. */
@Composable
fun ShimmerOverlay(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        -0.6f, 1.6f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmerX"
    )
    Box(
        modifier.drawBehind {
            val w = size.width
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                    startX = x * w - w * 0.5f,
                    endX = x * w + w * 0.5f
                )
            )
        }
    )
}

// ───────────────────────── 11. Cover tilts with the phone ─────────────────────────

/**
 * Smoothed tilt of the phone relative to how it is being held: x = left/right, y = forward/back,
 * both roughly in -0.5..0.5. Uses the accelerometer, only while this composable is on screen.
 */
@Composable
fun rememberDeviceTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var first = true
        var bx = 0f
        var by = 0f
        var sx = 0f
        var sy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val nx = (e.values[0] / 9.81f).coerceIn(-1f, 1f)
                val ny = (e.values[1] / 9.81f).coerceIn(-1f, 1f)
                if (first) {
                    first = false
                    bx = nx; by = ny; sx = nx; sy = ny
                }
                // the "rest" pose slowly follows how the phone is held; quick moves show as tilt
                bx += (nx - bx) * 0.01f
                by += (ny - by) * 0.01f
                sx += (nx - sx) * 0.12f
                sy += (ny - sy) * 0.12f
                tilt.value = Offset(
                    (sx - bx).coerceIn(-0.5f, 0.5f),
                    (sy - by).coerceIn(-0.5f, 0.5f)
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}
