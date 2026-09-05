package eu.kanade.tachiyomi.extension.vi.vitruyen

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class ViTruyen : KeiSource() {

    private val apiUrl: String
        get() = "https://api.${baseUrl.toHttpUrl().host}"

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ROOT)

    private val vietnamZone = ZoneId.of("Asia/Ho_Chi_Minh")

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter().apply { state = 1 }))

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter().apply { state = 0 }))

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenresFilter>()?.let {
            it.values[it.state].slug
        } ?: "dang-hot"

        val sort = filters.firstInstanceOrNull<SortFilter>()?.let { it.values[it.state].slug } ?: "latest"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.let { it.values[it.state].slug } ?: ""
        val schedule = filters.firstInstanceOrNull<SchedulesFilter>()?.let { it.values[it.state].slug } ?: ""
        val translator = filters.firstInstanceOrNull<TranslatorsFilter>()?.let { it.values[it.state].slug } ?: ""
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addPathSegments("api/next/search-suggestions")
                addQueryParameter("q", query)
            } else {
                addPathSegments("api/next/the-loai")
                addPathSegment(genre)
                addQueryParameter("page", page.toString())
                addQueryParameter("sort", sort)
                if (schedule.isNotBlank()) addQueryParameter("schedule", schedule)
                if (translator.isNotBlank()) addQueryParameter("translator", translator)
                if (status.isNotBlank()) {
                    addQueryParameter("status", status)
                }
            }
        }.build()

        client.get(url).use { response ->
            return parseMangaPage(response)
        }
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val res = response.parseAs<Data>()
        val manga = res.items.map {
            SManga.create().apply {
                setUrlWithoutDomain("$baseUrl/" + it.slug)
                title = it.name
                thumbnail_url = it.image
            }
        }

        val hasNextPage = res.page < res.totalPages

        return MangasPage(manga, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host == baseUrl.toHttpUrl().host) {
            val manga = SManga.create().apply {
                this.url = url.encodedPath
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
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val hero = document.selectFirst(".v2-detail-hero")!!
        val alternativeName = hero.selectFirst(".mt-4:contains(Tên khác) p")
        title = hero.selectFirst("h1")?.text() ?: ""
        thumbnail_url = hero.selectFirst("img")?.attr("abs:src")
        description = buildString {
            alternativeName?.text()?.let {
                append("Tên khác: ", it, "\n\n")
            }
            hero.selectFirst(".v2-creator-rich-content")?.wholeText()?.trim()?.let {
                append(it)
            }
        }.trim()
        genre = hero.select("a[href*='/the-loai/']").joinToString { it.text() }
        author = hero.selectFirst(".mt-4:contains(Tác giả) a")?.text()
            ?: hero.selectFirst(".mt-4:contains(Tác giả)")?.ownText()
        artist = hero.selectFirst(".mt-4:contains(Họa sĩ) a")?.text()
            ?: hero.selectFirst(".mt-4:contains(Họa sĩ)")?.ownText()
        status = when {
            hero.selectFirst("span:contains(Đang cập nhật)") != null -> SManga.ONGOING
            hero.selectFirst("span:contains(Hoàn thành)") != null -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".v2-chapter-list a.v2-chapter-item").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.select(".v2-chapter-item-title").text()

            val isLocked = element.select("i.fa-lock").isNotEmpty() ||
                element.text().contains("Khóa")
            val isUnlocked = element.select("i.fa-unlock, i.fa-check").isNotEmpty() ||
                element.text().contains("Đã mở")
            val isFree = element.text().contains("Miễn phí")

            if (isLocked) {
                name += " 🔒"
            } else if (isUnlocked) {
                name += " 🔓"
            } else if (isFree) {
                name += " 🆓"
            }

            date_upload = dateFormat.tryParseDate(element.select(".tabular-nums").text(), vietnamZone)
        }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        client.get(getChapterUrl(chapter)).use { response ->
            val document = response.asJsoup()

            document.selectFirst(".v2-reader-lock-panel")?.let {
                val title = it.selectFirst("h3")?.text() ?: "Chương đang được khóa"
                val price = it.selectFirst("p")?.text() ?: ""
                throw Exception("$title. $price".trim())
            }

            return document.select(".v2-reader-page-image-wrap img").mapIndexed { index, element ->
                Page(index, imageUrl = element.absUrl("src"))
            }
        }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/api/next/categories").use {
        it.parseAs<JsonElement>()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Không dùng chung được với tìm kiếm bằng tên"),
        )

        data?.parseAs<FilterOptions>()?.categories?.takeIf { it.isNotEmpty() }?.let {
            val categories = it.map { item -> Genre(item.name, item.slug) }
            filters.add(GenresFilter(categories))
        }

        filters.add(SortFilter())
        filters.add(StatusFilter())
        filters.add(SchedulesFilter())
        filters.add(TranslatorsFilter())
        return FilterList(filters)
    }
}
