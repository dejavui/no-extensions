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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.security.SecureRandom
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
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Origin")

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

        val masr2Token = document.selectFirst("#manga-secure-reader")
            ?.attr("data-masr2-token")

        if (masr2Token == null) {
            val listStylesDoc = if (document.selectFirst("#single-pager") != null) {
                client.get(chapterUrl.toHttpUrl().newBuilder().addQueryParameter("style", "list").build()).asJsoup()
            } else {
                document
            }
            return super.parsePages(listStylesDoc).distinctBy { it.imageUrl }
        }

        val clientId = generateClientId() // Fix CID for the entire chapter
        var token: String? = masr2Token
        val allImages = mutableListOf<String>()
        var retries = 0

        while (!token.isNullOrEmpty() && retries < 25) {
            val pagesUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("wp-json/manga-reader/v2/pages")
                .addQueryParameter("token", token)
                .addQueryParameter("cid", clientId)
                .build()

            val challengeHeader = headersBuilder()
                .set("Referer", chapterUrl)
                .set("Accept", "application/json")
                .build()

            val pages = try {
                val response = client.get(pagesUrl, challengeHeader)
                response.parseAs<PagesResponse>()
            } catch (e: Exception) {
                if (retries < 5) {
                    retries++
                    continue
                } else {
                    throw e
                }
            }

            // Handle rate limiting
            if (pages.code == "too_fast") {
                retries++
                continue
            }

            // Handle session mismatch
            if (pages.code == "client_mismatch") {
                // If this happens even with fixed CID, the token might have expired
                throw Exception("Lỗi phiên đọc (Client Mismatch): ${pages.message}")
            }

            if (pages.items.isEmpty() && pages.done) break
            if (pages.items.isEmpty()) {
                retries++
                continue
            }

            allImages.addAll(pages.items)
            token = if (pages.done) null else pages.nextToken
        }

        if (allImages.isEmpty()) throw Exception("Không lấy được danh sách ảnh (Server chặn hoặc timeout)")

        return allImages.mapIndexed { i, imageUrl ->
            Page(i, chapterUrl, imageUrl)
        }
    }

    private fun generateClientId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private class PagesResponse(
        val items: List<String> = emptyList(),
        val done: Boolean = false,
        val protocol: Int = 0,
        val cursor: Int = 0,
        @SerialName("next_cursor") val nextCursor: Int = 0,
        val count: Int = 0,
        @SerialName("next_token") val nextToken: String? = null,
        val code: String? = null,
        val message: String? = null,
    )
}
