package acr.browser.lightning.adblock.easylist

import java.util.concurrent.ConcurrentHashMap

/**
 * Matches request URLs against parsed EasyList network rules.
 *
 * Indexing strategy (kept deliberately simple and allocation-light):
 * - `||domain` rules live in [domainIndex] keyed by domain and are only evaluated when the
 *   request host is that domain or a subdomain of it.
 * - All other rules live in [genericRules] and are evaluated for every request.
 * - Decisions are memoised in a bounded cache because pages re-request the same subresources
 *   on reload/scroll.
 *
 * Priority follows EasyList semantics: `$important` block rules beat exceptions, exceptions
 * beat plain block rules.
 *
 * Pure JVM, no Android dependencies — unit-tested directly.
 */
class AdblockMatcher {

    private val domainIndex = ConcurrentHashMap<String, MutableList<NetworkRule>>()
    private val genericRules = mutableListOf<NetworkRule>()
    private val decisionCache = ConcurrentHashMap<String, Boolean>()

    @Volatile
    var ruleCount: Int = 0
        private set

    fun addRules(rules: List<NetworkRule>) {
        for (rule in rules) {
            val anchor = rule.domainAnchor
            if (anchor != null) {
                domainIndex.computeIfAbsent(anchor) { mutableListOf() }.add(rule)
            } else {
                genericRules.add(rule)
            }
        }
        ruleCount = genericRules.size + domainIndex.values.sumOf { it.size }
        decisionCache.clear()
    }

    fun clear() {
        domainIndex.clear()
        genericRules.clear()
        decisionCache.clear()
        ruleCount = 0
    }

    /** Returns a copy of all rules currently held by this matcher. */
    fun snapshot(): List<NetworkRule> {
        val all = ArrayList<NetworkRule>(ruleCount)
        all.addAll(genericRules)
        for (rules in domainIndex.values) all.addAll(rules)
        return all
    }

    /**
     * @param requestUrl full URL of the resource being requested.
     * @param pageUrl full URL of the page that triggered the request, or null if unknown.
     * @param resourceType EasyList type vocabulary, or null if unknown.
     * @return true if the request should be blocked.
     */
    fun isBlocked(requestUrl: String, pageUrl: String?, resourceType: String?): Boolean {
        if (requestUrl.isEmpty()) return false
        val host = hostOf(requestUrl) ?: return false
        val pageHost = pageUrl?.let { hostOf(it) }
        val isThirdParty = pageHost != null && !isFirstParty(host, pageHost)

        val cacheKey = requestUrl + "|" + (pageHost ?: "") + "|" + (resourceType ?: "")
        decisionCache[cacheKey]?.let { return it }

        var blocked = false
        var excepted = false
        var importantBlocked = false

        for (rule in candidates(host)) {
            if (!appliesTo(rule, pageHost, resourceType, isThirdParty)) continue
            if (!rule.regex.containsMatchIn(requestUrl)) continue
            when {
                rule.important && !rule.isException -> importantBlocked = true
                rule.isException -> excepted = true
                else -> blocked = true
            }
        }

        val result = when {
            importantBlocked -> true
            excepted -> false
            else -> blocked
        }

        if (decisionCache.size > MAX_CACHE_ENTRIES) decisionCache.clear()
        decisionCache[cacheKey] = result
        return result
    }

    private fun candidates(host: String): List<NetworkRule> {
        // Domain rules for the host itself and every parent suffix (sub.example.com ->
        // sub.example.com, example.com, com is skipped: single-label suffixes are useless).
        val indexed = ArrayList<NetworkRule>()
        var current = host
        while (true) {
            domainIndex[current]?.let { indexed.addAll(it) }
            val dot = current.indexOf('.')
            if (dot < 0) break
            current = current.substring(dot + 1)
            if (!current.contains('.')) break
        }
        if (indexed.isEmpty()) return genericRules
        // Avoid mutating the shared generic list while iterating.
        val all = ArrayList<NetworkRule>(genericRules.size + indexed.size)
        all.addAll(genericRules)
        all.addAll(indexed)
        return all
    }

    private fun appliesTo(
        rule: NetworkRule,
        pageHost: String?,
        resourceType: String?,
        isThirdParty: Boolean,
    ): Boolean {
        if (!EasyListParser.typeMatches(rule.types, resourceType)) return false
        if (rule.thirdParty != null && pageHost != null && rule.thirdParty != isThirdParty) {
            return false
        }
        if (pageHost != null) {
            if (rule.domains != null && rule.domains.none { domainMatches(pageHost, it) }) {
                return false
            }
            if (rule.excludedDomains != null && rule.excludedDomains.any { domainMatches(pageHost, it) }) {
                return false
            }
        }
        return true
    }

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun isFirstParty(requestHost: String, pageHost: String): Boolean =
        requestHost == pageHost || requestHost.endsWith(".$pageHost") ||
            pageHost.endsWith(".$requestHost")

    /** Extracts the lowercase host from an http(s) URL, or null if there is none. */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        var start = schemeEnd + 3
        // Strip userinfo.
        val at = url.indexOf('@', start)
        val slash = url.indexOf('/', start).takeIf { it >= 0 } ?: url.length
        if (at >= 0 && at < slash) start = at + 1
        var end = slash
        val colon = url.indexOf(':', start)
        if (colon >= 0 && colon < end) end = colon
        if (end <= start) return null
        return url.substring(start, end).lowercase()
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 20_000
    }
}
