package eu.kanade.tachiyomi.extension.vi.hentaicube

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
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

    private val thumbnailOriginalUrlRegex = Regex("""-\d+x\d+(\.[a-zA-Z]+)$""")

    override val chapterMode = ChapterMode.MangaAjaxPaginated

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

    private val oldMangaUrlRegex get() = Regex("""^$baseUrl/\w+/""")

    override fun getMangaUrl(manga: SManga): String = super.getMangaUrl(manga)
        .replace(oldMangaUrlRegex, "$baseUrl/$mangaSubString/")

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()

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
                client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
            } else {
                document
            }
            return super.parsePages(listStylesDoc).distinctBy { it.imageUrl }
        }

        val pageUrlString = "$baseUrl/wp-json/manga-reader/v3/pages"

        val headers = headersBuilder()
            .set("Accept", "application/json")
            .set("X-MSR-Request", "1")
            .build()

        val payload = PagesRequestDto(
            chapter = chapterId,
            manga = mangaId,
        )

        val pages = client.post(pageUrlString, headers, Fetch.XHR.from(chapterUrl), payload.toJsonRequestBody(), ensureSuccess = false)
            .parseAs<PagesResponse>()

        if (!pages.code.isNullOrEmpty()) {
            throw Exception(pages.message ?: "Lỗi ${pages.code}")
        }

        return pages.items.mapIndexed { i, imageUrl ->
            Page(i, chapterUrl, imageUrl)
        }
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
