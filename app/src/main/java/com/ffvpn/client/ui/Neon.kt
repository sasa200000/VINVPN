package com.ffvpn.client.ui

import android.net.TrafficStats
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ffvpn.client.core.Prefs
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * The shapes the light dance can take. Each one is a different way of drawing
 * the same border, so switching shape changes the look and nothing else.
 */
enum class NeonShape { RING, DASH, DOTS, GLOW, WAVE }

/**
 * The light dance: one shared second counter and one palette, so every ring,
 * card and chart on screen changes colour on the same tick instead of each
 * keeping its own clock.
 *
 * Before the tunnel is up every ring sits on canary yellow; once connected the
 * palette takes over and the colour steps once per second.
 *
 * [enabled] and [shape] are Compose state as well as preferences: the settings
 * sheet writes them and every ring on the home screen redraws at once. [load]
 * pulls the stored values in once Prefs exists.
 */
object Neon {
    /** Rainbow-neon, lifted from the launcher art: magenta to blue across the set. */
    val palette = listOf(
        Color(0xFFFF4FD8),
        Color(0xFFFF8A3D),
        Color(0xFFFFE14A),
        Color(0xFF5DFF9E),
        Color(0xFF4FE3FF),
        Color(0xFF6F8CFF),
        Color(0xFFC77DFF)
    )

    /** The colour every ring holds until the connection is actually up. */
    val canary = Color(0xFFFFE94A)

    var enabled by mutableStateOf(true)
    var shape by mutableStateOf(NeonShape.RING)

    /** Shared tick: +1 every 1000 ms while the dance is on. */
    val tick = mutableIntStateOf(0)

    fun load() {
        enabled = runCatching { Prefs.neonDance }.getOrDefault(true)
        shape = runCatching { NeonShape.valueOf(Prefs.neonShape) }.getOrDefault(NeonShape.RING)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        Prefs.neonDance = value
    }

    fun setShape(value: NeonShape) {
        shape = value
        Prefs.neonShape = value.name
    }

    /**
     * The colour for this tick. [offset] staggers a group of rings so the
     * download and upload rings are never the same colour as each other.
     * [connected] keeps the caller on canary yellow until the tunnel is up.
     */
    fun color(offset: Int = 0, connected: Boolean = true): Color {
        if (!connected) return canary
        if (!enabled) return palette[0]
        val index = ((tick.intValue + offset) % palette.size + palette.size) % palette.size
        return palette[index]
    }
}

/**
 * Advances [Neon.tick] once a second. Mounted once per screen that shows the
 * dance; a screen without it simply freezes on the last colour.
 */
@Composable
fun rememberNeonTick() {
    val enabled = Neon.enabled
    LaunchedEffect(enabled) {
        Neon.tick.intValue = 0
        while (enabled) {
            delay(1_000)
            Neon.tick.intValue++
        }
    }
}

private const val WAVE_POINTS = 96

/**
 * Draws one dance border of [shape] around a box of the given [size], inset by
 * half the stroke so the stroke stays inside. [phase] animates only the wave
 * shape; the rest are static paths whose colour comes from the caller.
 */
