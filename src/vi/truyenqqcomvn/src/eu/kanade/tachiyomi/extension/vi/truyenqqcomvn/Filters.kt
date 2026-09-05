package eu.kanade.tachiyomi.extension.vi.truyenqqcomvn

import eu.kanade.tachiyomi.source.model.Filter

class Genre(val name: String, val id: String) {
    override fun toString(): String = name
}

class GenreFilter(genres: Array<Genre>) :
    Filter.Select<Genre>(
        "Thể loại",
        genres,
    )

class SoftFilter :
    Filter.Select<Genre>(
        "Sắp xếp",
        arrayOf(
            Genre("Tất cả", ""),
            Genre("Truyện mới", "truyen-moi"),
            Genre("Truyện hot", "truyen-hot"),
            Genre("Truyện full", "truyen-full"),
        ),
    )

fun getGenreList() = arrayOf(
    Genre("Tất cả", ""),
    Genre("Ngôn Tình", "ngon-tinh"),
    Genre("Đam Mỹ", "dam-my"),
    Genre("Huyền Huyễn", "huyen-huyen"),
    Genre("Xuyên Không", "xuyen-khong"),
    Genre("Trọng Sinh", "trong-sinh"),
    Genre("Trinh Thám", "trinh-tham"),
    Genre("Cổ Đại", "co-dai"),
    Genre("Chuyển Sinh", "chuyen-sinh"),
    Genre("Manhwa", "manhwa"),
    Genre("Truyện Màu", "truyen-mau"),
    Genre("Comedy", "comedy"),
    Genre("Manhua", "manhua"),
    Genre("Romance", "romance"),
    Genre("School Life", "school-life"),
    Genre("Action", "action"),
    Genre("Ecchi", "ecchi"),
    Genre("Manga", "manga"),
    Genre("Mystery", "mystery"),
    Genre("Seinen", "seinen"),
    Genre("Smut", "smut"),
    Genre("Supernatural", "supernatural"),
    Genre("Tragedy", "tragedy"),
    Genre("Drama", "drama"),
    Genre("Adventure", "adventure"),
    Genre("Fantasy", "fantasy"),
    Genre("Isekai", "isekai"),
    Genre("Horror", "horror"),
    Genre("Shounen", "shounen"),
    Genre("Gender Bender", "gender-bender"),
    Genre("Psychological", "psychological"),
    Genre("Slice of Life", "slice-of-life"),
    Genre("Mecha", "mecha"),
    Genre("Martial Arts", "martial-arts"),
    Genre("Harem", "harem"),
    Genre("Shoujo", "shoujo"),
    Genre("Historical", "historical"),
    Genre("Webtoon", "webtoon"),
    Genre("Sci-fi", "sci-fi"),
    Genre("Josei", "josei"),
    Genre("Adult", "adult"),
    Genre("Mature", "mature"),
    Genre("Sports", "sports"),
    Genre("Anime", "anime"),
    Genre("Comic", "comic"),
    Genre("Cooking", "cooking"),
    Genre("One shot", "one-shot"),
    Genre("Doujinshi", "doujinshi"),
    Genre("Magic", "magic"),
    Genre("Live action", "live-action"),
    Genre("Soft Yuri", "soft-yuri"),
    Genre("Yuri", "yuri"),
    Genre("Shoujo Ai", "shoujo-ai"),
    Genre("Demons", "demons"),
    Genre("Shounen Ai", "shounen-ai"),
    Genre("Thiếu Nhi", "thieu-nhi"),
    Genre("Soft Yaoi", "soft-yaoi"),
    Genre("Yaoi", "yaoi"),
    Genre("Detective", "detective"),
    Genre("Khác", "khac"),
)
