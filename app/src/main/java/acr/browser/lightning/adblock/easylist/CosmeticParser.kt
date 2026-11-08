package acr.browser.lightning.adblock.easylist

/**
 * Result of parsing cosmetic rules from filter list texts.
 */
data class ParsedCosmetics(
    val rules: CosmeticRules,
    val skippedUnsafe: Int,
)

/**
 * Parser for the cosmetic part of EasyList-style filter lists (`##`, `#@#`).
 *
 * Supported:
 * - `##selector` — general hiding selector (applies everywhere)
 * - `domain1,domain2##selector` — domain-specific hiding selector
 * - `domain#@#selector` — exception (un-hide) selector
 *
 * Intentionally skipped:
 * - procedural cosmetics (`#?#`, `#$#`, `#$@#`)
 * - selectors containing characters that could break out of a `<style>` element or inject
 *   arbitrary CSS/JS (`{`, `}`, `<`, `>`, newline, `url(`, `expression(`, `javascript:`, `;` in
 *   declaration position). Selectors are validated conservatively: a suspicious selector is
 *   dropped, never sanitized into something else.
 *
 * Pure JVM, Android-free — unit-tested directly.
 */
class CosmeticParser {

    fun parse(lines: Sequence<String>): ParsedCosmetics {
        val general = LinkedHashSet<String>()
        val domain = mutableMapOf<String, MutableList<String>>()
        val exceptions = mutableMapOf<String, MutableSet<String>>()
        var skippedUnsafe = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            val isExceptionRule = line.contains("#@#")
            val isHidingRule = !isExceptionRule && line.contains("##")
            val isProcedural = line.contains("#?#") || line.contains("#\$#") || line.contains("#@\$#")
            if (isProcedural) continue
            if (!isExceptionRule && !isHidingRule) continue

            val delimiter = if (isExceptionRule) "#@#" else "##"
            val splitAt = line.indexOf(delimiter)
            val domainsPart = line.substring(0, splitAt)
            val selector = line.substring(splitAt + delimiter.length).trim()
            if (selector.isEmpty() || !isSafeSelector(selector)) {
                skippedUnsafe++
                continue
            }

            val domains = domainsPart.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (domains.isEmpty()) {
                if (!isExceptionRule) general.add(selector)
                // A general exception (#@# without domains) un-hides everywhere; modeled by
                // removing the selector from the general set at CSS build time is not possible
                // here, so it is simply ignored (exceptions are best-effort in MVP).
            } else {
                for (d in domains) {
                    if (isExceptionRule) {
                        exceptions.getOrPut(d) { mutableSetOf() }.add(selector)
                    } else {
                        domain.getOrPut(d) { mutableListOf() }.add(selector)
                    }
                }
            }
        }

        return ParsedCosmetics(
            CosmeticRules(general.toList(), domain, exceptions),
            skippedUnsafe,
        )
    }

    fun parse(text: String): ParsedCosmetics = parse(text.lineSequence())

    private fun isSafeSelector(selector: String): Boolean {
        if (selector.any { it == '\n' || it == '\r' }) return false
        if (selector.contains('{') || selector.contains('}') ||
            selector.contains('<') || selector.contains('>')
        ) {
            return false
        }
        val lower = selector.lowercase()
        if (lower.contains("url(") || lower.contains("expression(") ||
            lower.contains("javascript:") || lower.contains("@import")
        ) {
            return false
        }
        // ';' is only dangerous in declaration position; selectors legitimately contain
        // ';' rarely, so reject to stay conservative.
        if (selector.contains(';')) return false
        return true
    }
}
