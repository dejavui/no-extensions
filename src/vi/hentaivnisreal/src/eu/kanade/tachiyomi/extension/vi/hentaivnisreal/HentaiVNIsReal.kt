package eu.kanade.tachiyomi.extension.vi.hentaivnisreal

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class HentaiVNIsReal : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        removeAll("Origin")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/danh-sach".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("sort", "most-viewed")
        }.build()
        return parseMangaPage(client.get(url))
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/danh-sach".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return parseMangaPage(client.get(url))
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: "title"
            "$baseUrl/tim-kiem".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("type", searchType)
            }
        } else {
            val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "latest"
            "$baseUrl/danh-sach".toHttpUrl().newBuilder().apply {
                addQueryParameter("sort", sort)
            }
        }.apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return parseMangaPage(client.get(url))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "truyen") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            this.url = "/truyen/$slug"
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("ul.item-list li.item").map { element ->
            SManga.create().apply {
                val titleAnchor = element.selectFirst("div.box-description p a")!!
                title = titleAnchor.text()
                setUrlWithoutDomain(titleAnchor.absUrl("href"))
                thumbnail_url = element.selectFirst("div.box-cover img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li a:contains(Next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val info = document.selectFirst("div.page-info")!!
        title = info.selectFirst("h1 a")!!.text()
        author = info.select("span.info:contains(Tác giả:) + span").text()
        genre = info.select("span.info:contains(Thể Loại:) ~ span a.tag").joinToString { it.text() }
        status = parseStatus(info.select("span.info:contains(Tình Trạng:) + span").text())
        description = info.select("span.info:contains(Nội dung:) ~ p").joinToString { it.wholeText().trim() }
        thumbnail_url = document.selectFirst("div.page-ava img")?.absUrl("src")

        val altTitle = info.select("span.info:contains(Tên Khác:) + span").text()
        if (altTitle.isNotBlank()) {
            description = "Tên Khác: $altTitle\n\n$description"
        }
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains("Đang tiến hành", ignoreCase = true) -> SManga.ONGOING
        status.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================= Chapters ===============================

    private fun parseChapterList(document: Document): List<SChapter> = document.select("table.listing tbody tr").map { element ->
        SChapter.create().apply {
            val anchor = element.selectFirst("td a")!!
            name = anchor.selectFirst("h2.chuong_t")!!.text()
            setUrlWithoutDomain(anchor.absUrl("href"))
            date_upload = dateFormat.tryParseDate(element.select("td").last()?.text())
        }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT)

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("div#image img").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src"))
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Bộ lọc 'Sắp xếp' không hoạt động khi tìm kiếm bằng từ khóa và ngược lại"),
        Filter.Separator(),
        SortFilter(),
        Filter.Separator(),
        SearchTypeFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "Sắp xếp",
            arrayOf(
                "Mới nhất" to "latest",
                "Cũ nhất" to "oldest",
                "Xem nhiều nhất" to "most-viewed",
                "Xem ít nhất" to "least-viewed",
            ),
        )

    private class SearchTypeFilter :
        UriPartFilter(
            "Tìm theo",
            arrayOf(
                "Tên truyện" to "title",
                "Tác giả" to "author",
                "Charater" to "character",
                "Doujinshi" to "doujinshi",
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
