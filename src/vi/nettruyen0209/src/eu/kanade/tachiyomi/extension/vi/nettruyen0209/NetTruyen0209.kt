package eu.kanade.tachiyomi.extension.vi.nettruyen0209

import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class NetTruyen0209 : WPComics() {
    override val dateFormat = SimpleDateFormat("HH:mm dd-MM-yyyy", Locale.ROOT)

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override val searchPath = "search"

    override val popularPath = "danh-sach-truyen"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/$popularPath/$page/?sort=views&status=0", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$popularPath/$page/?sort=latest-updated&status=0", headers)

    override fun popularMangaNextPageSelector(): String = "a[title=Last Page]"

    // ============================== Pages =================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .flatMap { response ->
                val document = response.asJsoup()
                val pages = pageListParse(document)
                if (pages.isNotEmpty()) {
                    Observable.just(pages)
                } else {
                    val chapterId = CHAPTER_ID_REGEX.find(document.html())?.groupValues?.get(1)
                        ?: return@flatMap Observable.just(emptyList<Page>())

                    val ajaxRequest = POST(
                        "$baseUrl/ajax/image/list/chap/$chapterId?cache=0",
                        ajaxHeaders(response.request.url.toString()),
                    )
                    client.newCall(ajaxRequest).asObservableSuccess().map(::pageListParse)
                }
            }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = if (response.request.method == "POST") {
            response.parseAs<AjaxImageListDto>().html
        } else {
            response.asJsoup().html()
        }
        val document = Jsoup.parseBodyFragment(html, baseUrl)

        return pageListParse(document)
    }

    private fun pageListParse(document: Document): List<Page> = document.select(pageListSelector).mapNotNull { imageOrNull(it) }
        .filterNot { it.startsWith("data:") }
        .distinct()
        .mapIndexed { i, url -> Page(i, imageUrl = url) }
    private fun ajaxHeaders(referer: String) = headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .build()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        url.apply {
            if (query.isNotBlank()) {
                addPathSegment(searchPath)
                addQueryParameter(queryParam, query)
                addPathSegments("$page/")
            } else {
                (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                    when (filter) {
                        is GenreFilter -> filter.toUriPart()?.let {
                            url.addPathSegment("the-loai")
                            url.addPathSegment(it)
                            url.addPathSegments("$page/")
                            url.addQueryParameter("sort", "latest-updated")
                            url.addQueryParameter("status", "0")
                        }
                        is StatusFilter -> filter.toUriPart()?.let {
                            val paths = when (it) {
                                "2" -> {
                                    url.addPathSegment("truyen-hoan-thanh")
                                }
                                else -> {
                                    url.addPathSegment(popularPath)
                                }
                            }
                            url.addPathSegments("$page/")
                            url.addQueryParameter("sort", "latest-updated")
                            paths.addQueryParameter("status", it)
                        }
                        else -> {}
                    }
                }
            }
        }

        return GET(url.toString(), headers)
    }

    companion object {
        private val CHAPTER_ID_REGEX = Regex("""CHAPTER_ID\s*=\s*(\d+)""")
    }
}

@Serializable
private class AjaxImageListDto(
    val html: String,
)
