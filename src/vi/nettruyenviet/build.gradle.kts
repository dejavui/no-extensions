plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NetTruyenViet (unoriginal)"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://nettruyenviet10.com") {
            withCustom = true
        }
    }
}
