package acr.browser.lightning.adblock.easylist

/**
 * A downloadable EasyList-style filter list.
 *
 * @param id stable identifier used for the on-disk cache file and the enabled-lists preference.
 * @param name display name shown in settings.
 * @param url download URL of the list (fetched at runtime, never bundled in git).
 * @param tier Core lists are enabled by default; Extended lists are only enabled via the
 * "Maximum" toggle because they add weight and breakage risk.
 */
data class FilterList(
    val id: String,
    val name: String,
    val url: String,
    val tier: Tier,
) {
    enum class Tier { CORE, EXTENDED }
}

/**
 * The filter list registry. Adding a list is a single entry here; downloader, parser and
 * matcher are list-agnostic. Rule counts in comments are approximate — lists change daily
 * and overlap heavily, so ~80% of coverage comes from the first Core entries.
 */
object FilterListRegistry {

    val ALL: List<FilterList> = listOf(
        // Core — ON by default. All URLs verified reachable (2026-11-08):
        // the formerly used easylistrussian.txt / fanboy-annoyances.txt / AdGuard URLs
        // returned 404 / were unreachable, so the RU parts are fetched from the official
        // easylist/ruadlist GitHub repo instead (the combined easylistrussian.txt is
        // generated from these parts).
        FilterList(
            id = "easylist",
            name = "EasyList",
            url = "https://easylist.to/easylist/easylist.txt",
            tier = FilterList.Tier.CORE,
        ),
        FilterList(
            id = "easyprivacy",
            name = "EasyPrivacy",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            tier = FilterList.Tier.CORE,
        ),
        FilterList(
            id = "ru-advblock",
            name = "RU AdList (ads)",
            url = "https://raw.githubusercontent.com/easylist/ruadlist/master/advblock.txt",
            tier = FilterList.Tier.CORE,
        ),
        FilterList(
            id = "ru-bitblock",
            name = "RU AdList (trackers)",
            url = "https://raw.githubusercontent.com/easylist/ruadlist/master/bitblock.txt",
            tier = FilterList.Tier.CORE,
        ),
        FilterList(
            id = "ru-cntblock",
            name = "RU AdList (counters)",
            url = "https://raw.githubusercontent.com/easylist/ruadlist/master/cntblock.txt",
            tier = FilterList.Tier.CORE,
        ),
        // Extended — OFF by default, enabled with the "Maximum" toggle.
        // Parts of EasyList Adult (easylist/easylist repo); block parts only, since
        // $popup rules are not supported by the parser.
        FilterList(
            id = "adult-block",
            name = "EasyList Adult (site blocks)",
            url = "https://raw.githubusercontent.com/easylist/easylist/master/easylist_adult/adult_specific_block.txt",
            tier = FilterList.Tier.EXTENDED,
        ),
        FilterList(
            id = "adult-hide",
            name = "EasyList Adult (hide)",
            url = "https://raw.githubusercontent.com/easylist/easylist/master/easylist_adult/adult_specific_hide.txt",
            tier = FilterList.Tier.EXTENDED,
        ),
        FilterList(
            id = "adult-thirdparty",
            name = "EasyList Adult (third-party)",
            url = "https://raw.githubusercontent.com/easylist/easylist/master/easylist_adult/adult_thirdparty.txt",
            tier = FilterList.Tier.EXTENDED,
        ),
    )

    val CORE: List<FilterList> = ALL.filter { it.tier == FilterList.Tier.CORE }
    val EXTENDED: List<FilterList> = ALL.filter { it.tier == FilterList.Tier.EXTENDED }

    val DEFAULT_ENABLED_IDS: Set<String> = CORE.map { it.id }.toSet()

    fun byId(id: String): FilterList? = ALL.firstOrNull { it.id == id }
}
