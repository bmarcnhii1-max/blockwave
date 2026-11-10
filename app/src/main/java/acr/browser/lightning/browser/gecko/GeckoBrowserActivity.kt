package acr.browser.lightning.browser.gecko

import acr.browser.lightning.R
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Experimental standalone Gecko-engine screen (BlockWave phase 1).
 *
 * A minimal but real browser tab on GeckoView with strict tracking protection: address bar,
 * progress, back navigation. It intentionally lives outside the WebView tab system — full
 * tab integration (history, bookmarks, downloads, adblock lists) is phase 2.
 */
class GeckoBrowserActivity : ComponentActivity() {

    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.gecko_start_page)

        val runtime = GeckoEngine.get(this)
        if (runtime == null) {
            finish()
            return
        }

        val geckoSession = GeckoSession().also { session = it }
        geckoSession.open(runtime)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val s = session
                if (s != null) {
                    s.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GeckoScreen(
                    session = geckoSession,
                    startUrl = startUrl,
                    onClose = { finish() },
                )
            }
        }

        geckoSession.loadUri(startUrl)
    }

    override fun onDestroy() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        super.onDestroy()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeckoScreen(
    session: GeckoSession,
    startUrl: String,
    onClose: () -> Unit,
) {
    var urlText by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableStateOf(0) }

    DisposableEffect(session) {
        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progressValue: Int) {
                progress = progressValue
            }
        }
        val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url != null) {
                    urlText = url
                }
            }
        }
        session.progressDelegate = progressDelegate
        session.navigationDelegate = navigationDelegate
        onDispose {
            session.progressDelegate = null
            session.navigationDelegate = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gecko (beta)") },
            )
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
                        session.loadUri(target)
                    }
                ),
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                factory = { context ->
                    GeckoView(context).also { it.setSession(session) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
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
