package eu.kanade.tachiyomi.extension.vi.truyenqqcomvn

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TruyenQQComVN : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3) { it.host == baseUrl.toHttpUrl().host && it.encodedPath.contains("/media/book/") }
        rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val dateZone = ZoneId.of("Asia/Ho_Chi_Minh")

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SoftFilter().apply { state = 2 }))

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SoftFilter().apply { state = 1 }))

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("tim-kiem")
                .addQueryParameter("s", query)
                .addPage(page)
                .build()
            return parseMangaPage(client.get(url).asJsoup())
        }

        val softFilter = filters.firstInstanceOrNull<SoftFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()

        val isSoftActive = softFilter != null && softFilter.state != 0
        val isGenreActive = genreFilter != null && genreFilter.state != 0

        if (isSoftActive && isGenreActive) {
            return fetchMangaPage(page, "truyen-moi")
        }

        val path = when {
            softFilter != null && softFilter.state != 0 -> softFilter.values[softFilter.state].id
            genreFilter != null && genreFilter.state != 0 -> "the-loai/${genreFilter.values[genreFilter.state].id}"
            else -> "truyen-moi"
        }

        return fetchMangaPage(page, path)
    }

    private suspend fun fetchMangaPage(page: Int, path: String): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            path.split("/").forEach { addPathSegment(it) }
            addPage(page)
        }.build()

        return parseMangaPage(client.get(url).asJsoup())
    }

    private fun HttpUrl.Builder.addPage(page: Int): HttpUrl.Builder = apply {
        if (page > 1) addQueryParameter("page", page.toString())
    }

    private fun parseMangaPage(document: Document): MangasPage {
        val manga = document.select(".listing .item").map { element ->
            SManga.create().apply {
                val anchor = element.selectFirst("h3 a")!!
                setUrlWithoutDomain(anchor.attr("href"))
                title = anchor.text()
                thumbnail_url = element.selectFirst(".cover img")?.absUrl("src")
            }
        }
        val hasNextPage = document.select(".pagination .btn-page").any {
            it.text().contains("»") || (it.text().toIntOrNull() ?: 0) > (document.selectFirst(".pagination .btn-page.active")?.text()?.toIntOrNull() ?: 1)
        }
        return MangasPage(manga, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val reservedPaths = setOf("tim-kiem", "truyen-hot", "truyen-moi", "truyen-full", "the-loai")
        val slug = url.pathSegments.singleOrNull()
            ?.takeIf { it !in reservedPaths }
            ?: return null
        val path = "/$slug"

        return parseMangaDetails(client.get("$baseUrl$path").asJsoup()).apply {
            setUrlWithoutDomain(path)
        }
    }

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        client.get(getMangaUrl(manga)).use { response ->
            val document = response.asJsoup()
            return SMangaUpdate(parseMangaDetails(document), parseChapterList(document))
        }
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val info = document.selectFirst(".book-info")!!

        title = document.selectFirst("h1[itemprop=name]")?.text() ?: ""
        author = info.select(".line:has(.fa-user) .result span").joinToString { it.text() }
        genre = info.select(".line:has(.fa-folder) .result a").joinToString { it.text() }
        description = document.selectFirst("div[itemprop=description]")?.wholeText()?.trim()

        thumbnail_url = document.selectFirst(".poster img")?.absUrl("src")
        status = parseStatus(info.select(".line:has(.fa-ellipsis-h) .result .label-status").text())
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        listOf("Đang ra", "Đang tiến hành", "Updating").any { status.contains(it, ignoreCase = true) } -> SManga.ONGOING
        listOf("Hoàn thành", "Đã hoàn thành", "Full").any { status.contains(it, ignoreCase = true) } -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("#chapter-list .item").map { element ->
        SChapter.create().apply {
            val anchor = element.selectFirst(".item-name a")!!
            setUrlWithoutDomain(anchor.attr("href"))
            name = anchor.text().trim()
            date_upload = parseRelativeDate(element.selectFirst(".item-time")?.text())
        }
    }

    private fun parseRelativeDate(date: String?): Long {
        if (date == null) return 0L
        val now = Clock.System.now()
        val number = date.replace(Regex("[^0-9]"), "").trim().toIntOrNull() ?: 0
        val duration = when {
            date.contains("giây trước", ignoreCase = true) -> number.seconds
            date.contains("phút trước", ignoreCase = true) -> number.minutes
            date.contains("giờ trước", ignoreCase = true) -> number.hours
            date.contains("ngày trước", ignoreCase = true) -> number.days
            date.contains("tuần trước", ignoreCase = true) -> (number * 7).days
            date.contains("tháng trước", ignoreCase = true) -> (number * 30).days
            date.contains("năm trước", ignoreCase = true) -> (number * 365).days
            date.contains("hôm qua", ignoreCase = true) -> 1.days
            else -> return dateFormat.tryParseDate(date, dateZone)
        }
        return (now - duration).toEpochMilliseconds()
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        client.get(getChapterUrl(chapter)).use { response ->
            return response.asJsoup()
                .select(".inner img.lazy")
                .mapIndexed { idx, it ->
                    Page(idx, imageUrl = it.attr("data-src").ifEmpty { it.absUrl("src") })
                }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Tìm kiếm bằng tên sẽ bỏ qua bộ lọc Thể loại"),
        SoftFilter(),
        GenreFilter(getGenreList()),
    )
}
