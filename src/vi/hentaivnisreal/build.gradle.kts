import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiVN Is Real"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl {
            custom("https://hentaivnreal.com")
        }
        lang = "vi"
    }

    deeplink {
        path("/truyen/..*")
    }
}
