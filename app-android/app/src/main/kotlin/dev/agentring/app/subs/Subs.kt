package dev.agentring.app.subs

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.core.PutSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Subscriptions read in the app: the user logs in to each service in an in-app browser and picks the
 * billing page; the app re-reads those pages (same cookies, no window) every day and sends what it found
 * to the relay. Nothing but plan, status, date and price leaves the phone.
 */
object Subs {
    private const val PREFS = "hivepal_subs"
    private const val CHANNEL = "subscriptions"
    const val WARN_DAYS = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The billing page chosen for a service, if it's connected. */
    fun page(ctx: Context, id: String): String? = prefs(ctx).getString("page:$id", null)

    fun connect(ctx: Context, id: String, url: String) {
        prefs(ctx).edit().putString("page:$id", url).apply()
        CookieManager.getInstance().flush()
    }

    fun disconnect(ctx: Context, id: String) {
        prefs(ctx).edit().remove("page:$id").apply()
    }

    /** Reads every connected service and sends the result. Returns how many were read. */
    suspend fun syncAll(ctx: Context): Int {
        var n = 0
        for (s in BillingParser.SERVICES) {
            if (page(ctx, s.id) != null) {
                sync(ctx, s)
                n++
            }
        }
        return n
    }

    suspend fun sync(ctx: Context, s: Service) {
        val url = page(ctx, s.id) ?: return
        val text = readPage(ctx, url)
        val b = if (text == null) Billing("error", error = "load") else BillingParser.parse(text)
        val error = when (b.error) {
            null -> null
            "login" -> ctx.getString(R.string.subs_err_login, s.name)
            "challenge" -> ctx.getString(R.string.subs_err_challenge)
            else -> ctx.getString(R.string.subs_err_load)
        }
        val api = Graph.api() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                api.putSubscription(s.id, PutSubscription(s.name, b.status, b.plan, b.until, b.price, url, error))
            }
        }
        b.until?.let { warnIfSoon(ctx, s, it, b.status == "ending", b.price) }
    }

    /** The visible text of a page, loaded in a WebView that isn't on screen (with the app's cookies). */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun readPage(ctx: Context, url: String, settleMs: Long = 7000): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(45_000) {
            val web = WebView(ctx.applicationContext)
            try {
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                // A real size, so the page lays out as on a phone.
                web.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
                web.layout(0, 0, 1080, 2200)
                suspendCancellableCoroutine { cont ->
                    var done = false
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            if (done) return
                            done = true
                            // Single-page apps fill the page after "finished": give them a moment.
                            Handler(Looper.getMainLooper()).postDelayed({
                                view.evaluateJavascript("document.body ? document.body.innerText : ''") { js ->
                                    val text = runCatching { JSONArray("[$js]").getString(0) }.getOrNull()
                                    if (cont.isActive) cont.resume(text)
                                }
                            }, settleMs)
                        }
                    }
                    web.loadUrl(url)
                }
            } finally {
                web.destroy()
            }
        }
    }

    private fun warnIfSoon(ctx: Context, s: Service, until: Long, ending: Boolean, price: String?) {
        val days = ((until - System.currentTimeMillis() + 86_399_999) / 86_400_000).toInt()
        if (days !in 0..WARN_DAYS) return
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, ctx.getString(R.string.nav_subs), android.app.NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val text = ctx.getString(if (ending) R.string.subs_warn_ends else R.string.subs_warn_renews, s.name, days) +
            (price?.let { " · $it" } ?: "")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(ctx.getString(R.string.nav_subs))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(("subs:" + s.id).hashCode(), n) }
    }

    /** Once a day, when there's a network. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SubsWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("subscriptions", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class SubsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (Graph.api() == null) return Result.success()
        Subs.syncAll(applicationContext)
        return Result.success()
    }
}
