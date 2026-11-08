package acr.browser.lightning.adblock

import acr.browser.lightning.adblock.easylist.EasyListAdBlocker
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines the hosts-file blocker (bundled `hosts.txt`, domain-only, works offline on first
 * launch) with the EasyList engine (downloaded filter lists, path/type/domain aware).
 * A request is blocked if either engine blocks it.
 */
@Singleton
class CompositeAdBlocker @Inject constructor(
    private val hostsAdBlocker: BloomFilterAdBlocker,
    private val easyListAdBlocker: EasyListAdBlocker,
) : AdBlocker {

    override fun isAd(uri: Uri): Boolean =
        hostsAdBlocker.isAd(uri) || easyListAdBlocker.isAd(uri)

    override fun isAd(uri: Uri, pageUri: Uri?, isMainFrame: Boolean): Boolean =
        hostsAdBlocker.isAd(uri) || easyListAdBlocker.isAd(uri, pageUri, isMainFrame)

    /**
     * CSS hiding ad elements on [pageUrl] (cosmetic filtering), or null if nothing applies.
     */
    fun cosmeticCssFor(pageUrl: String): String? = easyListAdBlocker.cosmeticCssFor(pageUrl)

    /**
     * Hiding CSS for [pageUrl] split into chunks small enough for evaluateJavascript.
     */
    fun cosmeticCssChunksFor(pageUrl: String): List<String> =
        easyListAdBlocker.cosmeticCssChunksFor(pageUrl)

    /**
     * Completes once the EasyList engine has loaded its lists (see [EasyListAdBlocker]).
     */
    val easyListReady: kotlinx.coroutines.CompletableDeferred<Unit>
        get() = easyListAdBlocker.ready
}
