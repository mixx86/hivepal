package dev.agentring.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.BuildConfig
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.call.CallPermissions
import dev.agentring.app.call.CallUi
import dev.agentring.app.call.JoinOffer
import dev.agentring.app.data.Pairing
import dev.agentring.app.notify.Notifications
import dev.agentring.app.push.PushState
import dev.agentring.core.RelaySocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(pairing: Pairing) {
    val relay by Graph.connection.relayState.collectAsStateWithLifecycle()
    val agent by Graph.connection.agentOnline.collectAsStateWithLifecycle()
    val push by Graph.push.state.collectAsStateWithLifecycle()
    val revoked by Graph.pairing.revoked.collectAsStateWithLifecycle()
    val joinOffer by Graph.calls.joinOffer.collectAsStateWithLifecycle()
    val colors = LocalStatusColors.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentAvatar(pairing.agentName, online = agent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pairing.agentName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("agent_name"))
                val (label, color) = when (agent) {
                    true -> stringResource(R.string.agent_online) to colors.ok
                    false -> stringResource(R.string.agent_offline) to colors.idle
                    null -> stringResource(R.string.agent_unknown) to colors.idle
                }
                StatusPill(label, color)
            }
        }

        joinOffer?.let { JoinCallCard(it) }

        if (revoked) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.revoked_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.revoked_body), color = MaterialTheme.colorScheme.onErrorContainer)
                    Button(onClick = { Graph.unpair() }) { Text(stringResource(R.string.pair_again)) }
                }
            }
        }

        BrandCard {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CallAgentButton(pairing, relayConnected = relay == RelaySocket.State.CONNECTED, agentOnline = agent == true, revoked = revoked)
            }
        }

        if (!rememberCallsReady()) CallReadinessCard()

        BrandCard {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.connection),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                val (relayText, relayColor) = when (relay) {
                    RelaySocket.State.CONNECTED -> stringResource(R.string.connected) to colors.ok
                    RelaySocket.State.CONNECTING -> stringResource(R.string.connecting) to colors.warn
                    RelaySocket.State.DISCONNECTED -> stringResource(R.string.offline) to colors.bad
                }
                StatusRow(stringResource(R.string.status_relay), relayText, relayColor, "relay_status")
                val (agentText, agentColor) = when (agent) {
                    true -> stringResource(R.string.online) to colors.ok
                    false -> stringResource(R.string.offline) to colors.bad
                    null -> stringResource(R.string.unknown) to colors.idle
                }
                StatusRow(stringResource(R.string.status_agent), agentText, agentColor, "agent_status")
                val (pushText, pushColor) = when (val s = push) {
                    PushState.Registered -> stringResource(R.string.push_registered) to colors.ok
                    PushState.Registering -> stringResource(R.string.push_registering) to colors.warn
                    PushState.NotConfigured -> stringResource(R.string.push_not_configured) to colors.warn
                    is PushState.Unavailable -> stringResource(R.string.push_unavailable, s.reason) to colors.bad
                    is PushState.Error -> stringResource(R.string.push_error, s.message) to colors.bad
                }
                StatusRow(stringResource(R.string.status_push), pushText, pushColor, "push_status")
            }
        }
    }
}

/** "Call agent" (M4): places a call to the agent and opens the call screen. */
@Composable
internal fun CallAgentButton(pairing: Pairing, relayConnected: Boolean, agentOnline: Boolean, revoked: Boolean) {
    val ctx = LocalContext.current
    val call by Graph.calls.state.collectAsStateWithLifecycle()
    var placing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(relayConnected, agentOnline) { error = null }
    val idle = call == null || call is CallUi.Ended

    fun place() {
        placing = true
        error = null
        // Not the composition's scope: leaving the screen mid-request must not orphan a ringing call.
        Graph.appScope.launch(Dispatchers.Main.immediate) {
            val failure = Graph.calls.callAgent()
            placing = false
            if (failure == null) ctx.startActivity(CallActivity.outgoingIntent(ctx)) else error = failure
        }
    }
    // Go on either way: without the microphone the user can still hear the agent.
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { place() }

    val enabled = relayConnected && agentOnline && idle && !placing && !revoked
    val scheme = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
        if (enabled) {
            Box(
                Modifier.size(132.dp).background(
                    Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.25f), Color.Transparent)),
                    CircleShape,
                ),
            )
        }
        FilledIconButton(
            onClick = { if (CallPermissions.hasMic(ctx)) place() else mic.launch(Manifest.permission.RECORD_AUDIO) },
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.size(92.dp).testTag("call_agent"),
        ) {
            if (placing) {
                CircularProgressIndicator(Modifier.size(32.dp), color = scheme.onPrimary, strokeWidth = 3.dp)
            } else {
                Icon(painterResource(R.drawable.ic_call), stringResource(R.string.call_agent), Modifier.size(40.dp))
            }
        }
    }
    Text(stringResource(R.string.call_agent_named, pairing.agentName), style = MaterialTheme.typography.titleLarge)
    val hint = when {
        error != null -> stringResource(error!!)
        placing -> stringResource(R.string.call_calling, pairing.agentName)
        !relayConnected -> stringResource(R.string.call_agent_needs_relay)
        !agentOnline -> stringResource(R.string.call_agent_offline)
        !idle -> stringResource(R.string.call_agent_in_call)
        else -> stringResource(R.string.call_agent_hint, pairing.agentName)
    }
    Text(
        hint,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("call_agent_hint"),
    )
    if (error == R.string.call_agent_quota) PlanDashboardButton(pairing)
}

