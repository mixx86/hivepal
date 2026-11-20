package dev.agentring.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.agentring.app.BuildConfig
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import dev.agentring.app.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { WELCOME, HOW, PAIR, PUSH, CALLS, AGENTS, DONE }

/** Where the user's relay lives; only changes which instructions the tutorial shows. */
private enum class Setup { CLOUD, SELF_HOSTED }

/** True when the pairing points at the hosted service this build links to. */
internal fun Pairing.isCloud(): Boolean {
    val cloud = Uri.parse(BuildConfig.CLOUD_URL.ifEmpty { return false }).host ?: return false
    return Uri.parse(relayUrl).host.equals(cloud, ignoreCase = true)
}

/** The hosted service's dashboard (usage, plan), or null when the relay is self-hosted. */
internal fun Pairing.dashboardUrl(): String? = if (isCloud()) BuildConfig.CLOUD_URL.trimEnd('/') + "/app#usage" else null

@Composable
fun OnboardingFlow(paired: Pairing?, onFinished: () -> Unit) {
    var step by rememberSaveable {
        mutableStateOf(if (paired?.pushConfig == null && paired != null) Step.PUSH else if (paired != null) Step.CALLS else Step.WELCOME)
    }
    var setup by rememberSaveable {
        mutableStateOf(if (paired?.isCloud() == true || (paired == null && BuildConfig.CLOUD_URL.isNotEmpty())) Setup.CLOUD else Setup.SELF_HOSTED)
    }
    // Once paired there is no going back to the pairing pages.
    val canGoBack = step == Step.HOW || step == Step.PAIR
    // The agents step goes back through its own questions.
    var deliveryBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Builds without a hosted service skip the "cloud or own server" question.
    val offerCloud = BuildConfig.CLOUD_URL.isNotEmpty()
    // Before pairing, reserve the optional step; once the relay answers, hide it for Firebase.
    val needsPushSetup = paired?.pushConfig == null
    fun displayedIndex(s: Step) = if (!needsPushSetup && s.ordinal > Step.PUSH.ordinal) s.ordinal - 1 else s.ordinal
    val displayedTotal = Step.entries.size - 1 - if (needsPushSetup) 0 else 1
    fun back() {
        step = if (step == Step.PAIR && !offerCloud) Step.WELCOME else Step.entries[step.ordinal - 1]
    }
    BackHandler(enabled = canGoBack) { back() }
    // Opened from a pairing link: straight to the pairing page, which asks before pairing.
    val pendingLink by Graph.pendingPairLink.collectAsState()
    LaunchedEffect(pendingLink) {
        if (pendingLink != null && step.ordinal < Step.PAIR.ordinal) step = Step.PAIR
    }

    Scaffold(
        topBar = {
            if (step != Step.WELCOME) {
                TutorialTopBar(
                    index = displayedIndex(step),
                    total = displayedTotal,
                    onBack = if (canGoBack) ::back else if (step == Step.AGENTS) deliveryBack else null,
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 4 } + fadeOut())
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
            label = "onboarding",
        ) { s ->
            when (s) {
                Step.WELCOME -> WelcomePage(onNext = { step = if (offerCloud) Step.HOW else Step.PAIR })
                Step.HOW -> HowPage(setup) {
                    setup = it
                    step = Step.PAIR
                }
                Step.PAIR -> PairPage(setup, onPaired = {
                    step = if (Graph.pairing.current?.pushConfig == null) Step.PUSH else Step.CALLS
                })
                Step.PUSH -> PushSetupPage(onReady = { step = Step.CALLS })
                Step.CALLS -> CallsPage(onNext = { step = Step.AGENTS })
                Step.AGENTS -> AgentsDeliveryPage(onNext = { deliveryBack = null; step = Step.DONE }, setBack = { deliveryBack = it })
                Step.DONE -> DonePage(Graph.pairing.current ?: paired, onFinished)
            }
        }
    }
}

