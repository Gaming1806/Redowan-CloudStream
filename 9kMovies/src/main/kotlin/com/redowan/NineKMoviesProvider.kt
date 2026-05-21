package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.newExtractorLink

open class NineKMoviesProvider : MainAPI() {
    override var mainUrl = "https://9kmovies.democrat"
    override var name = "9kMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val mainPage = mainPageOf(
        "category/18-movies/" to "18+ Movies",
        "category/bengali/" to "Bengali",
        "category/bollywood/" to "Bollywood",
        "category/dual-audio/" to "Dual Audio",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/hollywood/" to "Hollywood",
        "category/kannada/" to "Kannada",
        "category/malayalam/" to "Malayalam",
        "category/marathi/" to "Marathi",
        "category/punjabi/" to "Punjabi",
        "category/tamil/" to "Tamil",
        "category/telugu/" to "Telugu",
        "category/tv-shows/" to "TV Shows",
        "category/web-series/" to "Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data}page/$page").document
        val home = doc.select("article.thumb-block").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun toResult(post: Element): SearchResponse? {
        val url = post.selectFirst("a")?.attr("href") ?: return null
        val title = post.selectFirst("header.entry-header span")?.text() ?: ""
        val imageUrl = post.selectFirst("img.video-main-thumb")?.attr("src") ?: ""
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = imageUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.thumb-block").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url, headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )).document
    val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
    val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
    val story = doc.selectFirst(".video-description")?.text()
    val episodesData = mutableListOf<Episode>()

    // Try getting indimega URL - use flexible selector
    val indimegaUrl = doc.selectFirst("a#tracking-url")?.attr("href")
        ?: doc.selectFirst("a.button[href*='indimega']")?.attr("href")

    if (!indimegaUrl.isNullOrEmpty()) {
        try {
            val indimegaDoc = app.get(indimegaUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to mainUrl
            )).document

            val buttons = indimegaDoc.select("a.buttn.direct")
            if (buttons.isNotEmpty()) {
                buttons.forEach { btn ->
                    val btnUrl = btn.attr("href")
                    val btnText = btn.text().trim()
                    if (btnUrl.isNotEmpty()) {
                        episodesData.add(newEpisode(btnUrl) {
                            this.name = btnText
                        })
                    }
                }
            } else {
                // fallback: add indimega page itself
                episodesData.add(newEpisode(indimegaUrl) {
                    this.name = "Download"
                })
            }
        } catch (e: Exception) {
            episodesData.add(newEpisode(indimegaUrl) {
                this.name = "Download"
            })
        }
    }

    // Last resort fallback - add page url itself
    if (episodesData.isEmpty()) {
        episodesData.add(newEpisode(url) {
            this.name = "Watch"
        })
    }

    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
        this.posterUrl = imageUrl
        this.plot = story?.trim()
    }
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var currentUrl = data
    
    // Follow redirect chain up to 5 hops
    repeat(5) {
        val response = app.get(currentUrl, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to mainUrl
        ), allowRedirects = false)
        
        val nextUrl = response.headers["location"] ?: return@repeat
        currentUrl = if (nextUrl.startsWith("http")) nextUrl 
                     else "https://${currentUrl.substringAfter("://").substringBefore("/")}$nextUrl"
    }

    // Try built-in extractors on final URL
    if (loadExtractor(currentUrl, data, subtitleCallback, callback)) return true

    // Scrape final page
    val doc = app.get(currentUrl, headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer" to data
    )).document
    val body = doc.toString()

    // Find direct video links
    listOf(
        Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
        Regex("""(https?://[^\s"'<>]+\.mkv[^\s"'<>]*)"""),
        Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
    ).forEach { regex ->
        regex.findAll(body).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(newExtractorLink(
                source = name,
                name = "$name ${getQualityName(data)}",
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) 
                    com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            ) {
                this.referer = currentUrl
                this.quality = getQualityFromUrl(data)
            })
        }
    }

    // Try all links on page
    doc.select("a[href]").forEach { link ->
        val href = link.attr("abs:href")
        if (href.isNotEmpty() && href != currentUrl) {
            loadExtractor(href, currentUrl, subtitleCallback, callback)
        }
    }

    return true
}

private fun getQualityName(url: String): String {
    return when {
        url.contains("1080") -> "1080P"
        url.contains("720") -> "720P"
        url.contains("480") -> "480P"
        url.contains("360") -> "360P"
        else -> ""
    }
}

private fun getQualityFromUrl(url: String): Int {
    return when {
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
}