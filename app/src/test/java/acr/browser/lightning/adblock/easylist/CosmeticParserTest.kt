package acr.browser.lightning.adblock.easylist

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CosmeticParserTest {

    private val parser = CosmeticParser()

    @Test
    fun `general and domain selectors are parsed`() {
        val parsed = parser.parse(
            """
            ##.banner-ad
            example.com##.sponsored
            example.com,sub.example.org##.promo
            """.trimIndent()
        )

        assertThat(parsed.rules.generalSelectors).containsExactly(".banner-ad")
        assertThat(parsed.rules.domainSelectors["example.com"]).containsExactly(".sponsored", ".promo")
        assertThat(parsed.rules.domainSelectors["sub.example.org"]).containsExactly(".promo")
    }

    @Test
    fun `exception selectors are parsed per domain`() {
        val parsed = parser.parse("example.com#@#.sponsored\n",)

        assertThat(parsed.rules.domainExceptions["example.com"]).containsExactly(".sponsored")
    }

    @Test
    fun `unsafe selectors are rejected`() {
        val parsed = parser.parse(
            """
            ##.foo{color:red}
            ##</style><script>alert(1)</script>
            ##.bar; color:red
            ##.ok-class
            """.trimIndent()
        )

        assertThat(parsed.rules.generalSelectors).containsExactly(".ok-class")
        assertThat(parsed.skippedUnsafe).isEqualTo(3)
    }

    @Test
    fun `procedural cosmetics are skipped`() {
        val parsed = parser.parse(
            """
            example.com#?#.foo
            example.com#${'$'}#.foo
            ##.banner
            """.trimIndent()
        )

        assertThat(parsed.rules.generalSelectors).containsExactly(".banner")
    }

    @Test
    fun `cssFor combines general and domain selectors minus exceptions`() {
        val rules = CosmeticRules(
            generalSelectors = listOf(".banner-ad"),
            domainSelectors = mapOf("example.com" to listOf(".sponsored", ".promo")),
            domainExceptions = mapOf("example.com" to setOf(".promo")),
        )

        val css = rules.cssFor("https://sub.example.com/page")

        assertThat(css).contains(".banner-ad{display:none!important}")
        assertThat(css).contains(".sponsored{display:none!important}")
        assertThat(css).doesNotContain(".promo")
    }

    @Test
    fun `cssFor returns null when nothing applies`() {
        val rules = CosmeticRules(
            generalSelectors = emptyList(),
            domainSelectors = mapOf("other.com" to listOf(".x")),
            domainExceptions = emptyMap(),
        )

        assertThat(rules.cssFor("https://example.com/")).isNull()
    }
}
