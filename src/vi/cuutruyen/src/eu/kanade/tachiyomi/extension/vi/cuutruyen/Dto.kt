package eu.kanade.tachiyomi.extension.vi.cuutruyen

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

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
class TitleDto(
    val name: String,
    val primary: Boolean,
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
    private val titles: List<TitleDto>? = null,
) {
    fun toSManga(useMobileCover: Boolean): SManga = SManga.create().apply {
        url = id.toString()
        title = name ?: ""
        author = this@MangaDto.author?.name ?: authorName
        description = buildString {
            if (team != null) {
                append("Nhóm dịch: ")
                appendLine(team.name.replaceFirstChar { it.uppercase() })
            }

            val altNames = titles?.filter { !it.primary }?.map { it.name }
            if (!altNames.isNullOrEmpty()) {
                append("Tên khác: ")
                appendLine(altNames.joinToString())
            }

            if (team != null || !altNames.isNullOrEmpty()) {
                appendLine()
            }

            append(this@MangaDto.description ?: "")
        }

        thumbnail_url = (if (useMobileCover) coverMobileUrl ?: coverUrl else coverUrl)?.normalizeStorageUrl()
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
    fun toSChapter(mangaId: String) = SChapter.create().apply {
        url = id.toString()
        memo = buildJsonObject { put("mangaId", JsonPrimitive(mangaId)) }
        name = buildString {
            append("Chương ")
            append(number)

            if (!this@ChapterDto.name.isNullOrEmpty()) {
                append(": ")
                append(this@ChapterDto.name)
            }
        }
        date_upload = dateFormat.tryParseZonedDateTime(createdAt)
        chapter_number = number.toFloatOrNull() ?: -1f
    }
}

@Serializable
class PageDto(
    private val order: Int,
    private val status: String,
    @SerialName("image_url")
    private val imageUrl: String? = null,
    @SerialName("image_path")
    private val imagePath: String? = null,
    @SerialName("drm_data")
    private val drmData: String? = null,
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
        val urlString = imageUrl?.normalizeStorageUrl() ?: ("https://${STORAGE_CDN[0]}$imagePath")
        val url = urlString.toHttpUrl().newBuilder()
            .fragment(drmData.toDrmFragment())
            .build()
            .toString()
        return Page(order, imageUrl = url)
    }
}

private fun String.normalizeStorageUrl(): String {
    val url = try {
        toHttpUrl()
    } catch (_: Exception) {
        return this
    }
    val replacementHost = when (url.host) {
        "storage-ct.lrclib.net" -> STORAGE_CDN[0]
        "storage-ct-riften.site" -> STORAGE_CDN[1]
        else -> return this
    }

    return url.newBuilder()
        .host(replacementHost)
        .build()
        .toString()
}

private fun String?.toDrmFragment(): String? = this?.let {
    "${ImageInterceptor.DRM_DATA_KEY}=${it.replace("\n", "")}"
}

private val STORAGE_CDN = listOf("storage-bravo.cuutruyen.net", "storage-charlie.cuutruyen.net")

@Serializable
class SearchByTagDTO(
    val mangas: List<MangaDto>,
)
