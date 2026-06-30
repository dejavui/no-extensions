plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MatoDex"
    className = "MatoDex"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl = "https://mato.suicaodex.com"
    }
}
