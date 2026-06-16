package eu.kanade.tachiyomi.extension.vi.mimimoe

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class DataDto(
    val items: List<MangaDto>,
    val page: Long,
    val totalPage: Long,
    val currentPage: Long,
    @SerialName("has_next")
    val hasNext: Boolean = false,
)

@Serializable
class MangaDto(
    private val id: Long,
    private val title: String,
    @SerialName("cover_url")
    private val coverUrl: String,
    private val description: String?,
    @SerialName("alt_names")
    private val differentNames: List<String>,
    private val authors: List<AuthorAndParodyAndCharacter>,
    private val genres: List<Genres>,
    private val parodies: List<AuthorAndParodyAndCharacter>,
    private val characters: List<AuthorAndParodyAndCharacter>,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@MangaDto.title
        thumbnail_url = coverUrl
        url = "$id"
        description = buildString {
            appendIfNotEmpty("Tên khác", differentNames)
            appendIfNotEmpty("Parody", parodies.map { it.name })
            appendIfNotEmpty("Nhân vật", characters.map { it.name })

            append(this@MangaDto.description)
        }
        author = authors.joinToString { it.name }
        genre = genres.joinToString { it.name }
        initialized = true
    }
    fun toSMangaBasic() = SManga.create().apply {
        title = this@MangaDto.title
        thumbnail_url = coverUrl
        url = "$id"
    }
    private fun StringBuilder.appendIfNotEmpty(label: String, list: List<String>) {
        if (list.isNotEmpty()) {
            append("$label: ${list.joinToString()}\n\n")
        }
    }
}

@Serializable
class AuthorAndParodyAndCharacter(
    val name: String,
)

@Serializable
class ChapterDto(
    private val id: Long,
    private val title: String? = null,
    private val order: Int,
    private val createdAt: String,
) {
    fun toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        name = title ?: "Chapter $order"
        date_upload = dateFormat.tryParse(createdAt)
        url = "$mangaId/$id"
    }
}
private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
}

@Serializable
class PageDto(
    private val pages: List<ListPages>,
) {
    fun toPage(): List<Page> = pages.mapIndexed { index, url ->
        Page(index, imageUrl = url.imageUrl)
    }
}

@Serializable
class ListPages(
    @SerialName("image_url")
    val imageUrl: String,
)

@Serializable
class Genres(
    val id: Long,
    val name: String,
)
