package acr.browser.lightning.adblock.easylist

/**
 * Cosmetic (element-hiding) rules extracted from filter lists.
 *
 * @param generalSelectors selectors applying to every page (from `##selector` rules).
 * @param domainSelectors selectors applying to specific domains (from `domain##selector` rules).
 * @param domainExceptions selectors un-hidden per domain (from `domain#@#selector` rules).
 */
data class CosmeticRules(
    val generalSelectors: List<String>,
    val domainSelectors: Map<String, List<String>>,
    val domainExceptions: Map<String, Set<String>>,
) {
    /**
     * Selectors hiding ad elements on [pageUrl], domain-specific first, then general.
     * Exceptions are subtracted. Empty if nothing applies.
     */
    fun selectorsFor(pageUrl: String): List<String> {
        val host = AdblockMatcher().hostOf(pageUrl) ?: return emptyList()
        val selectors = LinkedHashSet<String>()
        for ((domain, list) in domainSelectors) {
            if (host == domain || host.endsWith(".$domain")) {
                selectors.addAll(list)
            }
        }
        selectors.addAll(generalSelectors)
        for ((domain, exceptions) in domainExceptions) {
            if (host == domain || host.endsWith(".$domain")) {
                selectors.removeAll(exceptions)
            }
        }
        return selectors.toList()
    }

    /**
     * Builds the CSS text hiding ad elements on [pageUrl], or null if nothing applies.
     * Prefer [selectorsFor] + chunked injection: a single CSS blob with tens of thousands
     * of selectors exceeds WebView's evaluateJavascript size limits.
     */
    fun cssFor(pageUrl: String): String? {
        val selectors = selectorsFor(pageUrl)
        if (selectors.isEmpty()) return null
        // NOTE: rules must be concatenated WITHOUT separators. Joining separate rules with
        // commas (sel{...},sel{...}) makes the CSS error recovery drop everything after the
        // first stray comma, silently discarding ~99% of the rules.
        return selectors.joinToString("") { "$it{display:none!important}" }
    }

    val totalSelectors: Int
        get() = generalSelectors.size + domainSelectors.values.sumOf { it.size }

    companion object {
        val EMPTY = CosmeticRules(emptyList(), emptyMap(), emptyMap())
    }
}
