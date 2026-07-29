package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter

class TagFilter : AdvSearchEntryFilter("Tags")
class CategoryFilter : AdvSearchEntryFilter("Categories")
class GroupFilter : AdvSearchEntryFilter("Groups")
class ArtistFilter : AdvSearchEntryFilter("Artists")
class ParodyFilter : AdvSearchEntryFilter("Parodies")
class CharactersFilter : AdvSearchEntryFilter("Characters")
class UploadedFilter : AdvSearchEntryFilter("Uploaded")
class PagesFilter : AdvSearchEntryFilter("Pages")

open class AdvSearchEntryFilter(name: String) : Filter.Text(name)
class OffsetPageFilter : Filter.Text("Offset results by # pages")
class FavoriteFilter : Filter.CheckBox("Show favorites only", false)
class SortFilter(default: Int) : UriPartFilter("Sort By", SORT_OPTIONS, default)

val SORT_OPTIONS = arrayOf(
    Pair("Popular: All Time", "popular"),
    Pair("Popular: Month", "popular-month"),
    Pair("Popular: Week", "popular-week"),
    Pair("Popular: Today", "popular-today"),
    Pair("Recent", "date"),
)

open class UriPartFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
    state: Int,
) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}
