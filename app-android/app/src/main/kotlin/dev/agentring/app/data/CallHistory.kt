package dev.agentring.app.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import dev.agentring.core.Call
import dev.agentring.core.ProtocolJson
import dev.agentring.core.RelayApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * The calls with the agents (newest first), for the Calls screen and its missed-call badge.
 * Optionally copies them into the phone's own call log, so they show in the Phone app.
 */
class CallHistory(private val context: Context) {
    private val prefs = context.getSharedPreferences("agentring_calls", Context.MODE_PRIVATE)
    private val ser = ListSerializer(Call.serializer())

    private val _calls = MutableStateFlow(
        prefs.getString(KEY_CALLS, null)?.let { runCatching { ProtocolJson.decodeFromString(ser, it) }.getOrNull() } ?: emptyList(),
    )
    val calls: StateFlow<List<Call>> = _calls

    private val _seenUntil = MutableStateFlow(prefs.getLong(KEY_SEEN, 0L))
    val seenUntil: StateFlow<Long> = _seenUntil

    /** Missed calls from an agent the user hasn't looked at in the Calls screen yet. */
    fun unseenMissed(calls: List<Call>, seenUntil: Long): Int = calls.count { isMissed(it) && it.createdAt > seenUntil }

    fun markSeen() {
        val newest = _calls.value.maxOfOrNull { it.createdAt } ?: return
        _seenUntil.value = newest
        prefs.edit().putLong(KEY_SEEN, newest).apply()
    }

    /** Blocking. */
    @Synchronized
    fun refresh(api: RelayApi) {
        val calls = api.listCalls(100)
        _calls.value = calls
        prefs.edit().putString(KEY_CALLS, ProtocolJson.encodeToString(ser, calls)).apply()
        if (phoneLogEnabled && canWritePhoneLog()) copyToPhoneLog(calls)
    }

    // ---------- the Phone app's call log ----------

    var phoneLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHONE_LOG, false)
        set(value) {
            // Only calls from now on: turning it on must not flood the log with old calls.
            prefs.edit().putBoolean(KEY_PHONE_LOG, value).putLong(KEY_PHONE_LOG_SINCE, System.currentTimeMillis()).apply()
        }

    fun canWritePhoneLog(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun copyToPhoneLog(calls: List<Call>) {
        val since = prefs.getLong(KEY_PHONE_LOG_SINCE, Long.MAX_VALUE)
        val logged = prefs.getStringSet(KEY_LOGGED, emptySet())!!.toMutableSet()
        var changed = false
        for (c in calls.sortedBy { it.createdAt }) {
            if (c.createdAt < since || c.callId in logged || c.state !in FINAL) continue
            val type = when {
                c.direction == "user_to_agent" -> CallLog.Calls.OUTGOING_TYPE
                c.state == "declined" -> CallLog.Calls.REJECTED_TYPE
                c.answeredAt != null -> CallLog.Calls.INCOMING_TYPE
                else -> CallLog.Calls.MISSED_TYPE
            }
            val values = ContentValues().apply {
                // Not a phone number: the Phone app shows it as the caller's name.
                put(CallLog.Calls.NUMBER, c.agentName)
                put(CallLog.Calls.CACHED_NAME, c.agentName)
                put(CallLog.Calls.TYPE, type)
                put(CallLog.Calls.DATE, c.createdAt)
                put(CallLog.Calls.DURATION, (c.durationMs ?: 0L) / 1000)
                put(CallLog.Calls.NEW, if (type == CallLog.Calls.MISSED_TYPE) 1 else 0)
            }
            try {
                context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
                logged += c.callId
                changed = true
            } catch (e: Exception) {
                Log.w(TAG, "cannot write the call log", e)
                return
            }
        }
        if (changed) {
            // Keep the set small: the relay lists 100 calls at most.
            val keep = calls.map { it.callId }.toSet()
            prefs.edit().putStringSet(KEY_LOGGED, logged.filter { it in keep }.toSet()).apply()
        }
    }

    fun clear() {
        _calls.value = emptyList()
        _seenUntil.value = 0L
        prefs.edit().remove(KEY_CALLS).remove(KEY_SEEN).remove(KEY_LOGGED).apply()
    }

    companion object {
        private const val TAG = "CallHistory"
        private const val KEY_CALLS = "calls"
        private const val KEY_SEEN = "seen_until"
        private const val KEY_LOGGED = "logged"
        private const val KEY_PHONE_LOG = "phone_log"
        private const val KEY_PHONE_LOG_SINCE = "phone_log_since"
        private val FINAL = setOf("ended", "missed", "declined", "canceled")

        fun isMissed(c: Call): Boolean = c.direction == "agent_to_user" && (c.state == "missed" || c.state == "canceled")
    }
}
