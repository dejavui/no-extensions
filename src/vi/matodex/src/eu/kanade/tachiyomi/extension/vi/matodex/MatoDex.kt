package eu.kanade.tachiyomi.extension.vi.matodex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.util.Locale

@Source
abstract class MatoDex : HttpSource() {

    override val supportsLatest: Boolean = false

    private val apiUrl = "$baseUrl/api/v1/mato"

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/info.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val info = response.parseAs<MatoInfoDto>()
        return MangasPage(listOf(info.toSManga()), false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (page != 1 || !query.isMatoQuery()) {
            return Observable.just(MangasPage(emptyList(), false))
        }

        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/info.json", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<MatoInfoDto>().toSManga()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/"

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/chapters.json", headers)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<List<MatoChapterDto>>()
        .map { it.toSChapter() }
        .sortedByDescending { it.chapter_number }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
        return GET("$apiUrl/chapters/$chapterId.json", headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<MatoChapterPayloadDto>().toPages()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun String.isMatoQuery(): Boolean {
        val query = trim().lowercase(Locale.ROOT)
        return query.isEmpty() ||
            query in directSearchAliases ||
            directSearchAliases.any { query.contains(it) } ||
            startsWith(baseUrl)
    }
}

private val directSearchAliases = listOf(
    "mato",
    "matodex",
    "mato seihei",
    "mato seihei no slave",
    "ma đô",
    "ma do",
    "nô lệ",
    "no le",
    "chained soldier",
    "demon slave",
)