/** Set up the wake-up channel while the pairing result still tells us Firebase is unavailable. */
@Composable
private fun PushSetupPage(onReady: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    val setup = dev.agentring.app.push.UnifiedPushSetup
    var distributors by remember { mutableStateOf(setup.distributors(ctx)) }
    var endpoint by remember { mutableStateOf(setup.endpoint(ctx)) }
    var attemptedDistributor by remember { mutableStateOf<String?>(null) }
    var registering by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // The user may install ntfy from this page. Its package appears without restarting Hivepal.
    LaunchedEffect(Unit) {
        while (!ready) {
            distributors = setup.distributors(ctx)
            endpoint = setup.endpoint(ctx)
            delay(1_000)
        }
    }

    fun startRegistration() {
        if (activity == null || registering) return
        registering = true
        failed = false
        setup.enable(activity) { accepted ->
            registering = false
            failed = !accepted
        }
    }

    val distributorKey = distributors.sorted().joinToString(",")
    LaunchedEffect(distributorKey, endpoint) {
        if (endpoint == null && distributorKey.isNotEmpty() && attemptedDistributor != distributorKey) {
            attemptedDistributor = distributorKey
            startRegistration()
        }
    }
    LaunchedEffect(endpoint) {
        if (endpoint != null && !ready && !checking) {
            checking = true
            ready = withContext(Dispatchers.IO) { setup.sendEndpoint(ctx) }
            failed = !ready
            checking = false
        }
    }

    fun installNtfy() {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=io.heckel.ntfy"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=io.heckel.ntfy"))
        runCatching { ctx.startActivity(store) }.getOrElse { runCatching { ctx.startActivity(web) } }
    }

    Page(bottom = {
        when {
            ready -> PrimaryButton(stringResource(R.string.continue_), "push_continue", onClick = onReady)
            distributors.isEmpty() -> OutlinedButton(
                onClick = ::installNtfy,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("install_ntfy"),
            ) { Text(stringResource(R.string.push_install_ntfy), style = MaterialTheme.typography.titleMedium) }
            failed && !registering && !checking -> OutlinedButton(
                onClick = ::startRegistration,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("retry_unifiedpush"),
            ) { Text(stringResource(R.string.push_retry), style = MaterialTheme.typography.titleMedium) }
        }
        // Never a dead end: without push the app still works while it's open, and
        // Settings → Notifications can set it up later.
        if (!ready) {
            TextButton(onClick = onReady, modifier = Modifier.testTag("push_later")) {
                Text(stringResource(R.string.push_later))
            }
        }
    }) {
        HeroBadge(rememberVectorPainter(Icons.Filled.Notifications), size = 88.dp, pulse = registering || checking)
        Text(stringResource(R.string.push_setup_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.push_setup_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        BrandCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = when {
                    ready -> R.string.push_ready
                    checking -> R.string.push_checking
                    registering -> R.string.push_registering_up
                    distributors.isEmpty() -> R.string.push_no_distributor
                    failed -> R.string.push_failed
                    else -> R.string.push_registering_up
                }
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
                if (distributors.isEmpty()) {
                    Text(stringResource(R.string.push_install_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // The other way: Firebase, set up on the relay (then pair again). Offered, not pushed.
        if (!ready) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.push_prefer_fcm), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.push_option_fcm_missing), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FIREBASE_GUIDE))) } },
                    modifier = Modifier.testTag("push_fcm_howto"),
                ) { Text(stringResource(R.string.push_fcm_howto)) }
            }
        }
    }
}

@Composable
private fun TutorialTopBar(index: Int, total: Int, onBack: (() -> Unit)?) {
    val progress by animateFloatAsState(index / total.toFloat(), label = "progress")
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.step_of, index, total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 20.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(6.dp),
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            drawStopIndicator = {},
        )
    }
}

/** Scrollable page body with a pinned bottom action area. */
@Composable
private fun Page(
    bottom: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { bottom() }
    }
}

@Composable
private fun PrimaryButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag(tag),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

