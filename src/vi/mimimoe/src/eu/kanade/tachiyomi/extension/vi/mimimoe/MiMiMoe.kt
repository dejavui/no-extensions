package eu.kanade.tachiyomi.extension.vi.mimimoe

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class MiMiMoe : HttpSource() {
    override val name: String = "MiMiMoe"

    override val lang: String = "vi"

    override val baseUrl: String = "https://mimimoe.moe"

    private val apiUrl: String = "$baseUrl/api"

    override val supportsLatest: Boolean = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    private fun HttpUrl.Builder.addCommonParams(page: Int) = apply {
        addQueryParameter("exclude_genre", "196")
        addQueryParameter("page", page.toString())
        addQueryParameter("page_size", "45")
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val result = response.parseAs<DataDto>()
        val mangas = result.items.map { it.toSMangaBasic() }
        val hasNextPage = result.hasNext
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegments("manga")
            .addQueryParameter("sort", "updated_at")
            .addCommonParams(page)
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = parseMangaPage(response)

    override fun popularMangaRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegments("manga")
            .addQueryParameter("sort", "views")
            .addCommonParams(page)
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaPage(response)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegments("manga")
            .addPathSegment(id)
            .addPathSegment("chapters")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val segments = response.request.url.pathSegments
        val mangaId = segments[segments.size - 2]
        val res = response.parseAs<List<ChapterDto>>()
        return res.map { it.toSChapter(mangaId) }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val mangaId = chapter.url.substringBefore('/')
        val chapterId = chapter.url.substringAfter('/')
        return "$baseUrl/manga/$mangaId/chapter/$chapterId"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegments("manga")
            .addPathSegment(id)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val res = response.parseAs<MangaDto>()
        return res.toSManga()
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/chapter/${chapter.url.substringAfter("/")}", headers)

    override fun pageListParse(response: Response): List<Page> = response.parseAs<PageDto>().toPage()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val isIdSearch = query.startsWith(PREFIX_ID_SEARCH) ||
            (query.length >= 4 && query.toIntOrNull() != null)

        if (isIdSearch) {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            return client.newCall(GET("$apiUrl/$id", headers))
                .asObservableSuccess()
                .map { MangasPage(listOf(it.parseAs<MangaDto>().toSManga()), false) }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaParse(response: Response) = parseMangaPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = filters.ifEmpty { getFilterList() }
        val isAdvanced = filterList.any {
            (it is GenresFilter && it.state.any { g -> g.state != Filter.TriState.STATE_IGNORE }) ||
                (it is TextField && it.state.isNotEmpty())
        }
        val sortFilter = filterList.filterIsInstance<SortByList>().firstOrNull()
        val sortId = sortFilter?.let { it.values[it.state].id } ?: ""
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("manga")
            when {
                isAdvanced -> addPathSegments("advanced-search")
                sortId.isEmpty() -> addPathSegments("search")
                else -> {}
            }
            filterList.forEach { filter ->
                when (filter) {
                    is SortByList -> {
                        if (!isAdvanced && sortId.isNotEmpty()) addQueryParameter("sort", sortId)
                    }

                    is GenresFilter -> if (isAdvanced) {
                        filter.state.forEach {
                            when (it.state) {
                                Filter.TriState.STATE_INCLUDE -> addQueryParameter("genre", it.id)
                                Filter.TriState.STATE_EXCLUDE -> addQueryParameter("exclude_genre", it.id)
                            }
                        }
                    }

                    is TextField -> if (isAdvanced && filter.state.isNotEmpty()) {
                        if (filter.key == "author") {
                            filter.state.toIntOrNull()?.let { setQueryParameter(filter.key, it.toString()) }
                        } else {
                            setQueryParameter(filter.key, filter.state)
                        }
                    }
                    else -> {}
                }
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("page_size", "24")
            if (query.isNotBlank()) addQueryParameter("title", query)
        }.build()
        return GET(url, headers)
    }

    private fun genresRequest(): Request = GET("$apiUrl/genres", headers)

    private fun parseGenres(response: Response): List<Pair<String, Long>> = response.parseAs<List<Genres>>().map { Pair(it.name, it.id) }

    private var fetchGenresAttempts: Int = 0
    private fun fetchGenres() {
        if (fetchGenresAttempts < 3 && genreList.isEmpty()) {
            launchIO {
                try {
                    client.newCall(genresRequest()).await()
                        .use { parseGenres(it) }
                        .takeIf { it.isNotEmpty() }
                        ?.also { genreList = it }
                } catch (_: Exception) {
                } finally {
                    fetchGenresAttempts++
                }
            }
        }
    }

    private fun launchIO(block: suspend () -> Unit) = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { block() }

    private var genreList: List<Pair<String, Long>> = emptyList()

    private class GenresFilter(pairs: List<Pair<String, Long>>) :
        Filter.Group<Genre>(
            "Thể loại",
            pairs
                .sortedBy { it.first }
                .map { Genre(it.first, it.second.toString()) },
        )

    private class Genre(name: String, val id: String = name) : Filter.TriState(name) {
        override fun toString(): String = name
    }

    private class SortByList :
        Filter.Select<Genre>(
            "Sắp xếp",
            arrayOf(
                Genre("Mới", "updated_at"),
                Genre("Likes", "likes"),
                Genre("Views", "views"),
                Genre("Lưu", "follows"),
                Genre("Tên", "title"),
            ),
        )

    private class TextField(name: String, val key: String) : Filter.Text(name)

    override fun getFilterList(): FilterList {
        fetchGenres()
        return FilterList(
            SortByList(),
            TextField("Tác giả", "author"),
            TextField("Parody", "parody"),
            TextField("Nhân vật", "character"),
            if (genreList.isEmpty()) {
                Filter.Header("Nhấn 'Làm mới' để tải thể loại")
            } else {
                GenresFilter(genreList)
            },
        )
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
