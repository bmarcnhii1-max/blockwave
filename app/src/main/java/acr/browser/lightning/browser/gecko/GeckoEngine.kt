package acr.browser.lightning.browser.gecko

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Holds the process-wide [GeckoRuntime] for the experimental Gecko engine.
 *
 * Lazily created on first use so the WebView-based browser pays nothing (no native library
 * load, no extra threads) unless the user actually opens the Gecko screen. All failures are
 * swallowed to null — callers fall back to the WebView engine.
 */
object GeckoEngine {

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var failed = false

    @Synchronized
    fun get(context: Context): GeckoRuntime? {
        runtime?.let { return it }
        if (failed) return null
        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .contentBlocking(
                    ContentBlocking.Settings.Builder()
                        .antiTracking(ContentBlocking.AntiTracking.STRICT)
                        .strictSocialTrackingProtection(true)
                        .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                        .build()
                )
                .build()
            GeckoRuntime.create(context.applicationContext, settings).also { runtime = it }
        } catch (_: Exception) {
            failed = true
            null
        } catch (_: UnsatisfiedLinkError) {
            failed = true
            null
        }
    }
}
