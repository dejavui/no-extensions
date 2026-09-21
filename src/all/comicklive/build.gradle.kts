import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Comick (Unoriginal)"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    listOf(
        "all", "en", "ru", "vi", "fr", "pl", "id", "tr", "it", "es", "uk",
        "de", "ko", "th", "ro", "ms", "ja", "sv", "no",
    ).forEach {
        source {
            lang = it
            baseUrl {
                mirrors(
                    "https://comick.live",
                    "https://comick.art",
                )
            }
        }
    }

    deeplink {
        path("/comic/..*")
    }
}
