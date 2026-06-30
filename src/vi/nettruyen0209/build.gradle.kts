plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NetTruyen0209"
    theme = "wpcomics"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://nettruyen12s.com") {
            withCustom = true
        }
    }
}
