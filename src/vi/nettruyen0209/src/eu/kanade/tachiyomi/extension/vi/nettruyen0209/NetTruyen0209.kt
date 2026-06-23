package eu.kanade.tachiyomi.extension.vi.nettruyen0209

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
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

class NetTruyen0209 :
    WPComics(
        "NetTruyen0209",
        DEFAULT_DOMAIN,
        "vi",
        dateFormat = SimpleDateFormat("HH:mm dd-MM-yyyy", Locale.ROOT),
        gmtOffset = null,
    ),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences {
        getString(PREF_DEFAULT_BASE_URL, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != DEFAULT_DOMAIN) {
                edit()
                    .putString(PREF_BASE_URL, DEFAULT_DOMAIN)
                    .putString(PREF_DEFAULT_BASE_URL, DEFAULT_DOMAIN)
                    .apply()
            }
        }
    }

    override val baseUrl: String get() = getPrefBaseUrl()

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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_DOMAIN
            title = "Tên miền tùy chỉnh"
            summary = "Nhập tên miền đầy đủ (ví dụ: $DEFAULT_DOMAIN)"
            setDefaultValue(DEFAULT_DOMAIN)
            dialogTitle = "Ghi đè URL cơ sở"
            dialogMessage = "Default: $DEFAULT_DOMAIN"
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val inputUrl = newValue as String
                    if (inputUrl.isNotBlank()) {
                        inputUrl.toHttpUrl()
                    }
                    preferences.edit().putString(PREF_CUSTOM_DOMAIN, inputUrl).apply()
                    Toast.makeText(screen.context, "Tên miền đã được thay đổi", Toast.LENGTH_LONG).show()
                    true
                } catch (e: Exception) {
                    Toast.makeText(screen.context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.let(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(PREF_CUSTOM_DOMAIN, DEFAULT_DOMAIN)!!.removeSuffix("/")

    companion object {
        private const val DEFAULT_DOMAIN = "https://nettruyen11s.com"
        private const val PREF_DEFAULT_BASE_URL = "pref_default_base_url"
        private const val PREF_BASE_URL = "pref_base_url"
        private const val PREF_CUSTOM_DOMAIN = "pref_custom_domain"
        private val CHAPTER_ID_REGEX = Regex("""CHAPTER_ID\s*=\s*(\d+)""")
    }
}

@Serializable
private class AjaxImageListDto(
    val html: String,
)
