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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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

    // Supported hosts CloudStream can extract from
    private val supportedHosts = listOf(
        "streamtape", "mixdrop", "gofile.io", "voe.sx",
        "doodstream", "dood.watch", "dood.la", "dood.to", "dood.wf",
        "streamlare", "filelions", "streamhub", "upstream",
        "megaup.net", "send.now", "savefiles", "vikingfile",
        "vinovo", "frdl.io", "dsvplay", "clicknupload",
        "streamwish", "filemoon", "mp4upload", "streamvid",
        "embedrise", "vtube", "uqload"
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val story = doc.selectFirst(".video-description")?.text()
        val episodesData = mutableListOf<Episode>()

        // Step 1: Get indimega URL from 9kmovies download button
        val indimegaUrl = doc.selectFirst("a#tracking-url")?.attr("href")
            ?: doc.selectFirst("a.button[href*='indimega']")?.attr("href")

        if (!indimegaUrl.isNullOrEmpty()) {
            try {
                // Step 2: Fetch indimega page to get quality buttons (uptobhai links)
                val indimegaDoc = app.get(
                    indimegaUrl,
                    headers = ua + mapOf("Referer" to mainUrl)
                ).document

                val qualityButtons = indimegaDoc.select("a.buttn.direct")

                qualityButtons.forEach { btn ->
                    val uptoUrl = btn.attr("href")
                    val qualityText = btn.text().trim() // e.g. "1080P [ 2.1GB ]"
                    if (uptoUrl.isEmpty()) return@forEach

                    try {
                        // Step 3: Fetch uptobhai page to get actual mirror links
                        val uptoDoc = app.get(
                            uptoUrl,
                            headers = ua + mapOf("Referer" to "https://indimega.com/")
                        ).document

                        // Extract all mirror links from uptobhai page
                        val mirrorLinks = uptoDoc.select("a[href]")
                            .map { it.attr("abs:href") }
                            .filter { link ->
                                link.startsWith("http") &&
                                !link.contains("uptobhai") &&
                                supportedHosts.any { link.contains(it) }
                            }

                        if (mirrorLinks.isNotEmpty()) {
                            // Add each mirror as a separate episode link
                            mirrorLinks.forEach { mirrorUrl ->
                                val hostName = supportedHosts
                                    .firstOrNull { mirrorUrl.contains(it) }
                                    ?.split(".")?.first()
                                    ?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                                episodesData.add(newEpisode(mirrorUrl) {
                                    this.name = "$qualityText [$hostName]"
                                })
                            }
                        } else {
                            // uptobhai blocked server request - store uptobhai URL as fallback
                            episodesData.add(newEpisode(uptoUrl) {
                                this.name = qualityText
                            })
                        }
                    } catch (e: Exception) {
                        episodesData.add(newEpisode(uptoUrl) {
                            this.name = qualityText
                        })
                    }
                }
            } catch (e: Exception) {
                episodesData.add(newEpisode(indimegaUrl) {
                    this.name = "Download"
                })
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

        // Direct mirror links (streamtape, mixdrop etc) — just extract directly
        if (supportedHosts.any { data.contains(it) }) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // megaup direct mkv/mp4 link
        if (data.contains(".mkv") || data.contains(".mp4")) {
            callback.invoke(newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = quality
            })
            return true
        }

        // Fallback: try to scrape the page
        try {
            val doc = app.get(data, headers = ua).document
            val body = doc.toString()

            // Scan for video URLs
            listOf(
                Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
                Regex("""(https?://[^\s"'<>]+\.mkv[^\s"'<>]*)"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            ).forEach { regex ->
                regex.findAll(body).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.startsWith("http")) {
                        callback.invoke(newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8"))
                                ExtractorLinkType.M3U8
                            else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = quality
                        })
                    }
                }
            }

            // Try all links on page
            doc.select("a[href]")
                .map { it.attr("abs:href") }
                .filter { it.startsWith("http") && supportedHosts.any { h -> it.contains(h) } }
                .forEach { loadExtractor(it, data, subtitleCallback, callback) }

        } catch (e: Exception) { }

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