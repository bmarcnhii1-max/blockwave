package acr.browser.lightning.adblock.easylist

import java.util.concurrent.ConcurrentHashMap

/**
 * Matches request URLs against parsed EasyList network rules.
 *
 * Thread safety: the rule set is held in an immutable [Rules] snapshot and swapped atomically.
 * [isBlocked] reads one snapshot into a local variable, so a concurrent [addRules]/[clear]
 * (e.g. the user tapping "Update filter lists" while a page is loading) can never observe a
 * partially built index or throw [ConcurrentModificationException].
 *
 * Indexing strategy:
 * - `||domain` rules live in [Rules.domainIndex] keyed by domain and are only evaluated when the
 *   request host is that domain or a subdomain of it.
 * - All other rules live in [Rules.genericRules] and are evaluated for every request.
 * - Decisions are memoised in a bounded cache because pages re-request the same subresources
 *   on reload/scroll.
 *
 * Priority follows EasyList semantics: `$important` block rules beat exceptions, exceptions
 * beat plain block rules.
 *
 * Pure JVM, no Android dependencies — unit-tested directly.
 */
class AdblockMatcher {

    private data class Rules(
        val domainIndex: Map<String, List<NetworkRule>>,
        val genericRules: List<NetworkRule>,
    )

    @Volatile
    private var rules: Rules = Rules(emptyMap(), emptyList())

    private val decisionCache = ConcurrentHashMap<String, Boolean>()

    @Volatile
    var ruleCount: Int = 0
        private set

    fun addRules(newRules: List<NetworkRule>) {
        val current = rules
        val index = LinkedHashMap<String, MutableList<NetworkRule>>()
        for ((key, list) in current.domainIndex) {
            index.getOrPut(key) { ArrayList() }.addAll(list)
        }
        val generic = ArrayList<NetworkRule>(current.genericRules.size + newRules.size)
        generic.addAll(current.genericRules)
        for (rule in newRules) {
            val anchor = rule.domainAnchor
            if (anchor != null) {
                index.getOrPut(anchor) { ArrayList() }.add(rule)
            } else {
                generic.add(rule)
            }
        }
        val immutable = Rules(
            domainIndex = index.mapValues { it.value.toList() },
            genericRules = generic.toList(),
        )
        rules = immutable
        ruleCount = immutable.genericRules.size + immutable.domainIndex.values.sumOf { it.size }
        decisionCache.clear()
    }

    fun clear() {
        rules = Rules(emptyMap(), emptyList())
        ruleCount = 0
        decisionCache.clear()
    }

    /** Returns a copy of all rules currently held by this matcher. */
    fun snapshot(): List<NetworkRule> {
        val current = rules
        val all = ArrayList<NetworkRule>(ruleCount)
        all.addAll(current.genericRules)
        for (list in current.domainIndex.values) all.addAll(list)
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
        val current = rules

        val cacheKey = requestUrl + "|" + (pageHost ?: "") + "|" + (resourceType ?: "")
        decisionCache[cacheKey]?.let { return it }

        var blocked = false
        var excepted = false
        var importantBlocked = false

        for (rule in candidates(current, host)) {
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

    private fun candidates(current: Rules, host: String): List<NetworkRule> {
        // Domain rules for the host itself and every parent suffix (sub.example.com ->
        // sub.example.com, example.com; single-label suffixes are skipped as useless).
        val indexed = ArrayList<NetworkRule>()
        var cursor = host
        while (true) {
            current.domainIndex[cursor]?.let { indexed.addAll(it) }
            val dot = cursor.indexOf('.')
            if (dot < 0) break
            cursor = cursor.substring(dot + 1)
            if (!cursor.contains('.')) break
        }
        if (indexed.isEmpty()) return current.genericRules
        val all = ArrayList<NetworkRule>(current.genericRules.size + indexed.size)
        all.addAll(current.genericRules)
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
        if (pageHost == null) {
            // Without page context we cannot evaluate page-scoped options. Skip the rule rather
            // than applying a $third-party/$domain restriction we cannot verify.
            if (rule.thirdParty != null || rule.domains != null || rule.excludedDomains != null) {
                return false
            }
            return true
        }
        if (rule.thirdParty != null && rule.thirdParty != isThirdParty) return false
        if (rule.domains != null && rule.domains.none { domainMatches(pageHost, it) }) {
            return false
        }
        if (rule.excludedDomains != null && rule.excludedDomains.any { domainMatches(pageHost, it) }) {
            return false
        }
        return true
    }

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /**
     * First-party means the two hosts share a registrable base domain (eTLD+1 approximation).
     * Sibling subdomains (`a.example.org` vs `b.example.org`) are therefore first-party, which
     * matches Adblock Plus / uBlock Origin semantics and prevents `$third-party` overblocking.
     */
    private fun isFirstParty(requestHost: String, pageHost: String): Boolean =
        baseDomain(requestHost) == baseDomain(pageHost)

    /** Extracts the lowercase host from a URL with an authority, or null if there is none. */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val start = schemeEnd + 3
        var authorityStart = start
        // Strip userinfo.
        val at = url.indexOf('@', start)
        val authorityEnd = listOf('/', '?', '#')
            .mapNotNull { url.indexOf(it, start).takeIf { i -> i >= 0 } }
            .minOrNull() ?: url.length
        if (at >= 0 && at < authorityEnd) authorityStart = at + 1
        if (authorityEnd <= authorityStart) return null
        val authority = url.substring(authorityStart, authorityEnd)
        // IPv6 literal: [2001:db8::1]:8080
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            return authority.substring(1, close).lowercase()
        }
        val colon = authority.indexOf(':')
        val host = if (colon >= 0) authority.substring(0, colon) else authority
        if (host.isEmpty()) return null
        return host.lowercase()
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 20_000

        /** Common two-level public suffixes; used to approximate the registrable domain. */
        private val TWO_LEVEL_SUFFIXES = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au",
            "co.jp", "or.jp", "ne.jp", "com.br", "com.mx", "com.ar", "co.za", "co.nz",
            "co.in", "com.hk", "com.tw", "com.cn", "co.kr", "com.tr", "co.il", "co.id",
            "com.sg", "com.ph", "co.th", "com.vn", "com.ua", "com.pl", "com.eg",
            "co.ke", "com.ng", "com.pk", "com.bd", "co.tz", "co.ug", "com.my",
        )

        /**
         * Approximates the registrable domain (eTLD+1) without a full public suffix list.
         * `a.example.org` -> `example.org`; `a.example.co.uk` -> `example.co.uk`.
         */
        fun baseDomain(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val lastTwo = parts.takeLast(2).joinToString(".")
            return if (lastTwo in TWO_LEVEL_SUFFIXES && parts.size >= 3) {
                parts.takeLast(3).joinToString(".")
            } else {
                lastTwo
            }
        }
    }
}
