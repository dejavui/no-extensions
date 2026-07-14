package eu.kanade.tachiyomi.extension.vi.vitruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.collections.map

@Source
abstract class ViTruyen : HttpSource() {

    override val name: String = "ViTruyen"

    override val supportsLatest: Boolean = true

    private val apiUrl = "https://api.vitruyen1.com/api"

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/next/the-loai/dang-hot?page=$page&sort=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
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

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/next/the-loai/dang-hot?page=$page&sort=view", headers)

    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string(), response.request.url.toString())
        return SManga.create().apply {
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
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val res = response.asJsoup()
        return res.select(".v2-chapter-list a.v2-chapter-item").map { element ->
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

                date_upload = dateFormat.tryParse(element.select(".tabular-nums").text())
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> {
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

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/next/search-suggestions".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
        }.build()
        return GET(url)
    }
}
