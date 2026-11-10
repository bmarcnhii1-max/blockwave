package acr.browser.lightning.browser.gecko

import acr.browser.lightning.BrowserApp
import acr.browser.lightning.R
import acr.browser.lightning.adblock.easylist.AdblockMatcher
import acr.browser.lightning.adblock.easylist.EasyListAdBlocker
import acr.browser.lightning.adblock.allowlist.SessionAllowListModel
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Experimental Gecko-engine browser screen (BlockWave phase 2).
 *
 * A multi-tab mini-browser on GeckoView with strict tracking protection, popup/ad-frame
 * blocking driven by the shared EasyList engine ([EasyListAdBlocker]) and cosmetic CSS
 * hiding. It intentionally lives outside the WebView tab system — history, bookmarks,
 * downloads and the per-site allowlist UI stay WebView-side for now.
 *
 * Everything adblock-related here is non-blocking: the navigation callback runs on the
 * main thread, so it only consults [EasyListAdBlocker.matcherSnapshot] (null = allow).
 */
class GeckoBrowserActivity : ComponentActivity() {

    internal lateinit var easyListAdBlocker: EasyListAdBlocker
    internal lateinit var allowListModel: SessionAllowListModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = (application as BrowserApp).applicationComponent
        easyListAdBlocker = component.easyListAdBlocker()
        allowListModel = component.sessionAllowListModel()

