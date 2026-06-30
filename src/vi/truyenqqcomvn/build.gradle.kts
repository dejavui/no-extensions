plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TruyenQQ.com.vn"
    className = "TruyenQQComVN"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://truyenqq.com.vn") {
            withCustom.set(true)
        }
    }
}
