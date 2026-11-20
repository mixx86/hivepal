package dev.agentring.app.data

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Hivepal: the app's own settings to a file and back, so moving to another build (another signing
 * key means uninstalling) or another phone keeps them. What's in it: language, app and call
 * settings, pinned and muted chats, the call log setting, and the connected subscription services
 * with their pages. What isn't: the pairing (its keys open the relay: pair again with the QR), the
 * push channel (tied to this install's registration with the distributor), caches, and the
 * logins inside the subscriptions' browser (Android can't hand those cookies over reliably).
 */
object SettingsBackup {
    const val FORMAT = "hivepal-settings"
    const val VERSION = 1

    /** The preference files that travel, and the keys in them that don't (caches). */
    private val FILES = mapOf(
        "hivepal_ui" to emptySet(),
        "settings" to emptySet(),
        "calls" to emptySet(),
        "hivepal_chats" to emptySet(),
        "agentring_calls" to emptySet(),
        "hivepal_subs" to setOf("cache", "cache_at", "last_sync"),
    )

    fun export(ctx: Context, appVersion: String): String = export({ ctx.getSharedPreferences(it, Context.MODE_PRIVATE) }, appVersion)

    fun import(ctx: Context, text: String) = import({ ctx.getSharedPreferences(it, Context.MODE_PRIVATE) }, text)

    fun export(prefsOf: (String) -> android.content.SharedPreferences, appVersion: String): String = buildJsonObject {
        put("format", FORMAT)
        put("v", VERSION)
        put("app_version", appVersion)
        put("files", JsonObject(FILES.mapValues { (name, skip) ->
            JsonObject(prefsOf(name).all
                .filterKeys { it !in skip }
                .mapNotNull { (k, v) -> encode(v)?.let { k to it } }
                .toMap())
        }))
    }.toString()

    /** Replaces those settings with the file's. Throws IllegalArgumentException with a readable reason. */
    fun import(prefsOf: (String) -> android.content.SharedPreferences, text: String) {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("This isn't a Hivepal settings file")
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "This isn't a Hivepal settings file" }
        val v = root["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        require(v in 1..VERSION) { "This file comes from a newer Hivepal: update the app first" }
        val files = root["files"]?.jsonObject ?: throw IllegalArgumentException("The file has no settings")
        for ((name, skip) in FILES) {
            val values = files[name]?.jsonObject ?: continue
            val prefs = prefsOf(name)
            val e = prefs.edit()
            // Keep this install's caches; replace the rest.
            prefs.all.keys.filter { it !in skip }.forEach { e.remove(it) }
            for ((k, el) in values) {
                if (k in skip) continue
                put(e, k, el)
            }
            e.commit()
        }
    }

    private fun encode(v: Any?): JsonElement? = when (v) {
        is Boolean -> JsonObject(mapOf("t" to JsonPrimitive("b"), "v" to JsonPrimitive(v)))
        is Int -> JsonObject(mapOf("t" to JsonPrimitive("i"), "v" to JsonPrimitive(v)))
        is Long -> JsonObject(mapOf("t" to JsonPrimitive("l"), "v" to JsonPrimitive(v)))
        is Float -> JsonObject(mapOf("t" to JsonPrimitive("f"), "v" to JsonPrimitive(v)))
        is String -> JsonObject(mapOf("t" to JsonPrimitive("s"), "v" to JsonPrimitive(v)))
        is Set<*> -> JsonObject(mapOf("t" to JsonPrimitive("ss"), "v" to JsonArray(v.filterIsInstance<String>().map { JsonPrimitive(it) })))
        else -> null
    }

    private fun put(e: android.content.SharedPreferences.Editor, k: String, el: JsonElement) {
        val o = el as? JsonObject ?: return
        val value = o["v"] ?: JsonNull
        when (o["t"]?.jsonPrimitive?.content) {
            "b" -> value.jsonPrimitive.booleanOrNull?.let { e.putBoolean(k, it) }
            "i" -> value.jsonPrimitive.content.toIntOrNull()?.let { e.putInt(k, it) }
            "l" -> value.jsonPrimitive.content.toLongOrNull()?.let { e.putLong(k, it) }
            "f" -> value.jsonPrimitive.content.toFloatOrNull()?.let { e.putFloat(k, it) }
            "s" -> e.putString(k, value.jsonPrimitive.content)
            "ss" -> e.putStringSet(k, value.jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }
}
