package acr.browser.lightning.adblock

import acr.browser.lightning.R
import acr.browser.lightning.adblock.source.HostsDataSourceProvider
import acr.browser.lightning.adblock.source.HostsResult
import acr.browser.lightning.adblock.util.BloomFilter
import acr.browser.lightning.adblock.util.DefaultBloomFilter
import acr.browser.lightning.adblock.util.DelegatingBloomFilter
import acr.browser.lightning.adblock.util.hash.MurmurHashHostAdapter
import acr.browser.lightning.adblock.util.hash.MurmurHashStringAdapter
import acr.browser.lightning.adblock.util.`object`.JvmObjectStore
import acr.browser.lightning.adblock.util.`object`.ObjectStore
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.database.adblock.Host
import acr.browser.lightning.database.adblock.HostsPreferenceStore
import acr.browser.lightning.database.adblock.HostsRepository
import acr.browser.lightning.extensions.toast
import acr.browser.lightning.log.Logger
import android.app.Application
import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.URISyntaxException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AdBlocker] that is backed by a [BloomFilter].
 *
 * @param logger The logger used to log status.
 * @param hostsDataSourceProvider The provider that provides the data source used to populate the
 * bloom filter and [hostsRepository].
 * @param hostsRepository The long term store for blocked hosts.
 */
@Singleton
class BloomFilterAdBlocker @Inject constructor(
    private val logger: Logger,
    private val hostsDataSourceProvider: HostsDataSourceProvider,
    private val hostsRepository: HostsRepository,
    private val hostsPreferenceStore: HostsPreferenceStore,
    private val application: Application,
    private val appCoroutineScope: AppCoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
) : AdBlocker {

    private val objectStoreDispatcher = coroutineDispatchers.createDatabaseDispatcher()
    private val bloomFilter: DelegatingBloomFilter<Host> = DelegatingBloomFilter()
    private val objectStore: ObjectStore<DefaultBloomFilter<Host>> = JvmObjectStore(
        application = application,
        hashingAlgorithm = MurmurHashStringAdapter(),
        key = BLOOM_FILTER_KEY,
        objectStoreDispatcher = objectStoreDispatcher,
    )

    /**
     * Refresh requests. CONFLATED: rapid taps collapse into one pending reload. Carries the
     * force flag so a cold start can reuse the cached filter while an explicit user refresh
     * always re-downloads. (Previously a MutableStateFlow(true): tryEmit(true) never fired
     * because StateFlow conflates equal values — the refresh button did nothing.)
     */
    private val refreshChannel = Channel<Boolean>(Channel.CONFLATED)

    /**
     * Opened once the initial hosts load has finished (delegate set, or failed). [isAd]
     * waits on it briefly so the first page is not raced against the hosts download/parse.
     */
    private val readyLatch = CountDownLatch(1)

    init {
        appCoroutineScope.launch {
            try {
                // Initial load reuses the cache when it is fresh (forceRefresh = false).
                refreshChannel.send(false)
                refreshChannel.receiveAsFlow()
                    .map { forceRefresh ->
                        val storedBloomFilter = objectStore.retrieve()
                        val hostsDataSource = hostsDataSourceProvider.createHostsDataSource()
                        val hostsDataSourceIdentifier = hostsDataSource.identifier()
                        // Force a new hosts request if the hosts are out of date or if the repo has no hosts.
                        if (!forceRefresh &&
                            storedBloomFilter != null &&
                            hostsRepository.hasHosts() &&
                            hostsPreferenceStore.identity.get() == hostsDataSourceIdentifier
                        ) {
                            return@map storedBloomFilter
                        }

                        when (val result = hostsDataSource.loadHosts()) {
                            is HostsResult.Failure -> {
                                logger.log(TAG, "Unable to load hosts", result.cause)
                                // Fall back to the cached filter instead of disabling blocking
                                // entirely: a failed download must not wipe a good cache.
                                storedBloomFilter
                            }

                            is HostsResult.Success -> {
                                // Clear out the old hosts and bloom filter now that we have the new hosts.
                                hostsRepository.removeAllHosts()
                                hostsRepository.addHosts(result.hosts)
                                hostsPreferenceStore.identity.set(hostsDataSourceIdentifier)
                                createAndSaveBloomFilter(result.hosts)
                            }
                        }
                    }
                    .flowOn(coroutineDispatchers.io)
                    .catch { e ->
                        logger.log(TAG, "Hosts load flow failed", e)
                        emit(null)
                    }
                    .collect {
                        // If we were unsuccessful in loading hosts, and we don't have hosts in the repo, don't
                        // allow initialization, as false positives will result in bad browsing experience.
                        if (hostsRepository.hasHosts() && it != null) {
                            bloomFilter.delegate = it
                            logger.log(TAG, "Finished loading bloom filter")
                        } else {
                            logger.log(TAG, "Failed to load bloom filter")
                            appCoroutineScope.launch(coroutineDispatchers.main) {
                                application.toast(R.string.ad_block_load_failure)
                            }
                        }
                        if (readyLatch.count > 0) readyLatch.countDown()
                    }
            } catch (e: Exception) {
                logger.log(TAG, "Hosts loader crashed", e)
            } finally {
                while (readyLatch.count > 0) readyLatch.countDown()
            }
        }
    }

    /**
     * Force the ad blocker to (re)populate its internal hosts filter from the provided hosts data
     * source.
     */
    fun populateAdBlockerFromDataSource(forceRefresh: Boolean) {
        refreshChannel.trySend(forceRefresh)
    }

    private suspend fun createAndSaveBloomFilter(hosts: List<Host>): BloomFilter<Host> {
        logger.log(TAG, "Constructing bloom filter from list")

        val bloomFilter = DefaultBloomFilter(
            numberOfElements = hosts.size,
            falsePositiveRate = 0.01,
            hashingAlgorithm = MurmurHashHostAdapter()
        )
        bloomFilter.putAll(hosts)
        objectStore.store(bloomFilter)

        return bloomFilter
    }

    override fun isAd(uri: Uri): Boolean {
        readyLatch.await(10, TimeUnit.SECONDS)
        val domain = uri.host() ?: return false

        val mightBeOnBlockList = bloomFilter.mightContain(domain) ||
            (domain.name.startsWith("www.") && bloomFilter.mightContain(Host(domain.name.substring(4))))

        return when {
            mightBeOnBlockList -> {
                val isOnBlockList = hostsRepository.containsHost(domain)
                if (isOnBlockList) {
                    logger.log(TAG, "URL '$uri' is an ad")
                } else {
                    logger.log(TAG, "False positive for $uri")
                }

                isOnBlockList
            }

            else -> false
        }
    }

    /**
     * Extract the [Host] from a [String] representing a URL. Returns null if no host was extracted.
     */
    private fun Uri.host(): Host? = try {
        this.host?.let(::Host)
    } catch (exception: URISyntaxException) {
        logger.log(TAG, "Invalid URL: $this", exception)
        null
    }

    companion object {
        private const val TAG = "BloomFilterAdBlocker"
        private const val BLOOM_FILTER_KEY = "AdBlockingBloomFilter"
    }
}
