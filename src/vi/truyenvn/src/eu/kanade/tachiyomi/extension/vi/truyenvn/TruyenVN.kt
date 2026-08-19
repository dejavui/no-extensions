package eu.kanade.tachiyomi.extension.vi.truyenvn

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class TruyenVN : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val mangaSubString = "truyen-tranh"

    private val imageInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url

        if (url.host.contains("imggo")) {
            val newRequest = request.newBuilder()
                .removeHeader("Referer")
                .build()
            return@Interceptor chain.proceed(newRequest)
        }
        chain.proceed(request)
    }
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(imageInterceptor)
        .rateLimit(3)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("m_orderby", "views")
        }.build()
        return GET(url, headers)
    }

    override val popularMangaUrlSelector: String = "div.item-thumb a"

    override fun popularMangaFromElement(element: Element): SManga = super.popularMangaFromElement(element).apply {
        with(element) {
            selectFirst(popularMangaUrlSelector)!!.let {
                setUrlWithoutDomain(it.attr("abs:href"))
                title = it.attr("title")
            }
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("m_orderby", "latest")
        }.build()
        return GET(url, headers)
    }

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val request = super.searchRequest(page, query, filters)
        val url = request.url.newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return request.newBuilder().url(url).build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        launchIO { countViews(document) }

        var chapterElements = document.select(chapterListSelector())

        if (chapterElements.isEmpty()) {
            val scripts = document.select("script").map { it.data() }
            val storyId = scripts.firstNotNullOfOrNull { storyIdRegex.find(it)?.groupValues?.get(1) }
            val routeName = scripts.firstNotNullOfOrNull { routeNameRegex.find(it)?.groupValues?.get(1) }
            val apiUrl = scripts.firstNotNullOfOrNull { urlListChapRegex.find(it)?.groupValues?.get(1) }
                ?.replace("\\/", "/")
                ?: "$baseUrl/webapi/stories/list-chapter"

            if (storyId == null || routeName == null) return emptyList()

            val body = FormBody.Builder()
                .add("story_id", storyId)
                .add("routeName", routeName)
                .add("_token", document.select("meta[name=csrf-token]").attr("content"))
                .build()

            val request = POST(apiUrl, xhrHeaders, body)
            chapterElements = client.newCall(request).execute().use { xhrResponse ->
                val jsonResponse = xhrResponse.parseAs<TruyenVNResponseDto>()
                val html = jsonResponse.data?.html ?: return emptyList()
                Jsoup.parseBodyFragment(html, baseUrl).select(chapterListSelector())
            }
        }

        return chapterElements.map(::chapterFromElement)
    }

    override fun chapterFromElement(element: Element): SChapter = super.chapterFromElement(element).apply {
        url = url.trim()
    }

    private val storyIdRegex = """let dataStory = \{"story_id":(\d+)\}""".toRegex()
    private val routeNameRegex = """let dataView = \{.*"routeName":"([^"]+)"""".toRegex()
    private val urlListChapRegex = """"urlListChap":"([^"]+)"""".toRegex()
}

@Serializable
class TruyenVNResponseDto(
    val data: TruyenVNDataDto? = null,
)

@Serializable
class TruyenVNDataDto(
    val html: String,
)
