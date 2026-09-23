package eu.kanade.tachiyomi.extension.vi.tranh18

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class Tranh18 : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        removeAll("Origin")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page > 1) "$baseUrl/comics?page=$page" else baseUrl
        return parseMangaPage(client.get(url))
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page > 1) "$baseUrl/update?page=$page" else "$baseUrl/update"
        return parseMangaPage(client.get(url))
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addPathSegment("search")
                addQueryParameter("keyword", query)
            } else {
                addPathSegment("comics")
                filters.forEach {
                    when (it) {
                        is TagList -> addQueryParameter("tag", it.values[it.state].value)
                        is StatusList -> addQueryParameter("end", it.values[it.state].value)
                        is GenreList -> addQueryParameter("area", it.values[it.state].value)
                        else -> {}
                    }
                }
            }
            addQueryParameter("page", page.toString())
        }.build()

        return parseMangaPage(client.get(url))
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".mh-item, .mh-itme-top")
            .mapNotNull { element ->
                val sel = if (element.tagName() == "li") {
                    element.selectFirst(".mh-item, .manga-list-2-cover") ?: element
                } else {
                    element
                }
                val a = sel.selectFirst("a") ?: return@mapNotNull null
                val href = a.absUrl("href").ifEmpty { return@mapNotNull null }

                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    title = a.attr("title").ifEmpty {
                        sel.selectFirst(".title, h2, h3, .mh-item-detal h2")?.text() ?: a.text()
                    }
                    thumbnail_url = sel.selectFirst("p.mh-cover")?.attr("style")?.let { style ->
                        when {
                            style.contains("url(https://") -> style.substringAfter("url(").substringBefore(")")
                            style.contains("url(") -> baseUrl + style.substringAfter("url(").substringBefore(")")
                            else -> null
                        }
                    } ?: sel.selectFirst("img")?.run {
                        absUrl("data-original").ifEmpty { absUrl("src") }
                    }
                }
            }
        val hasNextPage = document.selectFirst(".page-pagination li.active ~ li:not(.disabled) a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host == baseUrl.toHttpUrl().host &&
            url.pathSegments.firstOrNull() == "comic" &&
            url.pathSegments.getOrNull(1)?.isNotBlank() == true
        ) {
            val manga = SManga.create().apply {
                setUrlWithoutDomain("/comic/${url.pathSegments[1]}")
            }
            return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
        }
        return null
    }

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = client.get(getMangaUrl(manga)).use { response ->
        val document = response.asJsoup()
        val details = parseMangaDetails(document).apply {
            url = manga.url
        }
        val chaptersList = parseChapterList(document)

        SMangaUpdate(details, chaptersList)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".info h1, .detail-info h1, h1")?.text() ?: ""
        genre = document.select("div.info:contains(Từ khóa) a[href*=tag]")
            .joinToString { it.text() }
        description = document.select("p.content").takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { it.wholeText().trim().substringBefore("#").trim() }
            ?: document.select("p.detail-desc")
                .joinToString("\n") { it.wholeText().trim().substringBefore("#").trim() }
        author = document.selectFirst("div.info:contains(Tác giả:) a[href*=author]")
            ?.text()?.removePrefix("Tác giả：")
        status = parseStatus(
            document.selectFirst("div.info .bd-fact:nth-child(1)")?.text(),
        )
        thumbnail_url = document.selectFirst(".banner_detail_form .cover img")?.absUrl("src")
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        listOf("Đang Tiến Hành", "Đang Cập Nhật").any { status.contains(it, ignoreCase = true) } -> SManga.ONGOING
        listOf("Hoàn Thành", "Đã Hoàn Thành", "Đã hoàn tất").any { status.contains(it, ignoreCase = true) } -> SManga.COMPLETED
        listOf("Tạm Ngưng", "Tạm Hoãn").any { status.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun parseChapterList(document: Document): List<SChapter> = document
        .select("ul.detail-list-select li")
        .mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(a.absUrl("href"))
                name = a.text()
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.value?.toFloatOrNull() ?: 0f
            }
        }
        .sortedByDescending { it.chapter_number }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).use { response ->
        val document = response.asJsoup()
        document.select(".comicpage img").mapIndexed { index, it ->
            val url = it.absUrl("data-original").ifEmpty { it.absUrl("src") }
            val finalUrl = if (url.startsWith("https://external-content.duckduckgo.com/iu/")) {
                url.toHttpUrl().queryParameter("u")
            } else {
                url
            }
            Page(index, imageUrl = finalUrl)
        }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/comics").asJsoup()

        val tags = document.select("#tags dd").map {
            FilterOption(it.text(), it.attr("data-val"))
        }
        val areas = document.select("#areas dd").map {
            FilterOption(it.text(), it.attr("data-val"))
        }
        val end = document.select("#end dd").map {
            FilterOption(it.text(), it.attr("data-val"))
        }

        return FilterData(tags, areas, end).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: return FilterList()

        return FilterList(
            Filter.Header("Không dùng chung với tìm kiếm bằng từ khóa."),
            TagList(filterData.tags.toTypedArray()),
            GenreList(filterData.areas.toTypedArray()),
            StatusList(filterData.end.toTypedArray()),
        )
    }

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)*)""")
    }
}