fun DrawScope.drawNeonBorder(
    shape: NeonShape,
    color: Color,
    size: Size,
    strokeWidth: Dp = 2.dp,
    cornerRadius: Dp = 14.dp,
    phase: Float = 0f
) {
    val stroke = strokeWidth.toPx()
    val inset = stroke / 2f + 1.dp.toPx()
    val rect = Rect(inset, inset, size.width - inset, size.height - inset)
    val radius = cornerRadius.toPx()
    val path = Path().apply { addRoundRect(RoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius)) }

    when (shape) {
        NeonShape.RING -> drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))

        NeonShape.DASH -> drawPath(
            path,
            color,
            style = Stroke(
                stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 4f, stroke * 3f))
            )
        )

        NeonShape.DOTS -> drawPath(
            path,
            color,
            style = Stroke(
                stroke * 1.2f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, stroke * 5f))
            )
        )

        NeonShape.GLOW -> {
            // Three passes from wide-and-faint to thin-and-solid read as a glow
            // without a blur filter, which would cost a layer per card.
            drawPath(path, color.copy(alpha = 0.16f), style = Stroke(stroke * 4f))
            drawPath(path, color.copy(alpha = 0.34f), style = Stroke(stroke * 2.2f))
            drawPath(path, color, style = Stroke(stroke))
        }

        NeonShape.WAVE -> {
            val wavy = Path()
            val measure = PathMeasure().apply { setPath(path, false) }
            val total = measure.length
            var started = false
            for (i in 0..WAVE_POINTS) {
                val distance = total * i / WAVE_POINTS
                val p = measure.getPosition(distance)
                val t = measure.getTangent(distance)
                // Push each point along its normal so the border ripples.
                val normalX = -t.y
                val normalY = t.x
                val ripple = sin(distance / 26f + phase) * stroke * 1.6f
                val x = p.x + normalX * ripple
                val y = p.y + normalY * ripple
                if (!started) {
                    wavy.moveTo(x, y)
                    started = true
                } else {
                    wavy.lineTo(x, y)
                }
            }
            drawPath(wavy, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * The dance ring around the connect button: a circle, drawn with the active
 * shape so the button matches the cards. [connected] decides canary-versus-
 * palette exactly like [Neon.color] does.
 */
fun DrawScope.drawNeonRing(
    center: Offset,
    radius: Float,
    connected: Boolean,
    offset: Int = 0,
    strokeWidth: Dp = 3.dp,
    phase: Float = 0f
) {
    val color = Neon.color(offset = offset, connected = connected)
    val stroke = strokeWidth.toPx()
    when (Neon.shape) {
        NeonShape.RING -> drawCircle(color, radius, center, style = Stroke(stroke))

        NeonShape.DASH -> drawCircle(
            color, radius, center,
            style = Stroke(
                stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 5f, stroke * 4f))
            )
        )

        NeonShape.DOTS -> drawCircle(
            color, radius, center,
            style = Stroke(
                stroke * 1.2f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, stroke * 6f))
            )
        )

        NeonShape.GLOW -> {
            drawCircle(color.copy(alpha = 0.14f), radius, center, style = Stroke(stroke * 4.5f))
            drawCircle(color.copy(alpha = 0.32f), radius, center, style = Stroke(stroke * 2.4f))
            drawCircle(color, radius, center, style = Stroke(stroke))
        }

        NeonShape.WAVE -> {
            val path = Path().apply {
                addOval(
                    Rect(
                        center.x - radius,
                        center.y - radius,
                        center.x + radius,
                        center.y + radius
                    )
                )
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val total = measure.length
            val wavy = Path()
            var started = false
            for (i in 0..WAVE_POINTS) {
                val distance = total * i / WAVE_POINTS
                val p = measure.getPosition(distance)
                val t = measure.getTangent(distance)
                val normalX = -t.y
                val normalY = t.x
                val ripple = sin(distance / 24f + phase) * stroke * 1.7f
                val x = p.x + normalX * ripple
                val y = p.y + normalY * ripple
                if (!started) {
                    wavy.moveTo(x, y)
                    started = true
                } else {
                    wavy.lineTo(x, y)
                }
            }
            drawPath(wavy, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Border colour for a card: canary until connected, palette after. */
@Composable
fun neonColor(offset: Int = 0, connected: Boolean): Color =
    remember(Neon.tick.intValue, Neon.enabled, Neon.shape, connected, offset) {
        Neon.color(offset = offset, connected = connected)
    }

/**
 * A dance border as a modifier, for any card that wants one. The wave shape
 * ripples on its own clock; every other shape just follows the colour tick.
 * With the dance switched off the modifier draws nothing, so the off switch in
 * Settings really does turn the whole effect off.
 */
@Composable
fun Modifier.neonBorder(
    connected: Boolean,
    offset: Int = 0,
    strokeWidth: Dp = 2.dp,
    cornerRadius: Dp = 14.dp
): Modifier {
    if (!Neon.enabled) return this
    val color = neonColor(offset = offset, connected = connected)
    val shape = Neon.shape
    val transition = rememberInfiniteTransition(label = "borderWave")
    val phaseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "borderPhase"
    )
    return this.then(
        Modifier.drawBehind {
            drawNeonBorder(
                shape = shape,
                color = color,
                size = size,
                strokeWidth = strokeWidth,
                cornerRadius = cornerRadius,
                phase = phaseState.value
            )
        }
    )
}

/**
 * A live wave chart, like the sea: crests drifting sideways, coloured from the
 * dance palette so the whole card turns with the rest of the screen. [level] is
 * 0f..1f and swells the amplitude — it tracks throughput when the caller has a
 * number and idles at a gentle swell when it does not.
 */
@Composable
fun WaveChart(
    level: Float,
    connected: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 76.dp
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing)),
        label = "drift"
    )
    val swell by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(2_400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "swell"
    )
    val color = neonColor(offset = 3, connected = connected)
    val amplitude = (0.28f + level.coerceIn(0f, 1f) * 0.42f) * swell

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height
        val base = h * 0.62f

        fun crest(wavelength: Float, speed: Float, scale: Float, alpha: Float, fill: Boolean) {
            val path = Path()
            val steps = 64
            for (i in 0..steps) {
                val x = w * i / steps
                val y = base + sin(x / wavelength + drift * speed) * h * amplitude * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (fill) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.30f * alpha), Color.Transparent)
                    )
                )
            }
            drawPath(
                path,
                color.copy(alpha = alpha),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        crest(wavelength = 132f, speed = 1f, scale = 1f, alpha = 0.95f, fill = true)
        crest(wavelength = 88f, speed = -1.4f, scale = 0.6f, alpha = 0.5f, fill = false)
        crest(wavelength = 210f, speed = 0.6f, scale = 0.35f, alpha = 0.3f, fill = false)
    }
}

/**
 * Device-wide throughput, sampled once a second: the delta of the totals since
 * the previous tick. Totals need no permission and cover the tunnel's own
 * traffic, which a per-uid counter would miss once the VPN is up.
 */
data class Rates(val down: Long, val up: Long)

@Composable
fun rememberRates(): Rates {
    var rates by remember { mutableStateOf(Rates(0L, 0L)) }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1_000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val down = if (rx >= 0 && lastRx >= 0 && rx >= lastRx) rx - lastRx else 0L
            val up = if (tx >= 0 && lastTx >= 0 && tx >= lastTx) tx - lastTx else 0L
            rates = Rates(down, up)
            lastRx = rx
            lastTx = tx
        }
    }
    return rates
}

/** Bytes per second in the unit a person reads at a glance. */
fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0L -> "0 B/s"
    bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
    bytesPerSecond < 1024L * 1024L ->
        String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
    else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
}

/**
 * 0f..1f for the wave chart, logarithmic so a few KB/s already lifts the sea
 * and a busy tunnel still has room to swell.
 */
fun rateLevel(totalPerSecond: Long): Float {
    if (totalPerSecond <= 0L) return 0f
    val ceiling = ln(1.0 + 8_000_000.0)
    return (ln(1.0 + totalPerSecond) / ceiling).toFloat().coerceIn(0f, 1f)
}
