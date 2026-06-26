plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SayHentai"
    className = "SayHentai"
    theme = "manhwaz"
    baseUrl = "https://sayhentai.cx"
    versionCode = 18
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
}
