import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CuuTruyenCC"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        baseUrl {
            custom("https://cuutruyen.cc")
        }
        lang = "vi"
    }

    deeplink {
        path("/..*")
    }
}
