package acr.browser.lightning.adblock.easylist

/**
 * A single EasyList network rule, compiled to a [Regex].
 *
 * Cosmetic rules (`##`, `#@#`, …) and regex-pattern rules are intentionally not represented:
 * the parser skips them (see [EasyListParser]). Cosmetic hiding is a documented Phase-2 item.
 *
 * @param regex compiled pattern, matched with [Regex.containsMatchIn] against the full URL.
 * @param isException true for `@@` exception rules.
 * @param important true if the rule carries the `$important` option, which overrides exceptions.
 * @param types resource types this rule applies to (null = all types). Values use the
 * EasyList type vocabulary (`script`, `image`, `stylesheet`, `xmlhttprequest`, `subdocument`,
 * `document`, `media`, `font`, `object`, `other`, `ping`, `websocket`, `webrtc`).
 * @param domains page domains the rule applies to (null = all pages), suffix-matched.
 * @param excludedDomains page domains the rule does NOT apply to.
 * @param thirdParty null = any party, true = third-party only, false = first-party only.
 * @param domainAnchor the host extracted from a `||domain` anchor, used for indexing.
 * @param listId identifier of the filter list this rule came from.
 */
data class NetworkRule(
    val regex: Regex,
    val isException: Boolean,
    val important: Boolean,
    val types: Set<String>?,
    val domains: Set<String>?,
    val excludedDomains: Set<String>?,
    val thirdParty: Boolean?,
    val domainAnchor: String?,
    val listId: String,
)
