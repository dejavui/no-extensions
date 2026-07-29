import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 28
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    val languages = listOf(
        "all", "ja", "en", "zh", "nl", "fr", "de", "hu", "it", "ko", "pl", "pt-BR", "ru", "es", "th", "vi",
    )

    languages.forEach {
        source {
            lang = it
            baseUrl = "https://e-hentai.org"
            if (it == "pt-BR") {
                id = 7151438547982231541L
            }
        }
    }

    deeplink {
        host("e-hentai.org")
        path("/g/..*/..*")
    }
}
