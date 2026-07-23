package eu.kanade.tachiyomi.extension.vi.cuutruyen

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

@Source
abstract class CuuTruyen :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    private val apiUrl: String get() = "$baseUrl/api/v2"

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(ImageInterceptor())
        addInterceptor(::thumbnailIntercept)
        rateLimit(3)
    }

    private val titleCache = object : LinkedHashMap<Int, String?>(
        (TITLE_CACHE_CAPACITY / TITLE_CACHE_LOAD_FACTOR).toInt(),
        TITLE_CACHE_LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String?>?): Boolean = size > TITLE_CACHE_CAPACITY
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("mangas/top")
            addQueryParameter("duration", "all")
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "24")
        }.build()

        client.get(url, headers, CacheControl.FORCE_NETWORK).use { response ->
            val responseDto = response.parseAs<ResponseDto<List<MangaDto>>>()
            return parseMangaList(responseDto.data, responseDto.metadata)
        }
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("mangas/recently_updated")
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "30")
        }.build()

        client.get(url, headers, CacheControl.FORCE_NETWORK).use { response ->
            val responseDto = response.parseAs<ResponseDto<List<MangaDto>>>()
            return parseMangaList(responseDto.data, responseDto.metadata)
        }
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = query.removePrefix(PREFIX_ID_SEARCH).trim()
            if (id.toIntOrNull() == null) {
                throw Exception("ID tìm kiếm không hợp lệ (phải là một số).")
            }
            val url = "/mangas/$id"
            val manga = SManga.create().apply { this.url = url }
            val details = getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
            details.url = url
            return MangasPage(listOf(details), false)
        }

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("mangas/search")
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            (filters.ifEmpty { getFilterList() }).forEach { filter ->
                when (filter) {
                    is TagFilter -> {
                        val tags = filter.state.filter { it.state }.joinToString(" AND ") { "\"${it.id}\"" }
                        if (tags.isNotEmpty()) {
                            addQueryParameter("tags", tags)
                        }
                    }
                    else -> {}
                }
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "24")
        }.build()

        client.get(url, headers, CacheControl.FORCE_NETWORK).use { response ->
            val path = response.request.url.encodedPath
            if (path.endsWith("mangas/search") || path.endsWith("mangas/top")) {
                val responseDto = response.parseAs<ResponseDto<List<MangaDto>>>()
                return parseMangaList(responseDto.data, responseDto.metadata)
            }

            val responseDto = response.parseAs<ResponseDto<SearchByTagDTO>>()
            return parseMangaList(responseDto.data.mangas, responseDto.metadata)
        }
    }

    private fun parseMangaList(data: List<MangaDto>, metadata: PaginationMetadataDto?): MangasPage {
        val coverKey = preferences.coverQuality
        val manga = data.map { it.toSManga(coverKey) }
        val hasNextPage = metadata?.let { it.currentPage < it.totalPages } ?: false

        data.forEach {
            titleCache[it.id] = when (coverKey) {
                "cover_mobile_url" -> it.coverMobileUrl
                else -> it.coverUrl
            }
        }

        return MangasPage(manga, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host == baseUrl.toHttpUrl().host) {
            client.get("$apiUrl${url.encodedPath}").use { response ->
                return response.parseAs<ResponseDto<MangaDto>>().data.toSManga(preferences.coverQuality)
            }
        }
        return null
    }

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = client.get("$apiUrl${manga.url}").use { response ->
            response.parseAs<ResponseDto<MangaDto>>().data.toSManga(preferences.coverQuality)
        }

        val chaptersList = client.get("$apiUrl${manga.url}/chapters", headers, CacheControl.FORCE_NETWORK).use { response ->
            val segments = response.request.url.pathSegments
            val lastIndex = segments.lastIndex
            val mangaUrl = "/${segments[lastIndex - 2]}/${segments[lastIndex - 1]}"
            response.parseAs<ResponseDto<List<ChapterDto>>>().data.map { it.toSChapter(mangaUrl) }
        }

        return SMangaUpdate(details, chaptersList)
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.split("/").last()
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("chapters")
            addPathSegment(chapterId)
        }.build()

        client.get(url, headers, CacheControl.FORCE_NETWORK).use { response ->
            return response.parseAs<ResponseDto<ChapterDto>>().data.pages!!.map { it.toPage() }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TagFilter(tagList()),
    )

    private fun thumbnailIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath
        val isMangaCoverRequest = path.contains("/manga/") && path.contains("/cover/")

        if (response.isSuccessful || !isMangaCoverRequest) {
            return response
        }

        val titleId = path.substringAfter("/manga/")
            .substringBefore("/cover/")
            .toIntOrNull() ?: return response
        val newCover = titleCache[titleId] ?: return response

        response.close()
        return chain.proceed(request.newBuilder().url(newCover).build())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "coverQuality"
            title = "Chất lượng ảnh bìa"
            entries = arrayOf("Chất lượng cao", "Di động")
            entryValues = arrayOf("cover_url", "cover_mobile_url")
            setDefaultValue("cover_url")

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String

                preferences.edit()
                    .putString("coverQuality", entry)
                    .commit()
            }
        }.let(screen::addPreference)
    }

    private val SharedPreferences.coverQuality
        get() = getString("coverQuality", "cover_url")

    companion object {
        private const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_CACHE_CAPACITY = 120
        private const val TITLE_CACHE_LOAD_FACTOR = 0.7F
    }
}
