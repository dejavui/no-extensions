package eu.kanade.tachiyomi.extension.vi.thegioitruyen

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

@Serializable
class FilterOption(val name: String, val value: String) {
    override fun toString() = name
}

class GenreFilter(options: Array<FilterOption>) : Filter.Select<FilterOption>("Thể loại", options)

@Serializable
class FilterData(
    val genres: List<FilterOption>,
)
