package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.SManga
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val EH_ARTIST_NAMESPACE = "artist"
private const val EH_AUTHOR_NAMESPACE = "author"

private val ONGOING_SUFFIX = arrayOf(
    "[ongoing]",
    "(ongoing)",
    "{ongoing}",
)

val EX_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

fun ExGalleryMetadata.copyTo(manga: SManga) {
    url?.let { manga.url = it }
    thumbnailUrl?.let { manga.thumbnail_url = it }

    (title ?: altTitle)?.let { manga.title = it }

    // Set artist (if we can find one)
    tags[EH_ARTIST_NAMESPACE]?.let {
        if (it.isNotEmpty()) manga.artist = it.joinToString(transform = Tag::name)
    }
    // Set author (if we can find one)
    tags[EH_AUTHOR_NAMESPACE]?.let {
        if (it.isNotEmpty()) manga.author = it.joinToString(transform = Tag::name)
    }
    // Set genre
    genre?.let { manga.genre = it }

    // Try to automatically identify if it is ongoing
    manga.status = SManga.COMPLETED
    title?.let { t ->
        if (ONGOING_SUFFIX.any { t.endsWith(it, ignoreCase = true) }) {
            manga.status = SManga.ONGOING
        }
    }

    // Build description
    val titleDesc = StringBuilder()
    title?.let { titleDesc += "Title: $it\n" }
    altTitle?.let { titleDesc += "Alternate Title: $it\n" }

    val detailsDesc = StringBuilder()
    uploader?.let { detailsDesc += "Uploader: $it\n" }
    datePosted?.let {
        val dateStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC).format(EX_DATE_FORMATTER)
        detailsDesc += "Posted: $dateStr\n"
    }
    visible?.let { detailsDesc += "Visible: $it\n" }
    language?.let {
        detailsDesc += "Language: $it"
        if (translated == true) detailsDesc += " TR"
        detailsDesc += "\n"
    }
    size?.let { detailsDesc += "File Size: ${humanReadableByteCount(it, true)}\n" }
    length?.let { detailsDesc += "Length: $it pages\n" }
    favorites?.let { detailsDesc += "Favorited: $it times\n" }
    averageRating?.let {
        detailsDesc += "Rating: $it"
        ratingCount?.let { count -> detailsDesc += " ($count)" }
        detailsDesc += "\n"
    }

    val tagsDesc = buildTagsDescription(this)

    manga.description = listOf(titleDesc.toString(), detailsDesc.toString(), tagsDesc.toString())
        .filter(String::isNotBlank)
        .joinToString(separator = "\n")
}

private fun buildTagsDescription(metadata: ExGalleryMetadata) = StringBuilder("Tags:\n").apply {
    metadata.tags.forEach { (namespace, tags) ->
        if (tags.isNotEmpty()) {
            val joinedTags = tags.joinToString(separator = " ", transform = { "<${it.name}>" })
            this += "▪ $namespace: $joinedTags\n"
        }
    }
}
