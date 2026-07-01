plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "GocTruyenTranh"
    versionCode = 12
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://goctruyentranh.com") {
            withCustom = true
        }
    }
}
