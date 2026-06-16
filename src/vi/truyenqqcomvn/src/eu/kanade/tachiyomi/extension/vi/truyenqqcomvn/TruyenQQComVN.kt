package eu.kanade.tachiyomi.extension.vi.truyenqqcomvn

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TruyenQQComVN :
    HttpSource(),
    ConfigurableSource {

    override val name: String = "TruyenQQ.com.vn"

    override val lang: String = "vi"

    private val defaultBaseUrl = "https://truyenqq.com.vn"

    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences = getPreferences {
        getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    override val baseUrl get() = getPrefBaseUrl()

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/truyen-hot" + if (page > 1) "?page=$page" else "", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
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

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/truyen-moi" + if (page > 1) "?page=$page" else "", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("tim-kiem")
                .addQueryParameter("s", query)
                .apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }
                .build()
            return GET(url, headers)
        }

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        if (genreFilter != null && genreFilter.state != 0) {
            val genreId = genreFilter.values[genreFilter.state].id
            return GET("$baseUrl/the-loai/$genreId" + if (page > 1) "?page=$page" else "", headers)
        }

        return latestUpdatesRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
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

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("#chapter-list .item").map { element ->
            SChapter.create().apply {
                val anchor = element.selectFirst(".item-name a")!!
                setUrlWithoutDomain(anchor.attr("href"))
                name = anchor.text().trim()
                date_upload = parseRelativeDate(element.selectFirst(".item-time")?.text()) ?: 0L
            }
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

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select(".inner img.lazy")
        .mapIndexed { idx, it ->
            Page(idx, imageUrl = it.attr("data-src").ifEmpty { it.absUrl("src") })
        }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Tìm kiếm bằng tên sẽ bỏ qua bộ lọc Thể loại"),
        GenreFilter(getGenreList()),
    )

    private class Genre(val name: String, val id: String) {
        override fun toString(): String = name
    }

    private class GenreFilter(genres: Array<Genre>) :
        Filter.Select<Genre>(
            "Thể loại",
            genres,
        )

    private fun getGenreList() = arrayOf(
        Genre("Tất cả", ""),
        Genre("Ngôn Tình", "ngon-tinh"),
        Genre("Đam Mỹ", "dam-my"),
        Genre("Huyền Huyễn", "huyen-huyen"),
        Genre("Xuyên Không", "xuyen-khong"),
        Genre("Trọng Sinh", "trong-sinh"),
        Genre("Trinh Thám", "trinh-tham"),
        Genre("Cổ Đại", "co-dai"),
        Genre("Chuyển Sinh", "chuyen-sinh"),
        Genre("Manhwa", "manhwa"),
        Genre("Truyện Màu", "truyen-mau"),
        Genre("Comedy", "comedy"),
        Genre("Manhua", "manhua"),
        Genre("Romance", "romance"),
        Genre("School Life", "school-life"),
        Genre("Action", "action"),
        Genre("Ecchi", "ecchi"),
        Genre("Manga", "manga"),
        Genre("Mystery", "mystery"),
        Genre("Seinen", "seinen"),
        Genre("Smut", "smut"),
        Genre("Supernatural", "supernatural"),
        Genre("Tragedy", "tragedy"),
        Genre("Drama", "drama"),
        Genre("Adventure", "adventure"),
        Genre("Fantasy", "fantasy"),
        Genre("Isekai", "isekai"),
        Genre("Horror", "horror"),
        Genre("Shounen", "shounen"),
        Genre("Gender Bender", "gender-bender"),
        Genre("Psychological", "psychological"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Mecha", "mecha"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Harem", "harem"),
        Genre("Shoujo", "shoujo"),
        Genre("Historical", "historical"),
        Genre("Webtoon", "webtoon"),
        Genre("Sci-fi", "sci-fi"),
        Genre("Josei", "josei"),
        Genre("Adult", "adult"),
        Genre("Mature", "mature"),
        Genre("Sports", "sports"),
        Genre("Anime", "anime"),
        Genre("Comic", "comic"),
        Genre("Cooking", "cooking"),
        Genre("One shot", "one-shot"),
        Genre("Doujinshi", "doujinshi"),
        Genre("Magic", "magic"),
        Genre("Live action", "live-action"),
        Genre("Soft Yuri", "soft-yuri"),
        Genre("Yuri", "yuri"),
        Genre("Shoujo Ai", "shoujo-ai"),
        Genre("Demons", "demons"),
        Genre("Shounen Ai", "shounen-ai"),
        Genre("Thiếu Nhi", "thieu-nhi"),
        Genre("Soft Yaoi", "soft-yaoi"),
        Genre("Yaoi", "yaoi"),
        Genre("Detective", "detective"),
        Genre("Khác", "khac"),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
