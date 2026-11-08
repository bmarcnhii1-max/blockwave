package acr.browser.lightning.adblock.easylist

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AdblockMatcherTest {

    private val parser = EasyListParser()

    private fun matcherWith(vararg lines: String): AdblockMatcher {
        val matcher = AdblockMatcher()
        val text = lines.joinToString("\n")
        matcher.addRules(parser.parse(text, "test").rules)
        return matcher
    }

    @Test
    fun `domain anchor blocks host and subdomains`() {
        val matcher = matcherWith("||ads.example.com^")

        assertThat(matcher.isBlocked("https://ads.example.com/banner.js", "https://news.site/", "script")).isTrue()
        assertThat(matcher.isBlocked("https://sub.ads.example.com/banner.js", "https://news.site/", "script")).isTrue()
        assertThat(matcher.isBlocked("https://notads.example.com/banner.js", "https://news.site/", "script")).isFalse()
        assertThat(matcher.isBlocked("https://ads.example.com.evil.com/banner.js", "https://news.site/", "script")).isFalse()
    }

    @Test
    fun `substring rule blocks path fragments`() {
        val matcher = matcherWith("ads/banner.js")

        assertThat(matcher.isBlocked("https://cdn.site/ads/banner.js", "https://site/", "script")).isTrue()
        assertThat(matcher.isBlocked("https://cdn.site/js/app.js", "https://site/", "script")).isFalse()
    }

    @Test
    fun `exception rule overrides block rule`() {
        val matcher = matcherWith("||ads.example.com^", "@@||ads.example.com/good.js^")

        assertThat(matcher.isBlocked("https://ads.example.com/good.js", "https://site/", "script")).isFalse()
        assertThat(matcher.isBlocked("https://ads.example.com/bad.js", "https://site/", "script")).isTrue()
    }

    @Test
    fun `important block overrides exception`() {
        val matcher = matcherWith("||ads.example.com^\$important", "@@||ads.example.com^")

        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://site/", "script")).isTrue()
    }

    @Test
    fun `type option restricts rule to matching resource types`() {
        val matcher = matcherWith("||ads.example.com^\$script")

        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://site/", "script")).isTrue()
        assertThat(matcher.isBlocked("https://ads.example.com/x.png", "https://site/", "image")).isFalse()
    }

    @Test
    fun `domain option restricts rule to listed page domains`() {
        val matcher = matcherWith("||ads.example.com^\$domain=news.site")

        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://news.site/a", "script")).isTrue()
        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://other.site/a", "script")).isFalse()
    }

    @Test
    fun `exclusions-only domain option applies everywhere except excluded`() {
        val matcher = matcherWith("||ads.example.com^\$third-party,domain=~good.site")

        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://other.site/", "script")).isTrue()
        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://good.site/", "script")).isFalse()
    }

    @Test
    fun `third party option respects first party requests`() {
        val matcher = matcherWith("||ads.example.com^\$third-party")

        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://other.site/", "script")).isTrue()
        assertThat(matcher.isBlocked("https://ads.example.com/x.js", "https://ads.example.com/", "script")).isFalse()
    }

    @Test
    fun `non http urls are never blocked`() {
        val matcher = matcherWith("||ads.example.com^")

        assertThat(matcher.isBlocked("data:text/html,hi", "https://site/", null)).isFalse()
        assertThat(matcher.isBlocked("", "https://site/", null)).isFalse()
    }

    @Test
    fun `host extraction handles ports and userinfo`() {
        val matcher = AdblockMatcher()

        assertThat(matcher.hostOf("https://user:pass@ads.example.com:8080/x")).isEqualTo("ads.example.com")
        assertThat(matcher.hostOf("https://example.com/x")).isEqualTo("example.com")
        assertThat(matcher.hostOf("not a url")).isNull()
    }
}
