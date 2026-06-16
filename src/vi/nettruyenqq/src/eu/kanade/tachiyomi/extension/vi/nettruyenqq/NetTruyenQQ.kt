package eu.kanade.tachiyomi.extension.vi.nettruyenqq

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class NetTruyenQQ : HttpSource() {
    override val name: String = "NetTruyenQQ"

    override val baseUrl: String = "https://www.nettruyenqq.us"

    override fun chapterListParse(response: Response): List<SChapter> {
        TODO("Not yet implemented")
    }

    override val lang: String = "vi"

    override val supportsLatest: Boolean = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/truyen-moi-cap-nhat" + if (page > 1) "/page/$page" else "", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        TODO("Not yet implemented")
    }

    override fun pageListParse(response: Response): List<Page> {
        TODO("Not yet implemented")
    }

    override fun popularMangaParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/truyen-yeu-thich" + if (page > 1) "/page/$page" else "", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        TODO("Not yet implemented")
    }
}
