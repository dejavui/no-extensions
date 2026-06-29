plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiCB"
    className = "HentaiCB"
    theme = "madara"
    baseUrl = "https://2tencb.pro"
    versionCode = 31
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    deeplink {
        path("/read/..*")
    }
}