// ---------------------------------------------------------------- pages

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Page(bottom = {
        PrimaryButton(stringResource(R.string.get_started), "get_started", onClick = onNext)
        Text(
            stringResource(R.string.welcome_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }) {
        LanguageToggle()
        Spacer(Modifier.height(8.dp))
        HiveLogo(size = 190.dp)
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.appear(2).testTag("title"),
        )
        Text(
            stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.appear(3),
        )
        Spacer(Modifier.height(4.dp))
        Feature(painterResource(R.drawable.ic_chat), R.string.feature_messages_title, R.string.feature_messages_body, order = 5)
        Feature(painterResource(R.drawable.ic_call), R.string.feature_calls_title, R.string.feature_calls_body, order = 6)
        Feature(painterResource(R.drawable.ic_mic), R.string.feature_callback_title, R.string.feature_callback_body, order = 7)
    }
}

@Composable
private fun Feature(icon: Painter, title: Int, body: Int, order: Int = 0) {
    Row(Modifier.fillMaxWidth().appear(order), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HowPage(current: Setup, onPick: (Setup) -> Unit) {
    Page(bottom = {}) {
        PageHeader(R.string.how_title, R.string.how_body)
        SetupOption(
            icon = painterResource(R.drawable.ic_cloud),
            title = stringResource(R.string.how_cloud_title),
            badge = stringResource(R.string.how_cloud_badge),
            body = stringResource(R.string.how_cloud_body),
            selected = current == Setup.CLOUD,
            tag = "setup_cloud",
        ) { onPick(Setup.CLOUD) }
        SetupOption(
            icon = painterResource(R.drawable.ic_server),
            title = stringResource(R.string.how_self_title),
            badge = stringResource(R.string.how_self_badge),
            body = stringResource(R.string.how_self_body),
            selected = current == Setup.SELF_HOSTED,
            tag = "setup_self",
        ) { onPick(Setup.SELF_HOSTED) }
        Text(
            stringResource(R.string.how_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SetupOption(icon: Painter, title: String, badge: String, body: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    BrandCard(selected = selected, onClick = onClick, modifier = Modifier.appear(3).selectBounce(selected).testTag(tag)) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageHeader(title: Int, body: Int, icon: Painter? = null, success: Boolean = false) {
    if (success) SuccessBadge(size = 80.dp)
    else if (icon != null) Box(Modifier.appear(0)) { HeroBadge(icon, size = 80.dp) }
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().appear(1).testTag("title"),
    )
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().appear(2),
    )
}

@Composable
private fun PairPage(setup: Setup, onPaired: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    // The paste field opens below the steps, out of sight on most phones: scroll to it and focus it.
    val pasteInView = remember { BringIntoViewRequester() }
    val pasteFocus = remember { FocusRequester() }
    LaunchedEffect(showPaste) {
        if (showPaste) {
            delay(300) // let AnimatedVisibility lay the field out first
            pasteInView.bringIntoView()
            runCatching { pasteFocus.requestFocus() }
        }
    }

    fun pair(raw: String) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) { Graph.pairWith(raw) }
                onPaired()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }
    // A pairing link: show which relay and pair only if the user says so (any web page can open one).
    val pendingLink by Graph.pendingPairLink.collectAsState()
    val linkRelay = remember(pendingLink) {
        pendingLink?.let { runCatching { android.net.Uri.parse(dev.agentring.core.QrPayload.parse(it).relayUrl).host }.getOrNull() ?: "" }
    }
    if (pendingLink != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { Graph.pendingPairLink.value = null },
            title = { Text(stringResource(R.string.pair_link_title)) },
            text = { Text(stringResource(R.string.pair_link_body, linkRelay ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    val link = pendingLink
                    Graph.pendingPairLink.value = null
                    link?.let(::pair)
                }, modifier = Modifier.testTag("pair_link_confirm")) { Text(stringResource(R.string.pair_link_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { Graph.pendingPairLink.value = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val scanFailed = stringResource(R.string.scan_failed, "%s")
    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(::pair) }
            .addOnFailureListener { e -> error = scanFailed.replace("%s", e.message ?: "") }
    }

    Page(bottom = {
        Button(
            onClick = ::scan,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("scan_qr"),
        ) {
            Icon(painterResource(R.drawable.ic_qr_scan), null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.scan_qr), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { showPaste = !showPaste }, modifier = Modifier.testTag("show_paste")) {
            Text(stringResource(R.string.or_paste))
        }
    }) {
        PageHeader(R.string.pair_title, if (setup == Setup.CLOUD) R.string.pair_body_cloud else R.string.pair_body_self, painterResource(R.drawable.ic_qr_scan))
        BrandCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (setup == Setup.CLOUD) {
                    NumberedStep(1, stringResource(R.string.pair_cloud_1), stringResource(R.string.pair_cloud_1_body)) {
                        if (BuildConfig.CLOUD_URL.isNotEmpty()) {
                            TextButton(
                                // Straight to the dashboard (sign-in first if needed), where the pairing code is.
                                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.CLOUD_URL.trimEnd('/') + "/app"))) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                modifier = Modifier.testTag("use_cloud"),
                            ) {
                                Text(stringResource(R.string.open_cloud, Uri.parse(BuildConfig.CLOUD_URL).host ?: BuildConfig.CLOUD_URL))
                                Spacer(Modifier.width(4.dp))
                                Icon(painterResource(R.drawable.ic_open_in_new), null, Modifier.size(16.dp))
                            }
                        }
                    }
                    NumberedStep(2, stringResource(R.string.pair_cloud_2), stringResource(R.string.pair_cloud_2_body))
                    NumberedStep(3, stringResource(R.string.pair_scan_step), stringResource(R.string.pair_scan_step_body))
                } else {
                    NumberedStep(1, stringResource(R.string.pair_self_1), stringResource(R.string.pair_self_1_body)) {
                        CommandBlock("docker compose up -d")
                    }
                    NumberedStep(2, stringResource(R.string.pair_self_2), stringResource(R.string.pair_self_2_body)) {
                        CommandBlock("hivepal pair")
                    }
                    NumberedStep(3, stringResource(R.string.pair_scan_step), stringResource(R.string.pair_scan_step_body))
                }
            }
        }

        AnimatedVisibility(visible = showPaste || code.isNotEmpty() || error != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.pairing_code)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(pasteInView).focusRequester(pasteFocus).testTag("pairing_code"),
                )
                OutlinedButton(
                    onClick = { pair(code) },
                    enabled = code.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pair_button"),
                ) { Text(stringResource(R.string.pair)) }
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.pairing))
            }
        }
        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("pair_error"))
                }
            }
        }
    }
}

