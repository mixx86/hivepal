package dev.agentring.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import dev.agentring.app.media.Attachments
import dev.agentring.app.media.VoiceRecorder
import dev.agentring.core.Attachment
import kotlinx.coroutines.delay
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
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
    var burst by remember { mutableStateOf(0) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var overQuota by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val pinned by Graph.chatPrefs.pinned.collectAsStateWithLifecycle()
    val muted by Graph.chatPrefs.muted.collectAsStateWithLifecycle()
    val isBots = Conv.isBots(conv)
    val agentId = if (!isGroup && !isBots) Conv.id(conv) else null
    val recorder = remember { VoiceRecorder(ctx.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var recordMs by remember { mutableStateOf(0L) }
    var uploading by remember { mutableStateOf(false) }
    var calling by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording) {
        while (recording) {
            recordMs = recorder.elapsedMs
            delay(200)
        }
    }

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

    /** Sends text (a typed message or a tapped quick reply) and/or an uploaded file. */
    fun post(text: String, replyTo: String? = null, upload: (suspend () -> Attachment)? = null, clearInput: Boolean = false) {
        if (sending) return
        scope.launch {
            sending = true
            sendError = null
            overQuota = false
            try {
                val generation = Graph.messages.generation
                val api = Graph.api() ?: error("not paired")
                val files = upload?.let {
                    uploading = true
                    listOf(it().fileId)
                }
                val m = withContext(Dispatchers.IO) {
                    api.postMessage(text, replyTo = replyTo, clientMsgId = UUID.randomUUID().toString(), conv = conv, attachments = files)
                }
                Graph.messages.upsert(m, generation = generation)
                if (clearInput) input = ""
            } catch (e: Exception) {
                sendError = e.message ?: e.javaClass.simpleName
                overQuota = e is RelayException && e.code == "quota_exceeded"
            } finally {
                sending = false
                uploading = false
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isNotEmpty()) post(text, clearInput = true)
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val caption = input.trim()
        post(caption, clearInput = true, upload = {
            withContext(Dispatchers.IO) {
                val bytes = Attachments.compressPhoto(ctx, uri)
                Graph.api()!!.uploadFile(bytes, "image/jpeg").also { Attachments.keep(ctx, it.fileId, bytes) }
            }
        })
    }

    fun stopAndSendVoice() {
        recording = false
        val (file, ms) = recorder.stop() ?: return
        post("", upload = {
            withContext(Dispatchers.IO) {
                val bytes = file.readBytes()
                Graph.api()!!.uploadFile(bytes, "audio/mp4", durationMs = ms).also {
                    Attachments.keep(ctx, it.fileId, bytes)
                    file.delete()
                }
            }
        })
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCatching { recorder.start(); recording = true }
    }

    fun startVoice() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        runCatching { recorder.start(); recording = true }.onFailure { sendError = it.message }
    }

    fun callThisAgent() {
        val id = agentId ?: return
        if (calling) return
        calling = true
        Graph.appScope.launch(Dispatchers.Main.immediate) {
            val failure = Graph.calls.callAgent(id)
            calling = false
            if (failure == null) ctx.startActivity(CallActivity.outgoingIntent(ctx)) else sendError = ctx.getString(failure)
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
                    if (agentId != null) {
                        IconButton(onClick = { callThisAgent() }, enabled = !calling, modifier = Modifier.testTag("chat_call")) {
                            Icon(Icons.Filled.Call, stringResource(R.string.call_back))
                        }
                    }
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
                                text = { Text(stringResource(if (conv in pinned) R.string.unpin_chat else R.string.pin_chat)) },
                                onClick = { menu = false; Graph.chatPrefs.togglePinned(conv) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (conv in muted) R.string.unmute_chat else R.string.mute_chat)) },
                                onClick = { menu = false; Graph.chatPrefs.toggleMuted(conv) },
                                modifier = Modifier.testTag("menu_mute"),
                            )
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
      Box(Modifier.padding(padding).fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f)) {
                    EmptyState(stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty, title))
                }
            } else {
                val lastReadByAgent = messages.lastOrNull { !it.fromAgent && it.readAt != null }?.msgId
                val byId = remember(messages) { messages.associateBy { it.msgId } }
                // Quick replies disappear once the user answered that message.
                val answered = remember(messages) { messages.filter { !it.fromAgent && it.replyTo != null }.associateBy { it.replyTo!! } }
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
                        Column(Modifier.animateItem()) {
                            if (older == null || !sameDay(older, m)) DayLabel(m.createdAt)
                            Bubble(
                                m,
                                author = if ((isGroup || isBots) && m.fromAgent && firstOfRun) Graph.directory.nameOf(m.author, main) else null,
                                showTime = lastOfRun,
                                showRead = m.msgId == lastReadByAgent,
                                topGap = if (firstOfRun) 10.dp else 2.dp,
                                mineSide = if (isBots) m.author == Conv.pair(conv).first() else !m.fromAgent,
                                quote = m.replyTo?.let { id -> byId[id]?.text },
                            )
                            val options = m.options
                            if (m.fromAgent && !options.isNullOrEmpty() && answered[m.msgId] == null) {
                                QuickReplies(options, enabled = !sending) {
                                    burst++
                                    post(it, replyTo = m.msgId)
                                }
                            }
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
            if (recording) {
                RecordingBar(recordMs, onCancel = { recording = false; recorder.cancel() }, onSend = { stopAndSendVoice() })
            } else Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !sending,
                    modifier = Modifier.size(48.dp).testTag("attach_photo"),
                ) { Icon(Icons.Filled.Add, stringResource(R.string.attach_photo)) }
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
                val sendSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                FilledIconButton(
                    onClick = { if (input.isNotBlank()) send() else startVoice() },
                    enabled = !sending,
                    interactionSource = sendSource,
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent),
                    modifier = Modifier.size(54.dp).pressBounce(sendSource).breathingGlow()
                        .background(BrandBrush, androidx.compose.foundation.shape.CircleShape).testTag("send_button"),
                ) {
                    when {
                        sending -> CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                        input.isNotBlank() -> Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                        else -> Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.record_voice))
                    }
                }
            }
        }
        HexBurst(burst)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    author: String?,
    showTime: Boolean,
    showRead: Boolean,
    topGap: Dp,
    mineSide: Boolean = !m.fromAgent,
    quote: String? = null,
) {
    val mine = mineSide
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
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
            color = if (mine) Color.Transparent else scheme.surfaceContainerHigh,
            contentColor = if (mine) Color.White else scheme.onSurface,
            border = if (mine) null else BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier.widthIn(max = 310.dp)
                .then(if (mine) Modifier.background(MineBrush, RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)) else Modifier)
                .combinedClickable(onClick = {}, onLongClick = { menu = true }),
        ) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.text.isNotBlank()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = {
                        menu = false
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                        android.widget.Toast.makeText(ctx, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                    })
                }
                if (!m.fromAgent) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = scheme.error) }, onClick = {
                        menu = false
                        Graph.appScope.launch(Dispatchers.IO) {
                            runCatching { Graph.api()!!.deleteMessage(m.msgId) }.onSuccess { Graph.messages.remove(m.msgId) }
                        }
                    }, modifier = Modifier.testTag("delete_message"))
                }
            }
            Column {
                if (!quote.isNullOrBlank()) {
                    Text(
                        "↪ " + Markdown.plain(quote).replace('\n', ' '),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = (if (mine) Color.White else scheme.onSurface).copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
                    )
                }
                m.attachments?.forEach { a -> AttachmentView(a, mine) }
                if (m.text.isNotBlank()) {
                    val link = if (mine) Color.White else scheme.primary
                    val codeBg = if (mine) Color.White.copy(alpha = 0.15f) else scheme.surfaceContainerHighest
                    val styled = remember(m.text, link, codeBg) { Markdown.render(m.text, link, codeBg) }
                    Text(styled, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplies(options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            SuggestionChip(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onPick(o)
                },
                enabled = enabled,
                interactionSource = source,
                label = { Text(o, style = MaterialTheme.typography.labelLarge) },
                colors = SuggestionChipDefaults.suggestionChipColors(labelColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                modifier = Modifier.pressBounce(source).testTag("quick_reply"),
            )
        }
    }
}

@Composable
private fun RecordingBar(ms: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.testTag("voice_cancel")) {
            Icon(Icons.Filled.Close, stringResource(R.string.cancel))
        }
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.error))
        Text(
            stringResource(R.string.recording, "%d:%02d".format(ms / 60000, (ms / 1000) % 60)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(onClick = onSend, modifier = Modifier.size(54.dp).testTag("voice_send")) {
            Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
        }
    }
}

