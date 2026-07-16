package eu.kanade.tachiyomi.extension.vi.vitruyen

import eu.kanade.tachiyomi.source.model.Filter

class Genre(val name: String, val slug: String) {
    override fun toString() = name
}

class GenresFilter(pairs: List<Genre>) : Filter.Select<Genre>("Thể loại", pairs.toTypedArray())

class SortFilter :
    Filter.Select<Genre>(
        "Sắp xếp",
        arrayOf(
            Genre("Mới nhất", "latest"),
            Genre("Đang hot", "view"),
        ),
    )

class StatusFilter :
    Filter.Select<Genre>(
        "Trạng thái",
        arrayOf(
            Genre("Tất cả", ""),
            Genre("Đang ra", "ongoing"),
            Genre("Hoàn thành", "completed"),
        ),
    )
