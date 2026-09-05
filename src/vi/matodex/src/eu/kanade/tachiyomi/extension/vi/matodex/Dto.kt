package eu.kanade.tachiyomi.extension.vi.matodex

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val MANGA_URL = "/"
private const val AUTHOR = "Takahiro, Takemura Youhei"

@Serializable
class MatoInfoDto(
    val title: String,
    private val altTitles: List<String> = emptyList(),
    private val cover: String,
    private val status: String,
    private val description: String,
    private val chapterCount: Int,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = MANGA_URL
        title = this@MatoInfoDto.title
        author = AUTHOR
        thumbnail_url = cover
        description = buildString {
            if (altTitles.isNotEmpty()) {
                append("Tên khác: ${altTitles.joinToString()}\n\n")
            }
            append("\n\nSố chương: $chapterCount")
            append(this@MatoInfoDto.description)
        }
        status = when (this@MatoInfoDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

@Serializable
class MatoChapterDto(
    val id: String,
    private val title: String,
    private val number: Double,
    private val publishedAt: String,
) {
    fun toSChapter(): SChapter = SChapter.create().apply {
        url = id
        name = title
        chapter_number = number.toFloat()
        date_upload = dateFormat.tryParse(publishedAt)
    }
}

@Serializable
class MatoChapterPayloadDto(
    private val pages: List<String>,
) {
    fun toPages(): List<Page> = pages.mapIndexed { index, page ->
        Page(index, imageUrl = page)
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
