plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiVNBond"
    className = "HentaiVNBond"
    theme = "madara"
    baseUrl = "https://hentaivn.bond"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://hentaivn.bond") {
            withCustom = true
        }
    }
}
