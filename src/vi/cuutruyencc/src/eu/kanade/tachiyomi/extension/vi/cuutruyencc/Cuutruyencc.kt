package eu.kanade.tachiyomi.extension.vi.cuutruyencc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToLong

@Source
abstract class Cuutruyencc : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
        .addInterceptor(::drmInterceptor)
        .rateLimit(3)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        removeAll("Origin")
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/api/best-mangas")
        val dto = response.parseAs<PopularResponseDto>()
        val mangas = dto.mangas.week.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$baseUrl/newest?page=$page")
        return parseMangaPage(response, page)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val searchUrl = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            addQueryParameter("page", page.toString())

            filters.filterIsInstance<GenreFilter>().firstOrNull()?.state
                ?.filter { it.state }
                ?.map { it.name }
                ?.takeIf { it.isNotEmpty() }
                ?.let { tags ->
                    val tagQuery = tags.joinToString(" AND ") { tag -> "\"$tag\"" }
                    addQueryParameter("tag-query", tagQuery)
                }
        }.build()

        val response = client.get(searchUrl)
        return parseMangaPage(response, page)
    }

    private fun parseMangaPage(response: Response, page: Int): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.snap-start").map {
            SManga.create().apply {
                val titleEl = it.selectFirst("h3.truncate")!!
                val anchor = it.selectFirst("a[href*=/mangas/]")!!
                title = titleEl.text()
                this.url = anchor.attr("abs:href").toHttpUrl().encodedPath
                thumbnail_url = it.selectFirst("img.manga-cover")
                    ?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("a[href*=\"page=${page + 1}\"]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            parseMangaDetails(document),
            parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val moreInfo = document.selectFirst(".bg-gray-200.p-4")
        title = document.selectFirst("h1")?.text() ?: title
        author = document.selectFirst("h2")?.text()
        description = buildString {
            document.selectFirst("#manga-description")?.wholeText()?.trim()?.let {
                append(it, "\n\n")
            }

            moreInfo?.let { info ->
                info.selectFirst("div:contains(Nguồn truyện) + div")?.text()?.let {
                    append("Nguồn: ", it, "\n")
                }

                info.selectFirst("div:contains(Thông tin thêm) + div")?.text()?.let {
                    append("Thông tin thêm: ", it, "\n")
                }

                info.select("div:contains(Tên khác:) + div span.text-gray-600")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n") { "• $it" }
                    .takeIf { it.isNotEmpty() }
                    ?.let { append("Tên khác:\n", it) }
            }
        }.trim()
        genre = document.select("a[href*=/tag/]").joinToString { it.text() }
        thumbnail_url = document.selectFirst("img.manga-cover")?.absUrl("src")
        initialized = true
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("div.chapter-item")
        .map {
            SChapter.create().apply {
                val anchor = it.selectFirst("a[href*=\"/chapters/\"]")!!
                this.url = anchor.attr("abs:href").toHttpUrl().encodedPath
                name = anchor.select("div.p-1").text() +
                    (
                        anchor.selectFirst("span.text-gray-800")
                            ?.let { s -> " - ${s.text()}" } ?: ""
                        )
                date_upload = it.select("div.text-gray-500").text()
                    .let(::parseRelativeDate)
            }
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val document = response.asJsoup()
        val images = document.select("img.lazy-load[data-src], img.zen-img[data-src]")
        return images.mapIndexed { i, img ->
            val src = img.attr("abs:data-src")
            val drm = img.attr("data-drm")
            val url = if (drm.isNotEmpty()) {
                src.toHttpUrl().newBuilder()
                    .addQueryParameter(DRM_QUERY, drm)
                    .build().toString()
            } else {
                src
            }
            Page(i, imageUrl = url)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = url.encodedPath }
        return fetchMangaUpdate(
            manga,
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(getGenreList()),
    )

    private fun drmInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val drmData = url.queryParameter(DRM_QUERY)
            ?: return chain.proceed(request)

        val response = chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().removeAllQueryParameters(DRM_QUERY).build())
                .build(),
        )

        val image = BitmapFactory.decodeStream(response.body.byteStream())
            ?: return response

        val strips = parseDrmData(drmData)
        if (strips.isEmpty()) {
            return response.newBuilder()
                .body(image.toResponseBody())
                .build()
        }

        val width = image.width
        val height = image.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        var acc = 0
        for ((origY, h) in strips) {
            if (h <= 0) continue
            if (acc + h > height || origY + h > height) break

            val srcRect = Rect(0, acc, width, acc + h)
            val dstRect = Rect(0, origY, width, origY + h)
            canvas.drawBitmap(image, srcRect, dstRect, null)
            acc += h
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output)

        return response.newBuilder()
            .body(output.toByteArray().toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    private fun parseDrmData(drmData: String): List<Pair<Int, Int>> = try {
        val raw = Base64.decode(
            drmData
                .replace(Regex("\\s+"), ""),
            Base64.DEFAULT,
        )
        val key = (PI * 10.0.pow(15)).roundToLong().toString()
        val out = StringBuilder()
        for (i in raw.indices) {
            val ch = (raw[i].toInt() xor key[i % key.length].code).toChar()
            out.append(ch)
        }
        val parts = out.split('|').toMutableList()
        if (parts.isNotEmpty() && parts[0].startsWith("#v")) parts.removeAt(0)
        parts.mapNotNull {
            val yh = it.trim().split('-')
            if (yh.size == 2) {
                val y = yh[0].toIntOrNull()
                val h = yh[1].toIntOrNull()
                if (y != null && h != null) y to h else null
            } else {
                null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseRelativeDate(date: String): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val parts = date.trim().split(" ")
        if (parts.size < 3) return 0L
        val value = parts[0].toLongOrNull() ?: return 0L
        val dateTime = when (parts[1]) {
            "giây" -> now.minusSeconds(value)
            "phút" -> now.minusMinutes(value)
            "giờ" -> now.minusHours(value)
            "ngày" -> now.minusDays(value)
            "tuần" -> now.minusWeeks(value)
            "tháng" -> now.minusMonths(value)
            "năm" -> now.minusYears(value)
            else -> now
        }
        return dateTime.toInstant().toEpochMilli()
    }

    private fun Bitmap.toResponseBody(): ResponseBody {
        val output = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.JPEG, 100, output)
        return output.toByteArray().toResponseBody("image/jpeg".toMediaType())
    }

    private fun MangaDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
        title = name
        this.url = if (id.startsWith("/")) id else "/mangas/$id"
        thumbnail_url = if (cover.startsWith("http")) cover else "$baseUrl$cover"
    }

    companion object {
        private const val DRM_QUERY = "ctcc_drm"
    }
}
