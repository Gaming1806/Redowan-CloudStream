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

    // ── FIX 1: Robust indimega URL extraction with multiple selector fallbacks ──
    private fun extractIndimegaUrl(body: String, doc: org.jsoup.nodes.Document): String? {
        // Try known selectors first
        val candidates = listOf(
            doc.selectFirst("a#tracking-url")?.attr("href"),
            doc.selectFirst("a.button[href*='indimega']")?.attr("href"),
            doc.selectFirst("a[href*='indimega']")?.attr("href"),
            doc.selectFirst("a.btn[href*='indimega']")?.attr("href"),
            doc.selectFirst("a[href*='indimega.com']")?.attr("href"),
            // Some sites wrap the link in an iframe or redirect script
            Regex("""indimega\.com[^\s"'<>]*""").find(body)?.value?.let { "https://$it" },
            Regex("""["'](https?://(?:www\.)?indimega\.com/[^"']+)["']""").find(body)?.groupValues?.get(1)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }

    // ── FIX 2: Robust uptobhai/upto link extractor with better error surfacing ──
    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()

        try {
            // Step 1: Initial fetch – get cookies and raw HTML
            val initialResponse = app.get(
                uptoUrl,
                headers = ua + mapOf("Referer" to "https://indimega.com/")
            )
            val cookies   = initialResponse.cookies
            val rawHtml   = initialResponse.text

            // Step 2: Try to find and call cuid unlock endpoint
            val cuidDomain = Regex("""https://([^/"']+)/cuid/""").find(rawHtml)?.groupValues?.get(1)
            if (!cuidDomain.isNullOrBlank()) {
                val uptoBase = "https://" + uptoUrl.substringAfter("://").substringBefore("/")
                try {
                    app.get(
                        "https://$cuidDomain/cuid/?f=$uptoBase",
                        headers = ua + mapOf("Referer" to uptoUrl),
                        cookies = cookies
                    )
                } catch (_: Exception) { /* Non-fatal – continue without unlock */ }
            }

            // Step 3: Re-fetch page after unlock attempt
            val unlockedResponse = app.get(
                uptoUrl,
                headers = ua + mapOf("Referer" to "https://indimega.com/"),
                cookies = cookies
            )
            val unlockedDoc  = unlockedResponse.document
            val unlockedHtml = unlockedResponse.text

            // Step 4: Collect anchor href links that point to supported hosts
            unlockedDoc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith("http") &&
                    !href.contains("uptobhai", ignoreCase = true) &&
                    !href.contains("uptomega", ignoreCase = true) &&
                    supportedHosts.any { href.contains(it, ignoreCase = true) }
                ) {
                    links.add(href)
                }
            }

            // Step 5: Scan raw HTML for direct video file URLs
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*)""")
                .findAll(unlockedHtml).forEach { links.add(it.groupValues[1]) }

            // Step 6: ── FIX 3: Also scan for iframe embeds pointing to supported hosts ──
            unlockedDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.startsWith("http") &&
                    supportedHosts.any { src.contains(it, ignoreCase = true) }
                ) {
                    links.add(src)
                }
            }

            // Step 7: Scan for JS-embedded URLs (e.g. file:"https://...mp4")
            Regex("""file\s*[=:]\s*["'](https?://[^"']+)["']""")
                .findAll(unlockedHtml).forEach {
                    val u = it.groupValues[1]
                    if (supportedHosts.any { h -> u.contains(h, ignoreCase = true) } ||
                        u.contains(".mp4") || u.contains(".m3u8")
                    ) links.add(u)
                }

        } catch (e: Exception) {
            // Surface errors to logcat so you can debug
            android.util.Log.e("9kMovies", "getUptoLinks failed for $uptoUrl: ${e.message}")
        }

        return links.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val response  = app.get(url, headers = ua)
        val doc       = response.document
        val rawHtml   = response.text
        val title     = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val imageUrl  = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val story     = doc.selectFirst(".video-description")?.text()
        val episodes  = mutableListOf<Episode>()

        // ── FIX 1 applied: use robust extractor ──
        val indimegaUrl = extractIndimegaUrl(rawHtml, doc)

        if (!indimegaUrl.isNullOrEmpty()) {
            try {
                val indimegaResponse = app.get(
                    indimegaUrl,
                    headers = ua + mapOf("Referer" to mainUrl)
                )
                val indimegaDoc  = indimegaResponse.document
                val indimegaHtml = indimegaResponse.text

                // ── FIX 4: Wider selector set for quality buttons ──
                // Original code used "a.buttn.direct" – try multiple class combinations
                val qualityButtons = indimegaDoc
                    .select("a.buttn, a.direct, a.btn-download, a[href*='uptobhai'], a[href*='uptomega'], a[href*='upto']")
                    .filter { it.attr("href").contains("upto", ignoreCase = true) }
                    .ifEmpty {
                        // Fallback: any anchor whose href contains an uptobhai/uptomega domain
                        indimegaDoc.select("a[href]").filter { a ->
                            val h = a.attr("href")
                            h.contains("uptobhai") || h.contains("uptomega") || h.contains("upto.")
                        }
                    }

                if (qualityButtons.isNotEmpty()) {
                    qualityButtons.forEach { btn ->
                        val uptoUrl     = btn.attr("href").trim()
                        val qualityText = btn.text().trim().ifEmpty { "Download" }
                        if (uptoUrl.isEmpty()) return@forEach

                        val mirrors = getUptoLinks(uptoUrl)
                        if (mirrors.isNotEmpty()) {
                            mirrors.forEach { mirrorUrl ->
                                val hostLabel = supportedHosts
                                    .firstOrNull { mirrorUrl.contains(it, ignoreCase = true) }
                                    ?.split(".")?.first()
                                    ?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                                episodes.add(newEpisode(mirrorUrl) {
                                    this.name = "$qualityText [$hostLabel]"
                                })
                            }
                        } else {
                            // ── FIX 5: Store uptoUrl tagged so loadLinks knows what to do ──
                            episodes.add(newEpisode("upto::$uptoUrl") {
                                this.name = qualityText
                            })
                        }
                    }
                } else {
                    // ── FIX 6: No quality buttons – scan indimega page directly for embeds ──
                    indimegaDoc.select("a[href]").forEach { a ->
                        val href = a.attr("abs:href")
                        if (href.startsWith("http") &&
                            supportedHosts.any { href.contains(it, ignoreCase = true) }
                        ) {
                            episodes.add(newEpisode(href) { this.name = a.text().trim().ifEmpty { "Watch" } })
                        }
                    }
                    // Also check iframes
                    indimegaDoc.select("iframe[src]").forEach { iframe ->
                        val src = iframe.attr("abs:src")
                        if (src.startsWith("http") &&
                            supportedHosts.any { src.contains(it, ignoreCase = true) }
                        ) {
                            episodes.add(newEpisode(src) { this.name = "Stream" })
                        }
                    }
                    // Regex sweep for direct links in raw HTML
                    Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*)""")
                        .findAll(indimegaHtml).forEach {
                            episodes.add(newEpisode(it.groupValues[1]) { this.name = "Direct" })
                        }

                    if (episodes.isEmpty()) {
                        episodes.add(newEpisode(indimegaUrl) { this.name = "Download" })
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("9kMovies", "load indimega failed: ${e.message}")
                episodes.add(newEpisode(indimegaUrl) { this.name = "Download" })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { this.name = "Watch" })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        // ── FIX 5 handler: tagged upto URL that wasn't resolved during load() ──
        if (data.startsWith("upto::")) {
            val uptoUrl = data.removePrefix("upto::")
            val mirrors = getUptoLinks(uptoUrl)
            if (mirrors.isNotEmpty()) {
                mirrors.forEach { mirrorUrl -> resolveLink(mirrorUrl, subtitleCallback, callback) }
                return true
            }
            // If still nothing, fall through to try loadExtractor on the raw URL
            loadExtractor(uptoUrl, mainUrl, subtitleCallback, callback)
            return true
        }

        return resolveLink(data, subtitleCallback, callback)
    }

    // ── FIX 7: Centralised link resolver avoids duplicated logic ──
    private suspend fun resolveLink(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = getQualityFromUrl(data)

        return when {
            // Direct video file
            data.contains(".mp4", ignoreCase = true) ||
            data.contains(".mkv", ignoreCase = true) ||
            data.contains(".m3u8", ignoreCase = true) -> {
                callback.invoke(newExtractorLink(
                    source = name,
                    name   = name,
                    url    = data,
                    type   = if (data.contains(".m3u8", ignoreCase = true))
                                 ExtractorLinkType.M3U8
                             else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                })
                true
            }

            // Known supported host – hand off to CloudStream's built-in extractor
            supportedHosts.any { data.contains(it, ignoreCase = true) } -> {
                loadExtractor(data, mainUrl, subtitleCallback, callback)
                true
            }

            // Unknown URL – still try loadExtractor as a best-effort
            else -> {
                loadExtractor(data, mainUrl, subtitleCallback, callback)
                true
            }
        }
    }

    private fun getQualityFromUrl(url: String): Int = when {
        url.contains("2160") || url.contains("4k", ignoreCase = true) -> Qualities.P2160.value
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720")  -> Qualities.P720.value
        url.contains("480")  -> Qualities.P480.value
        url.contains("360")  -> Qualities.P360.value
        else                 -> Qualities.Unknown.value
    }
}