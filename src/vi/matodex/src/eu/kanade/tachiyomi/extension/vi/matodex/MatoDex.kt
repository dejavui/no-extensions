package eu.kanade.tachiyomi.extension.vi.matodex

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

@Source
abstract class MatoDex : KeiSource() {

    private val apiUrl = "$baseUrl/api/v1/mato"

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        add("Referer", "$baseUrl/")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        client.get("$apiUrl/info.json", headers).use { response ->
            val info = response.parseAs<MatoInfoDto>()
            return MangasPage(listOf(info.toSManga()), false)
        }
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page != 1 || !query.isMatoQuery()) {
            return MangasPage(emptyList(), false)
        }

        return getPopularManga(page)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host == baseUrl.toHttpUrl().host) {
            client.get("$apiUrl/info.json", headers).use { response ->
                return response.parseAs<MatoInfoDto>().toSManga()
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
        val details = if (fetchDetails) {
            client.get("$apiUrl/info.json", headers).use { response ->
                response.parseAs<MatoInfoDto>().toSManga()
            }
        } else {
            manga
        }

        val chaptersList = if (fetchChapters) {
            client.get("$apiUrl/chapters.json", headers).use { response ->
                response.parseAs<List<MatoChapterDto>>()
                    .map { it.toSChapter() }
                    .sortedByDescending { it.chapter_number }
            }
        } else {
            chapters
        }

        return SMangaUpdate(details, chaptersList)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url
        client.get("$apiUrl/chapters/$chapterId.json", headers).use { response ->
            return response.parseAs<MatoChapterPayloadDto>().toPages()
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

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
