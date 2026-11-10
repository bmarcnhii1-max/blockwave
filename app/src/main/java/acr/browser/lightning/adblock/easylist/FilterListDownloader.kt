package acr.browser.lightning.adblock.easylist

import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.di.HostsClient
import acr.browser.lightning.log.Logger
import acr.browser.lightning.useragent.UserAgentProvider
import android.app.Application
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads EasyList-style filter lists to `filesDir/filterlists/<id>.txt`.
 *
 * Downloads are conditional (`If-Modified-Since` from the cached file's mtime), size-capped,
 * and written via a temp file + rename so a failed download never corrupts the cache.
 * Lists are fetched at runtime and never committed to git.
 */
@Singleton
class FilterListDownloader @Inject constructor(
    private val application: Application,
    @HostsClient private val okHttpClient: Deferred<@JvmSuppressWildcards OkHttpClient>,
    private val userAgentProvider: UserAgentProvider,
    private val logger: Logger,
    private val coroutineDispatchers: CoroutineDispatchers,
) {

    private val mutex = Mutex()

    fun cacheFile(listId: String): File =
        File(File(application.filesDir, CACHE_DIR), "$listId.txt")

    suspend fun download(list: FilterList): Boolean =
        downloadUrl(list.url, cacheFile(list.id), list.id)

    /**
     * Downloads [url] to [target]. Shared by list downloads and `%include` part files.
     */
    suspend fun downloadUrl(url: String, target: File, tag: String): Boolean = mutex.withLock {
        withContext(coroutineDispatchers.network) {
            val userAgent = try {
                userAgentProvider.getUserAgent()
            } catch (e: Exception) {
                logger.log(TAG, "User agent lookup failed, using fallback", e)
                FALLBACK_USER_AGENT
            }
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .get()
            if (target.exists()) {
                requestBuilder.header("If-Modified-Since", httpDate(target.lastModified()))
            }

            try {
                val response = okHttpClient.await().newCall(requestBuilder.build()).execute()
                response.use {
                    if (it.code == 304) {
                        // Refresh the mtime so the next cold start skips the conditional
                        // request instead of re-validating forever.
                        target.setLastModified(System.currentTimeMillis())
                        logger.log(TAG, "List $tag not modified")
                        return@withContext true
                    }
                    if (!it.isSuccessful) {
                        logger.log(TAG, "List $tag download failed: HTTP ${it.code}")
                        return@withContext false
                    }
                    val body = it.body
                    if (body.contentLength() > MAX_BYTES) {
                        logger.log(TAG, "List $tag exceeds size cap")
                        return@withContext false
                    }
                    // Enforce the cap while streaming: body.bytes() would buffer an
                    // unbounded chunked response before we could check its size.
                    // (readByteArray(n) cannot be used: it throws EOFException unless
                    // the stream holds exactly n bytes.)
                    val bytes = try {
                        body.source().use { source ->
                            val sink = okio.Buffer()
                            var total = 0L
                            while (true) {
                                val read = source.read(sink, 8192L)
                                if (read == -1L) break
                                total += read
                                if (total > MAX_BYTES) {
                                    logger.log(TAG, "List $tag exceeds size cap")
                                    return@withContext false
                                }
                            }
                            sink.readByteArray()
                        }
                    } catch (e: Exception) {
                        logger.log(TAG, "List $tag download failed", e)
                        return@withContext false
                    }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    logger.log(TAG, "Downloaded list $tag: ${bytes.size} bytes")
                    true
                }
            } catch (e: Exception) {
                logger.log(TAG, "List $tag download failed", e)
                false
            }
        }
    }

    companion object {
        private const val TAG = "FilterListDownloader"
        private const val CACHE_DIR = "filterlists"
        private const val MAX_BYTES = 10 * 1024 * 1024
        private const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private fun httpDate(epochMillis: Long): String {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            return format.format(java.util.Date(epochMillis))
        }
    }
}
