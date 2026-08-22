package eu.kanade.tachiyomi.extension.vi.fantasticdreamers

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class FantasticDreamers : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = apiUrl("Manga")
            .addQueryParameter("q", "label:Manga $query".trim())
            .addQueryParameter("start-index", ((page - 1) * 20 + 1).toString())
            .addQueryParameter("max-results", "20")
            .build()

        return client.get(url).use { response ->
            val feed = response.parseAs<BloggerFeedDto>()
            val mangas = feed.feed?.entry?.map { it.toSManga(baseUrl) }.orEmpty()
            val hasNextPage = (feed.feed?.totalResults?.t?.toIntOrNull() ?: 0) > page * 20
            MangasPage(mangas, hasNextPage)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var mangaDetails = manga
        if (fetchDetails) {
            mangaDetails = client.get(baseUrl + manga.url).use { response ->
                val document = response.asJsoup()
                manga.apply {
                    description = document.selectFirst("#synopsis")?.text()
                    genre = document.select("article.oh.a2 .label").joinToString { it.text() }

                    document.select("#extra-info .y6x11p").forEach { element ->
                        val text = element.text()
                        when {
                            text.contains("Tác giả") -> author = element.selectFirst(".dt")?.text()
                            text.contains("Họa sĩ") -> artist = element.selectFirst(".dt")?.text()
                        }
                    }

                    status = document.selectFirst("aside.s1 .y6x11p:contains(Trạng thái) .dt a")?.text().let {
                        when (it?.lowercase()) {
                            "đang tiến hành" -> SManga.ONGOING
                            "hoàn thành" -> SManga.COMPLETED
                            "tạm dừng" -> SManga.ON_HIATUS
                            "huỷ bỏ" -> SManga.CANCELLED
                            else -> SManga.UNKNOWN
                        }
                    }
                }
            }
        }

        var chaptersList = chapters
        if (fetchChapters) {
            chaptersList = client.get(baseUrl + manga.url).use { response ->
                val document = response.asJsoup()
                val label = chapterFeedRegex.find(document.html())?.groupValues?.get(1)
                    ?: manga.title.trim()

                val url = apiUrl(label)
                    .addQueryParameter("max-results", "150")
                    .build()

                client.get(url).use { feedResponse ->
                    val feed = feedResponse.parseAs<BloggerFeedDto>()
                    feed.feed?.entry?.filter { it.category?.any { cat -> cat.term == "Chapter" } == true }
                        ?.map { it.toSChapter(baseUrl) } ?: emptyList()
                }
            }
        }

        return SMangaUpdate(mangaDetails, chaptersList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).use { response ->
        val document = response.asJsoup()
        document.select(".check-box img").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    private fun apiUrl(label: String): HttpUrl.Builder = baseUrl.toHttpUrl().newBuilder()
        .addPathSegments("feeds/posts/default/-/$label")
        .addQueryParameter("alt", "json")

    private val chapterFeedRegex = """clwd\.run\(['"](.*?)['"]\)""".toRegex()
}
