package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.adblock.AdBlocker
import acr.browser.lightning.adblock.CompositeAdBlocker
import acr.browser.lightning.adblock.allowlist.AllowListModel
import acr.browser.lightning.browser.tab.settings.TabSettings
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.databinding.DialogAuthRequestBinding
import acr.browser.lightning.databinding.DialogSslWarningBinding
import acr.browser.lightning.di.FaviconCacheDir
import acr.browser.lightning.di.GeneratedHtmlDir
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.log.Logger
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.ssl.SslWarningPreferences
import acr.browser.lightning.utils.ThreadSafeFileProvider
import acr.browser.lightning.utils.isSpecialUrl
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * A [WebViewClient] that supports the tab adaptation.
 */
class TabWebViewClient @AssistedInject constructor(
    private val adBlocker: Deferred<@JvmSuppressWildcards AdBlocker>,
    private val allowListModel: AllowListModel,
    private val urlHandler: UrlHandler,
    @Assisted private val headers: Map<String, String>,
    private val sslWarningPreferences: SslWarningPreferences,
    private val textReflow: TextReflow,
    private val logger: Logger,
    @FaviconCacheDir private val faviconCacheDirThreadSafeFileProvider: ThreadSafeFileProvider,
    @GeneratedHtmlDir private val generatedHtmlDirThreadSafeFileProvider: ThreadSafeFileProvider,
    @Assisted("cache") private val cacheStoragePathHandler: InternalStoragePathHandler,
    @Assisted("files") private val filesStoragePathHandler: InternalStoragePathHandler,
    @Assisted private val tabCoroutineScope: TabCoroutineScope,
    @Assisted private val tabSettings: TabSettings,
) : WebViewClient() {

    /**
     * The factory for constructing the client.
     */
    @AssistedFactory
    interface Factory {

        /**
         * Create the client.
         */
        fun create(
            headers: Map<String, String>,
            @Assisted("cache") cacheStoragePathHandler: InternalStoragePathHandler,
            @Assisted("files") filesStoragePathHandler: InternalStoragePathHandler,
            tabCoroutineScope: TabCoroutineScope,
            tabSettings: TabSettings,
        ): TabWebViewClient
    }

    private val cache by lazy {
        runBlocking {
            faviconCacheDirThreadSafeFileProvider.file()
        }
    }

    private val files by lazy {
        runBlocking {
            generatedHtmlDirThreadSafeFileProvider.file()
        }
    }

    /**
     * Emits changes to the current URL.
     */
    val urlSharedFlow: MutableSharedFlow<String> = MutableSharedFlow()

    /**
     * The current SSL state of the page.
     */
    val sslStateFlow: MutableStateFlow<SslState> = MutableStateFlow(SslState.None)

    /**
     * Emits changes to the can go back state of the browser.
     */
    val goBackSharedFlow: MutableSharedFlow<Boolean> = MutableSharedFlow()

    /**
     * Emits changes to the can go forward state of the browser.
     */
    val goForwardSharedFlow: MutableSharedFlow<Boolean> = MutableSharedFlow()

    /**
     * Emit when the tab has started loading a new page
     */
    val startedSharedFlow = MutableSharedFlow<Unit>()

    /**
     * Emit when the tab has finished rendering its content.
     */
    val finishedSharedFlow = MutableSharedFlow<Unit>()

    /**
     * The latest search query entered by the user, or the latest loaded URL, whichever event
     * happened later.
     */
    var searchQuery: String = ""

    /**
     * The text selection in the search query, either the start and end of the selection if the
     * values are different, or the cursor position if they are the same.
     */
    var searchQuerySelection: Pair<Int, Int> = Pair(0, 0)

    private var currentUrl: String = ""
    private var isReflowRunning: Boolean = false
    private var zoomScale: Float = 0.0F
    private var urlWithSslError: String? = null
    private var lastView: WebView? = null

    init {
        // On cold start the filter lists may not be downloaded yet when the first page
        // loads, so the onPageStarted injection finds an empty engine. Re-inject once the
        // engine signals readiness, so cosmetic filtering is not lost for that page.
        tabCoroutineScope.launch {
            val blocker = runBlocking { adBlocker.await() }
            (blocker as? CompositeAdBlocker)?.easyListReady?.await()
            val view = lastView
            if (view != null && currentUrl.isNotEmpty()) {
                injectCosmeticCss(view, currentUrl)
            }
        }
    }

    /**
     * Number of requests blocked by the ad blocker in this tab (in-memory only; incognito
     * tabs therefore never persist it).
     */
    val blockedCount = MutableStateFlow(0)

    private fun shouldBlockRequest(pageUrl: String, requestUri: Uri, isMainFrame: Boolean) =
        !allowListModel.isUrlAllowedAds(pageUrl) &&
            runBlocking {
                val pageUri = pageUrl.takeIf { it.isNotEmpty() }?.let(Uri::parse)
                adBlocker.await().isAd(requestUri, pageUri, isMainFrame)
            }

    /**
     * Injects cosmetic (element-hiding) CSS for [url] into [view], unless ads are allowed
     * for the page. Called on page start and finish. CSS arrives in chunks (a single blob
     * with tens of thousands of selectors exceeds evaluateJavascript limits and is silently
     * dropped); injection replaces previously injected chunks.
     */
    private fun injectCosmeticCss(view: WebView, url: String) {
        if (url.isEmpty() || allowListModel.isUrlAllowedAds(url)) return
        val blocker = runBlocking { adBlocker.await() }
        val chunks = (blocker as? CompositeAdBlocker)?.cosmeticCssChunksFor(url) ?: return
        if (chunks.isEmpty()) return
        logger.log(TAG, "Injecting cosmetic CSS for $url in ${chunks.size} chunk(s)")
        view.evaluateJavascript(
            "(function(){document.querySelectorAll('style[data-adblock]').forEach(function(e){e.remove()});" +
                "window.__adblockStyles=[];})()",
            null
        )
        // Inject sequentially: firing many evaluateJavascript calls in parallel loses some of
        // them in WebView (callbacks never fire / scripts never run). Each chunk runs only
        // after the previous one completed.
        fun injectChunk(index: Int) {
            if (index >= chunks.size) return
            val css = chunks[index]
            val script = "(function(){try{var s=document.createElement('style');" +
                "s.setAttribute('data-adblock','1');" +
                "s.textContent=" + JSONObject.quote(css) + ";" +
                "(document.head||document.documentElement).appendChild(s);" +
                "window.__adblockStyles=window.__adblockStyles||[];window.__adblockStyles.push(s);" +
                "if(!window.__adblockObserver&&window.MutationObserver){" +
                "window.__adblockObserver=new MutationObserver(function(){" +
                "var h=document.head||document.documentElement;if(!h)return;" +
                "(window.__adblockStyles||[]).forEach(function(n){if(!n.isConnected)h.appendChild(n);});" +
                "});window.__adblockObserver.observe(document.documentElement,{childList:true,subtree:true});}" +
                "return document.querySelectorAll('style[data-adblock]').length;}catch(e){return 'ERR:'+e.message;}})()"
            view.evaluateJavascript(script) {
                injectChunk(index + 1)
            }
        }
        injectChunk(0)
    }

    private fun updateUrl(url: String) {
        searchQuery = if (!url.isSpecialUrl()) {
            url
        } else {
            ""
        }
        searchQuerySelection = Pair(0, searchQuery.length)
        currentUrl = url
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        updateUrl(url)
        tabCoroutineScope.launch {
            urlSharedFlow.emit(url)
            goBackSharedFlow.emit(view.canGoBack())
            goForwardSharedFlow.emit(view.canGoForward())
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        lastView = view
        injectCosmeticCss(view, url)
        updateUrl(url)
        tabCoroutineScope.launch {
            startedSharedFlow.emit(Unit)
            urlSharedFlow.emit(url)
            if (urlWithSslError != url) {
                urlWithSslError = null
                val sslState = if (URLUtil.isHttpsUrl(url)) {
                    SslState.Valid
                } else {
                    SslState.None
                }
                sslStateFlow.emit(sslState)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        injectCosmeticCss(view, url)
        tabCoroutineScope.launch {
            urlSharedFlow.emit(url)
            goBackSharedFlow.emit(view.canGoBack())
            goForwardSharedFlow.emit(view.canGoForward())
        }
        view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                tabCoroutineScope.launch {
                    finishedSharedFlow.emit(Unit)
                }
            }
        })
    }


    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (view.isShown && tabSettings.textReflowEnabled) {
            if (isReflowRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isReflowRunning) {
                isReflowRunning = view.postDelayed({
                    zoomScale = newScale
                    view.evaluateJavascript(textReflow.provideJs()) { isReflowRunning = false }
                }, 100)
            }

        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            val dialogView = DialogAuthRequestBinding.inflate(LayoutInflater.from(context))

            val realmLabel = dialogView.authRequestRealmTextview
            val name = dialogView.authRequestUsernameEdittext
            val password = dialogView.authRequestPasswordEdittext

            realmLabel.text = context.getString(R.string.label_realm, realm)

            setView(dialogView.root)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                logger.log(TAG, "Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            setTitle(context.getString(R.string.title_form_resubmission))
            setMessage(context.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.resizeAndShow()
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
        val context = webView.context
        urlWithSslError = webView.url

        tabCoroutineScope.launch {
            val sslState = SslState.Invalid(error)
            sslStateFlow.emit(sslState)
        }

        when (sslWarningPreferences.recallBehaviorForDomain(webView.url)) {
            SslWarningPreferences.Behavior.PROCEED -> return handler.proceed()
            SslWarningPreferences.Behavior.CANCEL -> return handler.cancel()
            null -> Unit
        }

        val errorCodeMessageCodes = error.getAllSslErrorMessageCodes()

        val stringBuilder = StringBuilder()
        for (messageCode in errorCodeMessageCodes) {
            stringBuilder.append(" - ").append(context.getString(messageCode)).append('\n')
        }
        val alertMessage =
            context.getString(R.string.message_insecure_connection, stringBuilder.toString())

        AlertDialog.Builder(context).apply {
            val view = DialogSslWarningBinding.inflate(LayoutInflater.from(context))
            val dontAskAgain = view.checkBoxDontAskAgain
            setTitle(context.getString(R.string.title_warning))
            setMessage(alertMessage)
            setCancelable(true)
            setView(view.root)
            setOnCancelListener { handler.cancel() }
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    sslWarningPreferences.rememberBehaviorForDomain(
                        webView.url.orEmpty(),
                        SslWarningPreferences.Behavior.PROCEED
                    )
                }
                handler.proceed()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    sslWarningPreferences.rememberBehaviorForDomain(
                        webView.url.orEmpty(),
                        SslWarningPreferences.Behavior.CANCEL
                    )
                }
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        urlHandler.shouldOverrideLoading(
            tabSettings.openAvailableAppsEnabled,
            view,
            request.url.toString(),
            headers
        ) || super.shouldOverrideUrlLoading(view, request)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (shouldBlockRequest(currentUrl, request.url, request.isForMainFrame)) {
            blockedCount.value = blockedCount.value + 1
            val empty = ByteArrayInputStream(emptyResponseByteArray)
            val response = WebResourceResponse(BLOCKED_RESPONSE_MIME_TYPE, BLOCKED_RESPONSE_ENCODING, empty)
            // Report a 404 instead of an empty 200: ad/tracker test pages (and real detection
            // scripts) treat an empty 200 as a successful load, so with an empty 200 blocking
            // is invisible to them. A 404 fires the expected error events for <script>, <img>,
            // <iframe> and fetch(). WebView has no way to simulate a network error here.
            response.setStatusCodeAndReasonPhrase(BLOCKED_STATUS_CODE, BLOCKED_REASON_PHRASE)
            return response
        }
        return if (request.url.path?.startsWith(files.path) == true) {
            filesStoragePathHandler.handle(request.url.path!!.substring(files.path.length))
        } else if (request.url.path?.startsWith(cache.path) == true) {
            cacheStoragePathHandler.handle(request.url.path!!.substring(cache.path.length))
        } else {
            super.shouldInterceptRequest(view, request)
        }
    }

    private fun SslError.getAllSslErrorMessageCodes(): List<Int> {
        val errorCodeMessageCodes = ArrayList<Int>(1)

        if (hasError(SslError.SSL_DATE_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_date_invalid)
        }
        if (hasError(SslError.SSL_EXPIRED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_expired)
        }
        if (hasError(SslError.SSL_IDMISMATCH)) {
            errorCodeMessageCodes.add(R.string.message_certificate_domain_mismatch)
        }
        if (hasError(SslError.SSL_NOTYETVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_not_yet_valid)
        }
        if (hasError(SslError.SSL_UNTRUSTED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_untrusted)
        }
        if (hasError(SslError.SSL_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        }

        return errorCodeMessageCodes
    }

    companion object {
        private const val TAG = "TabWebViewClient"

        private val emptyResponseByteArray: ByteArray = byteArrayOf()

        private const val BLOCKED_RESPONSE_MIME_TYPE = "text/plain"
        private const val BLOCKED_RESPONSE_ENCODING = "utf-8"
        private const val BLOCKED_STATUS_CODE = 404
        private const val BLOCKED_REASON_PHRASE = "Not Found"
    }
}
