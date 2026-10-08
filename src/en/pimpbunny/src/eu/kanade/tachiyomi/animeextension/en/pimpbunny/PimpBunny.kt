package eu.kanade.tachiyomi.animeextension.en.pimpbunny

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PimpBunny : ParsedAnimeHttpSource() {

    override val name = "PimpBunny"
    override val baseUrl = "https://pimpbunny.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private fun listRequest(page: Int, sortBy: String? = null): Request {
        val builder = "$baseUrl/videos/".toHttpUrl().newBuilder()
        if (page > 1) builder.addPathSegment(page.toString()).addPathSegment("")
        if (sortBy != null) builder.addQueryParameter("sort_by", sortBy)
        return GET(builder.build(), headers)
    }

    override fun popularAnimeRequest(page: Int): Request =
        listRequest(page, sortBy = "video_viewed")

    override fun popularAnimeSelector() = "div.item:has(a[href*='/videos/'])"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a[href*='/videos/']")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.attr("title").ifEmpty { link.text() }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-original")
                .ifEmpty { img.attr("abs:data-src") }
                .ifEmpty { img.attr("abs:src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "li.next a, a.next"

    override fun latestUpdatesRequest(page: Int): Request = listRequest(page)
    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val builder = "$baseUrl/search/".toHttpUrl().newBuilder().addPathSegment(query)
        if (page > 1) builder.addPathSegment(page.toString())
        builder.addPathSegment("")
        return GET(builder.build(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        description = document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select("a[href*='/categories/']").joinToString { it.text() }
        status = SAnime.COMPLETED
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Video"
            episode_number = 1F
        }
        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException()

    override fun videoListSelector() = "video source, source[type='video/mp4']"

    override fun videoFromElement(element: Element): Video {
        val url = element.attr("abs:src")
        val quality = element.attr("label")
            .ifEmpty { element.attr("title") }
            .ifEmpty { "Default" }
        return Video(url, quality, url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val fromTags = document.select(videoListSelector())
            .map { videoFromElement(it) }
            .filter { it.url.isNotBlank() }
        if (fromTags.isNotEmpty()) return fromTags

        val url = document.selectFirst("meta[property=og:video]")?.attr("content")
            ?: CONTENT_URL_REGEX.find(document.html())
                ?.groupValues
                ?.get(1)
                ?.replace("\\/", "/")

        return if (!url.isNullOrBlank()) {
            listOf(Video(url, "Default", url, headers))
        } else {
            emptyList()
        }
    }

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    companion object {
        private val CONTENT_URL_REGEX = Regex(""contentUrl"\\s*:\\s*"([^"]+)"")
    }
}
