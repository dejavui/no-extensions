package eu.kanade.tachiyomi.extension.all.ehentai

import android.net.Uri
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.CheckBox

class Watched :
    CheckBox("Watched List"),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendPath("watched")
        }
    }
}

class Favorites :
    CheckBox("Favorites"),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendPath("favorites.php")
        }
    }
}

class GenreOption(name: String, private val genreId: String) :
    CheckBox(name, false),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        builder.appendQueryParameter("f_$genreId", if (state) "1" else "0")
    }
}

class GenreGroup :
    UriGroup<GenreOption>(
        "Genres",
        listOf(
            GenreOption("Dōjinshi", "doujinshi"),
            GenreOption("Manga", "manga"),
            GenreOption("Artist CG", "artistcg"),
            GenreOption("Game CG", "gamecg"),
            GenreOption("Western", "western"),
            GenreOption("Non-H", "non-h"),
            GenreOption("Image Set", "imageset"),
            GenreOption("Cosplay", "cosplay"),
            GenreOption("Asian Porn", "asianporn"),
            GenreOption("Misc", "misc"),
        ),
    )

class AdvancedOption(name: String, private val param: String, defValue: Boolean = false) :
    CheckBox(name, defValue),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendQueryParameter(param, "on")
        }
    }
}

open class PageOption(name: String, private val queryKey: String) :
    Filter.Text(name),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state.isNotBlank()) {
            if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                builder.appendQueryParameter("f_sp", "on")
            }

            builder.appendQueryParameter(queryKey, state.trim())
        }
    }
}

class MinPagesOption : PageOption("Minimum Pages", "f_spf")
class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

class RatingOption :
    Filter.Select<String>(
        "Minimum Rating",
        arrayOf(
            "Any",
            "2 stars",
            "3 stars",
            "4 stars",
            "5 stars",
        ),
    ),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state > 0) {
            builder.appendQueryParameter("f_srdd", (state + 1).toString())
            builder.appendQueryParameter("f_sr", "on")
        }
    }
}

// Explicit type arg for listOf() to workaround this: KT-16570
class AdvancedGroup :
    UriGroup<Filter<*>>(
        "Advanced Options",
        listOf(
            AdvancedOption("Search Gallery Name", "f_sname", true),
            AdvancedOption("Search Gallery Tags", "f_stags", true),
            AdvancedOption("Search Gallery Description", "f_sdesc"),
            AdvancedOption("Search Torrent Filenames", "f_storr"),
            AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
            AdvancedOption("Search Low-Power Tags", "f_sdt1"),
            AdvancedOption("Search Downvoted Tags", "f_sdt2"),
            AdvancedOption("Show Expunged Galleries", "f_sh"),
            RatingOption(),
            MinPagesOption(),
            MaxPagesOption(),
        ),
    )

class EnforceLanguageFilter(default: Boolean) : CheckBox("Enforce language", default)

internal open class TextFilter(name: String, val type: String, val specific: String = "") : Filter.Text(name)
