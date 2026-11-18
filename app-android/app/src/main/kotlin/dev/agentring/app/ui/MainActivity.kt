package dev.agentring.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import kotlinx.coroutines.launch

/** Which conversation is on screen, so a push for the conversation the user is reading doesn't notify. */
object AppVisibility {
    @Volatile var openConv: String? = null
}

class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark: light status-bar icons whatever the system theme.
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handleIntent(intent)
        setContent {
            AgentringTheme {
                AppRoot(openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_MESSAGES -> openRequest.value = OpenRequest(intent.getStringExtra(EXTRA_CONV))
            // The widget's call button: call the main agent.
            OPEN_CALL -> {
                intent.removeExtra(EXTRA_OPEN)
                Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (Graph.calls.callAgent() == null) startActivity(CallActivity.outgoingIntent(this@MainActivity))
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_MESSAGES = "messages"
        const val EXTRA_CONV = "conv"
        const val OPEN_CALL = "call"
    }
}

@Composable
fun AppRoot(openRequest: MutableState<OpenRequest?>) {
    val pairing by Graph.pairing.pairing.collectAsStateWithLifecycle()
    val setupComplete by Graph.pairing.setupComplete.collectAsStateWithLifecycle()
    val p = pairing
    if (p == null || !setupComplete) {
        OnboardingFlow(paired = p, onFinished = { Graph.pairing.setSetupComplete(true) })
    } else {
        MainScaffold(p, openRequest)
    }
}
