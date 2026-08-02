package eu.kanade.tachiyomi.extension.all.ehentai

import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

@Source
abstract class EHentai :
    KeiSource(),
    ConfigurableSource {

    private val ehLang by lazy {
        when (lang) {
            "ja" -> "japanese"
            "en" -> "english"
            "zh" -> "chinese"
            "nl" -> "dutch"
            "fr" -> "french"
            "de" -> "german"
            "hu" -> "hungarian"
            "it" -> "italian"
            "ko" -> "korean"
            "pl" -> "polish"
            "pt-BR" -> "portuguese"
            "ru" -> "russian"
            "es" -> "spanish"
            "th" -> "thai"
            "vi" -> "vietnamese"
            else -> lang
        }
    }

    private val preferences: SharedPreferences = getPreferences()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }
    private val memberId get() = getMemberIdPref()
    private val passHash get() = getPassHashPref()
    private val igneous get() = getIgneousPref()
    private val forceEh get() = getForceEhPref()

    override val baseUrl: String
        get() = when {
            System.getenv("CI") == "true" -> "https://e-hentai.org"
            !forceEh && memberId.isNotEmpty() && passHash.isNotEmpty() -> "https://exhentai.org"
            else -> "https://e-hentai.org"
        }

    private var lastMangaId = ""

    // true if lang is a "natural human language"
    private fun isLangNatural(): Boolean = lang != "all"

    // Initializers

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        cookieJar(CookieJar.NO_COOKIES)
        addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != IMAGE_LOOPBACK_HOST) {
                return@addInterceptor chain.proceed(request)
            }

            val viewerUrl = request.url.fragment!!
            val result = runCatching {
                runBlocking {
                    val response = client.get(viewerUrl)
                    Parser.parseImageUrl(response, getOriginalImagePref())
                }
            }

            val imageUrl = result.getOrNull()
            if (imageUrl == null || result.isFailure) {
                return@addInterceptor chain.proceed(request)
            }

            val newRequest = request.newBuilder()
                .url(imageUrl)
                .removeHeader("Cookie")
                .build()

            chain.proceed(newRequest)
        }
        addInterceptor { chain ->
            val request = chain.request()
            val currentBaseUrl = baseUrl
            val newReq = request.newBuilder()
                .removeHeader("Cookie")
                .addHeader("Cookie", cookiesHeader)
                .header("Referer", "$currentBaseUrl/")
                .header("Origin", currentBaseUrl)
                .build()

            chain.proceed(newReq)
        }
    }

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (isLangNatural()) {
            "$baseUrl/?f_search=${languageTag()}&f_srdd=5&f_sr=on"
        } else {
            baseUrl
        }
        val request = exGetRequest(url, page)
        val response = client.newCall(request).awaitSuccess()
        val result = Parser.parseMangaList(response, ehLang, isLangNatural() && getEnforceLanguagePref())
        lastMangaId = result.lastMangaId
        return result.mangasPage
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = exGetRequest(baseUrl, page)
        val response = client.newCall(request).awaitSuccess()
        val result = Parser.parseMangaList(response, ehLang, isLangNatural() && getEnforceLanguagePref())
        lastMangaId = result.lastMangaId
        return result.mangasPage
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            val response = client.get("$baseUrl/g/$id")
            val details = Parser.parseDetails(response).apply {
                url = "/g/$id/"
            }
            return MangasPage(listOf(details), false)
        }

        val enforceLanguageFilter = filters.find { it is EnforceLanguageFilter }?.state == true
        var modifiedQuery = when {
            !isLangNatural() -> query
            query.isBlank() -> languageTag(enforceLanguageFilter)
            else -> languageTag(enforceLanguageFilter).let { if (it.isNotEmpty()) "$query,$it" else query }
        }
        filters.filterIsInstance<TextFilter>().forEach { filter ->
            if (filter.state.isNotEmpty()) {
                val splitted = filter.state.split(",").filter(String::isNotBlank)
                splitted.forEach { tag ->
                    val trimmed = tag.trim().lowercase()
                    val tagName = trimmed.removePrefix("-")
                    val isExclude = trimmed.startsWith('-')
                    modifiedQuery += if (isExclude) {
                        " -${filter.type}:\"$tagName\""
                    } else {
                        " ${filter.type}:\"$tagName\""
                    }
                }
            }
        }
        val baseSearchUrl = "$baseUrl$QUERY_PREFIX&f_search=${
            withContext(Dispatchers.IO) {
                URLEncoder.encode(modifiedQuery, "UTF-8")
            }
        }"
        val uri = Uri.parse(baseSearchUrl).buildUpon()

        filters.filterIsInstance<GenreGroup>().firstOrNull()?.state?.let { options ->
            if (options.none { it.state }) {
                options.forEach { it.state = true }
            }
        }

        filters.forEach {
            if (it is UriFilter) it.addToUri(uri)
        }

        if (uri.toString().contains("f_spf") || uri.toString().contains("f_spt")) {
            if (page > 1) uri.appendQueryParameter("from", lastMangaId)
        }

        val request = exGetRequest(uri.toString(), page)
        val response = client.newCall(request).awaitSuccess()
        val result = Parser.parseMangaList(response, ehLang, isLangNatural() && (enforceLanguageFilter || getEnforceLanguagePref()))
        lastMangaId = result.lastMangaId
        return result.mangasPage
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if ((url.host == "e-hentai.org" || url.host == "exhentai.org") && url.pathSegments.getOrNull(0) == "g") {
            val response = client.get(url)
            return Parser.parseDetails(response)
        }
        return null
    }

    // Details + Chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))

        return SMangaUpdate(
            manga = Parser.parseDetails(response),
            chapters = listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Chapter"
                    chapter_number = 1f
                },
            ),
        )
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = mutableListOf<String>()
        var nextUrl: String? = getChapterUrl(chapter)

        while (nextUrl != null) {
            val doc = client.get(nextUrl).asJsoup()
            pages += Parser.parseChapterPage(doc)
            nextUrl = Parser.nextPageUrl(doc)
        }

        return pages.mapIndexed { i, url ->
            val fakeUrl = "https://$IMAGE_LOOPBACK_HOST/#$url"
            Page(i, "", fakeUrl)
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .build()

    // Helpers

    private fun exGetRequest(url: String, page: Int): Request {
        val pageIndex = if (page == 1) null else page
        val finalUrl = pageIndex?.let {
            Uri.parse(url).buildUpon().appendQueryParameter("next", lastMangaId).toString()
        } ?: url

        return Request.Builder().url(finalUrl).headers(headers).build()
    }

    private fun languageTag(enforceLanguageFilter: Boolean = false): String = if (lang != "all" && (enforceLanguageFilter || getEnforceLanguagePref())) "language:$ehLang" else ""

    private val cookiesHeader get() = buildMap {
        val settings = mutableListOf("prn_n")
        if (lang != "all") {
            settings += "xl_" + languageMappings.filter { it.first != ehLang }
                .flatMap { it.second }
                .joinToString("x")
        }

        put("uconfig", settings.joinToString("-"))
        put("nw", "1")
        put("ipb_member_id", memberId)
        put("ipb_pass_hash", passHash)
        put("igneous", igneous)
    }.entries.joinToString(separator = "; ", postfix = ";") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    // Filters

    override fun getFilterList(data: JsonElement?) = FilterList(
        buildList {
            if (lang != "all") {
                add(EnforceLanguageFilter(getEnforceLanguagePref()))
            }
            add(Favorites())
            add(Watched())
            add(GenreGroup())
            add(Filter.Header("Separate tags with commas (,)"))
            add(Filter.Header("Prepend with dash (-) to exclude"))
            add(Filter.Header("Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories."))
            add(TextFilter("Tags", "tag"))
            add(TextFilter("Female Tags", "female"))
            add(TextFilter("Male Tags", "male"))
            add(AdvancedGroup())
        },
    )

    private val languageMappings = listOf(
        Pair("japanese", listOf("0", "1024", "2048")),
        Pair("english", listOf("1", "1025", "2049")),
        Pair("chinese", listOf("10", "1034", "2058")),
        Pair("dutch", listOf("20", "1044", "2068")),
        Pair("french", listOf("30", "1054", "2078")),
        Pair("german", listOf("40", "1064", "2088")),
        Pair("hungarian", listOf("50", "1074", "2098")),
        Pair("italian", listOf("60", "1084", "2108")),
        Pair("korean", listOf("70", "1094", "2118")),
        Pair("polish", listOf("80", "1104", "2128")),
        Pair("portuguese", listOf("90", "1114", "2138")),
        Pair("russian", listOf("100", "1124", "2148")),
        Pair("spanish", listOf("110", "1134", "2158")),
        Pair("thai", listOf("120", "1144", "2168")),
        Pair("vietnamese", listOf("130", "1154", "2178")),
    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = FORCE_EH
            title = FORCE_EH_TITLE
            summary = FORCE_EH_SUMMARY
            setDefaultValue(FORCE_EH_DEFAULT_VALUE)
        }.also(screen::addPreference)

        if (lang != "all") {
            CheckBoxPreference(screen.context).apply {
                key = "${ENFORCE_LANGUAGE_PREF_KEY}_$lang"
                title = ENFORCE_LANGUAGE_PREF_TITLE
                summary = ENFORCE_LANGUAGE_PREF_SUMMARY
                setDefaultValue(ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)
            }.also(screen::addPreference)
        }

        CheckBoxPreference(screen.context).apply {
            key = "${ORIGINAL_IMAGE_PREF_KEY}_$lang"
            title = ORIGINAL_IMAGE_PREF_TITLE
            summary = ORIGINAL_IMAGE_PREF_SUMMARY
            setDefaultValue(ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = MEMBER_ID_PREF_KEY
            title = MEMBER_ID_PREF_TITLE
            summary = MEMBER_ID_PREF_SUMMARY
            setDefaultValue(MEMBER_ID_PREF_DEFAULT_VALUE)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASS_HASH_PREF_KEY
            title = PASS_HASH_PREF_TITLE
            summary = PASS_HASH_PREF_SUMMARY
            setDefaultValue(PASS_HASH_PREF_DEFAULT_VALUE)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = IGNEOUS_PREF_KEY
            title = IGNEOUS_PREF_TITLE
            summary = IGNEOUS_PREF_SUMMARY
            setDefaultValue(IGNEOUS_PREF_DEFAULT_VALUE)
        }.also(screen::addPreference)
    }

    private fun getEnforceLanguagePref() = preferences.getBoolean("${ENFORCE_LANGUAGE_PREF_KEY}_$lang", ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)
    private fun getOriginalImagePref() = preferences.getBoolean("${ORIGINAL_IMAGE_PREF_KEY}_$lang", ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)
    private fun getForceEhPref() = preferences.getBoolean(FORCE_EH, FORCE_EH_DEFAULT_VALUE)

    private fun getCookieValue(cookieTitle: String, defaultValue: String, prefKey: String): String {
        val cookies = webViewCookieManager.getCookie("https://forums.e-hentai.org")
        var value: String? = null

        if (cookies != null) {
            val cookieArray = cookies.split("; ")
            for (cookie in cookieArray) {
                if (cookie.startsWith("$cookieTitle=")) {
                    value = cookie.split("=")[1]
                    break
                }
            }
        }

        return value ?: (preferences.getString(prefKey, defaultValue) ?: defaultValue)
    }

    private fun getPassHashPref() = getCookieValue(PASS_HASH_PREF_TITLE, PASS_HASH_PREF_DEFAULT_VALUE, PASS_HASH_PREF_KEY)
    private fun getMemberIdPref() = getCookieValue(MEMBER_ID_PREF_TITLE, MEMBER_ID_PREF_DEFAULT_VALUE, MEMBER_ID_PREF_KEY)
    private fun getIgneousPref() = getCookieValue(IGNEOUS_PREF_TITLE, IGNEOUS_PREF_DEFAULT_VALUE, IGNEOUS_PREF_KEY)

    companion object {
        const val QUERY_PREFIX = "?f_apply=Apply+Filter"
        const val PREFIX_ID_SEARCH = "id:"
        const val IMAGE_LOOPBACK_HOST = "127.0.0.1"

        private const val ENFORCE_LANGUAGE_PREF_KEY = "ENFORCE_LANGUAGE"
        private const val ENFORCE_LANGUAGE_PREF_TITLE = "Enforce Language"
        private const val ENFORCE_LANGUAGE_PREF_SUMMARY = "If checked, forces browsing of manga matching a language tag"
        private const val ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE = false

        private const val ORIGINAL_IMAGE_PREF_KEY = "ORIGINAL_IMAGE"
        private const val ORIGINAL_IMAGE_PREF_TITLE = "Original Image"
        private const val ORIGINAL_IMAGE_PREF_SUMMARY = "If checked, if your account has permission, it will use the original image and the image enhancement process will be slower"
        private const val ORIGINAL_IMAGE_PREF_DEFAULT_VALUE = false

        private const val MEMBER_ID_PREF_KEY = "MEMBER_ID"
        private const val MEMBER_ID_PREF_TITLE = "ipb_member_id"
        private const val MEMBER_ID_PREF_SUMMARY = "ipb_member_id value"
        private const val MEMBER_ID_PREF_DEFAULT_VALUE = ""

        private const val PASS_HASH_PREF_KEY = "PASS_HASH"
        private const val PASS_HASH_PREF_TITLE = "ipb_pass_hash"
        private const val PASS_HASH_PREF_SUMMARY = "ipb_pass_hash value"
        private const val PASS_HASH_PREF_DEFAULT_VALUE = ""

        private const val IGNEOUS_PREF_KEY = "IGNEOUS"
        private const val IGNEOUS_PREF_TITLE = "igneous"
        private const val IGNEOUS_PREF_SUMMARY = "igneous value override"
        private const val IGNEOUS_PREF_DEFAULT_VALUE = ""

        private const val FORCE_EH = "FORCE_EH"
        private const val FORCE_EH_TITLE = "Force e-hentai"
        private const val FORCE_EH_SUMMARY = "Force e-hentai to avoid content on exhentai"
        private const val FORCE_EH_DEFAULT_VALUE = true
    }
}
