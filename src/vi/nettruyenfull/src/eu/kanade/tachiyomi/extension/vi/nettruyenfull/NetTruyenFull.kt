package eu.kanade.tachiyomi.extension.vi.nettruyenfull

import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class NetTruyenFull : WPComics() {

    override val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        element.select("a").let {
            name = it.text()
            setUrlWithoutDomain(it.attr("href"))
        }
        date_upload = element.select("div.col-xs-7, div.col-xs-4").text().toDate()
    }

    override suspend fun mangaUpdateParse(response: Response, manga: SManga, chapters: List<SChapter>): SMangaUpdate {
        val document = response.asJsoup()
        val updatedManga = mangaDetailsParse(document)
        val updatedChapters = document.select(chapterListSelector())
            .mapNotNull { element ->
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.startsWith("javascript:") || href.isBlank()) return@mapNotNull null
                chapterFromElement(element)
            }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override val genresSelector = "ul li a[href*=/tim-truyen/], select.changed-redirect option[value*=/tim-truyen/]"
}
