plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    versionCode = 60
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    val languages = listOf(
        "all", "en", "zh", "ja"
    )

    languages.forEach {
        source {
            lang = it
            baseUrl = "https://nhentai.net"
            if (it == "all") {
                id = 7309872737163460316L
            }
        }
    }

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}

dependencies {
    implementation(project(":lib:randomua"))
}
