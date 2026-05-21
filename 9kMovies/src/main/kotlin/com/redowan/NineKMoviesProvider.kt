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
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val story = doc.selectFirst(".video-description")?.text()
        val episodesData = mutableListOf<Episode>()

        // Get indimega link from download button
        val indimegaUrl = doc.selectFirst("a.button#tracking-url")?.attr("href")
        if (!indimegaUrl.isNullOrEmpty()) {
            try {
                // Follow redirect to get real indimega URL
                val finalUrl = app.get(indimegaUrl).url
                val indimegaDoc = app.get(finalUrl).document

                // Scrape quality buttons from indimega
                indimegaDoc.select("a.buttn.direct").forEach { btn ->
                    val btnUrl = btn.attr("href")
                    val btnText = btn.text().trim()
                    if (btnUrl.isNotEmpty()) {
                        episodesData.add(newEpisode(btnUrl) {
                            this.name = btnText
                        })
                    }
                }
            } catch (e: Exception) {
                // fallback: add indimega link directly
                episodesData.add(newEpisode(indimegaUrl) {
                    this.name = "Download"
                })
            }
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
        // Follow all redirects to get final URL
        val finalUrl = app.get(data, allowRedirects = true).url

        // Try CloudStream built-in extractors first
        if (loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)) return true

        // Scrape final page for direct links
        val doc = app.get(finalUrl).document

        // Look for direct mp4/m3u8 links
        val body = doc.toString()
        val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")

        mp4Regex.findAll(body).forEach {
    callback.invoke(newExtractorLink(
        source = name,
        name = name,
        url = it.value,
        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
    ) {
        this.referer = finalUrl
        this.quality = getQualityFromUrl(data)
    })
}

        m3u8Regex.findAll(body).forEach {
    callback.invoke(newExtractorLink(
        source = name,
        name = name,
        url = it.value,
        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
    ) {
        this.referer = finalUrl
        this.quality = getQualityFromUrl(data)
    })
}

        // Try all hrefs on final page
        doc.select("a[href]").forEach { link ->
            val href = link.attr("abs:href")
            if (href.contains("download") || href.contains(".mp4") || href.contains("gdrive")) {
                loadExtractor(href, finalUrl, subtitleCallback, callback)
            }
        }

        return true
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