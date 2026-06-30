package eu.kanade.tachiyomi.extension.vi.nettruyenx

import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class NetTruyenX : WPComics() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
    override val popularPath = "truyen-tranh-hot"

    // Details
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        document.select("article#item-detail").let { info ->
            author = info.select("li.author p.col-xs-8").text()
            status = info.select("li.status p.col-xs-8").text().toStatus()
            genre = info.select("li.kind p.col-xs-8 a").joinToString { it.text() }
            val otherName = info.select("h2.other-name").text()
            description = info.select("div.detail-content div div:nth-child(4)").joinToString("\n") { it.wholeText().trim() } +
                if (otherName.isNotBlank()) "\n\n ${intl["OTHER_NAME"]}: $otherName" else ""
            thumbnail_url = imageOrNull(info.select("div.col-image img").first()!!)
        }
    }
    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/") // slug
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("Comic/Services/ComicService.asmx/ChapterList")
            .addQueryParameter("slug", slug)
            .build()
        return GET(url, headers)
    }

    @Serializable
    class ChapterDTO(
        val data: ArrayList<Data> = arrayListOf(),
    )

    @Serializable
    class Data(
        @SerialName("chapter_name") val chapterName: String,
        @SerialName("chapter_slug")val chapterSlug: String,
        @SerialName("updated_at") val updatedAt: String,

    )
    override fun chapterListParse(response: Response): List<SChapter> {
        val json = response.parseAs<ChapterDTO>()
        val slug = response.request.url.queryParameter("slug")!!
        val chapter = json.data.map {
            SChapter.create().apply {
                setUrlWithoutDomain("$baseUrl/truyen-tranh/$slug/${it.chapterSlug}")
                name = it.chapterName
                date_upload = dateFormatChapter.tryParse(it.updatedAt)
            }
        }
        return chapter
    }
    private val dateFormatChapter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
