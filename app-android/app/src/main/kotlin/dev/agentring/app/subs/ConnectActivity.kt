package dev.agentring.app.subs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.agentring.app.AppLanguage
import dev.agentring.app.R
import dev.agentring.app.ui.HivepalTheme

/**
 * A service's site inside Hivepal: the user logs in and opens the billing page, then "Use this page".
 * Its own screen with a plain WebView (a WebView inside animated Compose layers draws nothing).
 * Cookies stay in the app's WebView store, which the daily reading uses.
 *
 * Pages may open windows: "Sign in with Google" (Claude, ChatGPT…) opens Google's account chooser in a
 * popup that hands the result back to the page through `window.opener` and closes itself. Without real
 * windows the popup replaced the page and the sign-in ended on a blank accounts.google.com/gsi page.
 */
class ConnectActivity : ComponentActivity() {
    private var url by mutableStateOf("")
    private lateinit var web: WebView
    private lateinit var frame: android.widget.FrameLayout
    /** Windows the page opened, newest last; each is drawn over the page until it closes. */
    private val popups = mutableListOf<WebView>()

    private val chrome = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = newWebView()
            popups += popup
            frame.addView(popup, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) = closePopup(window)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Like a phone browser: the page's own viewport, pinch to zoom, scrolling.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        isVerticalScrollBarEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = chrome
        webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                u?.let { this@ConnectActivity.url = it }
            }
        }
    }

    private fun closePopup(popup: WebView) {
        if (!popups.remove(popup)) return
        frame.removeView(popup)
        popup.destroy()
        url = (popups.lastOrNull() ?: web).url ?: url
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: id
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()

        CookieManager.getInstance().setAcceptCookie(true)
        web = newWebView()
        // The page in a frame that takes the system bars' and keyboard's room (padding on a WebView itself
        // shifts its content without giving it room, so it can't scroll to the end).
        frame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(web, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val header = ComposeView(this).apply {
            setContent {
                HivepalTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.titleMedium)
                                    Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Button(onClick = { use(id) }, modifier = Modifier.testTag("use_page")) {
                                    Text(stringResource(R.string.subs_use_page))
                                }
                            }
                            Text(stringResource(R.string.subs_connect_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                        }
                    }
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#000814"))
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(frame)
        })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, nav.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        web.loadUrl(url)
    }

    private fun back() {
        val top = popups.lastOrNull()
        when {
            top != null && top.canGoBack() -> top.goBack()
            top != null -> closePopup(top)
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun use(id: String) {
        // The page underneath: a popup still open is a sign-in or a dialog, not the billing page.
        val page = web.url ?: url
        Subs.connect(this, id, page)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ID, id))
        finish()
    }

    override fun onDestroy() {
        popups.toList().forEach { closePopup(it) }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"

        fun intent(ctx: Context, s: Service): Intent = Intent(ctx, ConnectActivity::class.java)
            .putExtra(EXTRA_ID, s.id).putExtra(EXTRA_NAME, s.name).putExtra(EXTRA_URL, Subs.page(ctx, s.id) ?: s.start)
    }
}
