package eu.kanade.tachiyomi.extension.vi.truyenqqcomvn

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
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TruyenQQComVN : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        add("Referer", "$baseUrl/")
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/truyen-hot" + if (page > 1) "?page=$page" else ""

        return parseMangaPage(client.get(url, headers).asJsoup())
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/truyen-moi" + if (page > 1) "?page=$page" else ""

        return parseMangaPage(client.get(url, headers).asJsoup())
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("tim-kiem")
                .addQueryParameter("s", query)
                .apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }
                .build()
            return parseMangaPage(client.get(url, headers).asJsoup())
        }

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        if (genreFilter != null && genreFilter.state != 0) {
            val genreId = genreFilter.values[genreFilter.state].id
            val url = "$baseUrl/the-loai/$genreId" + if (page > 1) "?page=$page" else ""
            return parseMangaPage(client.get(url, headers).asJsoup())
        }

        return getLatestUpdates(page)
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
        if (url.host == baseUrl.toHttpUrl().host) {
            client.get(url, headers).use { response ->
                return parseMangaDetails(response.asJsoup())
            }
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
        client.get(getMangaUrl(manga), headers).use { response ->
            val document = response.asJsoup()
            val details = if (fetchDetails) parseMangaDetails(document) else manga
            val chaptersList = if (fetchChapters) parseChapterList(document) else chapters

            return SMangaUpdate(details, chaptersList)
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
            date_upload = parseRelativeDate(element.selectFirst(".item-time")?.text()) ?: 0L
        }
    }

    private fun parseRelativeDate(date: String?): Long? {
        if (date == null) return null
        val calendar = Calendar.getInstance()
        return when {
            date.contains("giây trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.SECOND, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("phút trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.MINUTE, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("giờ trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.HOUR_OF_DAY, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("ngày trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.DAY_OF_YEAR, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("tuần trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.WEEK_OF_YEAR, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("tháng trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.MONTH, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("năm trước", ignoreCase = true) -> {
                calendar.apply { add(Calendar.YEAR, -date.split(" ")[0].toInt()) }.timeInMillis
            }
            date.contains("hôm qua", ignoreCase = true) -> {
                calendar.apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            }
            else -> dateFormat.tryParse(date)
        }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        client.get(getChapterUrl(chapter), headers).use { response ->
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
        GenreFilter(getGenreList()),
    )
}
