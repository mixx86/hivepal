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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.core.Agent
import dev.agentring.core.DeliverySettings
import dev.agentring.core.RelayApi
import dev.agentring.core.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Hivepal: how agents hear from you, and remember (issue #41). The pieces here make up the setup's
 * questions (OnboardingScreens: one question per page), Settings (all of it on one card each) and, per
 * agent, the dialog in Agents. Everything is stored in the relay: these screens only read and change it.
 */

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("hivepal", text))
}

private fun Throwable.readable(): String = (this as? RelayException)?.message ?: message ?: toString()

/** Runs [block] on the IO thread with the relay API; a failure when not paired. */
private suspend fun <T> relay(block: (RelayApi) -> T): Result<T> = withContext(Dispatchers.IO) {
    val api = Graph.api() ?: return@withContext Result.failure(IllegalStateException("not paired"))
    runCatching { block(api) }
}

/** The relay's delivery settings and the changes made to them, shared by the pieces of one screen. */
@Stable
class DeliveryModel(private val scope: CoroutineScope) {
    var settings by mutableStateOf<DeliverySettings?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    suspend fun load() {
        relay { it.delivery() }.onSuccess { settings = it; loadError = null }.onFailure { loadError = it.readable() }
    }

    /** Applies a change; [after] gets null when it worked, else what went wrong. */
    fun change(block: (RelayApi) -> DeliverySettings, after: (String?) -> Unit = {}) {
        busy = true
        scope.launch {
            relay(block).onSuccess { settings = it; after(null) }.onFailure { after(it.readable()) }
            busy = false
        }
    }

    /** Runs a check that changes nothing ([block]), then reads the settings again. */
    fun check(block: (RelayApi) -> Unit, after: (String?) -> Unit) {
        busy = true
        scope.launch {
            val r = relay(block)
            load()
            after(r.exceptionOrNull()?.readable())
            busy = false
        }
    }
}

@Composable
fun rememberDeliveryModel(): DeliveryModel {
    val scope = rememberCoroutineScope()
    val m = remember { DeliveryModel(scope) }
    LaunchedEffect(m) { m.load() }
    return m
}

/** What an action did, shown right under it: green when it worked, red when not. */
data class Outcome(val text: String, val ok: Boolean)

