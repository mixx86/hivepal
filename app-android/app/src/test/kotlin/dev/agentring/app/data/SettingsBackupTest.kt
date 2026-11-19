package dev.agentring.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory SharedPreferences, enough for the backup. */
private class MemPrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(k: String, d: String?) = map[k] as String? ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = (map[k] as Set<String>?)?.toMutableSet() ?: d
    override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
    override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
    override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
    override fun contains(k: String) = k in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
        override fun putString(k: String, v: String?) = apply { ops += { it[k] = v } }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { ops += { it[k] = v?.toSet() } }
        override fun putInt(k: String, v: Int) = apply { ops += { it[k] = v } }
        override fun putLong(k: String, v: Long) = apply { ops += { it[k] = v } }
        override fun putFloat(k: String, v: Float) = apply { ops += { it[k] = v } }
        override fun putBoolean(k: String, v: Boolean) = apply { ops += { it[k] = v } }
        override fun remove(k: String) = apply { ops += { it.remove(k) } }
        override fun clear() = apply { ops += { it.clear() } }
        override fun commit(): Boolean { ops.forEach { it(map) }; return true }
        override fun apply() { commit() }
    }
}

class SettingsBackupTest {
    private fun store() = mutableMapOf<String, MemPrefs>()

    @Test
    fun `settings travel, pairing and caches don't`() {
        val old = store()
        val of = { n: String -> old.getOrPut(n) { MemPrefs() } }
        of("hivepal_ui").map["lang"] = "es"
        of("hivepal_chats").map["pinned"] = setOf("agent:nubi")
        of("settings").map["use_telecom"] = false
        of("hivepal_subs").map.putAll(mapOf("page:grok" to "https://grok.com/dashboard/", "order" to "grok,webdock", "cache" to "[big]"))
        of("calls").map["n"] = 3
        of("agentring_calls").map["since"] = 1_795_000_000_000L
        of("agentring_state").map["device_jwt"] = "secret"
        val file = SettingsBackup.export(of, "0.7.1")
        assertFalse("the pairing must not be in the file", "secret" in file)
        assertFalse("caches stay out", "[big]" in file)

        val fresh = store()
        val nf = { n: String -> fresh.getOrPut(n) { MemPrefs() } }
        nf("hivepal_subs").map["cache"] = "[mine]"
        nf("hivepal_ui").map["lang"] = "en"
        SettingsBackup.import(nf, file)
        assertEquals("es", nf("hivepal_ui").map["lang"])
        assertEquals(setOf("agent:nubi"), nf("hivepal_chats").map["pinned"])
        assertEquals(false, nf("settings").map["use_telecom"])
        assertEquals(3, nf("calls").map["n"])
        assertEquals(1_795_000_000_000L, nf("agentring_calls").map["since"])
        assertEquals("https://grok.com/dashboard/", nf("hivepal_subs").map["page:grok"])
        assertEquals("[mine]", nf("hivepal_subs").map["cache"]) // this install's cache is kept
        assertTrue(nf("agentring_state").map.isEmpty())
    }

    @Test
    fun `refuses what isn't a settings file`() {
        val of = { _: String -> MemPrefs() }
        for (bad in listOf("hello", """{"format":"other"}""", """{"format":"hivepal-settings","v":99,"files":{}}""")) {
            try {
                SettingsBackup.import(of, bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
