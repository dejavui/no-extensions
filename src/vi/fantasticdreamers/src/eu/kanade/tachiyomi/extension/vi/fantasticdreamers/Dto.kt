package eu.kanade.tachiyomi.extension.vi.fantasticdreamers

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
class BloggerFeedDto(
    val feed: BloggerFeedDataDto? = null,
)

@Serializable
class BloggerFeedDataDto(
    val entry: List<BloggerEntryDto>? = emptyList(),
    @SerialName($$"openSearch$totalResults") val totalResults: BloggerTotalResultsDto? = null,
)

@Serializable
class BloggerEntryDto(
    val title: BloggerTextDto? = null,
    val link: List<BloggerLinkDto>? = emptyList(),
    val published: BloggerTextDto? = null,
    val updated: BloggerTextDto? = null,
    val category: List<BloggerCategoryDto>? = emptyList(),
    val content: BloggerTextDto? = null,
    @SerialName($$"media$thumbnail") val thumbnail: BloggerThumbnailDto? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        title = this@BloggerEntryDto.title?.t ?: ""
        url = this@BloggerEntryDto.link?.find { it.rel == "alternate" }?.href?.substringAfter(baseUrl) ?: ""
        thumbnail_url = this@BloggerEntryDto.thumbnail?.url?.replace("/s72-c", "/w600")
            ?.replace("/s1600", "/w600")
    }

    fun toSChapter(baseUrl: String) = SChapter.create().apply {
        name = this@BloggerEntryDto.title?.t ?: ""
        url = this@BloggerEntryDto.link?.find { it.rel == "alternate" }?.href?.substringAfter(baseUrl) ?: ""
        date_upload = runCatching {
            OffsetDateTime.parse(this@BloggerEntryDto.published?.t).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }
}

@Serializable
class BloggerTextDto(
    @SerialName($$"$t") val t: String,
)

@Serializable
class BloggerLinkDto(
    val rel: String,
    val href: String,
)

@Serializable
class BloggerCategoryDto(
    val term: String,
)

@Serializable
class BloggerThumbnailDto(
    val url: String,
)

@Serializable
class BloggerTotalResultsDto(
    @SerialName($$"$t") val t: String,
)
