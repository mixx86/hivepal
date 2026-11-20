package dev.agentring.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin mirror of protocol/relay.openapi.yaml. Kept honest by
 * ProtocolFixturesTest, which round-trips every file in protocol/examples.
 */
val ProtocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class QrPayload(
    val v: Int,
    @SerialName("relay_url") val relayUrl: String,
    @SerialName("pairing_token") val pairingToken: String,
) {
    companion object {
        /**
         * Parses the QR / pasted pairing code: the JSON itself, or a pairing link (Hivepal relays):
         * `https://<relay>/pair#<base64url JSON>` or `hivepal://pair?c=<base64url JSON>`.
         * Throws [IllegalArgumentException] with a user-facing reason.
         */
        fun parse(raw: String): QrPayload {
            val text = raw.trim()
            val json = if (text.startsWith("{")) text else fromLink(text)
            val p = try {
                ProtocolJson.decodeFromString<QrPayload>(json ?: throw IllegalArgumentException())
            } catch (e: Exception) {
                throw IllegalArgumentException("This is not a Hivepal pairing code")
            }
            require(p.v == 1) { "Unsupported pairing code version ${p.v}; update the app" }
            require(p.pairingToken.startsWith("pt_")) { "Invalid pairing token" }
            RelayUrlPolicy.check(p.relayUrl)
            return p
        }

        private val LINK_CODE = Regex("""[?&]c=([A-Za-z0-9_-]+)""")

        /** The JSON inside a pairing link, or null if [text] isn't one. */
        private fun fromLink(text: String): String? {
            val lower = text.lowercase()
            if (!(lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("hivepal:"))) return null
            val code = text.substringAfter('#', "").takeIf { it.isNotEmpty() && !it.startsWith("Intent;") }
                ?: LINK_CODE.find(text)?.groupValues?.get(1)
                ?: return null
            return runCatching { String(java.util.Base64.getUrlDecoder().decode(code), Charsets.UTF_8) }.getOrNull()
        }
    }
}

@Serializable
data class PairRequest(
    @SerialName("pairing_token") val pairingToken: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@Serializable
data class FcmConfig(
    @SerialName("project_id") val projectId: String,
    @SerialName("application_id") val applicationId: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("sender_id") val senderId: String,
)

@Serializable
data class PushConfig(val provider: String, val fcm: FcmConfig)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_jwt") val deviceJwt: String,
    @SerialName("push_secret") val pushSecret: String,
    @SerialName("relay_id") val relayId: String,
    @SerialName("agent_name") val agentName: String,
    @SerialName("push_config") val pushConfig: PushConfig? = null,
)

@Serializable
data class Message(
    @SerialName("msg_id") val msgId: String,
    val from: String,
    val text: String,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("read_at") val readAt: Long? = null,
    /** Multi-agent fork: `agent:<id>` or `group:<id>`; absent (older relays) = `agent:main`. */
    val conv: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    /** Hivepal: quick replies the agent offers (buttons under the message). */
    val options: List<String>? = null,
    val attachments: List<Attachment>? = null,
) {
    val fromAgent: Boolean get() = from == FROM_AGENT

    /** The conversation, never null. */
    val convId: String get() = conv ?: Conv.MAIN

    /** The agent that wrote it (`main` for older relays), or null for the user's messages. */
    val author: String? get() = if (fromAgent) agentId ?: Conv.MAIN_AGENT else null

    companion object {
        const val FROM_AGENT = "agent"
        const val FROM_USER = "user"
    }
}

/** Conversation ids (multi-agent fork): `agent:<agent_id>` with one agent, `group:<group_id>` with several. */
object Conv {
    const val MAIN_AGENT = "main"
    const val MAIN = "agent:main"
    fun ofAgent(agentId: String) = "agent:$agentId"
    fun ofGroup(groupId: String) = "group:$groupId"
    fun isGroup(conv: String) = conv.startsWith("group:")
    /** Hivepal: a conversation between two agents, `bots:<a>.<b>` (ids sorted). */
    fun isBots(conv: String) = conv.startsWith("bots:")
    fun pair(conv: String): List<String> = conv.removePrefix("bots:").split('.')
    fun id(conv: String) = conv.substringAfter(':')
}

@Serializable
data class Attachment(
    @SerialName("file_id") val fileId: String,
    val mime: String,
    val size: Long = 0,
    @SerialName("duration_ms") val durationMs: Long? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isAudio: Boolean get() = mime.startsWith("audio/")
}

