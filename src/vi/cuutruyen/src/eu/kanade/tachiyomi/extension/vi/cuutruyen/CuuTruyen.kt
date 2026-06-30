package eu.kanade.tachiyomi.extension.vi.cuutruyen

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import rx.Observable

@Source
abstract class CuuTruyen :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences = getPreferences()

    private val apiUrl: String by lazy { "$baseUrl/api/v2" }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .addInterceptor(ImageInterceptor())
        .addInterceptor(::thumbnailIntercept)
        .rateLimit(3)
        .build()

    private val titleCache = object : LinkedHashMap<Int, String?>(
        (TITLE_CACHE_CAPACITY / TITLE_CACHE_LOAD_FACTOR).toInt(),
        TITLE_CACHE_LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String?>?): Boolean = size > TITLE_CACHE_CAPACITY
    }

    override fun popularMangaRequest(page: Int): Request = GET(
        apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("mangas/top")
            addQueryParameter("duration", "all")
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "24")
        }.build(),
        headers,
        CacheControl.FORCE_NETWORK,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val responseDto = response.parseAs<ResponseDto<List<MangaDto>>>()
        return parseMangaList(responseDto.data, responseDto.metadata)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("mangas/recently_updated")
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "30")
        }.build(),
        headers,
        CacheControl.FORCE_NETWORK,
    )

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH).trim()
            if (id.toIntOrNull() == null) {
                throw Exception("ID tìm kiếm không hợp lệ (phải là một số).")
            }
            val url = "/mangas/$id"
            fetchMangaDetails(SManga.create().apply { this.url = url })
                .map {
                    it.url = url
                    MangasPage(listOf(it), false)
                }
        }
        else -> super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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
        return GET(url, headers, CacheControl.FORCE_NETWORK)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val path = response.request.url.encodedPath
        if (path.endsWith("mangas/search") || path.endsWith("mangas/top")) {
            return popularMangaParse(response)
        }

        val responseDto = response.parseAs<ResponseDto<SearchByTagDTO>>()
        return parseMangaList(responseDto.data.mangas, responseDto.metadata)
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

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl${manga.url}")

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ResponseDto<MangaDto>>().data.toSManga(preferences.coverQuality)

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl${manga.url}/chapters", headers, CacheControl.FORCE_NETWORK)

    override fun chapterListParse(response: Response): List<SChapter> {
        val segments = response.request.url.pathSegments
        val lastIndex = segments.lastIndex
        val mangaUrl = "/${segments[lastIndex - 2]}/${segments[lastIndex - 1]}"
        return response.parseAs<ResponseDto<List<ChapterDto>>>().data.map { it.toSChapter(mangaUrl) }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request = GET(
        apiUrl.toHttpUrl().newBuilder().apply {
            val chapterId = chapter.url.split("/").last()
            addPathSegment("chapters")
            addPathSegment(chapterId)
        }.build(),
        headers,
        CacheControl.FORCE_NETWORK,
    )

    override fun pageListParse(response: Response): List<Page> = response.parseAs<ResponseDto<ChapterDto>>().data.pages!!.map { it.toPage() }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
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

private class TagFilter(tags: List<Tag>) :
    Filter.Group<Tag>(
        "Thể loại",
        tags,
    )
private class Tag(name: String, val id: String) : Filter.CheckBox(name) {
    override fun toString(): String = name
}

// Got this list off their Discord, they don't have a tag list on the website
private fun tagList() = listOf(
    Tag("Action", "action"),
    Tag("Adaption", "adaption"),
    Tag("Adventure", "adventure"),
    Tag("Aliens", "aliens"),
    Tag("Animals", "animals"),
    Tag("Anime", "anime"),
    Tag("Atlus", "atlus"),
    Tag("Award winning", "award winning"),
    Tag("Ẩm thực", "ẩm thực"),
    Tag("Bạo lực", "bạo lực"),
    Tag("Bi kịch", "bi kịch"),
    Tag("Bí ẩn", "bí ẩn"),
    Tag("Cảnh sát", "cảnh sát"),
    Tag("Chất lượng cao", "chất lượng cao"),
    Tag("Chính kịch", "chính kịch"),
    Tag("Chính trị", "chính trị"),
    Tag("Chuyển sinh", "chuyển sinh"),
    Tag("Chuyển thể", "chuyển thể"),
    Tag("Comedy", "comedy"),
    Tag("Công sở", "công sở"),
    Tag("Cooking", "cooking"),
    Tag("Có màu", "có màu"),
    Tag("Dark fantasy", "dark fantasy"),
    Tag("Databook", "databook"),
    Tag("Doujinshi", "doujinshi"),
    Tag("Drama", "drama"),
    Tag("Du hành thời gian", "du hành thời gian"),
    Tag("Đã hoàn thành", "đã hoàn thành"),
    Tag("Đang tiến hành", "đang tiến hành"),
    Tag("Địa chính trị", "địa chính trị"),
    Tag("Đời thường", "đời thường"),
    Tag("Động vật", "động vật"),
    Tag("Ecchi", "ecchi"),
    Tag("Fantasy", "fantasy"),
    Tag("Game", "game"),
    Tag("Ghosts", "ghosts"),
    Tag("Giật gân", "giật gân"),
    Tag("Gore", "gore"),
    Tag("Hài hước", "hài hước"),
    Tag("Hành động", "hành động"),
    Tag("Harem", "harem"),
    Tag("Hậu tận thế", "hậu tận thế"),
    Tag("Historical", "historical"),
    Tag("Học đường", "học đường"),
    Tag("Horror", "horror"),
    Tag("Idol", "idol"),
    Tag("Isekai", "isekai"),
    Tag("Josei", "josei"),
    Tag("Khoa học", "khoa học"),
    Tag("Khoa học viễn tưởng", "khoa học viễn tưởng"),
    Tag("Khoả thân", "khoả thân"),
    Tag("Kinh dị", "kinh dị"),
    Tag("Lãng mạn", "lãng mạn"),
    Tag("Lgbt", "lgbt"),
    Tag("Lịch sử", "lịch sử"),
    Tag("Manga", "manga"),
    Tag("Manhua", "manhua"),
    Tag("Manhwa", "manhwa"),
    Tag("Martial arts", "martial arts"),
    Tag("Máu me", "máu me"),
    Tag("Mecha", "mecha"),
    Tag("Medical", "medical"),
    Tag("Military", "military"),
    Tag("Miễn bản quyền", "miễn bản quyền"),
    Tag("Monsters", "monsters"),
    Tag("Monster girls", "monster girls"),
    Tag("Mystery", "mystery"),
    Tag("Nam biến nữ", "nam biến nữ"),
    Tag("Nam giả nữ", "nam giả nữ"),
    Tag("Nam x nam", "nam x nam"),
    Tag("Ngọt ngào", "ngọt ngào"),
    Tag("Ninja", "ninja"),
    Tag("Nsfw", "nsfw"),
    Tag("Ntr", "ntr"),
    Tag("Nữ giả nam", "nữ giả nam"),
    Tag("Oneshot", "oneshot"),
    Tag("Phép thuật", "phép thuật"),
    Tag("Phiêu lưu", "phiêu lưu"),
    Tag("Psychological", "psychological"),
    Tag("Quái vật", "quái vật"),
    Tag("Quân đội", "quân đội"),
    Tag("Romance", "romance"),
    Tag("Romcom", "romcom"),
    Tag("Rpg", "rpg"),
    Tag("Samurai", "samurai"),
    Tag("Sát thủ", "sát thủ"),
    Tag("School life", "school life"),
    Tag("Sci-fi", "sci fi"),
    Tag("Sega", "sega"),
    Tag("Seinen", "seinen"),
    Tag("Shoujo", "shoujo"),
    Tag("Shounen", "shounen"),
    Tag("Siêu nhiên", "siêu nhiên"),
    Tag("Sinh tồn", "sinh tồn"),
    Tag("Slice of life", "slice of life"),
    Tag("Smut", "smut"),
    Tag("Sport", "sport"),
    Tag("Supernatural", "supernatural"),
    Tag("Survival", "survival"),
    Tag("Tạm ngưng", "tạm ngưng"),
    Tag("Tâm lý", "tâm lý"),
    Tag("Thể thao", "thể thao"),
    Tag("Thiếu niên", "thiếu niên"),
    Tag("Thriller", "thriller"),
    Tag("Tình dục", "tình dục"),
    Tag("Tình yêu", "tình yêu"),
    Tag("Tình yêu không được đáp lại", "tình yêu không được đáp lại"),
    Tag("Tình yêu thuần khiết", "tình yêu thuần khiết"),
    Tag("Toán học", "toán học"),
    Tag("Tội phạm", "tội phạm"),
    Tag("Tragedy", "tragedy"),
    Tag("Trinh thám", "trinh thám"),
    Tag("Trung cổ", "trung cổ"),
    Tag("Tu tiên", "tu tiên"),
    Tag("Tuyển tập", "tuyển tập"),
    Tag("Vampires", "vampires"),
    Tag("Video games", "video games"),
    Tag("Việt nam", "việt nam"),
    Tag("Virtual reality", "virtual reality"),
    Tag("Võ thuật", "võ thuật"),
    Tag("Vô cp", "vô cp"),
    Tag("Web comic", "web comic"),
    Tag("Webtoon", "webtoon"),
    Tag("Wholesome", "wholesome"),
    Tag("Xuyên không", "xuyên không"),
    Tag("Y học", "y học"),
    Tag("Yaoi", "yaoi"),
    Tag("Yakuza", "yakuza"),
    Tag("Yonkoma", "yonkoma"),
    Tag("Yuri", "yuri"),
    Tag("Zombie", "zombie"),
)
