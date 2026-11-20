package dev.agentring.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import kotlinx.coroutines.delay

/*
 * Hivepal: "typing…" (issue #48). The relay says which agents are writing where (`agent.typing`, live
 * only); each one ends by itself at its `until`, so the screens re-check then.
 */

/** Who is writing right now: conversation → agent ids. */
@Composable
fun rememberTyping(): Map<String, List<String>> {
    val all by Graph.directory.typing.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Wake up when the next one runs out.
    LaunchedEffect(all, now) {
        val next = all.values.flatMap { it.values }.filter { it > now }.minOrNull() ?: return@LaunchedEffect
        delay(next - System.currentTimeMillis() + 50)
        now = System.currentTimeMillis()
    }
    return all.mapValues { (_, m) -> m.filterValues { it > now }.keys.toList() }.filterValues { it.isNotEmpty() }
}

/** "typing…", "Nubi is typing…" (a group), "Nubi and Coach are typing…". */
@Composable
fun typingLabel(agents: List<String>, named: Boolean, main: String): String = when {
    agents.isEmpty() -> ""
    !named -> stringResource(R.string.typing)
    agents.size == 1 -> stringResource(R.string.typing_one, Graph.directory.nameOf(agents[0], main))
    agents.size == 2 -> stringResource(R.string.typing_two, Graph.directory.nameOf(agents[0], main), Graph.directory.nameOf(agents[1], main))
    else -> stringResource(R.string.typing_many)
}

/** Three dots that rise and fade one after the other. */
@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant, dot: Int = 7) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 0 until 3) {
            val p by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1200, delayMillis = 0), RepeatMode.Restart, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160)),
                label = "dot$i",
            )
            // Up and bright for the first half of the cycle, resting for the rest.
            val lift = if (p < 0.5f) kotlin.math.sin(p * 2 * Math.PI).toFloat() else 0f
            Box(
                Modifier.offset(y = (-4 * lift).dp).size(dot.dp).alpha(0.35f + 0.65f * lift).background(color, CircleShape),
            )
        }
    }
}

/** The "…" bubble at the bottom of a chat, on the agents' side, with who is writing in a group. */
@Composable
fun TypingBubble(label: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp).testTag("typing_bubble")) {
        label?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) { TypingDots() }
    }
}
