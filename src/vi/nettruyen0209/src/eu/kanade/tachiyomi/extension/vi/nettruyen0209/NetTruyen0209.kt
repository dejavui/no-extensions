package eu.kanade.tachiyomi.extension.vi.nettruyen0209

import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class NetTruyen0209 : WPComics() {
    override val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm dd-MM-yyyy", Locale.ROOT)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
    }

    override val searchPath = "search"

    override val popularPath = "danh-sach-truyen"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/$popularPath/$page/?sort=views&status=0"
        return parseMangaPage(client.get(url), popularMangaSelector(), ::popularMangaFromElement)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/$popularPath/$page/?sort=latest-updated&status=0"
        return parseMangaPage(client.get(url), latestUpdatesSelector(), ::latestUpdatesFromElement)
    }

    override fun popularMangaNextPageSelector(): String = "a[title=Last Page]"

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val pages = parsePageList(response)
        if (pages.isNotEmpty()) {
            return pages
        }

        val document = response.asJsoup()
        val chapterId = CHAPTER_ID_REGEX.find(document.html())?.groupValues?.get(1)
            ?: return emptyList()

        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", response.request.url.toString())
            .build()

        val ajaxResponse = client.post("$baseUrl/ajax/image/list/chap/$chapterId?cache=0", ajaxHeaders, "".toRequestBody())
        return parsePageList(ajaxResponse)
    }

    override suspend fun parsePageList(response: Response): List<Page> {
        val html = if (response.request.method == "POST") {
            response.parseAs<AjaxImageListDto>().html
        } else {
            response.asJsoup().html()
        }
        val document = Jsoup.parseBodyFragment(html, baseUrl)

        return document.select(pageListSelector).mapNotNull { imageOrNull(it) }
            .filterNot { it.startsWith("data:") }
            .distinct()
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addPathSegment(searchPath)
                addQueryParameter(queryParam, query)
                addPathSegments("$page/")
            } else {
                (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                    when (filter) {
                        is GenreFilter -> filter.toUriPart()?.let {
                            addPathSegment("the-loai")
                            addPathSegment(it)
                            addPathSegments("$page/")
                            addQueryParameter("sort", "latest-updated")
                            addQueryParameter("status", "0")
                        }
                        is StatusFilter -> filter.toUriPart()?.let {
                            when (it) {
                                "2" -> addPathSegment("truyen-hoan-thanh")
                                else -> addPathSegment(popularPath)
                            }
                            addPathSegments("$page/")
                            addQueryParameter("sort", "latest-updated")
                            addQueryParameter("status", it)
                        }
                        else -> {}
                    }
                }
            }
        }.build()

        return parseMangaPage(client.get(url), searchMangaSelector(), ::searchMangaFromElement)
    }

    companion object {
        private val CHAPTER_ID_REGEX = Regex("""CHAPTER_ID\s*=\s*(\d+)""")
    }
}

@Serializable
private class AjaxImageListDto(
    val html: String,
)
