plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiVNBond"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "vi"
        baseUrl("https://hentaivn.bond") {
            withCustom = true
        }
    }
}
