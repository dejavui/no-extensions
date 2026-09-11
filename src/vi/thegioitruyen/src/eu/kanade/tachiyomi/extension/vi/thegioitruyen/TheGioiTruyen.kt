package eu.kanade.tachiyomi.extension.vi.thegioitruyen

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class TheGioiTruyen : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        removeAll("Origin")
    }
    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        val pagePart = if (page > 1) "page/$page/" else ""
        val response = client.get("$baseUrl/truyen/$pagePart")
        return parseMangaList(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val pagePart = if (page > 1) "page/$page/" else ""
        val response = client.get("$baseUrl/truyen/$pagePart")
        return parseMangaList(response.asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val pagePart = if (page > 1) "page/$page/" else ""

        if (query.isNotBlank()) {
            val url = "$baseUrl/$pagePart".toHttpUrl().newBuilder()
                .addQueryParameter("post_type", "truyen")
                .addQueryParameter("s", query)
                .build()
            val response = client.get(url)
            return parseMangaList(response.asJsoup())
        }

        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        if (genreFilter != null && genreFilter.state != 0) {
            val genreSlug = genreFilter.values[genreFilter.state].value
            val url = "$baseUrl/the-loai/$genreSlug/$pagePart"
            val response = client.get(url)
            return parseMangaList(response.asJsoup())
        }

        return getPopularManga(page)
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select(".tgt-card").map { element ->
            SManga.create().apply {
                val titleAnchor = element.selectFirst("a.tgt-card-title")!!
                setUrlWithoutDomain(titleAnchor.absUrl("href"))
                title = titleAnchor.text()
                thumbnail_url = element.selectFirst("a.tgt-card-thumb img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst(".next.page-numbers") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = url.encodedPath
        if (!path.contains("/truyen/")) return null

        return SManga.create().apply {
            this.url = path
        }
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val response = client.get("$baseUrl/the-loai/")
        val document = response.asJsoup()
        val genres = document.select("a.tgt-genre-card").mapNotNull { element ->
            val name = element.selectFirst(".tgt-genre-name")?.text()?.trim() ?: return@mapNotNull null
            val value = element.attr("href").removeSuffix("/").substringAfterLast("/")
            FilterOption(name, value)
        }
        return FilterData(genres).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: return FilterList()
        val genres = listOf(FilterOption("<Chọn thể loại>", "")) + filterData.genres
        return FilterList(
            GenreFilter(genres.toTypedArray()),
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        val document = response.asJsoup()

        return SMangaUpdate(
            manga = parseMangaDetails(document),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val altName = document.selectFirst(".tgt-info-origin")?.text()

        title = document.selectFirst("h1.tgt-info-title")?.text()!!
        description = buildString {
            if (altName != null) append("Tên khác: $altName \n\n")
            append(document.selectFirst(".tgt-desc")?.wholeText()?.trim())
        }

        genre = document.select("a.tgt-genre-tag").joinToString { it.text() }
        author = document.selectFirst("th:contains(Tác giả) + td")?.text()
        status = parseStatus(document.selectFirst("th:contains(Tình trạng) + td")?.text())
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        listOf("Đang phát hành", "Đang Tiến Hành", "Đang Cập Nhật").any { status.contains(it, ignoreCase = true) } -> SManga.ONGOING
        listOf("Hoàn Thành", "Đã Hoàn Thành", "Đã hoàn tất").any { status.contains(it, ignoreCase = true) } -> SManga.COMPLETED
        listOf("Tạm Ngưng", "Tạm Hoãn").any { status.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("div#chapter-list a.tgt-chapter-item").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst(".tgt-chap-name")!!.text()
            date_upload = element.selectFirst(".tgt-chap-date")?.text()?.let { parseDate(it) } ?: 0L
        }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    private fun parseDate(date: String): Long = dateFormat.tryParseDate(date)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val document = response.asJsoup()

        return document.select(".tgt-reader-pages img.tgt-reader-page").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src"))
        }
    }
}
