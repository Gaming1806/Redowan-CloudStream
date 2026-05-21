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
import org.jsoup.nodes.Document
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
        "category/18-movies/"    to "18+ Movies",
        "category/bengali/"      to "Bengali",
        "category/bollywood/"    to "Bollywood",
        "category/dual-audio/"   to "Dual Audio",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/hollywood/"    to "Hollywood",
        "category/kannada/"      to "Kannada",
        "category/malayalam/"    to "Malayalam",
        "category/marathi/"      to "Marathi",
        "category/punjabi/"      to "Punjabi",
        "category/tamil/"        to "Tamil",
        "category/telugu/"       to "Telugu",
        "category/tv-shows/"     to "TV Shows",
        "category/web-series/"   to "Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc  = app.get("$mainUrl/${request.data}page/$page", headers = ua).document
        val home = doc.select("article.thumb-block").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun toResult(post: Element): SearchResponse? {
        val url      = post.selectFirst("a")?.attr("href") ?: return null
        val title    = post.selectFirst("header.entry-header span")?.text() ?: ""
        val imageUrl = post.selectFirst("img.video-main-thumb")?.attr("src") ?: ""
        return newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = imageUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return doc.select("article.thumb-block").mapNotNull { toResult(it) }
    }

    private fun findHubUrl(html: String, doc: Document): String? {
        return listOf(
            doc.selectFirst("a#tracking-url")?.attr("href"),
            doc.selectFirst("a[href*='indimega']")?.attr("href"),
            doc.selectFirst("a[href*='indimovie']")?.attr("href"),
            doc.selectFirst("a[href*='9klinks']")?.attr("href"),
            doc.selectFirst("a[href*='9kmirror']")?.attr("href"),
            Regex("""["'](https?://(?:[a-z0-9-]+\.)*(?:indimega|indimovie|9klinks|9kmirror)\.[a-z]+/[^"']+)["']""")
                .find(html)?.groupValues?.get(1)
        ).firstOrNull { !it.isNullOrBlank() }
    }

    // ── Extract all URLs from a JS/HTML body that point to known video hosts ──
    private fun extractUrlsFromHtml(html: String): List<String> {
        val found = mutableListOf<String>()

        // All URL-like patterns referencing supported hosts or direct video files
        val allUrlsRegex = Regex("""(https?://[^\s"'<>\[\]{}\\]+)""")
        allUrlsRegex.findAll(html).forEach { m ->
            val u = m.groupValues[1].trimEnd(')', ';', ',', '\\')
            if (supportedHosts.any { u.contains(it, ignoreCase = true) } ||
                u.contains(".mp4", ignoreCase = true) ||
                u.contains(".m3u8", ignoreCase = true) ||
                u.contains(".mkv", ignoreCase = true)
            ) found.add(u)
        }

        // window.location / JS redirect patterns
        Regex("""window\.location(?:\.href)?\s*[=:]\s*["'`](https?://[^"'`]+)["'`]""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }

        // var url = / var link = / var file = / var src =
        Regex("""(?:var|let|const)\s+(?:url|link|file|src|href|download)\s*=\s*["'`](https?://[^"'`]+)["'`]""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }

        // data-url / data-href / data-link attributes
        Regex("""data-(?:url|href|link|src)\s*=\s*["'](https?://[^"']+)["']""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }

        // meta http-equiv refresh
        Regex("""content=["'][0-9]*;\s*url=(https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { found.add(it.groupValues[1]) }

        // atob() decoded base64 URLs — decode common base64 blobs
        Regex("""atob\(["'`]([A-Za-z0-9+/=]{20,})["'`]\)""")
            .findAll(html).forEach { m ->
                try {
                    val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                    if (decoded.startsWith("http")) found.add(decoded.trim())
                } catch (_: Exception) {}
            }

        return found.distinct()
    }

    // ── Resolve uptobhai.blog/view/XXXXX → actual video host URL ─────────────
    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()
        try {
            // --- Pass 1: initial fetch ---
            val r1       = app.get(uptoUrl, headers = ua + mapOf("Referer" to "https://indimega.com/"))
            val cookies  = r1.cookies
            val html1    = r1.text
            val finalUrl1 = r1.url  // URL after any HTTP redirects

            // If the request was redirected straight to a supported host, we're done
            if (finalUrl1 != uptoUrl &&
                supportedHosts.any { finalUrl1.contains(it, ignoreCase = true) }
            ) {
                links.add(finalUrl1)
                return links
            }

            // Scan HTML / JS of the uptobhai page for embedded URLs
            links.addAll(extractUrlsFromHtml(html1))

            // --- cuid unlock (some uptobhai variants use this) ---
            val cuidDomain = Regex("""https://([^/"'\s]+)/cuid/""").find(html1)?.groupValues?.get(1)
            if (!cuidDomain.isNullOrBlank()) {
                val base = "https://" + uptoUrl.substringAfter("://").substringBefore("/")
                try {
                    app.get("https://$cuidDomain/cuid/?f=$base",
                        headers = ua + mapOf("Referer" to uptoUrl), cookies = cookies)
                } catch (_: Exception) {}
            }

            // --- Pass 2: re-fetch after unlock attempt ---
            val r2       = app.get(uptoUrl,
                headers = ua + mapOf("Referer" to "https://indimega.com/"),
                cookies = cookies)
            val html2    = r2.text
            val finalUrl2 = r2.url

            if (finalUrl2 != uptoUrl &&
                supportedHosts.any { finalUrl2.contains(it, ignoreCase = true) }
            ) {
                links.add(finalUrl2)
                return links.distinct()
            }

            links.addAll(extractUrlsFromHtml(html2))

            // Also collect plain <a href> and <iframe src> from parsed DOM
            val doc2 = r2.document
            doc2.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith("http") &&
                    !href.contains("uptobhai", ignoreCase = true) &&
                    !href.contains("uptomega", ignoreCase = true) &&
                    supportedHosts.any { href.contains(it, ignoreCase = true) }
                ) links.add(href)
            }
            doc2.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.startsWith("http") && supportedHosts.any { src.contains(it, ignoreCase = true) })
                    links.add(src)
            }

            // --- Pass 3: Try to find and follow a "Get Link" / "Download" button ---
            // Some pages require a POST or a second GET to a /go/ or /download/ endpoint
            val getUrlFromDoc: (org.jsoup.nodes.Document) -> String? = { d ->
                d.select("a[href]").firstOrNull { a ->
                    val cls = a.className()
                    val txt = a.text().lowercase()
                    (cls.contains("get") || cls.contains("download") || cls.contains("go") ||
                     txt.contains("get link") || txt.contains("download") || txt.contains("click here") ||
                     txt.contains("continue") || txt.contains("proceed")) &&
                    a.attr("href").startsWith("http") &&
                    !a.attr("href").contains("uptobhai", ignoreCase = true)
                }?.attr("abs:href")
            }

            val goUrl = getUrlFromDoc(doc2)
            if (!goUrl.isNullOrBlank()) {
                try {
                    val r3    = app.get(goUrl, headers = ua + mapOf("Referer" to uptoUrl), cookies = cookies)
                    val html3 = r3.text
                    val final3 = r3.url
                    if (supportedHosts.any { final3.contains(it, ignoreCase = true) }) {
                        links.add(final3)
                    }
                    links.addAll(extractUrlsFromHtml(html3))
                } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            android.util.Log.e("9kMovies", "getUptoLinks($uptoUrl): ${e.message}")
        }
        return links.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = ua)
        val doc      = response.document
        val html     = response.text
        val title    = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val story    = doc.selectFirst(".video-description")?.text()
        val eps      = mutableListOf<Episode>()

        val hubUrl = findHubUrl(html, doc)
        if (hubUrl.isNullOrEmpty()) {
            eps.add(newEpisode(url) { this.name = "Watch" })
            return buildResponse(title, url, imageUrl, story, eps)
        }

        val hubResponse = try {
            app.get(hubUrl, headers = ua + mapOf("Referer" to mainUrl))
        } catch (_: Exception) {
            eps.add(newEpisode(url) { this.name = "Watch" })
            return buildResponse(title, url, imageUrl, story, eps)
        }

        val hubDoc  = hubResponse.document
        val hubHtml = hubResponse.text

        // ── Quality buttons: class "buttn direct" confirmed from debug ──────
        val uptoButtons = hubDoc.select("a.buttn.direct, a.buttn, a[href*='upto']")
            .filter { it.attr("href").contains("upto", ignoreCase = true) }
            .ifEmpty {
                // Fallback: any anchor with uptobhai/uptomega/upto in href
                hubDoc.select("a[href]").filter { a ->
                    val h = a.attr("href")
                    h.contains("uptobhai", ignoreCase = true) ||
                    h.contains("uptomega", ignoreCase = true) ||
                    h.contains("upto.", ignoreCase = true)
                }
            }

        if (uptoButtons.isNotEmpty()) {
            uptoButtons.forEach { btn ->
                val uptoUrl = btn.attr("href").trim()
                val label   = btn.text().trim().ifEmpty { "Download" }
                if (uptoUrl.isEmpty()) return@forEach

                val mirrors = getUptoLinks(uptoUrl)
                if (mirrors.isNotEmpty()) {
                    mirrors.forEach { mirrorUrl ->
                        val host = supportedHosts
                            .firstOrNull { mirrorUrl.contains(it, ignoreCase = true) }
                            ?.split(".")?.first()?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                        eps.add(newEpisode(mirrorUrl) { this.name = "$label [$host]" })
                    }
                } else {
                    // Store the uptobhai URL directly — loadLinks will retry resolution
                    eps.add(newEpisode("upto::$uptoUrl") { this.name = label })
                }
            }
        } else {
            // Fallback: scan hub page directly for supported hosts / iframes / video files
            hubDoc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith("http") && supportedHosts.any { href.contains(it, ignoreCase = true) })
                    eps.add(newEpisode(href) { this.name = a.text().trim().ifEmpty { "Watch" } })
            }
            hubDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.startsWith("http") && supportedHosts.any { src.contains(it, ignoreCase = true) })
                    eps.add(newEpisode(src) { this.name = "Stream" })
            }
            extractUrlsFromHtml(hubHtml).forEach {
                eps.add(newEpisode(it) { this.name = "Direct" })
            }
            if (eps.isEmpty()) eps.add(newEpisode(hubUrl) { this.name = "Open Hub" })
        }

        if (eps.isEmpty()) eps.add(newEpisode(url) { this.name = "Watch" })
        return buildResponse(title, url, imageUrl, story, eps)
    }

    private suspend fun buildResponse(
        title: String, url: String, imageUrl: String,
        story: String?, eps: MutableList<Episode>
    ) = newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
        this.posterUrl = imageUrl
        this.plot      = story?.trim()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("upto::")) {
            val uptoUrl = data.removePrefix("upto::")
            val mirrors = getUptoLinks(uptoUrl)
            if (mirrors.isNotEmpty()) {
                mirrors.forEach { resolveLink(it, subtitleCallback, callback) }
                return true
            }
            // Last resort: try loadExtractor directly on the upto URL
            loadExtractor(uptoUrl, mainUrl, subtitleCallback, callback)
            return true
        }
        return resolveLink(data, subtitleCallback, callback)
    }

    private suspend fun resolveLink(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = getQualityFromUrl(data)
        return when {
            data.contains(".mp4", ignoreCase = true) ||
            data.contains(".mkv", ignoreCase = true) ||
            data.contains(".m3u8", ignoreCase = true) -> {
                callback.invoke(newExtractorLink(
                    source = name, name = name, url = data,
                    type   = if (data.contains(".m3u8", ignoreCase = true))
                                 ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) { this.referer = mainUrl; this.quality = quality })
                true
            }
            supportedHosts.any { data.contains(it, ignoreCase = true) } -> {
                loadExtractor(data, mainUrl, subtitleCallback, callback)
                true
            }
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