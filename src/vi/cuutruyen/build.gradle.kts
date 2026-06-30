plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Cuu Truyen"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://cuutruyen.net") {
            mirrors.add("https://hetcuutruyen.net")
        }
    }
}
