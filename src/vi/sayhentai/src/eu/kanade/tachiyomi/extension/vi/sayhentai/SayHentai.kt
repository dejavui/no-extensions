package eu.kanade.tachiyomi.extension.vi.sayhentai

import eu.kanade.tachiyomi.multisrc.manhwaz.ManhwaZ
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import org.jsoup.Jsoup
import rx.Observable

@Source
abstract class SayHentai : ManhwaZ() {

    override val mangaDetailsAuthorHeading = "Tác giả"

    override val mangaDetailsStatusHeading = "Trạng thái"

    override fun popularMangaSelector() = "#slide-top > .item:contains(a)"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = client.newCall(chapterListRequest(manga))
        .asObservableSuccess()
        .flatMap { response ->
            val html = response.peekBody(Long.MAX_VALUE).string()
            val document = Jsoup.parse(html, response.request.url.toString())

            val chapters = super.chapterListParse(response)
            val moreChaptersUrl = document
                .selectFirst(".c-chapter-readmore")
                ?.attr("abs:data-ajax-url")
                ?.takeIf(String::isNotBlank)

            if (moreChaptersUrl != null) {
                client.newCall(GET(moreChaptersUrl, headers))
                    .asObservableSuccess()
                    .map { moreResponse ->
                        chapters + super.chapterListParse(moreResponse)
                    }
            } else {
                Observable.just(chapters)
            }
        }
}
