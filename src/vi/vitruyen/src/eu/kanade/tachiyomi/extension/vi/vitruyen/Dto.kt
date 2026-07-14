package eu.kanade.tachiyomi.extension.vi.vitruyen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Data(
    val items: List<EntryItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    val page: Int = 0,
    @SerialName("filter_options") val filterOptions: FilterOptions? = null,
)

@Serializable
class EntryItem(
    val slug: String,
    val name: String,
    val image: String? = null,
    @SerialName("chapter_name") val chapterName: String? = null,
)

@Serializable
class FilterOptions(
    val categories: List<FilterItem> = emptyList(),
    val translators: List<FilterItem> = emptyList(),
    val schedules: List<FilterItem> = emptyList(),
)

@Serializable
class FilterItem(
    val name: String,
    val slug: String,
)
