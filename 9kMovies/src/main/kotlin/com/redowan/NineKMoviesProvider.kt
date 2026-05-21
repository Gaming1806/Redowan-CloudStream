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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

open class NineKMoviesProvider : MainAPI() {
    override var mainUrl = "https://9kmovies.democrat"
    override var name = "9kMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private val supportedHosts = listOf(
        "streamtape", "mixdrop", "gofile.io", "voe.sx",
        "doodstream", "dood.watch", "dood.la", "dood.to", "dood.wf",
        "streamlare", "filelions", "streamhub", "upstream",
        "megaup.net", "send.now", "savefiles.com", "vikingfile",
        "vinovo.to", "frdl.io", "dsvplay", "clicknupload",
        "streamwish", "filemoon", "mp4upload", "streamvid",
        "embedrise", "vtube", "uqload"
    )

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
        val doc = app.get("$mainUrl/${request.data}page/$page", headers = ua).document
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
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return doc.select("article.thumb-block").mapNotNull { toResult(it) }
    }

    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()
        try {
            // Step 1: Initial page fetch to get cookies + find cuid domain
            val initialResponse = app.get(
                uptoUrl,
                headers = ua + mapOf("Referer" to "https://indimega.com/")
            )
            val cookies = initialResponse.cookies
            val initialBody = initialResponse.text

            // Step 2: Find and call the cuid unlock endpoint
            // The cuid domain is in the page JS and changes, find it dynamically
            val cuidDomain = Regex("""https://([^/]+)/cuid/""")
                .find(initialBody)?.groupValues?.get(1)

            if (cuidDomain != null) {
                val uptoBase = "https://" + uptoUrl.substringAfter("://").substringBefore("/")
                app.get(
                    "https://$cuidDomain/cuid/?f=${uptoBase}",
                    headers = ua + mapOf("Referer" to uptoUrl),
                    cookies = cookies
                )
            }

            // Step 3: Fetch page again after unlock with same cookies
            val unlockedDoc = app.get(
                uptoUrl,
                headers = ua + mapOf("Referer" to "https://indimega.com/"),
                cookies = cookies
            ).document

            // Step 4: Collect all mirror links
            unlockedDoc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith("http") &&
                    !href.contains("uptobhai") &&
                    supportedHosts.any { href.contains(it) }
                ) {
                    links.add(href)
                }
            }

            // Step 5: Also scan raw HTML for direct video file links
            val body = unlockedDoc.toString()
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*)""")
                .findAll(body).forEach { links.add(it.groupValues[1]) }

        } catch (e: Exception) { }

        return links.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val story = doc.selectFirst(".video-description")?.text()
        val episodesData = mutableListOf<Episode>()

        val indimegaUrl = doc.selectFirst("a#tracking-url")?.attr("href")
            ?: doc.selectFirst("a.button[href*='indimega']")?.attr("href")

        if (!indimegaUrl.isNullOrEmpty()) {
            try {
                val indimegaDoc = app.get(
                    indimegaUrl,
                    headers = ua + mapOf("Referer" to mainUrl)
                ).document

                indimegaDoc.select("a.buttn.direct").forEach { btn ->
                    val uptoUrl = btn.attr("href")
                    val qualityText = btn.text().trim()
                    if (uptoUrl.isEmpty()) return@forEach

                    val mirrors = getUptoLinks(uptoUrl)
                    if (mirrors.isNotEmpty()) {
                        mirrors.forEach { mirrorUrl ->
                            val host = supportedHosts
                                .firstOrNull { mirrorUrl.contains(it) }
                                ?.split(".")?.first()
                                ?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                            episodesData.add(newEpisode(mirrorUrl) {
                                this.name = "$qualityText [$host]"
                            })
                        }
                    } else {
                        // Fallback: store uptobhai URL directly
                        episodesData.add(newEpisode(uptoUrl) {
                            this.name = qualityText
                        })
                    }
                }
            } catch (e: Exception) {
                episodesData.add(newEpisode(indimegaUrl) { this.name = "Download" })
            }
        }

        if (episodesData.isEmpty()) {
            episodesData.add(newEpisode(url) { this.name = "Watch" })
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
        val quality = getQualityFromUrl(data)

        // Direct supported host - extract immediately
        if (supportedHosts.any { data.contains(it) }) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Direct video file
        if (data.contains(".mp4") || data.contains(".mkv") || data.contains(".m3u8")) {
            callback.invoke(newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8
                       else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = quality
            })
            return true
        }

        // Fallback: try as extractor
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }

    private fun getQualityFromUrl(url: String): Int = when {
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}