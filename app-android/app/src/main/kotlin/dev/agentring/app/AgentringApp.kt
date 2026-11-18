package dev.agentring.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import dev.agentring.app.call.CallNotifications
import dev.agentring.app.notify.Notifications

@OptIn(FlowPreview::class)
class AgentringApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
        CallNotifications.createChannels(this)
        // Firebase must be ready before FirebaseMessagingService delivers a push.
        Graph.pairing.current?.let { Graph.push.initFirebase(it.pushConfig) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(Graph.connection)
        // Hivepal: keep the home-screen widget in step with the messages.
        Graph.appScope.launch {
            Graph.messages.messages.debounce(500).collect { dev.agentring.app.widget.HivepalWidget.update(this@AgentringApp) }
        }
    }
}
