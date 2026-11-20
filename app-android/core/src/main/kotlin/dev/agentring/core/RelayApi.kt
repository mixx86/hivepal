package dev.agentring.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RelayException(val status: Int, val code: String, message: String) : IOException(message) {
    /** Credentials are no longer valid (device revoked or relay reset): the app must re-pair. */
    val isUnauthorized: Boolean get() = status == 401
}

/** Blocking client for the relay REST API, as a device. Call from a background thread. */
class RelayApi(
    val baseUrl: String,
    private val deviceJwt: String?,
    private val http: OkHttpClient = defaultClient,
    private val userAgent: String = "hivepal-android",
) {
    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        private val JSON = "application/json".toMediaType()
        private val EMPTY = "".toRequestBody(null)
    }

    @Serializable
    private data class FcmBody(@SerialName("fcm_token") val fcmToken: String)

    @Serializable
    private data class PostMessageBody(
        val text: String,
        @SerialName("reply_to") val replyTo: String? = null,
        @SerialName("client_msg_id") val clientMsgId: String? = null,
        val conv: String? = null,
        val attachments: List<String>? = null,
    )

    @Serializable
    private data class NameBody(val name: String)

    @Serializable
    private data class GroupBody(
        @SerialName("group_id") val groupId: String? = null,
        val name: String? = null,
        val members: List<String>? = null,
    )

    @Serializable
    private data class CreateCallBody(val reason: String? = null, @SerialName("agent_id") val agentId: String? = null)

    fun pair(req: PairRequest): PairResponse =
        call("POST", "/v1/pair", body(PairRequest.serializer(), req), PairResponse.serializer(), auth = false)

    /**
     * Hivepal: wake this device through UnifiedPush (the distributor's endpoint) instead of FCM. With
     * the app's Web Push keys ([p256dh], [auth]) the relay encrypts every push for it (RFC 8291).
     */
    fun setUnifiedPush(endpoint: String, p256dh: String? = null, auth: String? = null) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("endpoint", kotlinx.serialization.json.JsonPrimitive(endpoint))
            if (p256dh != null && auth != null) {
                put("p256dh", kotlinx.serialization.json.JsonPrimitive(p256dh))
                put("auth", kotlinx.serialization.json.JsonPrimitive(auth))
            }
        }
        call<Unit>("POST", "/v1/device/unifiedpush", body.toString().toRequestBody(JSON), null)
    }

    /** Hivepal: back to FCM for this device. */
    fun clearUnifiedPush() {
        call<Unit>("DELETE", "/v1/device/unifiedpush", null, null)
    }

    fun updateFcmToken(token: String) {
        call<Unit>("POST", "/v1/device/fcm", body(FcmBody.serializer(), FcmBody(token)), null)
    }

    fun status(): Status = call("GET", "/v1/status", null, Status.serializer())

    fun listMessages(after: String? = null, limit: Int = 200): MessagePage {
        val q = buildString {
            append("/v1/messages?limit=").append(limit)
            if (after != null) append("&after=").append(after)
        }
        return call("GET", q, null, MessagePage.serializer())
    }

    fun getMessage(msgId: String): Message = call("GET", "/v1/messages/$msgId", null, Message.serializer())

    /** `conv` = the conversation to write in (multi-agent fork); null = `agent:main`. */
    fun postMessage(
        text: String,
        replyTo: String? = null,
        clientMsgId: String? = null,
        conv: String? = null,
        attachments: List<String>? = null,
    ): Message =
        call(
            "POST", "/v1/messages",
            body(
                PostMessageBody.serializer(),
                PostMessageBody(text, replyTo, clientMsgId, conv?.takeIf { it != Conv.MAIN }, attachments?.takeIf { it.isNotEmpty() }),
            ),
            Message.serializer(),
        )

    /** Hivepal: uploads a photo or voice clip; attach the returned id to a message. */
    fun uploadFile(bytes: ByteArray, mime: String, durationMs: Long? = null): Attachment {
        val q = if (durationMs != null) "?duration_ms=$durationMs" else ""
        return call("POST", "/v1/files$q", bytes.toRequestBody(mime.toMediaType()), Attachment.serializer())
    }

    /** Hivepal: a file's bytes. Blocking. */
    fun downloadFile(fileId: String): ByteArray {
        val rb = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/files/$fileId").get().header("User-Agent", userAgent)
            .header("Authorization", "Bearer ${requireNotNull(deviceJwt) { "not paired" }}")
        http.newCall(rb.build()).execute().use { res ->
            if (!res.isSuccessful) throw RelayException(res.code, "http_${res.code}", "HTTP ${res.code}")
            return res.body!!.bytes()
        }
    }

    @Serializable
    private data class TranscriptBody(val text: String)

    /** Hivepal: a voice note as text (the relay transcribes it once). */
    fun transcript(fileId: String): String = call("GET", "/v1/files/$fileId/transcript", null, TranscriptBody.serializer()).text

    fun listReminders(): List<Reminder> = call("GET", "/v1/reminders", null, ReminderList.serializer()).reminders

    fun listSubscriptions(): List<Subscription> =
        call("GET", "/v1/subscriptions", null, SubscriptionList.serializer()).subscriptions

    /** Hivepal: what the phone read from a service's billing page. */
    fun putSubscription(subId: String, sub: PutSubscription): Subscription =
        call("PUT", "/v1/subscriptions/$subId", body(PutSubscription.serializer(), sub), Subscription.serializer())

    fun deleteSubscription(subId: String) {
        call<Unit>("DELETE", "/v1/subscriptions/$subId", null, null)
    }

    fun cancelReminder(remId: String) {
        call<Unit>("DELETE", "/v1/reminders/$remId", null, null)
    }

    // ---------- agents and groups (multi-agent fork; 404 on an upstream relay) ----------

    fun listAgents(): List<Agent> = call("GET", "/v1/agents", null, AgentList.serializer()).agents

    fun renameAgent(agentId: String, name: String): Agent =
        call("PATCH", "/v1/agents/$agentId", body(NameBody.serializer(), NameBody(name)), Agent.serializer())

    fun listGroups(): List<Group> = call("GET", "/v1/groups", null, GroupList.serializer()).groups

    fun createGroup(groupId: String, name: String, members: List<String>): Group =
        call("POST", "/v1/groups", body(GroupBody.serializer(), GroupBody(groupId, name, members)), Group.serializer())

    fun updateGroup(groupId: String, name: String? = null, members: List<String>? = null): Group =
        call("PATCH", "/v1/groups/$groupId", body(GroupBody.serializer(), GroupBody(null, name, members)), Group.serializer())

    fun deleteGroup(groupId: String) {
        call<Unit>("DELETE", "/v1/groups/$groupId", null, null)
    }

    // ---------- Hivepal: delivery to agents and their memory (issue #41; 404 on older relays) ----------

    private fun json(vararg pairs: Pair<String, Any?>): RequestBody =
        JsonObject(pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }).toString().toRequestBody(JSON)

    fun delivery(): DeliverySettings = call("GET", "/v1/settings/delivery", null, DeliverySettings.serializer())

    /** The relay logs in before saving: a wrong password is a [RelayException] with code `email_failed`. */
    fun setDeliveryEmail(address: String?, password: String? = null, server: String? = null): DeliverySettings {
        val fields = buildList<Pair<String, Any?>> {
            add("address" to address)
            if (!password.isNullOrEmpty()) add("password" to password)
            if (!server.isNullOrBlank()) add("server" to server.trim())
        }
        return call("PUT", "/v1/settings/email", json(*fields.toTypedArray()), DeliverySettings.serializer())
    }

    fun setMailboxRepo(url: String?, branch: String? = null): DeliverySettings {
        val fields = buildList<Pair<String, Any?>> {
            add("url" to url)
            if (!branch.isNullOrBlank()) add("branch" to branch.trim())
        }
        return call("PUT", "/v1/settings/mailbox-repo", json(*fields.toTypedArray()), DeliverySettings.serializer())
    }

    fun testMailboxRepo() {
        call<Unit>("POST", "/v1/settings/mailbox-repo/test", EMPTY, null)
    }

    fun setMailboxesForAll(on: Boolean): DeliverySettings =
        call("PUT", "/v1/settings/mailboxes", json("all_agents" to on), DeliverySettings.serializer())

    fun setMemory(markdown: Boolean, sqlite: Boolean): DeliverySettings =
        call("PUT", "/v1/settings/memory", json("markdown" to markdown, "sqlite" to sqlite), DeliverySettings.serializer())

    /** On, off, or null: the relay-wide default. */
    fun setAgentMailbox(agentId: String, enabled: Boolean?): Agent =
        call("PUT", "/v1/agents/$agentId/mailbox", json("enabled" to enabled), Agent.serializer())

    /** The address the relay emails when you write to the agent; null turns it off. */
    fun setAgentWake(agentId: String, email: String?): Agent =
        call("PUT", "/v1/agents/$agentId/wake", json("email" to email), Agent.serializer())

    fun testAgentWake(agentId: String) {
        call<Unit>("POST", "/v1/agents/$agentId/wake/test", EMPTY, null)
    }

    fun listCalls(limit: Int = 100): List<Call> = call("GET", "/v1/calls?limit=$limit", null, CallList.serializer()).calls

    /** Hivepal: deletes one of the user's own messages. */
    fun deleteMessage(msgId: String) {
        call<Unit>("POST", "/v1/messages/$msgId/delete", "".toRequestBody(null), null)
    }

    fun markRead(msgId: String) {
        call<Unit>("POST", "/v1/messages/$msgId/read", "".toRequestBody(null), null)
    }

    /**
     * Calls the agent (M4, direction `user_to_agent`). The relay returns this
     * phone's LiveKit join info right away; the call rings until the agent accepts.
     * Fails with 503 `agent_offline` / `calls_unavailable` or 409 `busy`.
     */
    fun createCall(reason: String? = null, agentId: String? = null): CallWithJoin =
        call(
            "POST", "/v1/calls",
            body(CreateCallBody.serializer(), CreateCallBody(reason, agentId?.takeIf { it != Conv.MAIN_AGENT })),
            CallWithJoin.serializer(),
        )

    fun getCall(callId: String): Call = call("GET", "/v1/calls/$callId", null, Call.serializer())

    /** Tells the relay this device is showing the incoming call (measures ring latency). */
    fun reportRinging(callId: String) {
        call<Unit>("POST", "/v1/calls/$callId/ringing", EMPTY, null)
    }

    /** Answers a ringing call; after the ring window it fails with 409 `invalid_state` (use [joinCall]). */
    fun acceptCall(callId: String): CallWithJoin = call("POST", "/v1/calls/$callId/accept", EMPTY, CallWithJoin.serializer())

    /**
     * Joins an agent call that is ringing or active, also after this phone's ring
     * ended, it declined or it hung up. 409 `call_ended` once the call is over.
     */
    fun joinCall(callId: String): CallWithJoin = call("POST", "/v1/calls/$callId/join", EMPTY, CallWithJoin.serializer())

    fun declineCall(callId: String): Call = call("POST", "/v1/calls/$callId/decline", EMPTY, Call.serializer())

    fun endCall(callId: String): Call = call("POST", "/v1/calls/$callId/end", EMPTY, Call.serializer())

    /** Hivepal: ring this phone after [delayS] seconds, as a call from the switchboard (time to lock it). */
    fun testCall(delayS: Int = 10) {
        call<Unit>("POST", "/v1/calls/test", """{"delay_s":$delayS}""".toRequestBody(JSON), null)
    }

    fun webSocketRequest(afterSeq: Long): Request {
        val url = "${baseUrl.trimEnd('/')}/v1/ws".toHttpUrl().newBuilder()
            .addQueryParameter("after_seq", afterSeq.toString())
            .build()
        return Request.Builder().url(url)
            .header("Authorization", "Bearer ${requireNotNull(deviceJwt)}")
            .header("User-Agent", userAgent)
            .build()
    }

    private fun <T> body(serializer: KSerializer<T>, value: T): RequestBody =
        ProtocolJson.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> call(method: String, path: String, body: RequestBody?, out: KSerializer<T>?, auth: Boolean = true): T {
        val rb = Request.Builder().url(baseUrl.trimEnd('/') + path).method(method, body).header("User-Agent", userAgent)
        if (auth) rb.header("Authorization", "Bearer ${requireNotNull(deviceJwt) { "not paired" }}")
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val err = runCatching { ProtocolJson.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
                throw RelayException(res.code, err?.code ?: "http_${res.code}", err?.message ?: "HTTP ${res.code}")
            }
            @Suppress("UNCHECKED_CAST")
            return if (out == null) Unit as T else ProtocolJson.decodeFromString(out, text)
        }
    }
}