@Composable
fun OutcomeLine(o: Outcome?, modifier: Modifier = Modifier) {
    o ?: return
    val color = if (o.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(if (o.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Text(o.text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.testTag("delivery_outcome"))
    }
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

/** A big tappable answer to one of the setup's questions: a radio, or a checkbox when [multi]. */
@Composable
fun ChoiceCard(
    title: String,
    body: String,
    selected: Boolean,
    tag: String,
    badge: String? = null,
    multi: Boolean = false,
    order: Int = 0,
    onClick: () -> Unit,
) {
    BrandCard(selected = selected, onClick = onClick, modifier = Modifier.appear(order).selectBounce(selected).testTag(tag)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (multi) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            else RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.padding(top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    badge?.let {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- email

/** Mail providers the relay knows the server of (the same list as the relay's settings.ts). */
private enum class Provider { GMAIL, ICLOUD, YAHOO, OTHER_KNOWN }

private fun providerOf(address: String): Provider? = when (address.substringAfterLast('@', "").lowercase()) {
    "gmail.com", "googlemail.com" -> Provider.GMAIL
    "icloud.com", "me.com", "mac.com" -> Provider.ICLOUD
    "yahoo.com", "yahoo.es", "ymail.com" -> Provider.YAHOO
    "fastmail.com", "fastmail.fm", "gmx.com", "gmx.net", "gmx.es", "gmx.de", "zoho.com", "zohomail.com" -> Provider.OTHER_KNOWN
    else -> null
}

private val EMAIL = Regex("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+\\.[^\\s@<>,;\"]+$")

/** The account wake-up emails are sent from: what's set, or the form to set it (checked by the relay). */
@Composable
fun EmailForm(m: DeliveryModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = m.settings ?: return
    var editing by remember { mutableStateOf(!s.email.configured) }
    var address by remember { mutableStateOf(s.email.address.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<Outcome?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.email.configured && !editing) {
            OutcomeLine(Outcome(stringResource(R.string.delivery_email_now, s.email.address.orEmpty(), s.email.server.orEmpty()), true))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = true; out = null }, enabled = !m.busy) { Text(stringResource(R.string.delivery_email_change)) }
                TextButton(
                    onClick = { m.change({ it.setDeliveryEmail(null) }) { e -> out = e?.let { Outcome(it, false) } } },
                    enabled = !m.busy,
                    modifier = Modifier.testTag("delivery_email_off"),
                ) { Text(stringResource(R.string.turn_off)) }
            }
            out?.let { OutcomeLine(it) }
            return@Column
        }
        val provider = providerOf(address)
        val needsServer = EMAIL.matches(address) && provider == null
        OutlinedTextField(
            value = address, onValueChange = { address = it.trim(); out = null }, singleLine = true,
            label = { Text(stringResource(R.string.delivery_email_address)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("delivery_email_address"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; out = null }, singleLine = true,
            label = { Text(stringResource(R.string.delivery_email_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("delivery_email_password"),
        )
        Hint(
            when (provider) {
                Provider.GMAIL -> stringResource(R.string.delivery_password_gmail)
                Provider.ICLOUD -> stringResource(R.string.delivery_password_icloud)
                Provider.YAHOO -> stringResource(R.string.delivery_password_yahoo)
                else -> stringResource(R.string.delivery_password_other)
            } + " " + stringResource(R.string.delivery_password_safe),
        )
        if (needsServer) {
            OutlinedTextField(
                value = server, onValueChange = { server = it.trim(); out = null }, singleLine = true,
                label = { Text(stringResource(R.string.delivery_email_server)) },
                placeholder = { Text("smtp.${address.substringAfterLast('@')}:465") },
                modifier = Modifier.fillMaxWidth().testTag("delivery_email_server"),
            )
            Hint(stringResource(R.string.delivery_email_server_hint))
        }
        Button(
            onClick = {
                out = Outcome(ctx.getString(R.string.delivery_email_checking), true)
                m.change({ it.setDeliveryEmail(address, password, server.ifEmpty { null }) }) { e ->
                    if (e == null) {
                        editing = false
                        password = ""
                        out = Outcome(ctx.getString(R.string.delivery_email_saved), true)
                    } else {
                        out = Outcome(e, false)
                    }
                }
            },
            enabled = !m.busy && EMAIL.matches(address) && password.isNotEmpty() && (!needsServer || server.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delivery_email_save"),
        ) { Text(stringResource(R.string.delivery_email_save)) }
        if (s.email.configured) {
            TextButton(onClick = { editing = false; out = null }) { Text(stringResource(R.string.cancel)) }
        }
        out?.let { OutcomeLine(it) }
    }
}

// ---------------------------------------------------------------- mailboxes

/** Your own repository for the mailboxes: its address, the relay's deploy key and a check that it works. */
@Composable
fun RepoForm(m: DeliveryModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = m.settings ?: return
    var url by remember { mutableStateOf(s.mailboxRepo.url.orEmpty()) }
    var branch by remember { mutableStateOf(s.mailboxRepo.branch.orEmpty()) }
    var saveOut by remember { mutableStateOf<Outcome?>(null) }
    var checkOut by remember { mutableStateOf<Outcome?>(null) }
    val repo = s.mailboxRepo
    val changed = url != repo.url.orEmpty() || branch != repo.branch.orEmpty()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim(); saveOut = null }, singleLine = true,
            label = { Text(stringResource(R.string.delivery_repo_url)) },
            placeholder = { Text("git@github.com:you/notes.git") },
            modifier = Modifier.fillMaxWidth().testTag("delivery_repo_url"),
        )
        OutlinedTextField(
            value = branch, onValueChange = { branch = it.trim(); saveOut = null }, singleLine = true,
            label = { Text(stringResource(R.string.delivery_repo_branch)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (changed || repo.url == null) {
            OutlinedButton(
                onClick = {
                    m.change({ it.setMailboxRepo(url, branch.ifEmpty { null }) }) { e ->
                        saveOut = e?.let { Outcome(it, false) }
                        checkOut = null
                    }
                },
                enabled = !m.busy && url.isNotEmpty() && changed,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delivery_repo_save"),
            ) { Text(stringResource(R.string.delivery_repo_save)) }
        }
        saveOut?.let { OutcomeLine(it) }
        if (repo.url != null && !changed) {
            val key = repo.publicKey.orEmpty()
            NumberedStep(1, stringResource(R.string.delivery_repo_step_key), stringResource(R.string.delivery_repo_step_key_body)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    Text(key, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(10.dp))
                }
                TextButton(onClick = { copy(ctx, key); checkOut = Outcome(ctx.getString(R.string.copied), true) }, modifier = Modifier.testTag("delivery_repo_copy")) {
                    Text(stringResource(R.string.copy))
                }
            }
            NumberedStep(2, stringResource(R.string.delivery_repo_step_check), null) {
                OutlinedButton(
                    onClick = {
                        checkOut = Outcome(ctx.getString(R.string.delivery_repo_checking), true)
                        m.check({ it.testMailboxRepo() }) { e ->
                            checkOut = if (e == null) Outcome(ctx.getString(R.string.delivery_repo_ok), true) else Outcome(e, false)
                        }
                    },
                    enabled = !m.busy,
                    modifier = Modifier.testTag("delivery_repo_test"),
                ) { Text(stringResource(R.string.delivery_repo_test)) }
            }
            // A check just done says it all; otherwise what the relay last saw.
            checkOut?.let { OutcomeLine(it) }
                ?: repo.lastError?.let { OutcomeLine(Outcome(stringResource(R.string.delivery_repo_error, it), false)) }
        }
    }
}

// ---------------------------------------------------------------- memory

private enum class MemoryChoice(val markdown: Boolean, val sqlite: Boolean) { OFF(false, false), MARKDOWN(true, false), SQLITE(false, true), BOTH(true, true) }

/** Whether bots remember, and how: as big cards in the setup, as radio rows in Settings. */
@Composable
fun MemoryChoices(m: DeliveryModel, cards: Boolean, modifier: Modifier = Modifier) {
    val s = m.settings ?: return
    var out by remember { mutableStateOf<Outcome?>(null) }
    val mailboxesOn = s.mailboxes.allAgents
    val now = MemoryChoice.entries.first { it.markdown == s.memory.markdown && it.sqlite == s.memory.sqlite }
    val recommended = if (mailboxesOn) MemoryChoice.MARKDOWN else MemoryChoice.SQLITE
    fun pick(c: MemoryChoice) {
        if (m.busy || c == now) return
        m.change({ api ->
            // Notes live in the mailbox: they turn mailboxes on.
            if (c.markdown && !mailboxesOn) api.setMailboxesForAll(true)
            api.setMemory(markdown = c.markdown, sqlite = c.sqlite)
        }) { e -> out = e?.let { Outcome(it, false) } }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (cards) 12.dp else 4.dp)) {
        for (c in MemoryChoice.entries) {
            val title = stringResource(
                when (c) {
                    MemoryChoice.OFF -> R.string.memory_off
                    MemoryChoice.MARKDOWN -> R.string.memory_markdown
                    MemoryChoice.SQLITE -> R.string.memory_sqlite
                    MemoryChoice.BOTH -> R.string.memory_both
                },
            )
            val body = when (c) {
                MemoryChoice.OFF -> stringResource(R.string.memory_off_body)
                MemoryChoice.MARKDOWN -> stringResource(R.string.memory_markdown_body) +
                    if (!mailboxesOn) " " + stringResource(R.string.memory_turns_mailboxes_on) else ""
                MemoryChoice.SQLITE -> stringResource(R.string.memory_sqlite_body)
                MemoryChoice.BOTH -> stringResource(R.string.memory_both_body)
            }
            val tag = "memory_" + c.name.lowercase()
            if (cards) {
                ChoiceCard(title, body, c == now, tag, badge = if (c == recommended) stringResource(R.string.recommended) else null, order = 3 + c.ordinal) { pick(c) }
            } else {
                RadioRow(c == now, title, body, tag) { pick(c) }
            }
        }
        out?.let { OutcomeLine(it) }
    }
}

// ---------------------------------------------------------------- summary

/** One line per choice, for the end of the setup. */
@Composable
fun DeliverySummary(s: DeliverySettings) {
    @Composable
    fun line(on: Boolean, text: String, order: Int) {
        Row(Modifier.fillMaxWidth().appear(order), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (on) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    BrandCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            line(s.email.configured, if (s.email.configured) stringResource(R.string.summary_email_on, s.email.address.orEmpty()) else stringResource(R.string.summary_email_off), 4)
            line(
                s.mailboxes.allAgents,
                when {
                    !s.mailboxes.allAgents -> stringResource(R.string.summary_mailboxes_off)
                    s.mailboxRepo.url != null -> stringResource(R.string.summary_mailboxes_repo, s.mailboxRepo.url!!)
                    else -> stringResource(R.string.summary_mailboxes_relay)
                },
                5,
            )
            line(
                s.memory.on,
                stringResource(
                    when {
                        s.memory.markdown && s.memory.sqlite -> R.string.summary_memory_both
                        s.memory.markdown -> R.string.summary_memory_markdown
                        s.memory.sqlite -> R.string.summary_memory_sqlite
                        else -> R.string.summary_memory_off
                    },
                ),
                6,
            )
        }
    }
}

// ---------------------------------------------------------------- Settings

/** Settings → Agents: email, mailboxes, memory. All of it at once, one card each. */
@Composable
fun DeliverySetup(modifier: Modifier = Modifier) {
    val m = rememberDeliveryModel()
    val s = m.settings
    if (s == null) {
        Hint(m.loadError?.let { stringResource(R.string.delivery_unavailable, it) } ?: stringResource(R.string.loading))
        return
    }
    var emailOn by remember { mutableStateOf(s.email.configured) }
    var external by remember { mutableStateOf(s.mailboxRepo.url != null) }
    var mailboxOut by remember { mutableStateOf<Outcome?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(stringResource(R.string.delivery_settings_body))
        BrandCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(emailOn, stringResource(R.string.delivery_email), stringResource(R.string.delivery_email_body), "delivery_email") { on ->
                    emailOn = on
                    if (!on && s.email.configured) m.change({ it.setDeliveryEmail(null) })
                }
                if (emailOn) EmailForm(m)
            }
        }
        BrandCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(s.mailboxes.allAgents, stringResource(R.string.delivery_mailboxes), stringResource(R.string.delivery_mailboxes_body), "delivery_mailboxes") { on ->
                    m.change({ it.setMailboxesForAll(on) }) { e -> mailboxOut = e?.let { Outcome(it, false) } }
                }
                mailboxOut?.let { OutcomeLine(it) }
                if (s.mailboxes.allAgents) {
                    RadioRow(!external, stringResource(R.string.delivery_repo_relay), stringResource(R.string.delivery_repo_relay_body), "delivery_repo_relay") {
                        external = false
                        if (s.mailboxRepo.url != null) m.change({ it.setMailboxRepo(null) })
                    }
                    RadioRow(external, stringResource(R.string.delivery_repo_external), stringResource(R.string.delivery_repo_external_body), "delivery_repo_external") {
                        external = true
                    }
                    if (external) RepoForm(m)
                }
            }
        }
        BrandCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.delivery_memory), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                MemoryChoices(m, cards = false)
            }
        }
    }
}

// ---------------------------------------------------------------- one agent

/** One agent: its wake-up address (save, test, off) and its mailbox (default, on, off) with how it clones it. */
@Composable
fun AgentDeliveryDialog(agent: Agent, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf(agent) }
    var d by remember { mutableStateOf<DeliverySettings?>(null) }
    var email by remember { mutableStateOf("") }
    var emailOut by remember { mutableStateOf<Outcome?>(null) }
    var mailboxOut by remember { mutableStateOf<Outcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { relay { it.delivery() }.onSuccess { d = it }.onFailure { emailOut = Outcome(it.readable(), false) } }
    fun agentCall(set: (Outcome?) -> Unit, done: String?, block: (RelayApi) -> Agent?) {
        busy = true
        set(null)
        scope.launch {
            relay(block).onSuccess { r -> r?.let { a = it; Graph.directory.put(it) }; set(done?.let { Outcome(it, true) }) }
                .onFailure { set(Outcome(it.readable(), false)) }
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
                        TextButton(
                            onClick = { agentCall({ emailOut = it }, ctx.getString(R.string.agent_wake_saved)) { it.setAgentWake(a.agentId, email) } },
                            enabled = !busy && EMAIL.matches(email),
                        ) { Text(stringResource(R.string.save)) }
                        if (a.wakes == true) {
                            TextButton(onClick = {
                                busy = true
                                emailOut = Outcome(ctx.getString(R.string.sending), true)
                                scope.launch {
                                    relay { it.testAgentWake(a.agentId) }
                                        .onSuccess { emailOut = Outcome(ctx.getString(R.string.agent_wake_sent), true) }
                                        .onFailure { emailOut = Outcome(it.readable(), false) }
                                    busy = false
                                }
                            }, enabled = !busy, modifier = Modifier.testTag("agent_wake_test")) { Text(stringResource(R.string.agent_wake_test)) }
                            TextButton(onClick = { agentCall({ emailOut = it }, null) { it.setAgentWake(a.agentId, null) } }, enabled = !busy) {
                                Text(stringResource(R.string.turn_off))
                            }
                        }
                    }
                }
                emailOut?.let { OutcomeLine(it) }

                Text(stringResource(R.string.delivery_mailboxes), style = MaterialTheme.typography.titleSmall)
                val default = d?.mailboxes?.allAgents == true
                RadioRow(false, stringResource(if (default) R.string.agent_mailbox_default_on else R.string.agent_mailbox_default_off), null, "agent_mailbox_default") {
                    agentCall({ mailboxOut = it }, null) { it.setAgentMailbox(a.agentId, null) }
                }
                RadioRow(a.mailbox == true, stringResource(R.string.agent_mailbox_on), null, "agent_mailbox_on") {
                    agentCall({ mailboxOut = it }, null) { it.setAgentMailbox(a.agentId, true) }
                }
                RadioRow(a.mailbox != true, stringResource(R.string.agent_mailbox_off), null, "agent_mailbox_off") {
                    agentCall({ mailboxOut = it }, null) { it.setAgentMailbox(a.agentId, false) }
                }
                if (a.mailbox == true) {
                    // The relay's own scheme: a relay on the LAN is plain http.
                    val scheme = relayUrl.substringBefore("://", "https")
                    val clone = "git clone $scheme://x:<key>@${relayUrl.substringAfter("://")}/v1/agents/${a.agentId}/mailbox.git"
                    Hint(stringResource(R.string.agent_mailbox_clone))
                    Text(clone, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    TextButton(onClick = { copy(ctx, clone); mailboxOut = Outcome(ctx.getString(R.string.copied), true) }) { Text(stringResource(R.string.copy)) }
                    d?.mailboxRepo?.url?.let { Hint(stringResource(R.string.agent_mailbox_repo, it, a.agentId)) }
                }
                mailboxOut?.let { OutcomeLine(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
