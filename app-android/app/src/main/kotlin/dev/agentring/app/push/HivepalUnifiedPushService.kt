package dev.agentring.app.push

import android.content.Context
import android.util.Log
import dev.agentring.app.Graph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import kotlin.concurrent.thread

/**
 * Hivepal: push without Google. A UnifiedPush distributor (ntfy, NextPush…) gives the app an endpoint;
 * the relay POSTs each push there and the distributor delivers it here. The body is the same signed
 * map FCM carries, as JSON, so it goes through the same [PushHandler] (signature checked there).
 */
class HivepalUnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        UnifiedPushSetup.saveEndpoint(applicationContext, endpoint.url)
        thread(name = "up-endpoint") { UnifiedPushSetup.sendEndpoint(applicationContext) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val data = UnifiedPushSetup.parse(message.content) ?: return Unit.also { Log.w(TAG, "push that isn't a Hivepal payload") }
        // Off the caller's thread: the handler may fetch a truncated message.
        thread(name = "up-push") { PushHandler.handle(applicationContext, data) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "UnifiedPush registration failed: $reason")
        UnifiedPushSetup.forget(applicationContext)
    }

    override fun onUnregistered(instance: String) {
        UnifiedPushSetup.forget(applicationContext)
    }

    companion object {
        const val TAG = "HivepalUP"
    }
}

/** The app's UnifiedPush choice: which distributor, the endpoint, and telling the relay. */
object UnifiedPushSetup {
    private const val PREFS = "hivepal_push"
    private const val KEY_ENDPOINT = "up_endpoint"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun endpoint(ctx: Context): String? = prefs(ctx).getString(KEY_ENDPOINT, null)

    fun distributors(ctx: Context): List<String> = UnifiedPush.getDistributors(ctx)

    fun saveEndpoint(ctx: Context, url: String) = prefs(ctx).edit().putString(KEY_ENDPOINT, url).apply()

    /** Blocking: gives the relay the current endpoint (again after re-pairing, for instance). */
    fun sendEndpoint(ctx: Context): Boolean {
        val url = endpoint(ctx) ?: return false
        return runCatching { Graph.api()?.setUnifiedPush(url) }
            .onFailure { Log.w(HivepalUnifiedPushService.TAG, "relay refused the endpoint: ${it.message}") }
            .isSuccess
    }

    /** Uses the distributor already chosen, or the default one; false when there's none installed. */
    fun enable(activity: android.app.Activity, done: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity, "default", "Hivepal", null)
            done(ok)
        }
    }

    /** Back to FCM: unregister, forget the endpoint and tell the relay. Blocking (network). */
    fun disable(ctx: Context) {
        runCatching { UnifiedPush.unregister(ctx, "default") }
        forget(ctx)
    }

    fun forget(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ENDPOINT).apply()
        thread(name = "up-clear") { runCatching { Graph.api()?.clearUnifiedPush() } }
    }

    /** The relay's JSON map of strings, or null. */
    fun parse(content: ByteArray): Map<String, String>? = runCatching {
        val obj = Json.parseToJsonElement(content.decodeToString()) as JsonObject
        obj.mapValues { (_, v) -> (v as JsonPrimitive).content }
    }.getOrNull()
}
