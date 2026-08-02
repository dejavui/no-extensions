import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hitomi"
    versionCode = 43
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    val languages = listOf(
        "all", "en", "id", "jv", "ca", "ceb", "cs", "da", "de", "et", "es", "eo",
        "fr", "it", "hi", "hu", "pl", "pt", "vi", "tr", "ru", "uk", "ar", "ko", "zh", "ja",
    )

    languages.forEach {
        source {
            lang = it
            baseUrl = "https://hitomi.la"
        }
    }
}
