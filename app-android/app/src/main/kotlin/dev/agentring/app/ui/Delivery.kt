package dev.agentring.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.core.Agent
import dev.agentring.core.DeliverySettings
import dev.agentring.core.RelayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Hivepal: how agents hear from you, and remember (issue #41). Used in the setup (a page you may skip)
 * and in Settings, and per agent in the Agents screen. Everything is stored in the relay: these screens
 * only read and change it.
 */

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("hivepal", text))
}

private fun Throwable.readable(): String = (this as? RelayException)?.message ?: message ?: toString()

/** Runs [block] on the IO thread with the relay API; null when not paired. */
private suspend fun <T> relay(block: (dev.agentring.core.RelayApi) -> T): Result<T> = withContext(Dispatchers.IO) {
    val api = Graph.api() ?: return@withContext Result.failure(IllegalStateException("not paired"))
    runCatching { block(api) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CheckRow(checked: Boolean, title: String, body: String, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = checked, onClick = { onChange(!checked) }).testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(body)
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, title: String, body: String?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).testTag(tag), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Hint(it) }
        }
    }
}

/**
 * The relay-wide choices: email (the account wake-up emails are sent from), mailboxes (Markdown files in
 * git, kept in the relay and optionally pushed to your repository) and memory (Markdown, SQLite).
 */
@Composable
fun DeliverySetup(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<DeliverySettings?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        relay { it.delivery() }.onSuccess { d = it }.onFailure { loadError = it.readable() }
    }
    fun change(block: (dev.agentring.core.RelayApi) -> DeliverySettings, done: String? = null) {
        busy = true
        note = null
        scope.launch {
            relay(block).onSuccess { d = it; note = done }.onFailure { note = it.readable() }
            busy = false
        }
    }

    val s = d
    if (s == null) {
        Hint(loadError?.let { stringResource(R.string.delivery_unavailable, it) } ?: stringResource(R.string.loading))
        return
    }

    // Email is "chosen" when the relay has an account, or while it's being filled in.
    var emailOn by remember { mutableStateOf(s.email.configured) }
    var address by remember { mutableStateOf(s.email.address.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var external by remember { mutableStateOf(s.mailboxRepo.url != null) }
    var repoUrl by remember { mutableStateOf(s.mailboxRepo.url.orEmpty()) }
    var branch by remember { mutableStateOf(s.mailboxRepo.branch.orEmpty()) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.delivery_how), style = MaterialTheme.typography.titleMedium)
        Hint(stringResource(R.string.delivery_how_body))

        // ---- email
        BrandCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(emailOn, stringResource(R.string.delivery_email), stringResource(R.string.delivery_email_body), "delivery_email") { on ->
                    emailOn = on
                    if (!on && s.email.configured) change({ it.setDeliveryEmail(null) })
                }
                if (emailOn) {
                    if (s.email.configured) Hint(stringResource(R.string.delivery_email_now, s.email.address.orEmpty(), s.email.server.orEmpty()))
                    OutlinedTextField(
                        value = address, onValueChange = { address = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.delivery_email_address)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("delivery_email_address"),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text(stringResource(R.string.delivery_email_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("delivery_email_password"),
                    )
                    Hint(stringResource(R.string.delivery_email_password_hint))
                    OutlinedTextField(
                        value = server, onValueChange = { server = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.delivery_email_server)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { change({ it.setDeliveryEmail(address, password, server) }, ctx.getString(R.string.delivery_email_saved)) },
                        enabled = !busy && address.contains('@') && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delivery_email_save"),
                    ) { Text(stringResource(R.string.delivery_email_save)) }
                }
            }
        }

        // ---- mailboxes
        val mailboxesOn = s.mailboxes.allAgents
        BrandCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(mailboxesOn, stringResource(R.string.delivery_mailboxes), stringResource(R.string.delivery_mailboxes_body), "delivery_mailboxes") { on ->
                    change({ it.setMailboxesForAll(on) })
                }
                if (mailboxesOn) {
                    RadioRow(!external, stringResource(R.string.delivery_repo_relay), stringResource(R.string.delivery_repo_relay_body), "delivery_repo_relay") {
                        external = false
                        if (s.mailboxRepo.url != null) change({ it.setMailboxRepo(null) })
                    }
                    RadioRow(external, stringResource(R.string.delivery_repo_external), stringResource(R.string.delivery_repo_external_body), "delivery_repo_external") {
                        external = true
                    }
                    if (external) {
                        OutlinedTextField(
                            value = repoUrl, onValueChange = { repoUrl = it.trim() }, singleLine = true,
                            label = { Text(stringResource(R.string.delivery_repo_url)) },
                            placeholder = { Text("git@github.com:you/notes.git") },
                            modifier = Modifier.fillMaxWidth().testTag("delivery_repo_url"),
                        )
                        OutlinedTextField(
                            value = branch, onValueChange = { branch = it.trim() }, singleLine = true,
                            label = { Text(stringResource(R.string.delivery_repo_branch)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { change({ it.setMailboxRepo(repoUrl, branch) }) },
                            enabled = !busy && repoUrl.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delivery_repo_save"),
                        ) { Text(stringResource(R.string.delivery_repo_save)) }
                        val repo = s.mailboxRepo
                        if (repo.url != null) {
                            Hint(stringResource(R.string.delivery_repo_key))
                            val key = repo.publicKey.orEmpty()
                            Text(key, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { copy(ctx, key); note = ctx.getString(R.string.copied) }) { Text(stringResource(R.string.copy)) }
                                TextButton(
                                    onClick = {
                                        busy = true
                                        note = ctx.getString(R.string.delivery_repo_checking)
                                        scope.launch {
                                            relay { it.testMailboxRepo() }
                                                .onSuccess { note = ctx.getString(R.string.delivery_repo_ok) }
                                                .onFailure { note = it.readable() }
                                            relay { it.delivery() }.onSuccess { d = it }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.testTag("delivery_repo_test"),
                                ) { Text(stringResource(R.string.delivery_repo_test)) }
                            }
                            repo.lastError?.let { Hint(stringResource(R.string.delivery_repo_error, it)) }
                        }
                    }
                }
            }
        }

        // ---- memory
        val mem = s.memory
        BrandCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.delivery_memory), style = MaterialTheme.typography.titleMedium)
                        Hint(stringResource(if (mem.on) R.string.delivery_memory_on else R.string.delivery_memory_off))
                    }
                    Switch(
                        checked = mem.on,
                        onCheckedChange = { on ->
                            // On: Markdown when there are mailboxes, else SQLite (it needs nothing else).
                            change({ if (on) it.setMemory(markdown = mailboxesOn, sqlite = !mailboxesOn) else it.setMemory(false, false) })
                        },
                        modifier = Modifier.testTag("delivery_memory"),
                    )
                }
                if (mem.on) {
                    RadioRow(mem.markdown && !mem.sqlite, stringResource(R.string.delivery_memory_markdown), stringResource(R.string.delivery_memory_markdown_body), "memory_markdown") {
                        change({ api -> if (!mailboxesOn) api.setMailboxesForAll(true); api.setMemory(markdown = true, sqlite = false) })
                    }
                    RadioRow(mem.sqlite && !mem.markdown, stringResource(R.string.delivery_memory_sqlite), stringResource(R.string.delivery_memory_sqlite_body), "memory_sqlite") {
                        change({ it.setMemory(markdown = false, sqlite = true) })
                    }
                    RadioRow(mem.markdown && mem.sqlite, stringResource(R.string.delivery_memory_both), null, "memory_both") {
                        change({ api -> if (!mailboxesOn) api.setMailboxesForAll(true); api.setMemory(markdown = true, sqlite = true) })
                    }
                }
            }
        }
        note?.let { Hint(it) }
    }
}

