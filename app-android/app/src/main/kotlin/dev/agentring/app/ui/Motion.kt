package dev.agentring.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Motion for the setup (issue #48): things arrive in order, answers respond, and a finished step
 * celebrates. Movement only at moments that mean something; nothing loops on screens you stay on,
 * except the welcome's hive. Android's "Remove animations" shows everything at once.
 */

/** Android's "Remove animations" (animator duration scale 0). */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Fades and rises into place once, [order] beats after the page appears. */
fun Modifier.appear(order: Int = 0): Modifier = composed {
    val reduced = reducedMotion()
    val p = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(order * 70L)
        p.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 28.dp.toPx()
    }
}

/** A small springy nudge each time [selected] turns true: the card answers the tap. */
fun Modifier.selectBounce(selected: Boolean): Modifier = composed {
    val s = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected && s.value == 1f) {
            s.snapTo(0.96f)
            s.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}

/** A pointy-topped hexagon with rounded corners, like the logo's cells (each corner cut at [round] of its sides). */
private fun hexagon(c: Offset, r: Float, round: Float = 0.2f): Path {
    val v = List(6) { i ->
        val a = (PI / 3 * i - PI / 2).toFloat()
        Offset(c.x + r * cos(a), c.y + r * sin(a))
    }
    fun lerp(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    return Path().apply {
        val start = lerp(v[0], v[1], round)
        moveTo(start.x, start.y)
        for (i in 0 until 6) {
            val a = v[(i + 1) % 6]
            val b = v[(i + 2) % 6]
            val before = lerp(v[i], a, 1f - round)
            val after = lerp(a, b, round)
            lineTo(before.x, before.y)
            quadraticTo(a.x, a.y, after.x, after.y)
        }
        close()
    }
}

/**
 * The welcome's hero: Hivepal's own logo assembling itself. The white centre cell appears, the six blue
 * cells come out of it one by one into place, then a soft light travels round the ring. Same geometry
 * as the launcher icon (tools/hivepal_icon.py): cells of radius R, their centres 1.85 R from the middle.
 */
@Composable
fun HiveLogo(size: Dp = 180.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val reduced = reducedMotion()
    val core = remember { Animatable(if (reduced) 1f else 0f) }
    val cells = remember { List(6) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        launch { core.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        delay(280)
        cells.forEach { c ->
            launch { c.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
            delay(95)
        }
    }
    val t = rememberInfiniteTransition(label = "hive")
    val wave by t.animateFloat(0f, if (reduced) 0f else 6f, infiniteRepeatable(tween(5_400, easing = LinearEasing)), label = "wave")
    val light = Color(0xFF8AD8FF)

    Canvas(Modifier.size(size).testTag("hive_logo")) {
        val r = this.size.minDimension / 5.7f
        val d = r * 1.85f
        val c = center
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.20f * core.value), Color.Transparent), c, d * 2.2f), d * 2.2f, c)
        for (i in 0 until 6) {
            val p = cells[i].value
            if (p <= 0.01f) continue
            val a = (PI / 3 * i - PI / 2).toFloat()
            // Out of the centre into place.
            val cc = Offset(c.x + d * p * cos(a), c.y + d * p * sin(a))
            // The light: brightest on the cell it's passing.
            val dist = ((wave - i) % 6f + 6f) % 6f
            val near = (1f - minOf(dist, 6f - dist) / 1.4f).coerceIn(0f, 1f)
            val color = androidx.compose.ui.graphics.lerp(primary, light, 0.45f * near)
            drawPath(hexagon(cc, r * p.coerceAtMost(1f)), color.copy(alpha = p.coerceIn(0f, 1f)))
        }
        val k = core.value
        if (k > 0.01f) {
            drawPath(hexagon(c, r * k), Color.White)
        }
    }
}

/** A finished step: the circle pops in, the check draws itself, and a few sparks fly out. */
@Composable
fun SuccessBadge(size: Dp = 88.dp) {
    val scheme = MaterialTheme.colorScheme
    val reduced = reducedMotion()
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    val draw = remember { Animatable(if (reduced) 1f else 0f) }
    val burst = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        launch { burst.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        draw.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.size(size * 2f)) {
        val c = center
        val r = size.toPx() / 2
        // Sparks: out from the edge, fading as they go.
        val b = burst.value
        if (b > 0f && b < 1f) {
            for (i in 0 until 12) {
                val a = (2 * PI / 12 * i + PI / 12).toFloat()
                val from = r * (1.05f + 0.55f * b)
                val to = from + r * 0.28f * (1f - b)
                drawLine(
                    scheme.primary.copy(alpha = 1f - b),
                    Offset(c.x + from * cos(a), c.y + from * sin(a)),
                    Offset(c.x + to * cos(a), c.y + to * sin(a)),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        val p = pop.value
        drawCircle(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.25f * p), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
        drawCircle(scheme.primary, r * p, c)
        // The check, drawn from its short arm to its long one.
        val check = Path().apply {
            moveTo(c.x - r * 0.38f, c.y + r * 0.02f)
            lineTo(c.x - r * 0.10f, c.y + r * 0.30f)
            lineTo(c.x + r * 0.42f, c.y - r * 0.28f)
        }
        val m = PathMeasure().apply { setPath(check, false) }
        val part = Path()
        m.getSegment(0f, m.length * draw.value, part, true)
        drawPath(part, scheme.onPrimary, style = Stroke(width = r * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
