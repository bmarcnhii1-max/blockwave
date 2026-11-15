package acr.browser.lightning.adblock.easylist

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EasyListParserTest {

    private val parser = EasyListParser()

    @Test
    fun `domain anchor rule is parsed and indexed`() {
        val parsed = parser.parse("||ads.example.com^\n", "test")

        assertThat(parsed.rules).hasSize(1)
        assertThat(parsed.rules[0].domainAnchor).isEqualTo("ads.example.com")
        assertThat(parsed.rules[0].isException).isFalse()
    }

    @Test
    fun `exception and important options are parsed`() {
        val parsed = parser.parse("@@||good.example.com/ads^\$important\n", "test")

        assertThat(parsed.rules).hasSize(1)
        assertThat(parsed.rules[0].isException).isTrue()
        assertThat(parsed.rules[0].important).isTrue()
    }

    @Test
    fun `cosmetic and regex rules are skipped`() {
        val parsed = parser.parse(
            """
            example.com##.banner
            example.com#@#.banner
            /ad[0-9]+\.js/
            ||ads.example.com^
            """.trimIndent(), "test"
        )

        assertThat(parsed.rules).hasSize(1)
        assertThat(parsed.skippedCosmetic).isEqualTo(2)
        assertThat(parsed.skippedUnsupported).isEqualTo(1)
    }

    @Test
    fun `unsupported options skip the rule`() {
        val parsed = parser.parse(
            """
            ||ads.example.com^${'$'}redirect=noop.js
            ||ads.example.com^${'$'}script,image
            """.trimIndent(), "test"
        )

        assertThat(parsed.rules).hasSize(1)
        assertThat(parsed.rules[0].types).containsExactly("script", "image")
    }

    @Test
    fun `supplement first-party pre-roll path is blocked`() {
        val parsed = parser.parse("||evtubescms.phncdn.com/pre_videos/\n", "blockwave")

        assertThat(parsed.rules).hasSize(1)

        val matcher = AdblockMatcher()
        matcher.addRules(parsed.rules)
        assertThat(
            matcher.isBlocked(
                "https://evtubescms.phncdn.com/pre_videos/002/945/492/2945492/mp4_720.mp4",
                "https://www.pornhub.com/",
                "media"
            )
        ).isTrue()
        // Normal video CDN traffic must not match.
        assertThat(
            matcher.isBlocked(
                "https://pix-fl.phncdn.com/c6371/videos/202507/20/16480875/original_16480875.mp4",
                "https://www.pornhub.com/",
                "media"
            )
        ).isFalse()
    }

    @Test
    fun `popup option matches main frame documents`() {
        val parsed = parser.parse("||ads.example.com^${'$'}popup\n", "test")

        assertThat(parsed.rules).hasSize(1)
        assertThat(parsed.rules[0].types).containsExactly("document")

        val matcher = AdblockMatcher()
        matcher.addRules(parsed.rules)
        assertThat(matcher.isBlocked("https://ads.example.com/popup.html", "https://site/", "document")).isTrue()
        assertThat(matcher.isBlocked("https://ads.example.com/popup.html", "https://site/", "script")).isFalse()
    }

    @Test
    fun `domain option with exclusions is parsed`() {
        val parsed = parser.parse("||ads.example.com^\$domain=example.com|~good.example.com\n", "test")

        assertThat(parsed.rules).hasSize(1)
        assertThat(parsed.rules[0].domains).containsExactly("example.com")
        assertThat(parsed.rules[0].excludedDomains).containsExactly("good.example.com")
    }

    @Test
    fun `comments and headers are skipped`() {
        val parsed = parser.parse(
            """
            [Adblock Plus 2.0]
            ! comment
            # comment-like cosmetic is still cosmetic
            ||ads.example.com^
            """.trimIndent(), "test"
        )

        assertThat(parsed.rules).hasSize(1)
    }
}
