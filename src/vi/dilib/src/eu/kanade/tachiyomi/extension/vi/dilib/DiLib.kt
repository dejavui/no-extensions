package eu.kanade.tachiyomi.extension.vi.dilib

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiLib : HttpSource() {

    override val name = "DiLib"

    override val baseUrl = "https://dilib.vn"

    override val lang = "vi"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/truyen-tranh/" else "$baseUrl/truyen-tranh/page/$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("div.product").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("a.woocommerce-LoopProduct-link")!!
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.select("h3 a").text().substringBeforeLast("(").trim()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("a.end_link, a.next_link_activ") != null ||
                         document.select("nav.woocommerce-pagination a").any { it.text().contains(Regex("\\d+")) && it.attr("href").contains("/page/") }

        // Better hasNextPage logic
        val pagination = document.selectFirst("nav.woocommerce-pagination")
        val isLastPage = pagination?.selectFirst("span.middle_link b")?.text()?.let {
            val parts = it.replace("Trang", "").trim().split("/")
            parts.size == 2 && parts[0] == parts[1]
        } ?: true

        return MangasPage(mangas, !isLastPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val formBody = FormBody.Builder()
                .add("keyword", query)
                .build()
            return POST("$baseUrl/truyen-tranh/", headers, formBody)
        }

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        if (genreFilter != null && genreFilter.state != 0) {
            val url = "$baseUrl${genreFilter.toUriPart()}" + if (page > 1) "page/$page" else ""
            return GET(url, headers)
        }

        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.fs20")?.text() ?: ""
            author = document.select("p:contains(Tác giả) a").text()
            genre = document.select("fieldset#pdf a.button2").joinToString { it.text() }
            description = document.select("div#primary > h2, div#primary > p").joinToString("\n\n") { it.text() }
            status = document.select("p:contains(Tình trạng)").text().toStatus()
            thumbnail_url = document.selectFirst("div.size-shop_catalog img")?.absUrl("src")
        }
    }

    private fun String.toStatus(): Int = when {
        contains("Hoàn thành", true) -> SManga.COMPLETED
        contains("Đang cập nhật", true) -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("fieldset:contains(DANH SÁCH CHƯƠNG) div.col-md-3 a").map { element ->
            SChapter.create().apply {
                name = element.text()
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("div#primary img.border[src*='/img/comic/']").mapIndexed { i, element ->
            Page(i, imageUrl = element.absUrl("src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Tìm kiếm ưu tiên từ khóa"),
        GenreFilter(),
    )

    private class GenreFilter : Filter.Select<String>(
        "Thể loại",
        arrayOf(
            "Tất cả",
            "Manga",
            "Manhua",
            "Manhwa",
            "Action",
            "Adventure",
            "Comedy",
            "Fantasy",
            "Shounen",
            "Shoujo",
            "Supernatural",
            "Sci-Fi",
            "Martial Arts",
            "Seinen",
            "Drama",
            "Mystery",
            "Cooking",
            "Harem",
            "Romance",
            "School Life",
            "Historical",
            "Psychological",
            "Tragedy",
            "Truyện Màu",
            "Horror",
            "Slice Of Life",
            "Adult (18+)",
            "Sports",
            "Ecchi",
            "Webtoon",
            "Mature",
            "Tu Tiên",
            "Vampire",
            "Josei",
            "Xuyên Không",
            "Magic",
            "Monsters",
            "Hệ Thống",
        ),
    ) {
        fun toUriPart(): String = when (state) {
            1 -> "/truyen-tranh/manga/"
            2 -> "/truyen-tranh/manhua/"
            3 -> "/truyen-tranh/manhwa/"
            4 -> "/truyen-tranh/action/"
            5 -> "/truyen-tranh/adventure/"
            6 -> "/truyen-tranh/comedy/"
            7 -> "/truyen-tranh/fantasy/"
            8 -> "/truyen-tranh/shounen/"
            9 -> "/truyen-tranh/shoujo/"
            10 -> "/truyen-tranh/supernatural/"
            11 -> "/truyen-tranh/sci-fi/"
            12 -> "/truyen-tranh/martial-arts/"
            13 -> "/truyen-tranh/seinen/"
            14 -> "/truyen-tranh/drama/"
            15 -> "/truyen-tranh/mystery/"
            16 -> "/truyen-tranh/cooking/"
            17 -> "/truyen-tranh/harem/"
            18 -> "/truyen-tranh/romance/"
            19 -> "/truyen-tranh/school-life/"
            20 -> "/truyen-tranh/historical/"
            21 -> "/truyen-tranh/psychological/"
            22 -> "/truyen-tranh/tragedy/"
            23 -> "/truyen-tranh/truyen-mau/"
            24 -> "/truyen-tranh/horror/"
            25 -> "/truyen-tranh/slice-of-life/"
            26 -> "/truyen-tranh/adult-18/"
            27 -> "/truyen-tranh/sports/"
            28 -> "/truyen-tranh/ecchi/"
            29 -> "/truyen-tranh/webtoon/"
            30 -> "/truyen-tranh/mature/"
            31 -> "/truyen-tranh/tu-tien/"
            32 -> "/truyen-tranh/vampire/"
            33 -> "/truyen-tranh/josei/"
            34 -> "/truyen-tranh/xuyen-khong/"
            35 -> "/truyen-tranh/magic/"
            36 -> "/truyen-tranh/monsters/"
            37 -> "/truyen-tranh/he-thong/"
            else -> ""
        }
    }
}
