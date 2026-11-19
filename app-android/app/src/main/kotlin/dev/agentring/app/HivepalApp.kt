package dev.agentring.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import dev.agentring.app.call.CallNotifications
import dev.agentring.app.notify.Notifications

@OptIn(FlowPreview::class)
class HivepalApp : Application() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate() {
        super.onCreate()
        AppLanguage.ensureDefault(this)
        dev.agentring.app.subs.Subs.schedule(this)
        Graph.init(this)
        Notifications.createChannels(this)
        CallNotifications.createChannels(this)
        // Firebase must be ready before FirebaseMessagingService delivers a push.
        Graph.pairing.current?.let { Graph.push.initFirebase(it.pushConfig) }
        // UnifiedPush (when chosen): make sure the relay has the current endpoint.
        if (Graph.pairing.current != null && dev.agentring.app.push.UnifiedPushSetup.endpoint(this) != null) {
            kotlin.concurrent.thread(name = "up-sync") { dev.agentring.app.push.UnifiedPushSetup.sendEndpoint(this) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(Graph.connection)
        // Hivepal: keep the home-screen widget in step with the messages.
        Graph.appScope.launch {
            Graph.messages.messages.debounce(500).collect { dev.agentring.app.widget.HivepalWidget.update(this@HivepalApp) }
        }
    }
}
