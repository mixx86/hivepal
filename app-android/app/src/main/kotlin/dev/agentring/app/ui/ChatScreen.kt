package dev.agentring.app.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import dev.agentring.app.notify.Notifications
import dev.agentring.core.Conv
import dev.agentring.core.Event
import dev.agentring.core.Message
import dev.agentring.core.RelayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

/** Messages more than this far apart start a new run (new author label and time). */
private const val RUN_GAP_MS = 10 * 60_000L

/** One conversation: with an agent (`agent:<id>`) or a group (`group:<id>`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(pairing: Pairing, conv: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by Graph.messages.messages.collectAsStateWithLifecycle()
    val agents by Graph.directory.agents.collectAsStateWithLifecycle()
    val groups by Graph.directory.groups.collectAsStateWithLifecycle()
    val online by Graph.directory.online.collectAsStateWithLifecycle()
    val messages = remember(all, conv) { all.filter { it.convId == conv } }
    val main = pairing.agentName
    val isGroup = Conv.isGroup(conv)
    val group = if (isGroup) groups.firstOrNull { it.groupId == Conv.id(conv) } else null
    val title = remember(agents, groups, conv, main) { Graph.directory.convName(conv, main) }

    var input by rememberSaveable(conv) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var overQuota by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // While this conversation is on screen (and the app in front), its pushes don't notify.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, conv) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> AppVisibility.openConv = conv
                Lifecycle.Event.ON_PAUSE -> if (AppVisibility.openConv == conv) AppVisibility.openConv = null
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) AppVisibility.openConv = conv
        onDispose {
            lifecycle.removeObserver(obs)
            if (AppVisibility.openConv == conv) AppVisibility.openConv = null
        }
    }
    // Opening the thread (or a new message arriving while it's open) marks the agents' messages read.
    LaunchedEffect(messages.size, conv) {
        Notifications.cancelMessages(ctx, conv)
        if (messages.any { it.fromAgent && it.readAt == null }) {
            withContext(Dispatchers.IO) { Graph.api()?.let { runCatching { Graph.messages.markAgentMessagesRead(it, conv) } } }
        }
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        scope.launch {
            sending = true
            sendError = null
            overQuota = false
            try {
                val generation = Graph.messages.generation
                val api = Graph.api() ?: error("not paired")
                val m = withContext(Dispatchers.IO) { api.postMessage(text, clientMsgId = UUID.randomUUID().toString(), conv = conv) }
                Graph.messages.upsert(m, generation = generation)
                input = ""
            } catch (e: Exception) {
                sendError = e.message ?: e.javaClass.simpleName
                overQuota = e is RelayException && e.code == "quota_exceeded"
            } finally {
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConvAvatar(conv, title, size = 38.dp, online = !isGroup && Conv.id(conv) in online)
                        Column {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("chat_title"),
                            )
                            val subtitle = when {
                                group != null -> group.members.joinToString(", ") { Graph.directory.nameOf(it, main) }
                                Conv.id(conv) in online -> stringResource(R.string.agent_online)
                                else -> null
                            }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chat_menu")) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (isGroup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_group)) },
                                    onClick = { menu = false; editingGroup = true },
                                )
                            } else if (agents.any { it.agentId == Conv.id(conv) }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = { menu = false; renaming = true },
                                    modifier = Modifier.testTag("menu_rename"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_chat)) },
                                onClick = { menu = false; confirmClear = true },
                                modifier = Modifier.testTag("menu_clear"),
                            )
                            if (isGroup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_group), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f)) {
                    EmptyState(stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty, title))
                }
            } else {
                val lastReadByAgent = messages.lastOrNull { !it.fromAgent && it.readAt != null }?.msgId
                val reversed = messages.asReversed()
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("message_list"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    items(reversed.size, key = { reversed[it].msgId }) { i ->
                        val m = reversed[i]
                        val older = reversed.getOrNull(i + 1)
                        val newer = reversed.getOrNull(i - 1)
                        val firstOfRun = older == null || older.author != m.author || !sameDay(older, m) ||
                            m.createdAt - older.createdAt > RUN_GAP_MS
                        val lastOfRun = newer == null || newer.author != m.author || !sameDay(newer, m) ||
                            newer.createdAt - m.createdAt > RUN_GAP_MS
                        Column {
                            if (older == null || !sameDay(older, m)) DayLabel(m.createdAt)
                            Bubble(
                                m,
                                author = if (isGroup && m.fromAgent && firstOfRun) Graph.directory.nameOf(m.author, main) else null,
                                showTime = lastOfRun,
                                showRead = m.msgId == lastReadByAgent,
                                topGap = if (firstOfRun) 10.dp else 2.dp,
                            )
                        }
                    }
                }
            }

            sendError?.let {
                Text(
                    if (overQuota) stringResource(R.string.message_quota) else stringResource(R.string.send_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (overQuota) PlanDashboardButton(pairing)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(if (isGroup) stringResource(R.string.write_message) else stringResource(R.string.message_hint, title))
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.weight(1f).testTag("message_input"),
                )
                FilledIconButton(
                    onClick = { send() },
                    enabled = input.isNotBlank() && !sending,
                    modifier = Modifier.size(54.dp).testTag("send_button"),
                ) {
                    if (sending) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_chat)) },
            text = { Text(stringResource(R.string.clear_chat_confirm, title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        Graph.messages.clearConversation(conv)
                    },
                    modifier = Modifier.testTag("clear_confirm"),
                ) { Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (renaming) RenameAgentDialog(Conv.id(conv), title, onDismiss = { renaming = false })
    if (editingGroup && group != null) {
        GroupDialog(existing = group, onDismiss = { editingGroup = false }, onDone = { editingGroup = false })
    }
    if (confirmDelete && group != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_group)) },
            text = { Text(stringResource(R.string.delete_group_confirm, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { Graph.api()!!.deleteGroup(group.groupId) }.isSuccess }
                        if (ok) {
                            Graph.directory.apply(Event.GroupUpdated("group.updated", "", 0, null, group.groupId, group.name, group.members, true))
                            onBack()
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Rename an agent on the relay: every phone sees the new name. */
@Composable
fun RenameAgentDialog(agentId: String, current: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_agent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_input"),
                )
                Text(
                    stringResource(R.string.agent_id_label, agentId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val r = runCatching { withContext(Dispatchers.IO) { Graph.api()!!.renameAgent(agentId, name.trim()) } }
                        busy = false
                        r.onSuccess {
                            Graph.directory.put(it)
                            if (agentId == Conv.MAIN_AGENT) Graph.pairing.renameAgent(it.name)
                            onDismiss()
                        }.onFailure { error = it.message ?: it.javaClass.simpleName }
                    }
                },
                modifier = Modifier.testTag("rename_save"),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun sameDay(a: Message, b: Message): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a.createdAt }
    val cb = Calendar.getInstance().apply { timeInMillis = b.createdAt }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayLabel(ms: Long) {
    val ctx = LocalContext.current
    val text = when {
        DateUtils.isToday(ms) -> stringResource(R.string.today)
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.yesterday)
        else -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Bubble(m: Message, author: String?, showTime: Boolean, showRead: Boolean, topGap: Dp) {
    val mine = !m.fromAgent
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = topGap),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (author != null) {
            Text(
                author,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorFor(Conv.ofAgent(m.author ?: Conv.MAIN_AGENT)),
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp,
            ),
            color = if (mine) scheme.primary else scheme.surfaceContainerHigh,
            contentColor = if (mine) scheme.onPrimary else scheme.onSurface,
            border = if (mine) null else BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (showTime || showRead) {
            val time = DateFormat.getTimeFormat(ctx).format(m.createdAt)
            Text(
                if (showRead) "$time · ${stringResource(R.string.read)}" else time,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
