package acr.browser.lightning.adblock

import android.net.Uri

/**
 * The ad blocking interface.
 */
interface AdBlocker {

    /**
     * a method that determines if the given URL is an ad or not. It performs a search of the URL's
     * domain on the blocked domain hash set.
     *
     * @param uri the URI to check for being an ad.
     * @return true if it is an ad, false if it is not an ad.
     */
    fun isAd(uri: Uri): Boolean

    /**
     * Determines if the given resource request is an ad, with page context. Implementations that
     * support EasyList-style rules (resource types, per-domain options, third-party checks)
     * should override this. The default delegates to [isAd].
     *
     * @param uri the requested resource URI.
     * @param pageUri the URI of the page that triggered the request, or null if unknown.
     * @param isMainFrame true if the request is for the main frame document.
     * @return true if the request should be blocked.
     */
    fun isAd(uri: Uri, pageUri: Uri?, isMainFrame: Boolean): Boolean = isAd(uri)

}
