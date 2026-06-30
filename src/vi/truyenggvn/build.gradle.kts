plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TruyenGGVN"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "vi"
        baseUrl("https://truyenggvn.com") {
            withCustom = true
        }
    }
}
