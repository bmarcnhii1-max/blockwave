package acr.browser.lightning.adblock.easylist

/**
 * Result of parsing one filter list text.
 *
 * @param rules successfully compiled network rules.
 * @param skippedCosmetic cosmetic rules skipped (Phase-2 feature).
 * @param skippedUnsupported rules skipped because of unsupported syntax/options.
 */
data class ParsedRules(
    val rules: List<NetworkRule>,
    val skippedCosmetic: Int,
    val skippedUnsupported: Int,
)

/**
 * Parser for EasyList-style filter lists.
 *
 * Supported network-rule syntax:
 * - `@@` exception rules, `$important`
 * - `||domain` anchor, leading `|` start anchor, trailing `|` end anchor
 * - `*` wildcard, `^` separator placeholder
 * - `$` options: resource types, `domain=` (incl. `~` exclusions), `third-party` / `~third-party`
 *
 * Intentionally unsupported (rule is skipped, never miscompiled):
 * - cosmetic rules (`##`, `#@#`, `#?#`, `#$#`, …)
 * - regex-pattern rules (`/pattern/…`)
 * - `$popup`, `$redirect`, `$csp`, `$removeparam`, `$header`, `$jsonprune`, `$rewrite`, …
 *
 * The parser is pure JVM and Android-free so it can be unit-tested directly.
 */
class EasyListParser {

