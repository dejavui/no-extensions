import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TheGioiTruyen"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        baseUrl = "https://thegioitruyen.vn"
        lang = "vi"
    }

    deeplink {
        path("/..*")
    }
}
