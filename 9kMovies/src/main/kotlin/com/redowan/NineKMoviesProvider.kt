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
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
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
    val doc = app.get(url).document
    val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
    val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
    val story = doc.selectFirst(".video-description")?.text()
    val episodesData = mutableListOf<Episode>()

    // iframe (luluvid etc) - primary watch source
    val iframeSrc = doc.selectFirst(".video-player iframe")?.attr("src")
    if (!iframeSrc.isNullOrEmpty()) {
        episodesData.add(newEpisode(iframeSrc) { this.name = "Watch Online" })
    }

    // download/redirect button
    val downloadHref = doc.selectFirst("a.button#tracking-url")?.attr("href")
    if (!downloadHref.isNullOrEmpty()) {
        episodesData.add(newEpisode(downloadHref) { this.name = "Download" })
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
    // Follow redirects to get the real URL
    val finalUrl = app.get(data).url

    // Try direct extraction on final URL
    if (loadExtractor(finalUrl, subtitleCallback, callback)) return true

    // If that fails, scrape the final page for links
    val doc = app.get(finalUrl).document
    doc.select("a[href]").forEach { link ->
        val href = link.attr("abs:href")
        if (href.contains(".mp4") || href.contains(".m3u8") ||
            href.contains("stream") || href.contains("video")) {
            loadExtractor(href, subtitleCallback, callback)
        }
    }

    // Also try iframe sources on the final page
    doc.select("iframe[src]").forEach { iframe ->
        val src = iframe.attr("abs:src")
        if (src.isNotEmpty()) loadExtractor(src, subtitleCallback, callback)
    }

    return true
}
}