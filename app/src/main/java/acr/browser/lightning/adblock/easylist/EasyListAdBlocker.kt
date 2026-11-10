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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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

    @Volatile
    private var matcher = AdblockMatcher()

    /** Serializes concurrent refresh()/reload()/init loads into one in-flight rebuild. */
    private val loadMutex = Mutex()

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

    /**
     * Current rule snapshot for callers that cannot block (e.g. Gecko's navigation
     * callback on the main thread). Null until the engine is ready; callers must
     * treat null as "allow".
     */
    fun matcherSnapshot(): AdblockMatcher? = if (isReady) matcher else null

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

    private suspend fun loadEnabledLists(refreshStale: Boolean, forceDownload: Boolean = false) =
        loadMutex.withLock {
        val enabledIds = userPreferencesDataStore.enabledFilterListIds()
        val fresh = AdblockMatcher()
        val generalCosmetics = LinkedHashSet<String>()
        val domainCosmetics = mutableMapOf<String, MutableList<String>>()
        val cosmeticExceptions = mutableMapOf<String, MutableSet<String>>()
        val cosmeticExcluded = LinkedHashSet<String>()

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
                val texts = withContext(coroutineDispatchers.io) {
                    collectListTexts(list, file, refreshStale, forceDownload)
                }
                var networkTotal = 0
                var cosmeticTotal = 0
                var skippedTotal = 0
                var unsafeTotal = 0
                for ((origin, text) in texts) {
                    val parsed = parser.parse(text, origin)
                    val cosmetics = cosmeticParser.parse(text)
                    fresh.addRules(parsed.rules)
                    generalCosmetics.addAll(cosmetics.rules.generalSelectors)
                    for ((domain, selectors) in cosmetics.rules.domainSelectors) {
                        domainCosmetics.getOrPut(domain) { mutableListOf() }.addAll(selectors)
                    }
                    for ((domain, selectors) in cosmetics.rules.domainExceptions) {
                        cosmeticExceptions.getOrPut(domain) { mutableSetOf() }.addAll(selectors)
                    }
                    cosmeticExcluded.addAll(cosmetics.rules.excludedDomains)
                    networkTotal += parsed.rules.size
                    cosmeticTotal += cosmetics.rules.totalSelectors
                    skippedTotal += parsed.skippedUnsupported
                    unsafeTotal += cosmetics.skippedUnsafe
                }
                logger.log(
                    TAG,
                    "List ${list.id}: $networkTotal network rules, " +
                        "$cosmeticTotal cosmetic selectors " +
                        "(skipped unsupported=$skippedTotal, unsafe cosmetic=$unsafeTotal)"
                )
            } catch (e: Exception) {
                logger.log(TAG, "Failed to parse list ${list.id}", e)
            }
        }

        // Atomic swap: readers always see one fully built matcher, never a half-cleared one.
        matcher = fresh
        cosmeticRules = CosmeticRules(
            generalSelectors = generalCosmetics.toList(),
            domainSelectors = domainCosmetics,
            domainExceptions = cosmeticExceptions,
            excludedDomains = cosmeticExcluded,
        )
        _ruleCount.value = matcher.ruleCount
        logger.log(
            TAG,
            "EasyList engine ready: ${matcher.ruleCount} network rules, " +
                "${cosmeticRules.totalSelectors} cosmetic selectors from ${enabledIds.size} list(s)"
        )
        }

    /**
     * Reads the list file plus every `%include path%` part it references (RU AdList and
     * similar lists are split into parts this way; without this the RU lists contribute a
     * dozen rules instead of thousands). Parts are downloaded relative to the list URL
     * and cached next to it. Depth is capped at one level with a visited set.
     */
    private suspend fun collectListTexts(
        list: FilterList,
        file: File,
        refreshStale: Boolean,
        forceDownload: Boolean,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val main = file.readText()
        out.add(list.id to main)
        val base = list.url.substringBeforeLast('/') + "/"
        val seen = LinkedHashSet<String>()
        for (line in main.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("%include ") || !trimmed.endsWith("%")) continue
            val reference = trimmed.removePrefix("%include ").removeSuffix("%").trim()
            if (reference.isEmpty() || reference.contains("..")) continue
            if (!seen.add(reference)) continue
            if (seen.size > MAX_INCLUDES) break
            // Three spellings exist in the wild:
            // - relative: `advblock/general_block.txt` (resolved against the list URL)
            // - absolute: `%include https://…%` (bitblock.txt Fanboy parts)
            // - repo shorthand: `%include ruadlist:bitblockonly.txt%`
            val partUrl = when {
                reference.startsWith("https://") -> {
                    if (!reference.startsWith("https://raw.githubusercontent.com/")) continue
                    reference
                }
                reference.startsWith("ruadlist:") -> {
                    RUADLIST_RAW_BASE + reference.removePrefix("ruadlist:")
                }
                reference.contains(":") || reference.startsWith("/") -> continue
                else -> base + reference
            }
            val partFile = File(file.parentFile, "${list.id}__${reference.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            val needsDownload = forceDownload || !partFile.exists() ||
                (refreshStale && System.currentTimeMillis() - partFile.lastModified() > STALE_AFTER_MS)
            if (needsDownload) {
                downloader.downloadUrl(partUrl, partFile, "${list.id}/$reference")
            }
            if (partFile.exists()) {
                out.add("${list.id}/$reference" to partFile.readText())
            }
        }
        return out
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
    fun cosmeticCssChunksFor(pageUrl: String, maxChunkBytes: Int = 128_000): List<String> {
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
        private const val MAX_INCLUDES = 32
        private const val RUADLIST_RAW_BASE =
            "https://raw.githubusercontent.com/easylist/ruadlist/master/"
    }
}