/** One agent: its wake-up address (save, test, off) and its mailbox (default, on, off) with how it clones it. */
@Composable
fun AgentDeliveryDialog(agent: Agent, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf(agent) }
    var d by remember { mutableStateOf<DeliverySettings?>(null) }
    var email by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { relay { it.delivery() }.onSuccess { d = it }.onFailure { note = it.readable() } }
    fun agentCall(done: String?, block: (dev.agentring.core.RelayApi) -> Agent?) {
        busy = true
        note = null
        scope.launch {
            relay(block).onSuccess { r -> r?.let { a = it; Graph.directory.put(it) }; note = done }.onFailure { note = it.readable() }
            busy = false
        }
    }
    val relayUrl = Graph.pairing.current?.relayUrl.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_delivery_title, a.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.delivery_email), style = MaterialTheme.typography.titleSmall)
                if (d?.email?.configured == false) {
                    Hint(stringResource(R.string.agent_wake_no_account))
                } else {
                    Hint(stringResource(if (a.wakes == true) R.string.agent_wake_on else R.string.agent_wake_off))
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.agent_wake_address)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("agent_wake_address"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { agentCall(ctx.getString(R.string.agent_wake_saved)) { it.setAgentWake(a.agentId, email) } }, enabled = !busy && email.contains('@')) {
                            Text(stringResource(R.string.save))
                        }
                        if (a.wakes == true) {
                            TextButton(onClick = {
                                busy = true
                                note = ctx.getString(R.string.sending)
                                scope.launch {
                                    relay { it.testAgentWake(a.agentId) }
                                        .onSuccess { note = ctx.getString(R.string.agent_wake_sent) }
                                        .onFailure { note = it.readable() }
                                    busy = false
                                }
                            }, enabled = !busy, modifier = Modifier.testTag("agent_wake_test")) { Text(stringResource(R.string.agent_wake_test)) }
                            TextButton(onClick = { agentCall(null) { it.setAgentWake(a.agentId, null) } }, enabled = !busy) {
                                Text(stringResource(R.string.turn_off))
                            }
                        }
                    }
                }

                Text(stringResource(R.string.delivery_mailboxes), style = MaterialTheme.typography.titleSmall)
                val default = d?.mailboxes?.allAgents == true
                RadioRow(false, stringResource(if (default) R.string.agent_mailbox_default_on else R.string.agent_mailbox_default_off), null, "agent_mailbox_default") {
                    agentCall(null) { it.setAgentMailbox(a.agentId, null) }
                }
                RadioRow(a.mailbox == true, stringResource(R.string.agent_mailbox_on), null, "agent_mailbox_on") {
                    agentCall(null) { it.setAgentMailbox(a.agentId, true) }
                }
                RadioRow(a.mailbox != true, stringResource(R.string.agent_mailbox_off), null, "agent_mailbox_off") {
                    agentCall(null) { it.setAgentMailbox(a.agentId, false) }
                }
                if (a.mailbox == true) {
                    val clone = "git clone https://x:<key>@${relayUrl.substringAfter("://")}/v1/agents/${a.agentId}/mailbox.git"
                    Hint(stringResource(R.string.agent_mailbox_clone))
                    Text(clone, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copy(ctx, clone); note = ctx.getString(R.string.copied) }) { Text(stringResource(R.string.copy)) }
                    d?.mailboxRepo?.url?.let { Hint(stringResource(R.string.agent_mailbox_repo, it, a.agentId)) }
                }
                note?.let { Hint(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
