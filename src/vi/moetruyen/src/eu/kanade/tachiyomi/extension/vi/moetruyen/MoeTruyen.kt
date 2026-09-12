package eu.kanade.tachiyomi.extension.vi.moetruyen

import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MoeTruyen : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(imgxInterceptor())
        rateLimit(3)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views_desc")
            .addQueryParameter("page", page.toString())
            .build()

        return parseMangaList(client.get(url).asJsoup())
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return parseMangaList(client.get(url).asJsoup())
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val linkElement = element.selectFirst("a[href^=/manga/]")!!
        setUrlWithoutDomain(linkElement.absUrl("href"))
        title = getFullListTitle(element)
        thumbnail_url = element.selectFirst("img")?.imgAttr()
    }

    private fun getFullListTitle(element: Element): String {
        val titleElement = element.selectFirst("h3")!!
        val titleAttr = titleElement.attrOrNull("title")
        if (titleAttr != null) {
            return titleAttr
        }

        val titleText = titleElement.text()
        if (!titleText.endsWith("...")) {
            return titleText
        }

        val imageAlt = element.selectFirst("img")?.attrOrNull("alt")
            ?.removePrefix("Bìa ")
            ?.trim()

        return imageAlt ?: titleText
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("article.manga-card--list")
            .map(::mangaFromElement)

        val hasNextPage = document
            .selectFirst("nav[aria-label='Phân trang truyện'] a[aria-label='Trang sau']:not(.is-disabled)")
            ?.attr("href")
            ?.let { it != "#" }
            ?: false

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.ifEmpty { null }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart()?.ifEmpty { null }
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()
        val includedGenres = genres.filter { it.isIncluded() }
        val excludedGenres = genres.filter { it.isExcluded() }

        val hasFilter = status != null || (sort != null && sort != "updated_desc") ||
            includedGenres.isNotEmpty() || excludedGenres.isNotEmpty()

        if (query.isBlank() && !hasFilter) {
            return getLatestUpdates(page)
        }

        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query)
                }

                status?.let { addQueryParameter("status", it) }
                sort?.let { addQueryParameter("sort", it) }
                includedGenres.forEach { addQueryParameter("include", it.id) }
                excludedGenres.forEach { addQueryParameter("exclude", it.id) }
            }
            .build()

        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply { setUrlWithoutDomain("/manga/$slug") }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    // ============================== Details ===============================

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = document.selectFirst("h1.manga-detail-title")!!.text()
        author = document.selectFirst("p.manga-detail-meta-line:contains(Tác giả)")
            ?.select("a")
            ?.joinToString { it.text() }

        genre = document.select(".manga-detail-genre-chips a.chip")
            .joinToString { it.text() }

        description = buildString {
            document.selectFirst("span:contains(Tên khác:) + span")?.textOrNull()?.let {
                append("Tên khác: ", it, "\n\n")
            }

            document.selectFirst("[data-description-content]")
                ?.wholeText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::append)
                ?: document.selectFirst(".manga-description__text")
                    ?.wholeText()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::append)
        }.trim()

        status = parseStatus(document.selectFirst(".manga-status-pill")?.textOrNull())
        thumbnail_url = document.selectFirst(".detail-cover img")?.absUrl("src")
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = if (fetchChapters) fetchChapterList(document) else chapters,
        )
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains("Còn tiếp", ignoreCase = true) -> SManga.ONGOING
        status.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Tạm dừng", ignoreCase = true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    private suspend fun fetchChapterList(firstDocument: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val visitedPages = mutableSetOf<String>()
        var currentPageUrl = firstDocument.location()
        var currentDocument = firstDocument

        while (visitedPages.add(currentPageUrl)) {
            chapters += parseChapterList(currentDocument)

            val nextChapterLinkElement: Element? = currentDocument.selectFirst(
                "nav[aria-label*='Phân trang chương'] a[aria-label='Trang chương sau']:not(.is-disabled)",
            )
            val nextChapterPageUrl: String? = nextChapterLinkElement?.let { link ->
                if (link.attr("href") == "#") {
                    null
                } else {
                    link.absUrl("href").ifEmpty { null }
                }
            }

            if (nextChapterPageUrl == null || visitedPages.contains(nextChapterPageUrl)) {
                break
            }

            currentPageUrl = nextChapterPageUrl
            currentDocument = client.get(currentPageUrl).asJsoup()
        }

        return chapters
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("ul.chapter-list li.chapter a.chapter-link").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst(".chapter-num")!!.text()

            val chapterTime = element.selectFirst(".chapter-time")
            val relativeDate = chapterTime?.text()
            val absoluteDate = chapterTime?.attr("title")
                ?.substringAfter("Cập nhật", missingDelimiterValue = "")
                ?.trim()
                ?.ifEmpty { null }

            date_upload = parseRelativeDate(relativeDate).takeIf { it != 0L }
                ?: dateFormat.tryParseDate(absoluteDate, dateZone)
        }
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L

        val number = numberRegex.find(dateStr)?.value?.toIntOrNull() ?: return 0L
        val duration = when {
            dateStr.contains("giây") -> number.seconds
            dateStr.contains("phút") -> number.minutes
            dateStr.contains("giờ") -> number.hours
            dateStr.contains("ngày") -> number.days
            dateStr.contains("tuần") -> (number * 7).days
            dateStr.contains("tháng") -> (number * 30).days
            dateStr.contains("năm") -> (number * 365).days
            else -> return 0L
        }

        return (Clock.System.now() - duration).toEpochMilliseconds()
    }

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val allImages = readerImages(document)

        val bannerElement = allImages.firstOrNull { element ->
            (element.attr("width") == "1200" && element.attr("height") == "626") ||
                (element.attr("data-imgx-width") == "1200" && element.attr("data-imgx-height") == "626")
        }
        val bannerImgxIndex = bannerElement?.attr("data-imgx-page-index")?.toIntOrNull()
        val images = allImages.filterNot { it == bannerElement }
        val readerPages = document.selectFirst("[data-reader-lazy-pages]")

        val accessUrl = images.firstOrNull()?.attr("data-imgx-access-url")?.ifBlank { null }
            ?: readerPages?.attr("data-reader-imgx-access-url")?.ifBlank { null }

        if (accessUrl != null) {
            val fullAccessUrl = if (accessUrl.startsWith("http")) accessUrl else "$baseUrl$accessUrl"

            return fetchPagesWithGrants(fullAccessUrl, images, readerPages, bannerImgxIndex)
        }

        return images
            .map { it.imgAttr() }
            .filter { it.isNotBlank() && !it.startsWith("data:") }
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
    }

    private fun readerImages(document: Document): List<Element> = document.select(".page-card .page-frame img")
        .filterNot { element ->
            element.parents().any { parent -> parent.tagName().equals("noscript", ignoreCase = true) }
        }

    private suspend fun fetchPagesWithGrants(
        accessUrl: String,
        images: List<Element>,
        readerPages: Element?,
        bannerImgxIndex: Int?,
    ): List<Page> {
        val initialEntries = readerPages?.attr("data-reader-imgx-initial-pages")
            ?.ifBlank { null }
            ?.let { json ->
                runCatching {
                    URLDecoder.decode(json, Charsets.UTF_8.name()).parseAs<List<PageAccessEntry>>()
                }.getOrDefault(emptyList())
            }
            .orEmpty()
            .filter { it.pageIndex != bannerImgxIndex && it.downloadUrl.isNotBlank() && it.grant != null }

        initialEntries.forEach { entry ->
            imgxGrants[entry.downloadUrl] = entry
        }

        val pageIndices = images.mapNotNull { it.attr("data-imgx-page-index").toIntOrNull() }
            .filterNot { it == bannerImgxIndex }
            .distinct()

        val initialIndices = initialEntries.mapTo(mutableSetOf()) { it.pageIndex }
        val pages = initialEntries.map { Page(it.pageIndex, imageUrl = it.downloadUrl) }.toMutableList()

        val indicesToFetch = pageIndices.filterNot { it in initialIndices }
        if (indicesToFetch.isEmpty()) {
            return pages.sortedBy { it.index }
                .mapIndexed { index, page -> Page(index, imageUrl = page.imageUrl) }
        }

        val proofToken = readerPages?.attr("data-reader-imgx-proof-token")?.ifBlank { null }
        if (pageIndices.isEmpty()) {
            return pages.sortedBy { it.index }
                .mapIndexed { index, page -> Page(index, imageUrl = page.imageUrl) }
        }

        val batchSize = 10

        for (start in indicesToFetch.indices step batchSize) {
            val end = minOf(start + batchSize, indicesToFetch.size)
            val chunk = indicesToFetch.subList(start, end)
            val proof = proofToken?.let { createPageAccessProof(accessUrl, chunk, it) }
            val body = PageAccessRequest(pageIndexes = chunk, pageAccessProof = proof).toJsonRequestBody()
            val accessHeaders = headers.newBuilder()
                .set("Accept", "application/json")
                .apply {
                    proof?.let {
                        set("X-IMGX-Reader-Proof", it.proof)
                        set("X-IMGX-Reader-Proof-Version", it.version)
                    }
                }
                .build()

            val pageAccess = client.post(accessUrl, accessHeaders, body).parseAs<PageAccessResponse>()

            for (entry in pageAccess.pages) {
                if (entry.downloadUrl.isNotBlank() && entry.grant != null) {
                    imgxGrants[entry.downloadUrl] = entry
                    pages.add(Page(entry.pageIndex, imageUrl = entry.downloadUrl))
                }
            }
        }

        return pages.sortedBy { it.index }
            .mapIndexed { index, page -> Page(index, imageUrl = page.imageUrl) }
    }

    private fun createPageAccessProof(accessUrl: String, pageIndexes: List<Int>, token: String): PageAccessProof {
        val version = "imgx-page-access-proof-v1"
        val issuedAt = Clock.System.now().toEpochMilliseconds()
        val nonce = randomHex()
        val accessPath = accessUrl.toHttpUrl().encodedPath
        val pageIndexPart = pageIndexes.joinToString(",")
        val proofInput = listOf(
            version,
            token,
            accessPath,
            "",
            pageIndexPart,
            issuedAt.toString(),
            nonce.lowercase(Locale.ROOT),
        ).joinToString("\n")

        val proof = MessageDigest.getInstance("SHA-256")
            .digest(proofInput.toByteArray(Charsets.UTF_8))
            .base64UrlNoPadding()

        return PageAccessProof(
            version = version,
            token = token,
            issuedAt = issuedAt,
            nonce = nonce,
            proof = proof,
        )
    }

    private fun randomHex(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun ByteArray.base64UrlNoPadding(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun imgxInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val entry = imgxGrants.remove(url)

        if (entry?.grant == null) {
            return@Interceptor chain.proceed(request)
        }

        val response = chain.proceed(request)
        val source = response.body.source()

        if (!source.request(14) ||
            source.buffer[0] != 0x49.toByte() || source.buffer[1] != 0x4D.toByte() ||
            source.buffer[2] != 0x47.toByte() || source.buffer[3] != 0x58.toByte()
        ) {
            return@Interceptor response
        }

        val webp = response.body.use {
            ImageDecryptor.decrypt(source.readByteArray(), entry.grant, entry.storageKey)
        }

        response.newBuilder()
            .body(webp.toResponseBody("image/webp".toMediaType()))
            .build()
    }

    private fun DecryptedImage.toResponseBody(mediaType: MediaType): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = size.toLong()

        override fun source(): BufferedSource = ByteArrayInputStream(data, offset, size).source().buffer()
    }

    private val imgxGrants = Collections.synchronizedMap(
        object : LinkedHashMap<String, PageAccessEntry>(IMGX_GRANT_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageAccessEntry>?): Boolean = size > IMGX_GRANT_CACHE_SIZE
        },
    )

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/manga").asJsoup()
        .select(".filter-option[data-genre]")
        .mapNotNull { element ->
            val id = element.attr("data-genre").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = element.selectFirst(".filter-name")?.text()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            GenreOption(name, id)
        }
        .distinctBy { it.id }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    // =============================== Related ==============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val section = document.selectFirst("section[aria-labelledby=manga-related-similar-title]")
            ?: return emptyList()

        return section.select("article.manga-related-card").mapNotNull { card ->
            val link = card.selectFirst("a.manga-related-card__link[href^=/manga/]")
                ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val dateZone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val numberRegex = Regex("""\d+""")
    private val secureRandom = SecureRandom()

    private fun Element.imgAttr(): String = absUrl("data-src").ifEmpty { absUrl("src") }

    private companion object {
        const val IMGX_GRANT_CACHE_SIZE = 500
    }
}
