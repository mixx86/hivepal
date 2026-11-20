package dev.agentring.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.CallHistory
import dev.agentring.app.data.Pairing
import dev.agentring.core.Agent
import dev.agentring.core.Call
import dev.agentring.core.Conv
import dev.agentring.core.Group
import dev.agentring.core.RelaySocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Call the main agent (the voice AI), and the history of calls with every agent. */
@Composable
fun CallsScreen(pairing: Pairing) {
    val calls by Graph.callHistory.calls.collectAsStateWithLifecycle()
    val relay by Graph.connection.relayState.collectAsStateWithLifecycle()
    val agent by Graph.connection.agentOnline.collectAsStateWithLifecycle()
    val revoked by Graph.pairing.revoked.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Graph.api()?.let { runCatching { Graph.callHistory.refresh(it) } } }
        Graph.callHistory.markSeen()
    }
    LaunchedEffect(calls.size) { Graph.callHistory.markSeen() }

    LazyColumn(Modifier.fillMaxSize().testTag("call_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            BrandCard(Modifier.padding(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CallAgentButton(pairing, relayConnected = relay == RelaySocket.State.CONNECTED, agentOnline = agent == true, revoked = revoked)
                }
            }
        }
        if (calls.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.calls_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.calls_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(calls, key = { it.callId }) { c -> CallRow(c) }
    }
}

@Composable
private fun CallRow(c: Call) {
    val scheme = MaterialTheme.colorScheme
    val missed = CallHistory.isMissed(c)
    val (label, color) = when {
        c.isRinging || c.isActive -> stringResource(R.string.call_active) to scheme.primary
        missed -> stringResource(R.string.call_missed) to scheme.error
        c.direction == "user_to_agent" -> stringResource(R.string.call_outgoing) to scheme.onSurfaceVariant
        c.state == "declined" -> stringResource(R.string.call_declined) to scheme.onSurfaceVariant
        else -> stringResource(R.string.call_incoming) to scheme.onSurfaceVariant
    }
    val arrow = if (c.direction == "user_to_agent") "↗" else "↙"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("call_${c.callId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ConvAvatar(Conv.ofAgent(c.agentId ?: Conv.MAIN_AGENT), c.agentName, size = 46.dp)
        Column(Modifier.weight(1f)) {
            Text(
                c.agentName,
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) scheme.error else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildString {
                append("$arrow $label")
                c.durationMs?.takeIf { it > 0 }?.let { append(" · ${it / 60000}:${"%02d".format((it / 1000) % 60)}") }
                if (!c.reason.isNullOrBlank()) append(" · ${c.reason}")
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(shortTime(c.createdAt), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** The agents (rename) and the groups (create, edit) on the relay. */
@Composable
fun AgentsScreen(pairing: Pairing, onOpenChat: (String) -> Unit) {
    val agents by Graph.directory.agents.collectAsStateWithLifecycle()
    val groups by Graph.directory.groups.collectAsStateWithLifecycle()
    val online by Graph.directory.online.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Agent?>(null) }
    var delivering by remember { mutableStateOf<Agent?>(null) }
    var editing by remember { mutableStateOf<Group?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Graph.api()?.let { runCatching { Graph.directory.refresh(it) } } }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("agents_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader(stringResource(R.string.agents_section)) }
        if (agents.size <= 1) {
            item {
                Text(
                    stringResource(R.string.no_agents_api),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        val shown = agents.ifEmpty { listOf(Agent(Conv.MAIN_AGENT, pairing.agentName)) }
        items(shown, key = { "a:" + it.agentId }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenChat(Conv.ofAgent(a.agentId)) }.padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("agent_${a.agentId}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ConvAvatar(Conv.ofAgent(a.agentId), a.name, size = 46.dp, online = a.agentId in online)
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    val seen = a.lastSeenAt
                    Text(
                        stringResource(R.string.agent_id_label, a.agentId) + when {
                            a.agentId in online -> " · " + stringResource(R.string.agent_online)
                            seen != null -> " · " + stringResource(R.string.last_seen, android.text.format.DateUtils.getRelativeTimeSpanString(
                                seen, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        // A bot not seen for over a day: its routines may have stopped.
                        color = if (seen != null && a.agentId !in online && System.currentTimeMillis() - seen > 24 * 3600_000L)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (agents.isNotEmpty()) {
                    IconButton(onClick = { delivering = a }, modifier = Modifier.testTag("delivery_${a.agentId}")) {
                        Icon(
                            Icons.Filled.Email,
                            stringResource(R.string.agent_delivery),
                            tint = if (a.wakes == true || a.mailbox == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renaming = a }, modifier = Modifier.testTag("rename_${a.agentId}")) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.rename), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader(stringResource(R.string.groups_section))
        }
        items(groups, key = { "g:" + it.groupId }) { g ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenChat(Conv.ofGroup(g.groupId)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ConvAvatar(Conv.ofGroup(g.groupId), g.name, size = 46.dp)
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        g.members.joinToString(", ") { Graph.directory.nameOf(it, pairing.agentName) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { editing = g }) {
                    Icon(Icons.Filled.Edit, stringResource(R.string.edit_group), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (agents.size > 1) {
            item {
                OutlinedButton(
                    onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.new_group))
                }
            }
        }
    }

    renaming?.let { a -> RenameAgentDialog(a.agentId, a.name, onDismiss = { renaming = null }) }
    delivering?.let { a -> AgentDeliveryDialog(a, onDismiss = { delivering = null }) }
    editing?.let { g -> GroupDialog(existing = g, onDismiss = { editing = null }, onDone = { editing = null }) }
    if (creating) GroupDialog(existing = null, onDismiss = { creating = false }, onDone = { creating = false; onOpenChat(it) })
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}
