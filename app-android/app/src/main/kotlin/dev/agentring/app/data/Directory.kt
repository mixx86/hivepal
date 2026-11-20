package dev.agentring.app.data

import android.content.Context
import android.util.Log
import dev.agentring.core.Agent
import dev.agentring.core.Conv
import dev.agentring.core.Event
import dev.agentring.core.Group
import dev.agentring.core.ProtocolJson
import dev.agentring.core.RelayApi
import dev.agentring.core.RelayException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer

/**
 * The agents and groups on the relay (multi-agent fork), cached so names show at once.
 * On an upstream relay (no /v1/agents) there is just `main`, named after the pairing.
 */
class Directory(context: Context) {
    private val prefs = context.getSharedPreferences("agentring_directory", Context.MODE_PRIVATE)
    private val agentsSer = ListSerializer(Agent.serializer())
    private val groupsSer = ListSerializer(Group.serializer())

    private val _agents = MutableStateFlow(load(KEY_AGENTS, agentsSer))
    val agents: StateFlow<List<Agent>> = _agents

    private val _groups = MutableStateFlow(load(KEY_GROUPS, groupsSer))
    val groups: StateFlow<List<Group>> = _groups

    /** Which agents hold a WebSocket now (live, from `agent.presence`). */
    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online

    /**
     * Hivepal: who is writing where (live, from `agent.typing`): conversation → agent → until, in this
     * phone's clock. Screens drop the ones past their time ([dev.agentring.app.ui.rememberTyping]).
     */
    private val _typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val typing: StateFlow<Map<String, Map<String, Long>>> = _typing

    private fun setTyping(conv: String, agentId: String, until: Long?) {
        _typing.update { all ->
            val m = (all[conv] ?: emptyMap()).toMutableMap()
            if (until != null) m[agentId] = until else m.remove(agentId)
            if (m.isEmpty()) all - conv else all + (conv to m)
        }
    }

    private fun <T> load(key: String, ser: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        prefs.getString(key, null)?.let { runCatching { ProtocolJson.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    private fun save() {
        prefs.edit()
            .putString(KEY_AGENTS, ProtocolJson.encodeToString(agentsSer, _agents.value))
            .putString(KEY_GROUPS, ProtocolJson.encodeToString(groupsSer, _groups.value))
            .apply()
    }

    fun agent(agentId: String): Agent? = _agents.value.firstOrNull { it.agentId == agentId }
    fun group(groupId: String): Group? = _groups.value.firstOrNull { it.groupId == groupId }

    /** Display name of an agent; `main` falls back to the pairing's agent name. */
    fun nameOf(agentId: String?, mainName: String): String {
        val id = agentId ?: Conv.MAIN_AGENT
        return agent(id)?.name ?: if (id == Conv.MAIN_AGENT) mainName else id.replaceFirstChar { it.uppercase() }
    }

    fun convName(conv: String, mainName: String): String = when {
        Conv.isGroup(conv) -> group(Conv.id(conv))?.name ?: Conv.id(conv)
        Conv.isBots(conv) -> Conv.pair(conv).joinToString(" ↔ ") { nameOf(it, mainName) }
        else -> nameOf(Conv.id(conv), mainName)
    }

    /** Blocking. An upstream relay answers 404: keep just `main`. */
    fun refresh(api: RelayApi) {
        try {
            val agents = api.listAgents()
            val groups = api.listGroups()
            _agents.value = agents
            _groups.value = groups
            agents.firstOrNull { it.agentId == Conv.MAIN_AGENT }?.let { m ->
                if (dev.agentring.app.Graph.pairing.current?.agentName != m.name) dev.agentring.app.Graph.pairing.renameAgent(m.name)
            }
            _online.update { agents.filter { it.online == true }.map { it.agentId }.toSet() }
            save()
        } catch (e: RelayException) {
            if (e.status != 404) throw e
            Log.i(TAG, "relay has no agents API (upstream relay)")
        }
    }

    fun apply(event: Event) {
        when (event) {
            is Event.AgentPresence -> {
                val id = event.agentId ?: Conv.MAIN_AGENT
                _online.update { if (event.online) it + id else it - id }
            }
            is Event.AgentTyping -> {
                // The relay's clock may differ from the phone's: keep the time left, not the instant.
                val left = (event.until ?: (event.ts + 20_000)) - event.ts
                setTyping(event.conv, event.agentId, if (event.typing) System.currentTimeMillis() + left else null)
            }
            // Its message is the answer.
            is Event.MsgNew -> if (event.from == "agent") setTyping(event.conv ?: Conv.ofAgent(Conv.MAIN_AGENT), event.agentId ?: Conv.MAIN_AGENT, null)
            // A new connection: the relay tells again who is writing.
            is Event.WsHello -> _typing.value = emptyMap()
            is Event.AgentUpdated -> {
                _agents.update { list ->
                    val rest = list.filter { it.agentId != event.agentId }
                    if (event.removed == true) rest
                    else {
                        if (event.agentId == Conv.MAIN_AGENT) dev.agentring.app.Graph.pairing.renameAgent(event.name)
                        val old = list.firstOrNull { it.agentId == event.agentId }
                        val updated = old?.copy(name = event.name) ?: Agent(event.agentId, event.name, event.ts)
                        // Keep the order: main first, then by when they were added.
                        (rest + updated).sortedWith(compareBy({ it.agentId != Conv.MAIN_AGENT }, { it.createdAt }))
                    }
                }
                save()
            }
            is Event.GroupUpdated -> {
                _groups.update { list ->
                    val rest = list.filter { it.groupId != event.groupId }
                    if (event.removed == true) rest
                    else {
                        val old = list.firstOrNull { it.groupId == event.groupId }
                        (rest + Group(event.groupId, event.name, event.members, old?.createdAt ?: event.ts)).sortedBy { it.createdAt }
                    }
                }
                save()
            }
            else -> Unit
        }
    }

    /** Local rename after the relay accepted it (the agent.updated event follows). */
    fun put(agent: Agent) {
        _agents.update { list -> list.map { if (it.agentId == agent.agentId) it.copy(name = agent.name) else it } }
        save()
    }

    fun put(group: Group) {
        _groups.update { list -> (list.filter { it.groupId != group.groupId } + group).sortedBy { it.createdAt } }
        save()
    }

    fun clear() {
        _agents.value = emptyList()
        _groups.value = emptyList()
        _online.value = emptySet()
        _typing.value = emptyMap()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "Directory"
        const val KEY_AGENTS = "agents"
        const val KEY_GROUPS = "groups"
    }
}