@Serializable
data class Reminder(
    @SerialName("rem_id") val remId: String,
    @SerialName("agent_id") val agentId: String,
    val conv: String,
    val text: String,
    val options: List<String>? = null,
    val at: Long,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class ReminderList(val reminders: List<Reminder>)

/** Hivepal: a subscription and when it renews (or ends), as the user's scraper read it from the service. */
@Serializable
data class Subscription(
    @SerialName("sub_id") val subId: String,
    val name: String,
    val plan: String? = null,
    val status: String,
    val until: Long? = null,
    val price: String? = null,
    val url: String? = null,
    val error: String? = null,
    @SerialName("checked_at") val checkedAt: Long = 0,
)

@Serializable
data class SubscriptionList(val subscriptions: List<Subscription>)

@Serializable
data class PutSubscription(
    val name: String,
    val status: String,
    val plan: String? = null,
    val until: Long? = null,
    val price: String? = null,
    val url: String? = null,
    val error: String? = null,
)

@Serializable
data class Agent(
    @SerialName("agent_id") val agentId: String,
    val name: String,
    @SerialName("created_at") val createdAt: Long = 0,
    val online: Boolean? = null,
    /** Hivepal: when the agent last used its key. */
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
    /** Hivepal: the relay emails it when you write (the address is never shown back). */
    val wakes: Boolean? = null,
    /** Hivepal: the relay writes its messages as Markdown files in its mailbox. */
    val mailbox: Boolean? = null,
)

// ---------- Hivepal: how agents hear from the user, and remember (issue #41) ----------

@Serializable
data class EmailSettings(
    val configured: Boolean = false,
    val address: String? = null,
    val server: String? = null,
    @SerialName("from_env") val fromEnv: Boolean? = null,
)

@Serializable
data class MailboxesSettings(@SerialName("all_agents") val allAgents: Boolean = false)

@Serializable
data class MemorySettings(val markdown: Boolean = false, val sqlite: Boolean = false) {
    val on: Boolean get() = markdown || sqlite
}

@Serializable
data class MailboxRepo(
    val url: String? = null,
    val branch: String? = null,
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("last_push_at") val lastPushAt: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class DeliverySettings(
    val email: EmailSettings = EmailSettings(),
    val mailboxes: MailboxesSettings = MailboxesSettings(),
    val memory: MemorySettings = MemorySettings(),
    @SerialName("mailbox_repo") val mailboxRepo: MailboxRepo = MailboxRepo(),
)

@Serializable
data class Group(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val members: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class AgentList(val agents: List<Agent>)

@Serializable
data class GroupList(val groups: List<Group>)

@Serializable
data class CallList(val calls: List<Call>)

@Serializable
data class MessagePage(val messages: List<Message>, @SerialName("has_more") val hasMore: Boolean)

@Serializable
data class Status(
    @SerialName("relay_id") val relayId: String,
    val version: String,
    @SerialName("agent_name") val agentName: String,
    @SerialName("agent_online") val agentOnline: Boolean,
    @SerialName("push_provider") val pushProvider: String,
    /** The call currently ringing or active, if any. */
    @SerialName("active_call") val activeCall: Call? = null,
)

@Serializable
data class Call(
    @SerialName("call_id") val callId: String,
    val direction: String,
    val state: String,
    val reason: String? = null,
    val priority: String,
    @SerialName("agent_name") val agentName: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("answered_at") val answeredAt: Long? = null,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("ended_by") val endedBy: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("device_id") val deviceId: String? = null,
) {
    val isRinging: Boolean get() = state == "ringing"
    val isActive: Boolean get() = state == "active"

    /** A group call from the agent that is in progress: a phone not on it may join. */
    val isJoinable: Boolean get() = isActive && direction == "agent_to_user"
}

@Serializable
data class JoinInfo(
    @SerialName("livekit_url") val livekitUrl: String,
    @SerialName("livekit_token") val livekitToken: String,
    val room: String,
    val identity: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class CallWithJoin(val call: Call, val join: JoinInfo)

@Serializable
data class ApiErrorBody(val error: Detail) {
    @Serializable
    data class Detail(val code: String, val message: String)
}

/** Relay events. Unknown types decode to [Event.Unknown] so old apps keep working. */
sealed interface Event {
    val type: String
    val id: String
    val ts: Long
    val seq: Long?

    @Serializable
    data class CallInvite(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        val direction: String,
        @SerialName("agent_name") val agentName: String,
        val reason: String,
        val priority: String,
        @SerialName("expires_at") val expiresAt: Long,
        @SerialName("agent_id") val agentId: String? = null,
    ) : Event

    @Serializable
    data class CallRinging(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        @SerialName("device_id") val deviceId: String,
    ) : Event

    /** call.accept and call.decline share a shape. */
    @Serializable
    data class CallAnswer(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        val by: String,
        @SerialName("device_id") val deviceId: String? = null,
    ) : Event

    @Serializable
    data class CallEnd(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        val by: String,
        val reason: String,
        @SerialName("duration_ms") val durationMs: Long? = null,
    ) : Event

    @Serializable
    data class CallMissed(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
    ) : Event

    @Serializable
    data class MsgNew(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("msg_id") val msgId: String,
        val from: String,
        val text: String,
        @SerialName("reply_to") val replyTo: String? = null,
        val truncated: Boolean? = null,
        val conv: String? = null,
        @SerialName("agent_id") val agentId: String? = null,
        val options: List<String>? = null,
        val attachments: List<Attachment>? = null,
    ) : Event {
        fun toMessage() = Message(
            msgId = msgId, from = from, text = text, replyTo = replyTo, createdAt = ts, conv = conv, agentId = agentId,
            options = options, attachments = attachments,
        )
    }

    @Serializable
    data class MsgRead(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("msg_id") val msgId: String,
        val by: String,
        val conv: String? = null,
    ) : Event

    @Serializable
    data class AgentPresence(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        val online: Boolean,
        /** Absent = `main` (older relays). */
        @SerialName("agent_id") val agentId: String? = null,
    ) : Event

    /** Hivepal: an agent started or stopped writing (ephemeral; `until` is when it ends by itself). */
    @Serializable
    data class AgentTyping(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("agent_id") val agentId: String,
        val conv: String,
        val typing: Boolean,
        val until: Long? = null,
    ) : Event

    /** Hivepal: its author deleted a message. */
    @Serializable
    data class MsgDeleted(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("msg_id") val msgId: String,
        val conv: String? = null,
    ) : Event

    /** An agent was added, renamed or removed (multi-agent fork). */
    @Serializable
    data class AgentUpdated(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("agent_id") val agentId: String,
        val name: String,
        val removed: Boolean? = null,
    ) : Event

    /** A group was created, changed or removed (multi-agent fork). */
    @Serializable
    data class GroupUpdated(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("group_id") val groupId: String,
        val name: String,
        val members: List<String> = emptyList(),
        val removed: Boolean? = null,
    ) : Event

    @Serializable
    data class WsHello(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("relay_id") val relayId: String,
        @SerialName("last_seq") val lastSeq: Long,
        @SerialName("server_ts") val serverTs: Long,
    ) : Event

    data class Unknown(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long?,
        val raw: JsonObject,
    ) : Event

    companion object {
        fun parse(json: String): Event = parse(ProtocolJson.parseToJsonElement(json).jsonObject)

        fun parse(obj: JsonObject): Event {
            val type = obj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("event without type")
            val j = ProtocolJson
            return when (type) {
                "call.invite" -> j.decodeFromJsonElement(CallInvite.serializer(), obj)
                "call.ringing" -> j.decodeFromJsonElement(CallRinging.serializer(), obj)
                "call.accept", "call.decline" -> j.decodeFromJsonElement(CallAnswer.serializer(), obj)
                "call.end" -> j.decodeFromJsonElement(CallEnd.serializer(), obj)
                "call.missed" -> j.decodeFromJsonElement(CallMissed.serializer(), obj)
                "msg.new" -> j.decodeFromJsonElement(MsgNew.serializer(), obj)
                "msg.read" -> j.decodeFromJsonElement(MsgRead.serializer(), obj)
                "agent.presence" -> j.decodeFromJsonElement(AgentPresence.serializer(), obj)
                "ws.hello" -> j.decodeFromJsonElement(WsHello.serializer(), obj)
                "agent.updated" -> j.decodeFromJsonElement(AgentUpdated.serializer(), obj)
                "group.updated" -> j.decodeFromJsonElement(GroupUpdated.serializer(), obj)
                "msg.deleted" -> j.decodeFromJsonElement(MsgDeleted.serializer(), obj)
                "agent.typing" -> j.decodeFromJsonElement(AgentTyping.serializer(), obj)
                else -> Unknown(
                    type = type,
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    ts = obj["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    seq = obj["seq"]?.jsonPrimitive?.content?.toLongOrNull(),
                    raw = obj,
                )
            }
        }
    }
}
