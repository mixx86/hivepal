package dev.agentring.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Per-chat choices on this phone (Hivepal): pinned chats go first, muted ones don't notify. */
class ChatPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hivepal_chats", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(prefs.getStringSet(KEY_PINNED, emptySet())!!.toSet())
    val pinned: StateFlow<Set<String>> = _pinned

    private val _muted = MutableStateFlow(prefs.getStringSet(KEY_MUTED, emptySet())!!.toSet())
    val muted: StateFlow<Set<String>> = _muted

    fun togglePinned(conv: String) = toggle(_pinned, KEY_PINNED, conv)
    fun toggleMuted(conv: String) = toggle(_muted, KEY_MUTED, conv)

    fun isMuted(conv: String) = conv in _muted.value

    private fun toggle(flow: MutableStateFlow<Set<String>>, key: String, conv: String) {
        val next = if (conv in flow.value) flow.value - conv else flow.value + conv
        flow.value = next
        prefs.edit().putStringSet(key, next).apply()
    }

    private companion object {
        const val KEY_PINNED = "pinned"
        const val KEY_MUTED = "muted"
    }
}
