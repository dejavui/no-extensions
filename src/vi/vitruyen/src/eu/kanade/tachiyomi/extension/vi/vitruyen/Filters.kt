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

class SchedulesFilter :
    Filter.Select<Genre>(
        "Lịch chiếu",
        arrayOf(
            Genre("Tất cả", ""),
            Genre("Hàng ngày", "daily"),
            Genre("Thứ hai", "mon"),
            Genre("Thứ ba", "tue"),
            Genre("Thứ tư", "web"),
            Genre("Thứ năm", "thu"),
            Genre("Thứ sáu", "fri"),
            Genre("Thứ bảy", "sat"),
            Genre("Chủ nhật", "sun"),
        ),
    )

class TranslatorsFilter :
    Filter.Select<Genre>(
        "Nhóm dịch",
        arrayOf(
            Genre("Tất cả", ""),
            Genre("Mega Team", "mega-team"),
            Genre("Roho Team", "roho-team"),
            Genre("Whale Team", "whale-team"),
            Genre("DelayTeam", "delayteam"),
            Genre("Vlogtruyen", "vlogtruyen"),
            Genre("FantaC", "fantac"),
            Genre("Phản Nghịch", "phan-nghich"),
            Genre("Maple Team", "maple-team"),
            Genre("Thiên Hồ Comic", "thien-ho-comic"),
            Genre("Thần Hy Team", "than-hy-team"),
            Genre("AQN Team", "aqn-team"),
            Genre("Mirori Team", "mirori-team"),
            Genre("Trảm Thiên", "tram-thien"),
            Genre("Già Trâu Team", "gia-trau-team"),
            Genre("Serpens Team", "serpens-team"),
            Genre("Jesartha Sicilia", "jesartha-sicilia"),
            Genre("Team gù", "team-gu"),
            Genre("Cánh Cụt Dịch Truyện", "canh-cut-dich-truyen"),
            Genre("Chim Cánh Cụt", "chim-canh-cut"),
            Genre("Pandora Team", "pandora-team"),
            Genre("Hắc Nguyệt Quang", "hac-nguyet-quang"),
            Genre("AVA", "ava"),
            Genre("Si mê em Team", "si-me-em-team"),
            Genre("Miu Miu Team", "miu-miu-team"),
            Genre("BT Comic Team", "bt-comic-team"),
            Genre("Bạch Lang Comic", "bach-lang-comic"),
            Genre("Lazy Team", "lazy-team"),
            Genre("Truyentranhlh - Trạm Chuyển Sinh", "truyentranhlh-tram-chuyen-sinh"),
            Genre("HLHT Team", "hlht-team"),
            Genre("Miroles W", "miroles-w"),
            Genre("Intergalactic", "intergalactic"),
            Genre("Team Cỏ Dại", "team-co-dai"),
            Genre("Hakax", "hakax"),
            Genre("Fix", "fix"),
            Genre("Eira Trans", "eira-trans"),
        ),
    )
