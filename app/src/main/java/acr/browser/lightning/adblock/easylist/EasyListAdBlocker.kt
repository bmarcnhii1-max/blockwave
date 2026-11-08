package acr.browser.lightning.adblock.easylist

import acr.browser.lightning.adblock.AdBlocker
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferencesDataStore
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AdBlocker] backed by EasyList-style network rules downloaded at runtime.
 *
 * On startup it loads the enabled lists from the on-disk cache; if a list has never been
 * downloaded (or is older than [STALE_AFTER_MS]) it is fetched first. Failures keep the
 * last good cache, so a network outage never disables blocking entirely.
 */
@Singleton
class EasyListAdBlocker @Inject constructor(
    private val downloader: FilterListDownloader,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val logger: Logger,
    private val appCoroutineScope: AppCoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
) : AdBlocker {

    private val parser = EasyListParser()
    private val cosmeticParser = CosmeticParser()
    private val matcher = AdblockMatcher()

    @Volatile
    private var cosmeticRules: CosmeticRules = CosmeticRules.EMPTY

    /**
     * Opened once the initial load (download + parse) has finished — successfully or not.
     * [isAd] waits on it briefly so the first page a user opens is not raced against the
     * download (which previously caused 0% blocking on ad-blocker test pages).
     */
    private val readyLatch = CountDownLatch(1)

    /** Completes with the same semantics as [readyLatch]; UI code can await it. */
    val ready = CompletableDeferred<Unit>()

    private val _ruleCount = MutableStateFlow(0)
    val ruleCount: StateFlow<Int> = _ruleCount

    /** True once the initial load has finished. */
    val isReady: Boolean get() = readyLatch.count == 0L

    init {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            try {
                loadEnabledLists(refreshStale = true)
            } finally {
                readyLatch.countDown()
                ready.complete(Unit)
            }
        }
    }

    /** Blocks briefly until the initial load finishes (max [READY_WAIT_MS]). */
    fun awaitReady() {
        readyLatch.await(READY_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** Re-downloads the enabled lists and rebuilds the matcher. Safe to call repeatedly. */
    fun refresh() {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            loadEnabledLists(refreshStale = false, forceDownload = true)
        }
    }

    /** Rebuilds the matcher from the enabled lists after the user changed them in settings. */
    fun reload() {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            loadEnabledLists(refreshStale = false)
        }
    }

    private suspend fun loadEnabledLists(refreshStale: Boolean, forceDownload: Boolean = false) {
        val enabledIds = userPreferencesDataStore.enabledFilterListIds()
        val fresh = AdblockMatcher()
        val generalCosmetics = LinkedHashSet<String>()
        val domainCosmetics = mutableMapOf<String, MutableList<String>>()
        val cosmeticExceptions = mutableMapOf<String, MutableSet<String>>()

        for (listId in enabledIds) {
            val list = FilterListRegistry.byId(listId) ?: continue
            val file = downloader.cacheFile(list.id)
            val needsDownload = forceDownload || !file.exists() ||
                (refreshStale && System.currentTimeMillis() - file.lastModified() > STALE_AFTER_MS)
            if (needsDownload) {
                downloader.download(list)
            }
            if (!file.exists()) {
                logger.log(TAG, "No cached file for list ${list.id}, skipping")
                continue
            }
            try {
                val text = withContext(coroutineDispatchers.io) { file.readText() }
                val parsed = parser.parse(text, list.id)
                val cosmetics = cosmeticParser.parse(text)
                fresh.addRules(parsed.rules)
                generalCosmetics.addAll(cosmetics.rules.generalSelectors)
                for ((domain, selectors) in cosmetics.rules.domainSelectors) {
                    domainCosmetics.getOrPut(domain) { mutableListOf() }.addAll(selectors)
                }
                for ((domain, selectors) in cosmetics.rules.domainExceptions) {
                    cosmeticExceptions.getOrPut(domain) { mutableSetOf() }.addAll(selectors)
                }
                logger.log(
                    TAG,
                    "List ${list.id}: ${parsed.rules.size} network rules, " +
                        "${cosmetics.rules.totalSelectors} cosmetic selectors " +
                        "(skipped unsupported=${parsed.skippedUnsupported}, unsafe cosmetic=${cosmetics.skippedUnsafe})"
                )
            } catch (e: Exception) {
                logger.log(TAG, "Failed to parse list ${list.id}", e)
            }
        }

        // Swap contents atomically from the caller's perspective.
        matcher.clear()
        matcher.addRules(snapshotRules(fresh))
        cosmeticRules = CosmeticRules(
            generalSelectors = generalCosmetics.toList(),
            domainSelectors = domainCosmetics,
            domainExceptions = cosmeticExceptions,
        )
        _ruleCount.value = matcher.ruleCount
        logger.log(
            TAG,
            "EasyList engine ready: ${matcher.ruleCount} network rules, " +
                "${cosmeticRules.totalSelectors} cosmetic selectors from ${enabledIds.size} list(s)"
        )
    }

    /**
     * CSS hiding ad elements on [pageUrl], or null if nothing applies / engine not ready.
     */
    fun cosmeticCssFor(pageUrl: String): String? {
        if (!isReady) return null
        return cosmeticRules.cssFor(pageUrl)
    }

    /**
     * Hiding selectors for [pageUrl] as CSS chunks (each under [maxChunkBytes]), domain-specific
     * selectors first. Empty if nothing applies / engine not ready. Chunking is required because
     * a single injected CSS blob with tens of thousands of selectors exceeds WebView's
     * evaluateJavascript size limits and is silently dropped.
     */
    fun cosmeticCssChunksFor(pageUrl: String, maxChunkBytes: Int = 20_000): List<String> {
        if (!isReady) return emptyList()
        val selectors = cosmeticRules.selectorsFor(pageUrl)
        if (selectors.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (selector in selectors) {
            // No comma separator between rules (see CosmeticRules.cssFor).
            val rule = "$selector{display:none!important}"
            if (current.length + rule.length > maxChunkBytes && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
            }
            current.append(rule)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        logger.log(TAG, "Cosmetic CSS for $pageUrl: ${selectors.size} selectors in ${chunks.size} chunk(s)")
        return chunks
    }

    private fun snapshotRules(other: AdblockMatcher): List<NetworkRule> {
        // AdblockMatcher does not expose its rules; rebuild by re-parsing is wasteful, so the
        // matcher keeps an internal snapshot copy instead (see AdblockMatcher.snapshot()).
        return other.snapshot()
    }

    override fun isAd(uri: Uri): Boolean {
        awaitReady()
        return matcher.isBlocked(uri.toString(), pageUrl = null, resourceType = null)
    }

    override fun isAd(uri: Uri, pageUri: Uri?, isMainFrame: Boolean): Boolean {
        awaitReady()
        val resourceType = when {
            isMainFrame -> "document"
            else -> inferType(uri.toString())
        }
        return matcher.isBlocked(uri.toString(), pageUri?.toString(), resourceType)
    }

    private fun inferType(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") -> "font"
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") ||
                path.endsWith(".ogg") || path.endsWith(".wav") -> "media"
            else -> null
        }
    }

    companion object {
        private const val TAG = "EasyListAdBlocker"
        private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
        private const val READY_WAIT_MS = 10_000L
    }
}
