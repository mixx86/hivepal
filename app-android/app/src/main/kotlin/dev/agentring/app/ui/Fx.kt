package dev.agentring.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The brand gradient: royal → electric blue → cyan. */
val BrandBrush: Brush get() = Brush.linearGradient(listOf(Retro.Royal, Retro.Blue, Retro.Cyan))
val MineBrush: Brush get() = Brush.linearGradient(listOf(Retro.Royal, Retro.Blue))

/** Shrinks while pressed and springs back; a light haptic tick on click. */
fun Modifier.bouncyClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce",
    )
    val haptic = LocalHapticFeedback.current
    this.scale(scale).clickable(interactionSource = source, indication = null, enabled = enabled) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** Only the press bounce, for components that handle their own click. */
fun Modifier.pressBounce(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this.scale(scale)
}

/** A still, soft glow behind the element (the main buttons): the brand's light, without moving. */
fun Modifier.softGlow(color: Color = Retro.Blue, radius: Float = 1.25f, alpha: Float = 0.28f): Modifier = drawBehind {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, size.minDimension * radius / 2),
        radius = size.minDimension * radius / 2,
    )
}

/**
 * A soft glow that breathes behind the element. Movement draws the eye, so only for what needs
 * attention now (a subscription about to renew); steady buttons use [softGlow].
 */
fun Modifier.breathingGlow(color: Color = Retro.Blue, radius: Float = 1.25f): Modifier = composed {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(0.15f, 0.45f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    drawBehind {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center, size.minDimension * radius / 2),
            radius = size.minDimension * radius / 2,
        )
    }
}

/** Unread count that pops when it changes and pulses gently while there's something to read. */
@Composable
fun PulseBadge(count: Int, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(count) {
        pop.snapTo(1.45f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
    }
    val t = rememberInfiniteTransition(label = "pulse")
    val ring by t.animateFloat(1f, 1.6f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "ring")
    val ringAlpha by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "ringA")
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).scale(ring).drawBehind { drawCircle(Retro.Cyan.copy(alpha = ringAlpha), style = androidx.compose.ui.graphics.drawscope.Stroke(2f)) },
        )
        Box(
            Modifier.size(22.dp).scale(pop.value).background(BrandBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "99+" else "$count", style = MaterialTheme.typography.labelSmall, color = Retro.Ink)
        }
    }
}

private class Particle(val angle: Float, val speed: Float, val spin: Float, val size: Float, val color: Color)

private fun hexPath(r: Float): Path = Path().apply {
    for (i in 0 until 6) {
        val a = (i * 60 - 90) * PI.toFloat() / 180f
        val p = Offset(r * cos(a), r * sin(a))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/**
 * A burst of little honeycomb cells from `origin` (fraction of the box) every time `trigger` changes
 * (and isn't 0). Put it last in a Box over the content; it doesn't take touches.
 */
@Composable
fun HexBurst(trigger: Int, origin: Offset = Offset(0.5f, 0.8f)) {
    if (trigger == 0) return
    val progress = remember { Animatable(0f) }
    val particles = remember(trigger) {
        val colors = listOf(Retro.Blue, Retro.Cyan, Color.White, Color(0xFF4361EE), Color(0xFFFFD60A))
        List(34) {
            Particle(
                angle = (-PI / 2 + (Random.nextFloat() - 0.5f) * PI * 1.4).toFloat(),
                speed = 500f + Random.nextFloat() * 900f,
                spin = (Random.nextFloat() - 0.5f) * 720f,
                size = 7f + Random.nextFloat() * 10f,
                color = colors[it % colors.size],
            )
        }
    }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    if (p >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val o = Offset(size.width * origin.x, size.height * origin.y)
        particles.forEach { q ->
            val d = q.speed * p
            val pos = Offset(o.x + cos(q.angle) * d, o.y + sin(q.angle) * d + 900f * p * p)
            rotate(q.spin * p, pos) {
                translate(pos.x, pos.y) {
                    drawPath(hexPath(q.size), q.color.copy(alpha = (1f - p).coerceIn(0f, 1f)))
                }
            }
        }
    }
}
