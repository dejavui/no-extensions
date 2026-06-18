package eu.kanade.tachiyomi.extension.vi.truyenggvn

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.tryParse
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TruyenGGVN : HttpSource() {

    override val name = "TruyenGGVN"

    override val baseUrl = "https://truyenggvn.com"

    override val lang = "vi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    // ============================= Utilities ==============================

    private fun mangaFromElement(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#main_homepage ul.list_grid.grid li").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("h3 a")!!
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst(".page_redirect a:contains(›)") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseStatus(status: String?): Int {
        val ongoingWords = listOf("Đang Cập Nhật", "Đang Tiến Hành", "Còn tiếp", "Đang ra")
        val completedWords = listOf("Hoàn Thành", "Đã Hoàn Thành", "Hoàn")
        val hiatusWords = listOf("Tạm ngưng", "Tạm hoãn", "Bị drop")
        return when {
            status == null -> SManga.UNKNOWN
            ongoingWords.any { status.contains(it, ignoreCase = true) } -> SManga.ONGOING
            completedWords.any { status.contains(it, ignoreCase = true) } -> SManga.COMPLETED
            hiatusWords.any { status.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/truyen-yeu-thich" + if (page > 1) "/trang-$page.html" else "", headers)

    override fun popularMangaParse(response: Response): MangasPage = mangaFromElement(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/truyen-moi-cap-nhat" + if (page > 1) "/trang-$page.html" else "", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val endpoint = if (query.isNotBlank()) "tim-kiem" else "tim-kiem-nang-cao"
        val url = ("$baseUrl/$endpoint" + if (page > 1) "/trang-$page.html" else "").toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            } else {
                (filters.ifEmpty { getFilterList() }).forEach { filter ->
                    when (filter) {
                        is CountryFilter -> addQueryParameter("country", filter.values[filter.state].id)
                        is StatusFilter -> addQueryParameter("status", filter.values[filter.state].id)
                        is ChapterCountFilter -> addQueryParameter("minchapter", filter.values[filter.state].id)
                        is SortFilter -> filter.state?.let {
                            addQueryParameter("sort", (it.index * 2 + if (it.ascending) 1 else 0).toString())
                        }
                        is GenreFilter -> {
                            addQueryParameter(
                                "category",
                                filter.state.filter { it.state == Filter.TriState.STATE_INCLUDE }
                                    .joinToString(",") { it.id },
                            )
                            addQueryParameter(
                                "notcategory",
                                filter.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }
                                    .joinToString(",") { it.id },
                            )
                        }
                        else -> {}
                    }
                }
            }
        }.build()

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.select("h1[itemprop=name]").text()
        author = document.select("span:contains(Tác Giả) + span").text()
        genre = document.select(".book-genres ul li a").joinToString { it.text() }
        status = parseStatus(document.select("span:contains(Tình trạng) + span").text())
        description = document.selectFirst(".detail-content")?.wholeText()?.trim()
        thumbnail_url = document.selectFirst(".image img")?.absUrl("src")
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".list_chapter .works-chapter-item").map { element ->
            SChapter.create().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text()
                date_upload = dateFormat.tryParse(element.select(".time-chap").text())
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request = super.pageListRequest(chapter)
        .newBuilder()
        .cacheControl(CacheControl.FORCE_NETWORK)
        .build()

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select(".page-chapter img").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src").ifEmpty { element.absUrl("data-original") })
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Filters ================================

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(getGenreList()),
        Filter.Separator(),
        StatusFilter(),
        SortFilter(),
        ChapterCountFilter(),
        CountryFilter(),
    )

    private class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Thể loại", genres)

    private class Genre(name: String, val id: String) : Filter.TriState(name) {
        override fun toString(): String = name
    }

    private class StatusFilter :
        Filter.Select<Genre>(
            "Tình trạng",
            arrayOf(
                Genre("Tất cả", "-1"),
                Genre("Đang tiến hành", "0"),
                Genre("Hoàn thành", "2"),
            ),
        )

    private class SortFilter :
        Filter.Sort(
            "Sắp xếp",
            arrayOf("Ngày đăng", "Ngày cập nhật", "Lượt xem"),
            Selection(1, false),
        )

    private class ChapterCountFilter :
        Filter.Select<Genre>(
            "Số lượng chương",
            arrayOf(
                Genre("> 0", "0"),
                Genre(">= 100", "100"),
                Genre(">= 200", "200"),
                Genre(">= 300", "300"),
                Genre(">= 400", "400"),
                Genre(">= 500", "500"),
            ),
        )

    private class CountryFilter :
        Filter.Select<Genre>(
            "Quốc gia",
            arrayOf(
                Genre("Tất cả", "0"),
                Genre("Trung Quốc", "1"),
                Genre("Việt Nam", "2"),
                Genre("Hàn Quốc", "3"),
                Genre("Nhật Bản", "4"),
                Genre("Mỹ", "5"),
            ),
        )

    private fun getGenreList() = listOf(
        Genre("Action", "26"),
        Genre("Adventure", "27"),
        Genre("Anime", "62"),
        Genre("Chuyển Sinh", "91"),
        Genre("Cổ Đại", "90"),
        Genre("Comedy", "28"),
        Genre("Comic", "60"),
        Genre("Demons", "99"),
        Genre("Detective", "100"),
        Genre("Doujinshi", "96"),
        Genre("Drama", "29"),
        Genre("Fantasy", "30"),
        Genre("Gender Bender", "45"),
        Genre("Harem", "47"),
        Genre("Historical", "51"),
        Genre("Horror", "44"),
        Genre("Huyền Huyễn", "468"),
        Genre("Isekai", "85"),
        Genre("Josei", "54"),
        Genre("Mafia", "69"),
        Genre("Magic", "58"),
        Genre("Manga", "469"),
        Genre("Manhua", "35"),
        Genre("Manhwa", "49"),
        Genre("Martial Arts", "41"),
        Genre("Military", "101"),
        Genre("Mystery", "39"),
        Genre("Ngôn Tình", "87"),
        Genre("One shot", "95"),
        Genre("Psychological", "40"),
        Genre("Romance", "36"),
        Genre("School Life", "37"),
        Genre("Sci-fi", "43"),
        Genre("Seinen", "42"),
        Genre("Shoujo", "38"),
        Genre("Shoujo Ai", "98"),
        Genre("Shounen", "31"),
        Genre("Shounen Ai", "86"),
        Genre("Slice of life", "46"),
        Genre("Sports", "57"),
        Genre("Supernatural", "32"),
        Genre("Tragedy", "52"),
        Genre("Trọng Sinh", "82"),
        Genre("Truyện Màu", "92"),
        Genre("Webtoon", "55"),
        Genre("Xuyên Không", "88"),
    )
}
