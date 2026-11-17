package dev.agentring.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import kotlinx.coroutines.launch

/** What a notification (or the launcher) asked to open. `conv` null = the chat list. */
data class OpenRequest(val conv: String?)

enum class Dest { CHATS, CALLS, AGENTS, STATUS, SETTINGS }

/** The app once paired: a menu (drawer) with Chats, Calls, Agents, Status and Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(pairing: Pairing, openRequest: MutableState<OpenRequest?>) {
    var dest by rememberSaveable { mutableStateOf(Dest.CHATS) }
    var conv by rememberSaveable { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(openRequest.value) {
        openRequest.value?.let {
            dest = Dest.CHATS
            conv = it.conv
            openRequest.value = null
            drawer.close()
        }
    }

    val messages by Graph.messages.messages.collectAsStateWithLifecycle()
    val calls by Graph.callHistory.calls.collectAsStateWithLifecycle()
    val seenUntil by Graph.callHistory.seenUntil.collectAsStateWithLifecycle()
    val unread = messages.count { it.fromAgent && it.readAt == null }
    val missed = Graph.callHistory.unseenMissed(calls, seenUntil)

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && conv == null && dest != Dest.CHATS) { dest = Dest.CHATS }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = conv == null || drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                DrawerHeader(pairing)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                fun go(d: Dest) {
                    dest = d
                    conv = null
                    scope.launch { drawer.close() }
                }
                DrawerItem(stringResource(R.string.nav_chats), null, dest == Dest.CHATS, unread, "nav_chats", { go(Dest.CHATS) }) {
                    Icon(painterResource(R.drawable.ic_chat), null)
                }
                DrawerItem(stringResource(R.string.nav_calls), null, dest == Dest.CALLS, missed, "nav_calls", { go(Dest.CALLS) }) {
                    Icon(Icons.Filled.Call, null)
                }
                DrawerItem(stringResource(R.string.nav_agents), Icons.Filled.Face, dest == Dest.AGENTS, 0, "nav_agents", { go(Dest.AGENTS) })
                DrawerItem(stringResource(R.string.nav_status), Icons.Filled.Info, dest == Dest.STATUS, 0, "nav_status", { go(Dest.STATUS) })
                DrawerItem(stringResource(R.string.tab_settings), Icons.Filled.Settings, dest == Dest.SETTINGS, 0, "nav_settings", { go(Dest.SETTINGS) })
            }
        },
    ) {
        val open = conv
        if (open != null) {
            BackHandler { conv = null }
            ChatScreen(pairing, open, onBack = { conv = null })
            return@ModalNavigationDrawer
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when (dest) {
                                    Dest.CHATS -> R.string.nav_chats
                                    Dest.CALLS -> R.string.nav_calls
                                    Dest.AGENTS -> R.string.nav_agents
                                    Dest.STATUS -> R.string.nav_status
                                    Dest.SETTINGS -> R.string.tab_settings
                                },
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("open_menu")) {
                            Box {
                                Icon(Icons.Filled.Menu, stringResource(R.string.open_menu))
                                if (unread + missed > 0 && dest != Dest.CHATS) {
                                    Badge(Modifier.align(Alignment.TopEnd).size(8.dp))
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (dest) {
                    Dest.CHATS -> ChatsScreen(pairing, onOpen = { conv = it })
                    Dest.CALLS -> CallsScreen(pairing)
                    Dest.AGENTS -> AgentsScreen(pairing, onOpenChat = { conv = it })
                    Dest.STATUS -> HomeScreen(pairing)
                    Dest.SETTINGS -> SettingsScreen(pairing)
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(pairing: Pairing) {
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeroBadge(painterResource(R.drawable.ic_call), size = 40.dp)
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(
                pairing.relayUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    badge: Int,
    tag: String,
    onClick: () -> Unit,
    iconContent: (@Composable () -> Unit)? = null,
) {
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.titleSmall) },
        icon = iconContent ?: { if (icon != null) Icon(icon, null) },
        badge = if (badge > 0) ({ Badge { Text("$badge") } }) else null,
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(horizontal = 12.dp).testTag(tag),
    )
}