        val startUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.gecko_start_page)

        val runtime = GeckoEngine.get(this)
        if (runtime == null) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is handled inside the Compose screen (current tab goBack).
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GeckoBrowserScreen(
                    runtime = runtime,
                    startUrl = startUrl,
                    adblock = GeckoAdblock(easyListAdBlocker, allowListModel),
                    onShare = { url -> shareUrl(url) },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun shareUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        ContextCompat.startActivity(
            this,
            Intent.createChooser(intent, getString(R.string.action_share)),
            null
        )
    }

    companion object {
        private const val EXTRA_URL = "gecko_url"

        fun launch(context: Context, url: String?) {
            val intent = Intent(context, GeckoBrowserActivity::class.java)
            if (!url.isNullOrBlank()) {
                intent.putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * Non-blocking bridge between Gecko navigation callbacks and the shared EasyList engine.
 * All methods are safe to call on the main thread: when the engine is not ready yet,
 * everything is allowed and no CSS is produced.
 */
internal class GeckoAdblock(
    private val easyListAdBlocker: EasyListAdBlocker,
    private val allowListModel: SessionAllowListModel,
) {
    /**
     * @return true if the load of [uri] (triggered from [pageUrl]) must be denied.
     * Top-level user navigations are never denied — only embedded/popup loads.
     */
    fun shouldDeny(uri: String, pageUrl: String?, topLevel: Boolean): Boolean {
        if (topLevel) return false
        if (pageUrl != null && allowListModel.isUrlAllowedAds(pageUrl)) return false
        val matcher: AdblockMatcher = easyListAdBlocker.matcherSnapshot() ?: return false
        return matcher.isBlocked(uri, pageUrl, null)
    }

    /** CSS hiding ad elements on [pageUrl], split for javascript: URL injection. */
    fun cosmeticChunks(pageUrl: String): List<String> {
        if (pageUrl.isNotEmpty() && allowListModel.isUrlAllowedAds(pageUrl)) return emptyList()
        return easyListAdBlocker.cosmeticCssChunksFor(pageUrl)
    }
}

private data class GeckoTab(
    val id: Int,
    val session: GeckoSession,
    var title: String = "",
    var url: String = "",
    var progress: Int = 100,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeckoBrowserScreen(
    runtime: org.mozilla.geckoview.GeckoRuntime,
    startUrl: String,
    adblock: GeckoAdblock,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tabs = remember { mutableStateListOf<GeckoTab>() }
    var selectedId by remember { mutableStateOf(-1) }
    var nextId by remember { mutableStateOf(0) }
    var urlText by remember { mutableStateOf(startUrl) }

    fun openTab(url: String, select: Boolean = true): GeckoTab {
        val session = GeckoSession()
        val tab = GeckoTab(id = nextId++, session = session, url = url)
        session.navigationDelegate = GeckoNavDelegate(
            tabs = tabs,
            tabRef = { tabs.firstOrNull { it.id == tab.id } },
            adblock = adblock,
            onOpenPopup = { popupUrl -> openTab(popupUrl, select = false) },
            onPageChanged = { u -> if (tab.id == selectedId) urlText = u },
        )
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progressValue: Int) {
                tab.progress = progressValue
            }

            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url
                tab.title = url
                if (tab.id == selectedId) urlText = url
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.progress = 100
                if (success) injectCosmetics(session, adblock, tab.url)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (title != null) tab.title = title
            }

            override fun onCloseRequest(session: GeckoSession) {
                tabs.remove(tab)
                session.close()
                if (tabs.isEmpty()) onClose() else if (tab.id == selectedId) {
                    selectedId = tabs.last().id
                }
            }
        }
        session.open(runtime)
        tabs.add(tab)
        if (select) selectedId = tab.id
        session.loadUri(url)
        return tab
    }

    DisposableEffect(Unit) {
        openTab(startUrl)
        onDispose {
            for (tab in tabs) {
                try {
                    tab.session.close()
                } catch (_: Exception) {
                }
            }
            tabs.clear()
        }
    }

    val current = tabs.firstOrNull { it.id == selectedId }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Gecko (beta)") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val target = urlText.trim().toGeckoUrl()
                        current?.session?.loadUri(target)
                    }
                ),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { current?.session?.goBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "back")
                }
                IconButton(onClick = { current?.session?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "forward")
                }
                IconButton(onClick = { current?.session?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "reload")
                }
                IconButton(onClick = { current?.let { onShare(it.url) } }) {
                    Icon(Icons.Default.Share, contentDescription = "share")
                }
                IconButton(onClick = { openTab(startUrl) }) {
                    Icon(Icons.Default.Add, contentDescription = "new tab")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                for (tab in tabs) {
                    Row(
                        modifier = Modifier
                            .clickable { selectedId = tab.id }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = tab.title.ifEmpty { tab.url }.take(24),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (tab.id == selectedId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (tabs.size > 1) {
                            IconButton(onClick = {
                                tabs.remove(tab)
                                try {
                                    tab.session.close()
                                } catch (_: Exception) {
                                }
                                if (tab.id == selectedId && tabs.isNotEmpty()) {
                                    selectedId = tabs.last().id
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "close tab")
                            }
                        }
                    }
                }
            }
            if ((current?.progress ?: 100) in 1..99) {
                LinearProgressIndicator(
                    progress = { (current?.progress ?: 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            key(current?.id) {
                if (current != null) {
                    AndroidView(
                        factory = { context ->
                            GeckoView(context).also { it.setSession(current.session) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}

private class GeckoNavDelegate(
    private val tabs: List<GeckoTab>,
    private val tabRef: () -> GeckoTab?,
    private val adblock: GeckoAdblock,
    private val onOpenPopup: (String) -> Unit,
    private val onPageChanged: (String) -> Unit,
) : GeckoSession.NavigationDelegate {

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny>? {
        val uri = request.uri
        val pageUrl = tabRef()?.url?.takeIf { it.isNotEmpty() }
        if (uri == pageUrl) return null
        if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
            // Popup window: hand it to a background tab instead of the system default.
            onOpenPopup(uri)
            return GeckoResult.deny()
        }
        // Block ad/tracker subresource navigations (frames, redirects) that have no
        // user gesture behind them. User-initiated top-level navigations always pass.
        if (!request.hasUserGesture && pageUrl != null &&
            adblock.shouldDeny(uri, pageUrl, topLevel = false)
        ) {
            return GeckoResult.deny()
        }
        return null
    }

    override fun onNewSession(
        session: GeckoSession,
        uri: String,
    ): GeckoResult<GeckoSession>? {
        val pageUrl = tabRef()?.url?.takeIf { it.isNotEmpty() }
        if (adblock.shouldDeny(uri, pageUrl, topLevel = false)) {
            return GeckoResult.fromValue(null as GeckoSession?)
        }
        onOpenPopup(uri)
        val created = tabs.lastOrNull()
        return if (created != null) {
            GeckoResult.fromValue(created.session)
        } else {
            null
        }
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean,
    ) {
        if (url != null) {
            tabRef()?.url = url
            onPageChanged(url)
        }
    }
}

/**
 * Cosmetic hiding for Gecko: the WebView path (evaluateJavascript) does not exist here,
 * so the CSS chunks travel inside javascript: navigations. Gecko executes them in the
 * page context; each chunk appends one <style data-adblock> element.
 */
private fun injectCosmetics(
    session: GeckoSession,
    adblock: GeckoAdblock,
    pageUrl: String,
) {
    val chunks = try {
        adblock.cosmeticChunks(pageUrl)
    } catch (_: Exception) {
        return
    }
    if (chunks.isEmpty()) return
    try {
        val clear = "javascript:(function(){document.querySelectorAll('style[data-adblock]')" +
            ".forEach(function(e){e.remove()});})()"
        session.loadUri(clear)
        for (css in chunks) {
            // Quote for a JS single-quoted string going through a javascript: URL.
            val quoted = css
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
            val script = "javascript:(function(){var s=document.createElement('style');" +
                "s.setAttribute('data-adblock','1');s.textContent='$quoted';" +
                "(document.head||document.documentElement).appendChild(s);})()"
            session.loadUri(script)
        }
    } catch (_: Exception) {
        // javascript: URLs may be restricted on some builds — ETP still applies.
    }
}

private fun String.toGeckoUrl(): String {
    val trimmed = trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.contains(" ") || !trimmed.contains(".")) {
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
    return "https://$trimmed"
}
