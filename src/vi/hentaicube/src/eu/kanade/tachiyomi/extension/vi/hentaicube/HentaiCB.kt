package eu.kanade.tachiyomi.extension.vi.hentaicube

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HentaiCB : Madara() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        val host = baseUrl.toHttpUrl().host
        rateLimit(3) { it.host == host && it.encodedPath.contains("/wp-content/uploads/") }
        rateLimit(3) { it.host == host && it.encodedPath.contains("/ajax/chapters/") }
        rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    override val chapterDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    override val filterNonMangaItems = false

    override val mangaSubString = "read"

    override val altNameSelector = ".post-content_item:contains(Tên khác) .summary-content"

    override fun getHomeUrl(): String = baseUrl

    private val thumbnailOriginalUrlRegex = Regex("-\\d+x\\d+(\\.[a-zA-Z]+)$")

    override fun processThumbnail(url: String?, fromSearch: Boolean): String? = super.processThumbnail(
        url,
        fromSearch,
    )?.replace(thumbnailOriginalUrlRegex, "$1")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val queryFixed = query
            .replace("–", "-")
            .replace("’", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("…", "...")

        return super.getSearchMangaList(page, queryFixed, filters)
    }

    private val oldMangaUrlRegex by lazy { Regex("^$baseUrl/\\w+/") }

    override fun getMangaUrl(manga: SManga): String = super.getMangaUrl(manga)
        .replace(oldMangaUrlRegex, "$baseUrl/$mangaSubString/")

    override suspend fun fetchChapters(mangaPath: String, id: String, mangaPage: Document?): List<SChapter> {
        val document = mangaPage ?: client.get(baseUrl.toHttpUrl().newBuilder().addEncodedPathSegments(mangaPath).build()).asJsoup()
        val chaptersWrapper = document.select("div[id^=manga-chapters-holder]")

        var chapters = parseChapterList(document, mangaPath)

        if (chapters.isEmpty() && chaptersWrapper.isNotEmpty()) {
            val mangaUrl = document.location().removeSuffix("/")
            val mangaId = chaptersWrapper.attr("data-id")

            val allChapters = mutableListOf<SChapter>()
            var page = 1

            while (true) {
                val url = "$mangaUrl/ajax/chapters/?t=$page"
                var response = client.post(url, xhrHeaders, FormBody.Builder().build(), ensureSuccess = false)

                // Newer Madara versions throws HTTP 400 when using the old endpoint.
                if (response.code == 400 && page == 1) {
                    response.close()
                    val body = FormBody.Builder()
                        .add("action", "manga_get_chapters")
                        .add("manga", mangaId)
                        .build()
                    response = client.post("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, body)
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    if (code == 404) break
                    throw Exception("HTTP $code")
                }

                val xhrDocument = response.asJsoup()
                val pageChapters = parseChapterList(xhrDocument, mangaPath)
                if (pageChapters.isEmpty()) {
                    response.close()
                    break
                }
                allChapters.addAll(pageChapters)

                val hasNextPage = xhrDocument.selectFirst("div.pagination a[data-page='${page + 1}']") != null
                response.close()

                if (!hasNextPage) break
                page++
            }
            chapters = allChapters
        }

        return chapters
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

        val readerElement =
            document.selectFirst(".mcld-chapter-reactions")
                ?: document.selectFirst("[data-chapter-id], [data-manga-id]")
                ?: document.selectFirst(".reading-content .msr-reader")
        val chapterId = readerElement?.attr("data-chapter-id")?.toIntOrNull()
            ?: readerElement?.attr("data-msr-chapter")?.toIntOrNull()
        val mangaId = readerElement?.attr("data-manga-id")?.toIntOrNull()
            ?: readerElement?.attr("data-msr-manga")?.toIntOrNull()

        if (chapterId == null || mangaId == null) {
            val listStylesDoc = if (document.selectFirst("#single-pager") != null) {
                client.get(chapterUrl.toHttpUrl().newBuilder().build()).asJsoup()
            } else {
                document
            }
            return super.parsePages(listStylesDoc).distinctBy { it.imageUrl }
        }

        val pageUrlString = "$baseUrl/wp-json/manga-reader/v3/pages"

        val headers = headersBuilder()
            .set("Referer", chapterUrl)
            .set("Accept", "application/json")
            .set("Cache-Control", "no-cache")
            .set("X-MSR-Request", "1")
            .build()

        val payload = PagesRequestDto(
            chapter = chapterId,
            manga = mangaId,
        )

        val pages = client.post(pageUrlString, headers, payload.toJsonRequestBody(), ensureSuccess = false)
            .parseAs<PagesResponse>()

        if (!pages.code.isNullOrEmpty()) {
            throw Exception(pages.message ?: "Lỗi ${pages.code}")
        }

        if (pages.items.isEmpty()) {
            throw Exception("Không lấy được danh sách trang truyện")
        }

        return pages.items.mapIndexed { i, imageUrl ->
            Page(i, chapterUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val requestHeaders = headers.newBuilder()
            .removeAll("Origin")
            .build()
        return super.imageRequest(page).newBuilder().headers(requestHeaders).build()
    }

    @Serializable
    private class PagesRequestDto(
        val chapter: Int,
        val manga: Int,
    )

    @Serializable
    private class PagesResponse(
        val items: List<String> = emptyList(),
        val protocol: Int = 0,
        val count: Int = 0,
        val code: String? = null,
        val message: String? = null,
    )
}