    fun parse(lines: Sequence<String>, listId: String): ParsedRules {
        val rules = ArrayList<NetworkRule>()
        var skippedCosmetic = 0
        var skippedUnsupported = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("#")) continue
            if (isCosmetic(line)) {
                skippedCosmetic++
                continue
            }
            val rule = parseLine(line, listId)
            if (rule != null) {
                rules.add(rule)
            } else {
                skippedUnsupported++
            }
        }

        return ParsedRules(rules, skippedCosmetic, skippedUnsupported)
    }

    fun parse(text: String, listId: String): ParsedRules =
        parse(text.lineSequence(), listId)

    private fun isCosmetic(line: String): Boolean =
        line.contains("##") || line.contains("#@#") || line.contains("#?#") ||
            line.contains("#\$#") || line.contains("#@\$#")

    private fun parseLine(line: String, listId: String): NetworkRule? {
        var rest = line
        var isException = false
        if (rest.startsWith("@@")) {
            isException = true
            rest = rest.substring(2)
        }
        if (rest.isEmpty()) return null
        // Regex-pattern rules (/pattern/options) are not supported in MVP.
        if (rest.startsWith("/")) return null

        val optionsIndex = rest.indexOf('$')
        val patternPart: String
        val optionsPart: String?
        if (optionsIndex >= 0) {
            patternPart = rest.substring(0, optionsIndex)
            optionsPart = rest.substring(optionsIndex + 1)
        } else {
            patternPart = rest
            optionsPart = null
        }
        if (patternPart.isEmpty()) return null

        var important = false
        var types: MutableSet<String>? = null
        var domains: MutableSet<String>? = null
        var excludedDomains: MutableSet<String>? = null
        var thirdParty: Boolean? = null

        if (optionsPart != null) {
            for (rawOption in optionsPart.split(',')) {
                val option = rawOption.trim().lowercase()
                if (option.isEmpty()) continue
                when {
                    option == "important" -> important = true
                    option == "third-party" -> thirdParty = true
                    option == "~third-party" -> thirdParty = false
                    option in SUPPORTED_TYPES -> {
                        if (types == null) types = mutableSetOf()
                        types.add(option)
                    }
                    option.startsWith("~") && option.substring(1) in SUPPORTED_TYPES -> {
                        // Negated types (~script) restrict the rule to every other type.
                        // Represented as "all except" via a marker prefix.
                        if (types == null) types = mutableSetOf(ALL_BUT_MARKER)
                        types.add("~" + option.substring(1))
                    }
                    option.startsWith("domain=") -> {
                        val parsed = parseDomainOption(option.removePrefix("domain="))
                            ?: return null
                        // `$domain=~a|~b` (exclusions only) applies to ALL pages except the
                        // excluded ones. Representing it as an empty include-set would make
                        // the rule match nothing, so keep domains = null in that case.
                        domains = if (parsed.first.isEmpty()) null else parsed.first
                        excludedDomains = if (parsed.second.isEmpty()) null else parsed.second
                    }
                    else -> return null // unsupported option: skip the rule safely
                }
            }
        }

        val compiled = compilePattern(patternPart) ?: return null

        return NetworkRule(
            regex = compiled.first,
            isException = isException,
            important = important,
            types = types,
            domains = domains,
            excludedDomains = excludedDomains,
            thirdParty = thirdParty,
            domainAnchor = compiled.second,
            listId = listId,
        )
    }

    private fun parseDomainOption(value: String): Pair<MutableSet<String>, MutableSet<String>>? {
        val included = mutableSetOf<String>()
        val excluded = mutableSetOf<String>()
        for (part in value.split('|')) {
            val domain = part.trim().lowercase()
            if (domain.isEmpty()) return null
            if (domain.startsWith("~")) {
                excluded.add(domain.substring(1))
            } else {
                included.add(domain)
            }
        }
        return included to excluded
    }

    /**
     * Compiles an EasyList pattern to a [Regex]. Returns the regex plus the extracted
     * `||domain` anchor (if any) used for indexing in [AdblockMatcher].
     */
    private fun compilePattern(pattern: String): Pair<Regex, String?>? {
        var anchor: String? = null
        var core = pattern
        var prefix = ""
        var suffix = ""

        when {
            core.startsWith("||") -> {
                // ||example.com^, ||example.com:8080/path, ||example.com|
                var end = core.length
                for (i in 2 until core.length) {
                    val c = core[i]
                    if (c == '^' || c == '/' || c == '?' || c == '|') {
                        end = i
                        break
                    }
                }
                val hostPart = core.substring(2, end).lowercase()
                if (hostPart.isEmpty()) return null
                // Split off an explicit :port so the anchor stays a bare registrable host.
                val colonIdx = hostPart.indexOf(':')
                val bareHost = if (colonIdx >= 0) hostPart.substring(0, colonIdx) else hostPart
                val portPart = if (colonIdx >= 0) hostPart.substring(colonIdx) else ""
                if (bareHost.isEmpty() || !bareHost.contains('.')) return null
                anchor = bareHost
                // Note: '.' is deliberately NOT a valid separator after the host, so
                // ||example.com does not match example.com.evil.com. The separator after
                // the host is enforced exactly once: either by the group below (pure
                // `||domain` / `||domain^`), or by the first char of the remaining core
                // (usually '/'). A leading '^' in the core is therefore stripped.
                // Any URL scheme is accepted (http/https/ws/…) like Adblock Plus.
                prefix = "^[a-zA-Z][a-zA-Z0-9+.\\-]*:(?://)?([^/]*\\.)?" +
                    Regex.escape(bareHost) + Regex.escape(portPart)
                core = core.substring(end)
                when {
                    core.isEmpty() -> suffix = "(:|/|\\?|#|$)"
                    core == "|" || core == "^|" -> {
                        // Trailing | end anchor (ABP converts ^| to ^).
                        suffix = "$"
                        core = ""
                    }
                    core.startsWith("^") -> {
                        suffix = "(:|/|\\?|#|$)"
                        core = core.substring(1)
                    }
                    else -> Unit // core starts with '/' or another separator char itself
                }
            }

            core.startsWith("|") -> {
                prefix = "^"
                core = core.substring(1)
            }
        }

        if (core.endsWith("|") && !core.endsWith("||") && core.length > 1) {
            suffix = "$"
            core = core.substring(0, core.length - 1)
        }

        if (core.isEmpty() && anchor == null) return null

        val body = convertCore(core)
        val regexString = prefix + body + suffix
        return try {
            Regex(regexString, RegexOption.IGNORE_CASE) to anchor
        } catch (e: Exception) {
            null
        }
    }

    private fun convertCore(core: String): String {
        val out = StringBuilder(core.length + 8)
        for (c in core) {
            when (c) {
                '*' -> out.append(".*")
                '^' -> out.append(SEPARATOR)
                else -> out.append(Regex.escape(c.toString()))
            }
        }
        return out.toString()
    }

    companion object {
        private const val ALL_BUT_MARKER = "__all_but__"

        // Adblock Plus treats everything outside [0-9a-zA-Z-._~%] as a separator,
        // so `:`, `@`, `=`, `&`, `;`, `,`, `!`, `'` etc. all terminate a token.
        private const val SEPARATOR = "(?:[^a-zA-Z0-9\\-._~%]|$)"

        private val SUPPORTED_TYPES = setOf(
            "script", "image", "stylesheet", "xmlhttprequest", "subdocument", "document",
            "media", "font", "object", "other", "ping", "websocket", "webrtc",
        )

        /** True if [types] (with possible `~type` negations) matches [resourceType]. */
        fun typeMatches(types: Set<String>?, resourceType: String?): Boolean {
            if (types == null) return true
            // Unknown resource type must NOT satisfy a type-restricted rule: the WebView
            // worker only knows extension-derived types, and treating XHR/iframe traffic
            // as `image`/`script` is a real false-positive source.
            if (resourceType == null) return false
            return if (types.contains(ALL_BUT_MARKER)) {
                !types.contains("~$resourceType")
            } else {
                types.contains(resourceType)
            }
        }
    }
}
