package dev.agentring.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.core.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY = 86_400_000L

/** Days until `until`, rounded up (today = 0 once it's past). */
private fun daysLeft(until: Long, now: Long = System.currentTimeMillis()): Int =
    if (until <= now) 0 else ((until - now + DAY - 1) / DAY).toInt()

private fun urgency(s: Subscription, days: Int?): Color = when {
    s.status == "error" -> Color(0xFFFFD60A)
    s.status == "ended" -> Color(0xFFFF6B8B)
    days == null -> Retro.Muted
    days <= 3 -> Color(0xFFFF6B8B)
    days <= 7 -> Color(0xFFFFD60A)
    else -> Retro.Cyan
}

/**
 * Subscriptions, read by the app itself from each service's billing page: connect a service once (log in
 * inside the app and pick the page), then it's read every day. A ring per service empties as the date comes.
 */
@Composable
fun SubscriptionsScreen() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Subscription>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }  // bumps when a service is connected or removed
    suspend fun load() {
        val r = withContext(Dispatchers.IO) { runCatching { Graph.api()!!.listSubscriptions() } }
        r.onSuccess { list = it; error = null }.onFailure { error = it.message }
    }
    fun refresh(only: dev.agentring.app.subs.Service? = null) {
        scope.launch {
            busy = true
            if (only != null) dev.agentring.app.subs.Subs.sync(ctx, only) else dev.agentring.app.subs.Subs.syncAll(ctx)
            load()
            busy = false
        }
    }
    LaunchedEffect(Unit) { load() }
    // Connecting happens in its own screen (ConnectActivity): log in there and pick the billing page.
    val connect = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        version++
        val id = res.data?.getStringExtra(dev.agentring.app.subs.ConnectActivity.EXTRA_ID)
        dev.agentring.app.subs.BillingParser.SERVICES.firstOrNull { it.id == id }?.let { refresh(it) }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("subscriptions_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.subs_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            error?.let { Text(stringResource(R.string.action_failed, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            androidx.compose.material3.FilledTonalButton(
                onClick = { refresh() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("subs_refresh"),
            ) { Text(if (busy) stringResource(R.string.subs_reading) else stringResource(R.string.subs_refresh)) }
        }
        itemsIndexed(list.orEmpty(), key = { _, s -> s.subId }) { i, s -> SubscriptionCard(s, i, Modifier.animateItem()) }
        item {
            Text(stringResource(R.string.subs_services).uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        }
        items(dev.agentring.app.subs.BillingParser.SERVICES, key = { "svc:" + it.id + version }) { svc ->
            val page = dev.agentring.app.subs.Subs.page(ctx, svc.id)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(svc.name, style = MaterialTheme.typography.titleMedium)
                    Text(page?.removePrefix("https://")?.take(42) ?: stringResource(R.string.subs_not_connected),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                androidx.compose.material3.OutlinedButton(onClick = { connect.launch(dev.agentring.app.subs.ConnectActivity.intent(ctx, svc)) }, modifier = Modifier.testTag("connect_${svc.id}")) {
                    Text(stringResource(if (page == null) R.string.subs_connect else R.string.subs_change))
                }
                if (page != null) {
                    androidx.compose.material3.TextButton(onClick = {
                        dev.agentring.app.subs.Subs.disconnect(ctx, svc.id)
                        version++
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { Graph.api()!!.deleteSubscription(svc.id) } }
                            load()
                        }
                    }) { Text(stringResource(R.string.subs_remove)) }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(s: Subscription, index: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val days = s.until?.let { daysLeft(it) }
    val color = urgency(s, days)
    // The ring fills in on appear, one card after another.
    val fill = remember { Animatable(0f) }
    val target = when {
        days == null -> 0f
        else -> (days / 31f).coerceIn(0.04f, 1f)
    }
    LaunchedEffect(target) {
        delay(80L * index)
        fill.animateTo(target, tween(900, easing = FastOutSlowInEasing))
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .bouncyClick(enabled = s.url != null) {
                s.url?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
            },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(72.dp).then(if (days != null && days <= 3) Modifier.breathingGlow(color, 1.4f) else Modifier),
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 7.dp.toPx()
                    val inset = w / 2
                    val arc = Size(size.width - w, size.height - w)
                    drawArc(color.copy(alpha = 0.15f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                    drawArc(color, -90f, 360f * fill.value, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(days?.toString() ?: "?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(stringResource(R.string.subs_days), style = MaterialTheme.typography.labelSmall, color = Retro.Muted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOfNotNull(s.name, s.plan).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                val whenText = s.until?.let {
                    val date = android.text.format.DateUtils.formatDateTime(ctx, it,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
                    stringResource(if (s.status == "ending" || s.status == "ended") R.string.subs_ends else R.string.subs_renews, date)
                } ?: stringResource(R.string.subs_no_date)
                Text(whenText, style = MaterialTheme.typography.bodyMedium, color = color)
                s.price?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val checked = android.text.format.DateUtils.getRelativeTimeSpanString(
                    s.checkedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                Text(
                    if (s.status == "error") stringResource(R.string.subs_error, s.error ?: "", checked)
                    else stringResource(R.string.subs_checked, checked),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.status == "error") Color(0xFFFFD60A) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
