package eu.kanade.tachiyomi.extension.vi.cuutruyencc

import kotlinx.serialization.Serializable

@Serializable
class PopularResponseDto(
    val success: Boolean,
    val mangas: PopularMangasDto,
)

@Serializable
class PopularMangasDto(
    val week: List<MangaDto>,
    val month: List<MangaDto>,
    val all: List<MangaDto>,
)

@Serializable
class MangaDto(
    val id: String,
    val name: String,
    val cover: String,
    val chapter: String? = null,
    val chapterTime: String? = null,
)
