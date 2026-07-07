package eu.kanade.tachiyomi.extension.vi.dilib

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
abstract class Dilib :
    HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .setRandomUserAgent()

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl$SEARCH_PATH".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("media", BOOK_TYPE)
            addQueryParameter("sort", POPULAR_ORDER)
        }.build()

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseBrowsePage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl$LIST_PATH/page/$page"
        } else {
            "$baseUrl$LIST_PATH"
        }

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = filters.ifEmpty { getFilterList() }

        val mainCategory = filterList.firstInstanceOrNull<MainCategoriesFilter>()?.toUriPart() ?: DEFAULT_MAINCATEGORY
        val subCategory = filterList.firstInstanceOrNull<SubCategoriesFilter>()?.toUriPart() ?: DEFAULT_SUBCATEGORY
        val author = filterList.firstInstanceOrNull<AuthorFilter>()?.state ?: ""
        val order = filterList.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: DEFAULT_ORDER

        val url = "$baseUrl$SEARCH_PATH".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("find", query)
            }
            if (mainCategory != DEFAULT_MAINCATEGORY) {
                addQueryParameter("chinh", mainCategory)
            }
            if (subCategory != DEFAULT_SUBCATEGORY) {
                addQueryParameter("phu", subCategory)
            }
            if (author.isNotBlank()) {
                addQueryParameter("author", author)
            }
            addQueryParameter("media", BOOK_TYPE)
            if (order != DEFAULT_ORDER) {
                addQueryParameter("sort", order)
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseBrowsePage(response)

    // ============================== Manga List ============================

    private fun parseBrowsePage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.products.row > div.type-product").mapNotNull { element ->
            val mangaLink = element.selectFirst(".block_product_thumbnail a, .block_product_content a") ?: return@mapNotNull null
            val mangaTitle = element.selectFirst(".block_product_content a")?.text() ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(mangaLink.absUrl("href"))
                title = mangaTitle
                thumbnail_url = element.selectFirst(".block_product_thumbnail img")?.run {
                    absUrl("data-src").ifEmpty { absUrl("src") }
                }
            }
        }

        val hasNextPage = document.selectFirst(".woocommerce-pagination a.pagecurrent ~ span a") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("div#primary h1")?.text() ?: "N/A"
        thumbnail_url = document.selectFirst("div#primary .size-shop_catalog img")?.absUrl("src")
        author = document.selectFirst("div#primary h1 + p")?.text()
        genre = document.select("fieldset#pdf a.button2").joinToString { it.text() }.takeIf { it.isNotBlank() }
        status = parseStatus(document.selectFirst("p:contains(Tình trạng)")?.ownText())

        val subtitle = document.selectFirst("div#content h2")?.text()
        val intro = document.selectFirst("div#content h2 + p")?.text()
        val updateTime = document.selectFirst("p:contains(Cập nhật lúc)")?.ownText()?.trim()?.let { "Cập nhật lúc: $it" }
        description = listOfNotNull(updateTime, subtitle, intro).joinToString("\n\n").trim()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains("Đang cập nhật", ignoreCase = true) -> SManga.ONGOING
        status.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val path = manga.url.removePrefix("/")
        val chapter1Url = "$baseUrl$LIST_PATH${path.replace(".html", "-chap-1.html")}"
        return GET(chapter1Url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val baseChapterPath = response.request.url.toString().substringBefore("-chap-")

        return document.select("select option")
            .filter { it.attr("value").contains("-chap-", ignoreCase = true) }
            .map { option ->
                val optionValue = option.attr("value")
                val chapterUrl = if (optionValue.startsWith("/")) optionValue else "$baseChapterPath$optionValue.html"
                SChapter.create().apply {
                    name = option.text().trim()
                    setUrlWithoutDomain(chapterUrl)
                }
            }
            .distinctBy { it.url }
            .reversed()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("div#primary > img.border").mapIndexedNotNull { index, element ->
            val imageUrl = element.absUrl("data-src").ifEmpty { element.absUrl("src") }
                .replace("\r", "")
                .takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null

            Page(index, imageUrl = imageUrl)
        }.distinctBy { it.imageUrl }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = getFilters()

    companion object {
        private const val SEARCH_PATH = "/search.php"
        private const val LIST_PATH = "/truyen-tranh/"
        private const val BOOK_TYPE = "5"
        private const val POPULAR_ORDER = "5"
        private const val DEFAULT_MAINCATEGORY = ""
        private const val DEFAULT_SUBCATEGORY = ""
        private const val DEFAULT_ORDER = "1"
    }
}
