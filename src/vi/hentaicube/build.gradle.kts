plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiCB"
    versionCode = 33
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "vi"
        baseUrl("https://2tencb.pro") {
            withCustom = true
        }
        id = 823638192569572166
    }

    deeplink {
        path("/read/..*")
    }
}
