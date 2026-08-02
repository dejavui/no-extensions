package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Parser {
    data class MangaListResult(val mangasPage: MangasPage, val lastMangaId: String)

    fun parseMangaList(response: Response, ehLang: String, enforceLanguage: Boolean): MangaListResult {
        val doc = response.asJsoup()
        val mangaElements = doc.select("table.itg td.glname")
            .let { elements ->
                if (enforceLanguage) {
                    elements.filter { element ->
                        element.select("div[title^=language]").firstOrNull()?.let { it.text() == ehLang } ?: true
                    }
                } else {
                    elements
                }
            }

        var lastId = ""
        val parsedMangas = mangaElements.mapIndexed { i, element ->
            SManga.create().apply {
                element.selectFirst("a")?.apply {
                    title = this.select(".glink").text()
                    val href = attr("href")
                    url = ExGalleryMetadata.normalizeUrl(href)
                    if (i == mangaElements.lastIndex) {
                        lastId = ExGalleryMetadata.galleryId(href)
                    }
                }
                element.parent()?.select(".glthumb img")?.first()?.apply {
                    thumbnail_url = attr("data-src").nullIfBlank() ?: attr("src")
                }
            }
        }

        val hasNextPage = doc.select("a#unext[href]").hasText()
        return MangaListResult(MangasPage(parsedMangas, hasNextPage), lastId)
    }

    fun parseDetails(response: Response): SManga = with(response.asJsoup()) {
        val metadata = ExGalleryMetadata().apply {
            url = response.request.url.encodedPath
            title = select("#gn").text().nullIfBlank()?.trim()
            altTitle = select("#gj").text().nullIfBlank()?.trim()

            thumbnailUrl = select("#gd1 div").attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = select("#gdc div").text().nullIfBlank()?.trim()?.lowercase()
            uploader = select("#gdn").text().nullIfBlank()?.trim()

            select("#gdd tr").forEach {
                val left = it.select(".gdt1").text().nullIfBlank()?.trim()?.removeSuffix(":")?.lowercase()
                val right = it.select(".gdt2").text().nullIfBlank()?.trim()

                if (left != null && right != null) {
                    ignore {
                        when (left) {
                            "posted" -> datePosted = runCatching {
                                LocalDateTime.parse(right, EX_DATE_FORMATTER)
                                    .toInstant(ZoneOffset.UTC)
                                    .toEpochMilli()
                            }.getOrDefault(0L)
                            "visible" -> visible = right
                            "language" -> {
                                language = right.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                                translated = right.endsWith(TR_SUFFIX, true)
                            }
                            "file size" -> size = parseHumanReadableByteCount(right)?.toLong()
                            "length" -> length = right.removeSuffix("pages").trim().nullIfBlank()?.toInt()
                            "favorited" -> favorites = right.removeSuffix("times").trim().nullIfBlank()?.toInt()
                        }
                    }
                }
            }

            averageRating = ignore {
                select("#rating_label").text().removePrefix("Average:").trim().nullIfBlank()?.toDouble()
            }
            ratingCount = ignore {
                select("#rating_count").text().trim().nullIfBlank()?.toInt()
            }

            tags.clear()
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(element.text().trim(), element.hasClass("gtl"))
                }
                tags[namespace] = currentTags
            }
        }

        SManga.create().apply {
            metadata.copyTo(this)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    fun parseChapterPage(doc: Document): List<String> = doc.select("#gdt a").map { it.attr("href") }

    fun nextPageUrl(doc: Document): String? = doc.selectFirst("a[onclick=return false]")?.last()?.let {
        if (it.text() == ">") it.attr("href") else null
    }

    fun parseImageUrl(response: Response, getOriginal: Boolean): String {
        val doc = response.asJsoup()
        val imgUrl = doc.select("#img").attr("abs:src")
        val nlValue = Regex("nl\\('(.+?)'\\)").find(doc.selectFirst("#loadfail")?.attr("onclick").orEmpty())?.groupValues?.get(1)

        if (getOriginal) {
            val originalUrl = doc.selectFirst("a[href*=/fullimg/]")?.attr("abs:href")
            if (!originalUrl.isNullOrEmpty()) {
                return originalUrl.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("nl", nlValue)
                    ?.build()
                    ?.toString() ?: imgUrl
            }
        }

        if (nlValue.isNullOrEmpty()) return imgUrl
        val bakUrl = response.request.url.newBuilder().addQueryParameter("nl", nlValue).toString()
        return "$imgUrl#$bakUrl"
    }

    private val EX_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private const val TR_SUFFIX = "TR"
}