/** A photo (tap: full screen) or a voice clip (play/pause) inside a bubble. */
@Composable
private fun AttachmentView(a: Attachment, mine: Boolean) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    if (a.isImage) {
        var bmp by remember(a.fileId) { mutableStateOf<Bitmap?>(null) }
        var full by remember { mutableStateOf(false) }
        LaunchedEffect(a.fileId) { bmp = withContext(Dispatchers.IO) { runCatching { Attachments.thumbnail(ctx, a) }.getOrNull() } }
        val b = bmp
        if (b == null) {
            Box(Modifier.size(220.dp, 160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            val ratio = (b.width.toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.6f, 2f)
            Image(
                b.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(280.dp).aspectRatio(ratio).clickable { full = true }.testTag("photo"),
            )
            if (full) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { full = false }) {
                    Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clickable { full = false })
                }
            }
        }
    } else if (a.isAudio) {
        var playing by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var position by remember { mutableStateOf(0L) }
        DisposableEffect(a.fileId) { onDispose { player?.release() } }
        LaunchedEffect(playing) {
            while (playing) {
                position = player?.currentPosition?.toLong() ?: 0L
                delay(200)
            }
        }
        val total = a.durationMs ?: player?.duration?.toLong() ?: 0L
        Row(
            Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).width(220.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val p = player
                    if (p != null && playing) {
                        p.pause(); playing = false
                    } else if (p != null) {
                        p.start(); playing = true
                    } else {
                        Graph.appScope.launch(Dispatchers.IO) {
                            val f = runCatching { Attachments.local(ctx, a) }.getOrNull() ?: return@launch
                            val mp = MediaPlayer().apply {
                                setDataSource(f.path)
                                prepare()
                                setOnCompletionListener { playing = false; position = 0; it.seekTo(0) }
                            }
                            withContext(Dispatchers.Main) { player = mp; mp.start(); playing = true }
                        }
                    }
                },
                modifier = Modifier.testTag("play_voice"),
            ) {
                if (playing) Icon(painterResource(R.drawable.ic_pause), null) else Icon(Icons.Filled.PlayArrow, null)
            }
            val frac = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(Modifier.weight(1f).size(height = 4.dp, width = 0.dp).clip(RoundedCornerShape(2.dp))
                .background((if (mine) Color.White else scheme.onSurface).copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(frac).size(height = 4.dp, width = 0.dp).background(if (mine) Color.White else scheme.primary))
            }
            Spacer(Modifier.width(10.dp))
            val shown = if (playing || position > 0) position else total
            Text("%d:%02d".format(shown / 60000, (shown / 1000) % 60), style = MaterialTheme.typography.labelMedium)
        }
        // What the voice note says (transcribed once by the relay), so it can be read too.
        var transcript by remember(a.fileId) { mutableStateOf<String?>(Attachments.cachedTranscript(a.fileId)) }
        LaunchedEffect(a.fileId) {
            if (transcript == null) {
                transcript = withContext(Dispatchers.IO) {
                    runCatching { Graph.api()!!.transcript(a.fileId) }.getOrNull()?.also { Attachments.cacheTranscript(a.fileId, it) }
                }
            }
        }
        transcript?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = (if (mine) Color.White else scheme.onSurface).copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).widthIn(max = 260.dp),
            )
        }
    }
}
