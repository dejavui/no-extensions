package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.Utils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.Utils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.Utils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.Utils.getTags
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {

    private val nhLang by lazy {
        when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }
    }

    private val apiUrl = "$baseUrl/api/v2"

    private val preferences: SharedPreferences = getPreferences()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }

    private var displayFullTitle: Boolean = preferences.getString(TITLE_PREF, "full") == "full"

    // Initializers

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        val app = applicationContext
        val cacheParent = app.cacheDir.takeIf { it.exists() || it.mkdirs() }
            ?: app.externalCacheDir
            ?: app.filesDir
        val cacheDirectory = File(cacheParent, "nhentai_api_cache_$lang")

        return apply {
            cache(Cache(cacheDirectory, 5L * 1024 * 1024))
            addInterceptor(NhApiRetryInterceptor())
//            addNetworkInterceptor(NhGalleryCacheInterceptor())
            addNetworkInterceptor(NhAuthorizationInterceptor())

            val host = baseUrl.toHttpUrl().host
            rateLimit(1, 6.seconds) {
                it.host == host && (it.encodedPath.contains("/search") || it.encodedPath.contains("/favorites"))
            }
            rateLimit(1, 4.seconds) {
                it.host == host && it.encodedPath == "/api/v2/galleries"
            }
            rateLimit(1, 3.seconds) {
                it.host == host && it.encodedPath.matches(Regex("/api/v2/galleries/\\d+/?"))
            }

            rateLimit(3, 1.seconds) {
                it.host.matches(Regex("[it]\\d+\\.nhentai\\.net"))
            }
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        setRandomUserAgent(filterInclude = listOf("chrome"))
        removeAll("Origin")
    }

    // Authentication

    private val apiKey get() = preferences.getString(API_KEY, "")

    private val cookieToken
        get() = webViewCookieManager.getCookie(baseUrl)
            ?.split("; ")
            ?.firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter("access_token=") ?: ""

    // CDNs / Config

    private val nhConfig: NHConfig by lazy {
        runCatching {
            val request = Request.Builder().url("$apiUrl/config").headers(headers).build()
            client.newCall(request).execute().parseAs<NHConfig>(jsonInstance)
        }.getOrDefault(
            NHConfig(
                imageServers = (1..4).map { "https://i$it.nhentai.net" },
                thumbServers = (1..4).map { "https://t$it.nhentai.net" },
            ),
        )
    }

    private val imageServer get() = nhConfig.imageServers.random()

    private val thumbServer get() = nhConfig.thumbServers.random()

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", nhLang.ifBlank { "\"\"" }.let { if (it == "\"\"") it else "language:$it" })
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return parseSearchPage(client.get(url))
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (nhLang.isBlank()) {
            "$apiUrl/galleries".toHttpUrl().newBuilder()
                .addQueryParameter("per_page", "25")
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", "language:$nhLang")
        }
        url.addQueryParameter("page", page.toString())
        return parseSearchPage(client.get(url.build()))
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val id = query.removePrefix(PREFIX_ID_SEARCH).toIntOrNull() ?: query.toIntOrNull()
        if (id != null) {
            val data = client.get("$apiUrl/galleries/$id").parseAs<Hentai>(jsonInstance)
            val details = parseDetails(data)
            return MangasPage(listOf(details), false)
        }

        val filterList = filters.ifEmpty { getFilterList() }
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage = filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        val url = if (favoriteFilter?.state == true) {
            "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery".trim())
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", "$query $nhLangSearch$advQuery".trim().ifBlank { "\"\"" })
                .apply {
                    filterList.firstInstanceOrNull<SortFilter>()?.let {
                        addQueryParameter("sort", it.toUriPart())
                    }
                }
        }

        url.addQueryParameter("page", offsetPage.toString())
        return parseSearchPage(client.get(url.build()))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host == baseUrl.toHttpUrl().host && url.pathSegments.getOrNull(0) == "g") {
            val id = url.pathSegments.getOrNull(1) ?: return null
            val data = client.get("$apiUrl/galleries/$id").parseAs<Hentai>(jsonInstance)
            return parseDetails(data)
        }
        return null
    }

    private fun parseSearchPage(response: Response): MangasPage {
        val res = response.parseAs<PaginatedResponse<GalleryItem>>(jsonInstance)
        val mangas = res.result.mapNotNull { runCatching { parseSearchData(it) }.getOrNull() }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = res.numPages?.let { it > page }
            ?: res.total?.let { it > page * res.perPage }
            ?: false
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseSearchData(data: GalleryItem): SManga = SManga.create().apply {
        url = "/g/${data.id}/"
        title = (data.englishTitle ?: data.japaneseTitle)!!.let {
            if (displayFullTitle) it else it.shortenTitle()
        }
        thumbnail_url = "$thumbServer/${data.thumbnail}"
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // Manga Details

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    private fun parseDetails(data: Hentai): SManga = SManga.create().apply {
        url = "/g/${data.id}/"
        title = if (displayFullTitle) {
            data.title.english ?: data.title.japanese ?: data.title.pretty!!
        } else {
            data.title.pretty ?: (data.title.english ?: data.title.japanese)!!.shortenTitle()
        }
        thumbnail_url = "$thumbServer/${data.thumbnail.path}"
        status = SManga.COMPLETED
        artist = getArtists(data)
        author = getGroups(data) ?: getArtists(data)
        description = buildString {
            append("Full English and Japanese titles:\n")
            append(data.title.english ?: data.title.japanese ?: data.title.pretty ?: "", "\n")
            append(data.title.japanese ?: "", "\n\n")
            append("Pages: ", data.numPages, "\n")
            append("Favorited by: ", data.numFavorites, "\n")
            append(getTagDescription(data))
        }
        genre = getTags(data)
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    // Chapter List + Details

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.removeSurrounding("/g/", "/")
        val data = client.get(
            url = "$apiUrl/galleries/$id",
            cacheControl = CacheControl.Builder().maxStale(2, TimeUnit.HOURS).build(),
        ).parseAs<Hentai>(jsonInstance)

        return SMangaUpdate(
            manga = parseDetails(data),
            chapters = listOf(data.toSChapter()),
        )
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.removeSurrounding("/g/", "/")
        val data = client.get("$apiUrl/galleries/$id").parseAs<Hentai>(jsonInstance)
        return data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = "$imageServer/${page.path}")
        }
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = newValue == "full"
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = SORT_PREF
            title = SORT_PREF
            entries = SORT_OPTIONS.map { it.first }.toTypedArray()
            entryValues = SORT_OPTIONS.map { it.second }.toTypedArray()
            summary = "%s"
            setDefaultValue("popular")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = API_KEY
            title = "API key"
            summary = "Profile > Settings > API Keys"
            setDefaultValue("")
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    // Helpers

    private fun String.shortenTitle() = replace(SHORTEN_TITLE_REGEX, "").trim()

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
                    val isRange = filter is PagesFilter || filter is UploadedFilter
                    if (tag.startsWith("-")) append("-")
                    append(filter.name, ':')
                    if (!isRange) append('"')
                    append(tag.removePrefix("-"))
                    if (!isRange) append('"')
                    append(" ")
                }
        }
    }.trim()

    // Interceptors

