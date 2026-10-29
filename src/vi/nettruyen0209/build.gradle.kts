import io.github.keiyoushi.gradle.api.ContentWarning
import jdk.internal.net.http.common.TimeSource.source

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NetTruyen0209"
    theme = "wpcomics"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl {
            custom("https://nettruyen15s.com")
        }
    }
}