/** Plan limit reached on the hosted service: a way to its web dashboard (usage and plan). */
@Composable
internal fun PlanDashboardButton(pairing: Pairing) {
    val url = pairing.dashboardUrl() ?: return
    val ctx = LocalContext.current
    TextButton(
        onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        modifier = Modifier.testTag("plan_dashboard"),
    ) { Text(stringResource(R.string.plan_open_dashboard)) }
}

/** A group call is going on without this phone: join it. */
@Composable
private fun JoinCallCard(offer: JoinOffer) {
    val ctx = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("join_call_card"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.call_in_progress_with, offer.info.agentName), style = MaterialTheme.typography.titleMedium)
            if (offer.info.reason.isNotBlank()) Text(offer.info.reason, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { ctx.startActivity(CallActivity.joinIntent(ctx, offer.info)) },
                modifier = Modifier.fillMaxWidth().testTag("join_call"),
            ) {
                Icon(Icons.Filled.Call, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.call_join))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color, tag: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(tag))
        }
    }
}

@Composable
fun SettingsScreen(pairing: Pairing) {
    val ctx = LocalContext.current
    var confirmUnpair by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionLabel(stringResource(R.string.settings_pairing))
        BrandCard {
            Column(Modifier.padding(vertical = 6.dp)) {
                InfoRow(stringResource(R.string.settings_agent), pairing.agentName)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(stringResource(R.string.settings_relay), pairing.relayUrl)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(stringResource(R.string.settings_device), pairing.deviceId)
            }
        }

        SectionLabel(stringResource(R.string.settings_language))
        BrandCard {
            val current = remember { dev.agentring.app.AppLanguage.current(ctx) }
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dev.agentring.app.AppLanguage.SUPPORTED.forEach { (tag, label) ->
                    val selected = tag == current
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                dev.agentring.app.AppLanguage.set(ctx, tag)
                                // Before Android 13 the screen is rebuilt here; 13+ does it by itself.
                                if (android.os.Build.VERSION.SDK_INT < 33) (ctx as? android.app.Activity)?.recreate()
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.weight(1f).testTag("lang_$tag"),
                    )
                }
            }
        }

        SectionLabel(stringResource(R.string.settings_calls))
        CallReadinessCard(showHeader = false)
        BrandCard {
            var useTelecom by remember { mutableStateOf(Graph.settings.useTelecom) }
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_use_telecom), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_use_telecom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useTelecom,
                    onCheckedChange = {
                        useTelecom = it
                        Graph.settings.useTelecom = it
                    },
                    modifier = Modifier.testTag("use_telecom"),
                )
            }
        }

        BrandCard {
            var phoneLog by remember { mutableStateOf(Graph.callHistory.phoneLogEnabled && Graph.callHistory.canWritePhoneLog()) }
            val askLog = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                phoneLog = granted
                Graph.callHistory.phoneLogEnabled = granted
            }
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.phone_log_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.phone_log_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = phoneLog,
                    onCheckedChange = { on ->
                        if (!on) {
                            phoneLog = false
                            Graph.callHistory.phoneLogEnabled = false
                        } else if (Graph.callHistory.canWritePhoneLog()) {
                            phoneLog = true
                            Graph.callHistory.phoneLogEnabled = true
                        } else {
                            askLog.launch(Manifest.permission.WRITE_CALL_LOG)
                        }
                    },
                    modifier = Modifier.testTag("phone_log"),
                )
            }
        }

        SectionLabel(stringResource(R.string.updates))
        UpdatesCard()

        SectionLabel(stringResource(R.string.settings_more))
        OutlinedButton(
            onClick = { Notifications.showTest(ctx) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("test_notification"),
        ) { Text(stringResource(R.string.test_notification)) }
        TestCallButton()
        if (pairing.isCloud()) {
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.CLOUD_URL + "/app"))) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.open_dashboard)) }
        }
        OutlinedButton(onClick = { confirmUnpair = true }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("unpair")) {
            Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.error)
        }
        Text(
            stringResource(R.string.settings_footer, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.unpair)) },
            text = { Text(stringResource(R.string.unpair_confirm, pairing.deviceId)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    Graph.unpair()
                }, modifier = Modifier.testTag("unpair_confirm")) { Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}


/**
 * Hivepal: the installed version, a check for a newer release (only when tapped: no background calls
 * to GitHub) and Obtainium, which checks on its own. Preview and self-built apps are another channel.
 */
@Composable
private fun UpdatesCard() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var result by remember { mutableStateOf<dev.agentring.core.Updates.Result?>(null) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (val r = result) {
                null -> stringResource(if (failed) R.string.updates_failed else R.string.updates_installed, BuildConfig.VERSION_NAME)
                is dev.agentring.core.Updates.Result.UpToDate -> stringResource(R.string.updates_up_to_date, r.version)
                is dev.agentring.core.Updates.Result.Newer -> stringResource(R.string.updates_newer, r.version)
                dev.agentring.core.Updates.Result.NotARelease -> stringResource(R.string.updates_not_release, BuildConfig.VERSION_NAME)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val newer = result as? dev.agentring.core.Updates.Result.Newer
        if (newer != null) {
            Button(onClick = { open(newer.url) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.updates_download, newer.version))
            }
        } else {
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    failed = false
                    scope.launch {
                        result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            runCatching { dev.agentring.core.Updates.check(BuildConfig.VERSION_NAME) }.getOrNull()
                        }
                        failed = result == null
                        checking = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("check_updates"),
            ) { Text(stringResource(if (checking) R.string.updates_checking else R.string.updates_check)) }
        }
        TextButton(onClick = {
            // Obtainium adds the app from its releases and checks for new ones; its site when it isn't installed.
            if (open("obtainium://add/https://github.com/${dev.agentring.core.Updates.REPO}").isFailure) open("https://obtainium.imranr.dev")
        }) { Text(stringResource(R.string.updates_obtainium)) }
    }
}