//    private class NhGalleryCacheInterceptor : Interceptor {
//        override fun intercept(chain: Interceptor.Chain): Response {
//            val response = chain.proceed(chain.request())
//            if (!GALLERY_PATH_REGEX.matches(response.request.url.encodedPath)) return response
//
//            return response.newBuilder()
//                .removeHeader("Cache-Control")
//                .removeHeader("Expires")
//                .removeHeader("Pragma")
//                .header("Cache-Control", "max-age=$GALLERY_CACHE_MAX_AGE_SECONDS")
//                .build()
//        }
//    }

    private class NhApiRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.host != NHENTAI_HOST || !API_PATH_REGEX.matches(request.url.encodedPath)) {
                return chain.proceed(request)
            }

            val response = chain.proceed(request)
            if (response.code != 429 || request.header(BACKOFF_RETRY_HEADER) != null) {
                return response
            }

            val retryAfter = response.header("Retry-After")?.trim()
            if (!retryAfter.isNullOrEmpty() && retryAfter.toLongOrNull() != 0L) return response

            response.close()
            return chain.proceed(
                request.newBuilder()
                    .header(BACKOFF_RETRY_HEADER, "1")
                    .build(),
            )
        }
    }

    private inner class NhAuthorizationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url

            if (url.host != baseUrl.toHttpUrl().host || !API_PATH_REGEX.matches(url.encodedPath)) {
                return chain.proceed(request)
            }

            val currentApiKey = apiKey
            if (!currentApiKey.isNullOrBlank()) {
                val newRequest = request.newBuilder()
                    .header("Authorization", "Key $currentApiKey")
                    .build()
                val response = chain.proceed(newRequest)
                if (response.code == 401) {
                    response.close()
                    throw IOException("Invalid API key")
                }
                return response
            }

            if (url.encodedPath.contains("/favorites")) {
                val accessToken = cookieToken
                val newRequest = if (accessToken.isNotBlank()) {
                    request.newBuilder()
                        .header("Authorization", "User $accessToken")
                        .build()
                } else {
                    request
                }
                val response = chain.proceed(newRequest)
                if (response.code == 401) {
                    response.close()
                    throw IOException("Log in via WebView or add API key in settings")
                }
                return response
            }

            return chain.proceed(request)
        }
    }

    // Filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),
        Filter.Separator(),
        SortFilter(
            SORT_OPTIONS.indexOfFirst { it.second == preferences.getString(SORT_PREF, "popular") }
                .coerceAtLeast(0),
        ),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    companion object {
        private const val NHENTAI_HOST = "nhentai.net"

//        private val GALLERY_PATH_REGEX = Regex("^/api/v2/galleries/\\d+/?$")
        private val API_PATH_REGEX = Regex("^/api/v2/.*$")
        private const val BACKOFF_RETRY_HEADER = "X-NHentai-Backoff-Retry"
//        private const val GALLERY_CACHE_MAX_AGE_SECONDS = 7200

        private const val API_KEY = "api_key"
        const val PREFIX_ID_SEARCH = "id:"

        private const val TITLE_PREF = "Display manga title as:"
        private val SHORTEN_TITLE_REGEX = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")

        private const val SORT_PREF = "Default sort preference when searching"
    }
}
