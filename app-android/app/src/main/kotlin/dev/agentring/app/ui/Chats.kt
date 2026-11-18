package dev.agentring.app.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import dev.agentring.core.Conv
import dev.agentring.core.Group
import dev.agentring.core.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** A colour per agent (stable across launches), so a group's messages read at a glance. */
private val PALETTE = listOf(
    Color(0xFFE8703A), Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFA855F7),
    Color(0xFFEAB308), Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFF6366F1),
)

fun colorFor(key: String): Color = PALETTE[(key.hashCode() and 0x7fffffff) % PALETTE.size]

/** Round avatar with an initial in the agent's colour; groups get a ring and up to two initials. */
@Composable
fun ConvAvatar(conv: String, name: String, size: Dp = 52.dp, online: Boolean = false) {
    val color = colorFor(conv)
    val group = Conv.isGroup(conv)
    val initials = if (group) {
        name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "G" }
    } else {
        name.trim().take(1).uppercase().ifEmpty { "A" }
    }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .background(color.copy(alpha = if (group) 0.18f else 0.22f), CircleShape)
                .then(if (group) Modifier.border(2.dp, color, CircleShape) else Modifier),
        ) {
            Text(
                initials,
                style = if (size > 44.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .background(LocalStatusColors.current.ok, CircleShape)
                    .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

/** "14:05" today, "Ayer", or "17/11". */
@Composable
fun shortTime(ms: Long): String {
    val ctx = LocalContext.current
    return when {
        DateUtils.isToday(ms) -> DateFormat.getTimeFormat(ctx).format(ms)
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.yesterday)
        else -> {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            "%02d/%02d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        }
    }
}

private data class ConvRow(val conv: String, val name: String, val last: Message?, val unread: Int, val members: Int?)

@Composable
fun ChatsScreen(pairing: Pairing, onOpen: (String) -> Unit) {
    val messages by Graph.messages.messages.collectAsStateWithLifecycle()
    val agents by Graph.directory.agents.collectAsStateWithLifecycle()
    val groups by Graph.directory.groups.collectAsStateWithLifecycle()
    val online by Graph.directory.online.collectAsStateWithLifecycle()
    val pinned by Graph.chatPrefs.pinned.collectAsStateWithLifecycle()
    val muted by Graph.chatPrefs.muted.collectAsStateWithLifecycle()
    val main = pairing.agentName
    var creating by remember { mutableStateOf(false) }

    val rows = run {
        val byConv = messages.groupBy { it.convId }
        val convs = linkedSetOf<String>()
        groups.forEach { convs += Conv.ofGroup(it.groupId) }
        agents.forEach { convs += Conv.ofAgent(it.agentId) }
        convs += byConv.keys
        // Agents that never wrote and aren't main stay out of the list until they do.
        convs.filter { it in byConv || Conv.isGroup(it) || it == Conv.MAIN }
            .map { c ->
                val list = byConv[c].orEmpty()
                ConvRow(
                    conv = c,
                    name = Graph.directory.convName(c, main),
                    last = list.lastOrNull(),
                    unread = if (Conv.isBots(c)) 0 else list.count { it.fromAgent && it.readAt == null },
                    members = if (Conv.isGroup(c)) Graph.directory.group(Conv.id(c))?.members?.size else null,
                )
            }
            // Pinned first, then the most recent.
            .sortedWith(compareBy<ConvRow>({ it.conv !in pinned }, { -(it.last?.createdAt ?: 0L) }))
    }
    val (botRows, userRows) = rows.partition { Conv.isBots(it.conv) }

    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            EmptyState(stringResource(R.string.chats_empty), stringResource(R.string.chats_empty_body))
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("chat_list"), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(userRows, key = { it.conv }) { r ->
                    ChatRow(r, online = !Conv.isGroup(r.conv) && Conv.id(r.conv) in online, main = main,
                        pinned = r.conv in pinned, muted = r.conv in muted) { onOpen(r.conv) }
                }
                if (botRows.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.bots_section).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(botRows, key = { it.conv }) { r ->
                        ChatRow(r, online = false, main = main, pinned = r.conv in pinned, muted = true) { onOpen(r.conv) }
                    }
                }
            }
        }
        if (agents.size > 1) {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.new_group)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("new_group"),
            )
        }
    }
    if (creating) GroupDialog(existing = null, onDismiss = { creating = false }, onDone = { creating = false; onOpen(it) })
}

@Composable
private fun ChatRow(r: ConvRow, online: Boolean, main: String, pinned: Boolean = false, muted: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("chat_${r.conv}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ConvAvatar(r.conv, r.name, online = online)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (r.members != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.group_members_count, r.members),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (muted && !Conv.isBots(r.conv)) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_notif_off), stringResource(R.string.muted),
                        Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_pin), null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
                r.last?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        shortTime(it.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (r.unread > 0) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val last = r.last
                val body = last?.let { Markdown.plain(it.text).ifBlank { attachmentLabel(it) } } ?: ""
                val preview = when {
                    last == null -> ""
                    !last.fromAgent -> stringResource(R.string.you_prefix).trim() + " " + body
                    Conv.isGroup(r.conv) || Conv.isBots(r.conv) -> Graph.directory.nameOf(last.author, main) + ": " + body
                    else -> body
                }
                Text(
                    preview.replace('\n', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.unread > 0) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (r.unread > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (r.unread > 0 && !Conv.isBots(r.conv)) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(22.dp).background(scheme.primary, CircleShape),
                    ) {
                        Text("${r.unread}", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        HeroBadge(androidx.compose.ui.res.painterResource(R.drawable.ic_chat), size = 64.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Create a group (existing = null) or edit one: name and members. */
@Composable
fun GroupDialog(existing: Group?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val agents by Graph.directory.agents.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var members by remember { mutableStateOf(existing?.members?.toSet() ?: emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val needMembers = stringResource(R.string.group_need_members)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.new_group else R.string.edit_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("group_name"),
                )
                Text(stringResource(R.string.group_members), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                agents.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { members = if (a.agentId in members) members - a.agentId else members + a.agentId },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = a.agentId in members, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        ConvAvatar(Conv.ofAgent(a.agentId), a.name, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    if (members.isEmpty()) {
                        error = needMembers
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val ordered = agents.map { it.agentId }.filter { it in members }
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val api = Graph.api() ?: error("not paired")
                                if (existing == null) api.createGroup(groupIdFor(name.trim()), name.trim(), ordered)
                                else api.updateGroup(existing.groupId, name.trim(), ordered)
                            }
                        }
                        busy = false
                        result.onSuccess {
                            Graph.directory.put(it)
                            onDone(Conv.ofGroup(it.groupId))
                        }.onFailure { error = it.message ?: it.javaClass.simpleName }
                    }
                },
                modifier = Modifier.testTag("group_save"),
            ) { Text(stringResource(if (existing == null) R.string.create else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A group id from its name ("Gym y casa" → "gym-y-casa"), unique among the known groups. */
private fun groupIdFor(name: String): String {
    val base = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(24)
        .ifEmpty { "grupo" }
    val taken = Graph.directory.groups.value.map { it.groupId }.toSet()
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

@Composable
private fun attachmentLabel(m: Message): String {
    val a = m.attachments?.firstOrNull() ?: return ""
    return if (a.isImage) "📷 " + stringResource(R.string.photo) else "🎤 " + stringResource(R.string.voice_note)
}
