package dev.agentring.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import dev.agentring.core.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

/** Searches every message on this phone (accents and case don't matter). */
@Composable
fun SearchScreen(pairing: Pairing, onOpen: (String) -> Unit) {
    val messages by Graph.messages.messages.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val needle = fold(q.trim())
    val hits = remember(messages, needle) {
        if (needle.length < 2) emptyList() else messages.filter { fold(it.text).contains(needle) }.asReversed().take(200)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, null) } },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus).testTag("search_input"),
        )
        if (needle.length >= 2 && hits.isEmpty()) {
            Text(stringResource(R.string.search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits, key = { it.msgId }) { m ->
                val conv = m.convId
                val name = Graph.directory.convName(conv, pairing.agentName)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(conv) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ConvAvatar(conv, name, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(shortTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(highlight(m.text, needle), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The matched words in bold, starting a little before the first match so it shows. */
@Composable
private fun highlight(text: String, needle: String) = buildAnnotatedString {
    val flat = text.replace('\n', ' ')
    val folded = fold(flat)
    val first = folded.indexOf(needle)
    val start = (first - 30).coerceAtLeast(0).let { if (it > 0) flat.indexOf(' ', it).let { sp -> if (sp in 0 until first) sp + 1 else it } else 0 }
    if (start > 0) append("…")
    var pos = start
    while (true) {
        val i = folded.indexOf(needle, pos)
        if (i < 0) break
        append(flat.substring(pos, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(flat.substring(i, i + needle.length)) }
        pos = i + needle.length
    }
    append(flat.substring(pos))
}

/** What the agents have scheduled; the user can cancel any of it. */
@Composable
fun RemindersScreen(pairing: Pairing) {
    var list by remember { mutableStateOf<List<Reminder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val r = withContext(Dispatchers.IO) { runCatching { Graph.api()!!.listReminders() } }
        r.onSuccess { list = it; error = null }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.fillMaxSize().testTag("reminders_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(stringResource(R.string.reminders_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            error?.let { Text(stringResource(R.string.action_failed, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
            if (list?.isEmpty() == true) {
                Text(stringResource(R.string.reminders_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp))
            }
        }
        items(list.orEmpty(), key = { it.remId }) { r ->
            val name = Graph.directory.nameOf(r.agentId, pairing.agentName)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ConvAvatar(r.conv, name, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text("$name · ${whenText(r.at)}", style = MaterialTheme.typography.titleSmall)
                    Text(r.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Graph.api()!!.cancelReminder(r.remId) } }
                        load()
                    }
                }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel_reminder)) }
            }
        }
    }
}

@Composable
private fun whenText(ms: Long): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val time = android.text.format.DateFormat.getTimeFormat(ctx).format(ms)
    return when {
        android.text.format.DateUtils.isToday(ms) -> "${stringResource(R.string.today)} $time"
        android.text.format.DateUtils.isToday(ms - android.text.format.DateUtils.DAY_IN_MILLIS) -> "${stringResource(R.string.tomorrow)} $time"
        else -> android.text.format.DateUtils.formatDateTime(ctx, ms, android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_ALL) + " $time"
    }
}
