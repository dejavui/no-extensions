plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ZetTruyen"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://www.zettruyen.ink") {
            withCustom = true
        }
    }
}
