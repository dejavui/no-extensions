package eu.kanade.tachiyomi.extension.vi.cuutruyen

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
}

@Serializable
class ResponseDto<T>(
    val data: T,
    @SerialName("_metadata")
    val metadata: PaginationMetadataDto? = null,
)

@Serializable
class PaginationMetadataDto(
    @SerialName("total_pages")
    val totalPages: Int,
    @SerialName("current_page")
    val currentPage: Int,
)

@Serializable
class AuthorDto(
    val name: String,
)

@Serializable
class TeamDto(
    val name: String,
)

@Serializable
class TagDto(
    val name: String,
)

@Serializable
class MangaDto(
    val id: Int,
    private val name: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("cover_mobile_url")
    val coverMobileUrl: String? = null,

    private val author: AuthorDto? = null,
    @SerialName("author_name")
    private val authorName: String? = null,

    private val description: String? = null,
    private val team: TeamDto? = null,

    private val tags: List<TagDto>? = null,
) {
    fun toSManga(coverQuality: String? = null): SManga = SManga.create().apply {
        url = "/mangas/$id"
        title = name ?: ""
        author = this@MangaDto.author?.name ?: authorName
        description = buildString {
            if (team != null) {
                append("Nhóm dịch: ")
                appendLine(team.name)
                appendLine()
            }

            append(this@MangaDto.description ?: "")
        }

        thumbnail_url = when (coverQuality) {
            "cover_mobile_url" -> coverMobileUrl
            else -> coverUrl
        }
        tags?.map { it.name }?.let {
            genre = it.joinToString()
            status = when {
                it.contains("đang tiến hành") -> SManga.ONGOING
                it.contains("đã hoàn thành") -> SManga.COMPLETED
                it.contains("tạm ngưng") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }
}

@Serializable
class ChapterDto(
    private val id: Int,
    private val number: String,
    @SerialName("created_at")
    private val createdAt: String,
    private val name: String? = null,
    val pages: List<PageDto>? = null,
) {
    fun toSChapter(mangaUrl: String) = SChapter.create().apply {
        url = "$mangaUrl/chapters/$id"
        name = buildString {
            append("Chương ")
            append(number)

            if (!this@ChapterDto.name.isNullOrEmpty()) {
                append(": ")
                append(this@ChapterDto.name)
            }
        }
        date_upload = dateFormat.tryParse(createdAt)
        chapter_number = number.toFloatOrNull() ?: -1f
    }
}

@Serializable
class PageDto(
    private val order: Int,
    private val status: String,
    @SerialName("image_url")
    private val imageUrl: String,
    @SerialName("drm_data")
    private val drmData: String,
) {
    fun toPage(): Page {
        if (status != "processed") {
            val message = when (status) {
                "enqueued" -> "Đang đợi xử lý hình ảnh, vui lòng chờ ít phút."
                "processing" -> "Đang xử lý hình ảnh, vui lòng chờ ít phút."
                "failed" -> "Xử lý hình ảnh thất bại."
                else -> "Hình ảnh chưa sẵn sàng."
            }

            throw Exception(message)
        }

        val url = imageUrl.toHttpUrl().newBuilder()
            .fragment("${ImageInterceptor.DRM_DATA_KEY}=${drmData.replace("\n", "")}")
            .build()
            .toString()
        return Page(order, imageUrl = url)
    }
}

@Serializable
class SearchByTagDTO(
    val mangas: List<MangaDto>,
)