@Composable
private fun CallsPage(onNext: () -> Unit) {
    val ready = rememberCallsReady()
    Page(bottom = {
        PrimaryButton(stringResource(if (ready) R.string.continue_ else R.string.calls_later), "calls_next", onClick = onNext)
    }) {
        PageHeader(R.string.calls_title, R.string.calls_body, rememberVectorPainter(Icons.Filled.Notifications))
        CallReadinessCard(showHeader = false)
    }
}

/** Hivepal: how agents hear from you (email, mailboxes) and whether bots remember; may be skipped. */
/** The questions of the "how your agents hear from you" step, one per page. */
private enum class Ask { INTRO, CHANNELS, EMAIL, MAILBOX, MEMORY, SUMMARY }

/**
 * How agents hear from you, and whether they remember: a few short questions, each on its own page,
 * that lead to every option (email, mailboxes in the relay or your repository, memory as notes or a
 * database). Each answer is saved in the relay as it's given; Settings shows all of it at once.
 */
@Composable
private fun AgentsDeliveryPage(onNext: () -> Unit, setBack: ((() -> Unit)?) -> Unit) {
    val m = rememberDeliveryModel()
    var ask by rememberSaveable { mutableStateOf(Ask.INTRO) }
    // The questions answered so far, to go back through them (names, so it survives a rotation).
    var trail by rememberSaveable { mutableStateOf("") }
    var wantEmail by rememberSaveable { mutableStateOf(false) }
    var wantMailbox by rememberSaveable { mutableStateOf(false) }
    var external by rememberSaveable { mutableStateOf(false) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    val s = m.settings
    LaunchedEffect(s != null) {
        if (s != null && !seeded) {
            seeded = true
            wantEmail = s.email.configured
            wantMailbox = s.mailboxes.allAgents
            external = s.mailboxRepo.url != null
        }
    }
    fun go(to: Ask) {
        trail = if (trail.isEmpty()) ask.name else "$trail,${ask.name}"
        ask = to
    }
    fun back() {
        val steps = trail.split(',')
        ask = Ask.valueOf(steps.last())
        trail = steps.dropLast(1).joinToString(",")
    }
    val canGoBack = trail.isNotEmpty()
    BackHandler(enabled = canGoBack) { back() }
    SideEffect { setBack(if (canGoBack) ::back else null) }

    val skip: @Composable () -> Unit = {
        androidx.compose.material3.TextButton(onClick = onNext, modifier = Modifier.testTag("delivery_skip")) {
            Text(stringResource(R.string.delivery_skip))
        }
    }
    if (s == null) {
        // An older relay doesn't have it: nothing to ask.
        Page(bottom = { PrimaryButton(stringResource(R.string.continue_), "delivery_next", onClick = onNext); skip() }) {
            PageHeader(R.string.delivery_title, R.string.delivery_intro_body, rememberVectorPainter(Icons.Filled.Email))
            Text(
                m.loadError?.let { stringResource(R.string.delivery_unavailable, it) } ?: stringResource(R.string.loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    AnimatedContent(
        targetState = ask,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 4 } + fadeOut())
        },
        label = "delivery",
    ) { a ->
        when (a) {
            Ask.INTRO -> Page(bottom = { skip() }) {
                PageHeader(R.string.delivery_title, R.string.delivery_intro_body, rememberVectorPainter(Icons.Filled.Email))
                Text(
                    stringResource(R.string.delivery_intro_question),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).appear(3),
                )
                ChoiceCard(
                    stringResource(R.string.delivery_always), stringResource(R.string.delivery_always_body),
                    selected = false, tag = "delivery_always", order = 4,
                ) { go(Ask.MEMORY) }
                ChoiceCard(
                    stringResource(R.string.delivery_sometimes), stringResource(R.string.delivery_sometimes_body),
                    selected = false, tag = "delivery_sometimes", order = 5,
                ) { go(Ask.CHANNELS) }
                Hint(R.string.delivery_change_later)
            }

            Ask.CHANNELS -> Page(bottom = {
                PrimaryButton(stringResource(R.string.continue_), "delivery_next", enabled = (wantEmail || wantMailbox) && !m.busy) {
                    if (wantMailbox != s.mailboxes.allAgents) m.change({ it.setMailboxesForAll(wantMailbox) })
                    go(if (wantEmail) Ask.EMAIL else Ask.MAILBOX)
                }
            }) {
                PageHeader(R.string.delivery_channels_title, R.string.delivery_channels_body)
                ChoiceCard(
                    stringResource(R.string.delivery_email), stringResource(R.string.delivery_email_body),
                    selected = wantEmail, tag = "delivery_email", multi = true, order = 3,
                ) { wantEmail = !wantEmail }
                ChoiceCard(
                    stringResource(R.string.delivery_mailboxes), stringResource(R.string.delivery_mailboxes_body),
                    selected = wantMailbox, tag = "delivery_mailboxes", multi = true, order = 4,
                ) { wantMailbox = !wantMailbox }
            }

            Ask.EMAIL -> Page(bottom = {
                val next = if (wantMailbox) Ask.MAILBOX else Ask.MEMORY
                PrimaryButton(stringResource(R.string.continue_), "delivery_next", enabled = s.email.configured && !m.busy) { go(next) }
                if (!s.email.configured) {
                    androidx.compose.material3.TextButton(onClick = { go(next) }, modifier = Modifier.testTag("delivery_not_now")) {
                        Text(stringResource(R.string.delivery_not_now))
                    }
                }
            }) {
                PageHeader(R.string.delivery_email_title, R.string.delivery_email_page_body)
                EmailForm(m, Modifier.appear(3))
            }

            Ask.MAILBOX -> Page(bottom = {
                PrimaryButton(stringResource(R.string.continue_), "delivery_next", enabled = !m.busy && (!external || s.mailboxRepo.url != null)) {
                    if (!external && s.mailboxRepo.url != null) m.change({ it.setMailboxRepo(null) })
                    go(Ask.MEMORY)
                }
            }) {
                PageHeader(R.string.delivery_where_title, R.string.delivery_where_body)
                ChoiceCard(
                    stringResource(R.string.delivery_repo_relay), stringResource(R.string.delivery_repo_relay_body),
                    selected = !external, tag = "delivery_repo_relay", badge = stringResource(R.string.recommended), order = 3,
                ) { external = false }
                ChoiceCard(
                    stringResource(R.string.delivery_repo_external), stringResource(R.string.delivery_repo_external_body),
                    selected = external, tag = "delivery_repo_external", order = 4,
                ) { external = true }
                if (external) RepoForm(m)
            }

            Ask.MEMORY -> Page(bottom = {
                PrimaryButton(stringResource(R.string.continue_), "delivery_next", enabled = !m.busy) { go(Ask.SUMMARY) }
            }) {
                PageHeader(R.string.delivery_memory_title, R.string.delivery_memory_page_body)
                MemoryChoices(m, cards = true)
            }

            Ask.SUMMARY -> Page(bottom = {
                PrimaryButton(stringResource(R.string.continue_), "delivery_done", onClick = onNext)
            }) {
                PageHeader(R.string.delivery_summary_title, R.string.delivery_summary_body, success = true)
                DeliverySummary(s)
                if (s.email.configured) Hint(R.string.delivery_summary_next_email)
                if (s.mailboxes.allAgents) Hint(R.string.delivery_summary_next_mailbox)
                Hint(R.string.delivery_change_later)
            }
        }
    }
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DonePage(pairing: Pairing?, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val cloud = pairing?.isCloud() == true
    Page(bottom = {
        PrimaryButton(stringResource(R.string.open_app), "continue", onClick = onFinished)
    }) {
        SuccessBadge(size = 88.dp)
        Text(
            stringResource(R.string.done_title, pairing?.agentName ?: "hivepal"),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.appear(2).testTag("title"),
        )
        Text(
            stringResource(R.string.done_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.appear(3),
        )
        // No Firebase is fine when UnifiedPush is set up: warn only without any push channel.
        if (pairing != null && pairing.pushConfig == null && dev.agentring.app.push.UnifiedPushSetup.endpoint(ctx) == null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.done_no_push), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        BrandCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(stringResource(R.string.try_it), style = MaterialTheme.typography.titleMedium)
                if (cloud) {
                    NumberedStep(1, stringResource(R.string.try_cloud_1), stringResource(R.string.try_cloud_1_body))
                    NumberedStep(2, stringResource(R.string.try_cloud_2), stringResource(R.string.try_cloud_2_body))
                } else {
                    NumberedStep(1, stringResource(R.string.try_self_1)) { CommandBlock("hivepal msg \"hello\"") }
                    NumberedStep(2, stringResource(R.string.try_self_2)) { CommandBlock("hivepal call \"test\"") }
                    NumberedStep(3, stringResource(R.string.try_self_3), stringResource(R.string.try_self_3_body)) {
                        CommandBlock("claude mcp add hivepal -- hivepal mcp")
                    }
                }
            }
        }
        OutlinedButton(onClick = { Notifications.showTest(ctx) }, modifier = Modifier.fillMaxWidth().testTag("test_notification")) {
            Text(stringResource(R.string.test_notification))
        }
        TestCallButton()
    }
}


/** English · Spanish (in the current language), for someone who hasn't paired yet (Settings has the same choice later). */
@Composable
private fun LanguageToggle() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = remember { dev.agentring.app.AppLanguage.current(ctx) }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        dev.agentring.app.AppLanguage.SUPPORTED.forEachIndexed { i, (tag, label) ->
            if (i > 0) Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            androidx.compose.material3.TextButton(
                onClick = {
                    if (tag != current) {
                        dev.agentring.app.AppLanguage.set(ctx, tag)
                        if (android.os.Build.VERSION.SDK_INT < 33) (ctx as? android.app.Activity)?.recreate()
                    }
                },
                modifier = Modifier.testTag("welcome_lang_$tag"),
            ) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tag == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (tag == current) androidx.compose.ui.text.font.FontWeight.Bold else null,
                )
            }
        }
    }
}
