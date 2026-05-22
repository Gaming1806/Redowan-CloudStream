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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class NineKMoviesProvider : MainAPI() {

    // ── Domain list: tried in order until one returns real HTML ───────────────
    // Open each in browser to check which is currently live, put it first
    private val candidateDomains = listOf(
        "https://9kmovies.democrat",
        "https://9kmovies.you",
        "https://9kmovies.com",
        "https://9kmovies.org",
        "https://9kmovies.download",
        "https://9kmovies.dev",
        "https://9kmovies.pw"
    )

    override var mainUrl  = candidateDomains.first()
    override var name     = "9kMovies"
    override var lang     = "en"
    override val hasMainPage        = true
    override val hasDownloadSupport = true
    override val hasQuickSearch     = false
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    // ── Full browser headers — critical for Cloudflare bypass ─────────────────
    private val ua = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                       "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                       "Chrome/124.0.0.0 Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;" +
                                       "q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language"           to "en-US,en;q=0.9",
        "Accept-Encoding"           to "gzip, deflate, br",
        "Connection"                to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest"            to "document",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "none",
        "Sec-Fetch-User"            to "?1",
        "Cache-Control"             to "max-age=0",
        "sec-ch-ua"                 to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
        "sec-ch-ua-mobile"          to "?0",
        "sec-ch-ua-platform"        to "\"Windows\""
    )

    val supportedHosts = listOf(
        "streamtape", "mixdrop", "gofile.io", "voe.sx",
        "doodstream", "dood.watch", "dood.la", "dood.to", "dood.wf",
        "streamlare", "filelions", "streamhub", "upstream",
        "megaup.net", "send.now", "savefiles.com", "vikingfile",
        "vinovo.to", "frdl.io", "dsvplay", "clicknupload",
        "streamwish", "filemoon", "mp4upload", "streamvid",
        "embedrise", "vtube", "uqload", "luluvid"
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

    // ── Auto-detect the working domain on first real use ──────────────────────
    private var domainVerified = false
    private suspend fun ensureWorkingDomain() {
        if (domainVerified) return
        for (candidate in candidateDomains) {
            try {
                val resp = app.get("$candidate/", headers = ua)
                val html = resp.text
                // A real page has article tags or known post classes; a CF
                // challenge page contains "Just a moment" or "cf-browser-verification"
                val isChallenge = html.contains("Just a moment", ignoreCase = true) ||
                                  html.contains("cf-browser-verification", ignoreCase = true) ||
                                  html.contains("Enable JavaScript", ignoreCase = true)
                val hasContent  = html.contains("article", ignoreCase = true) ||
                                  html.contains("entry-title", ignoreCase = true) ||
                                  html.contains("thumb-block", ignoreCase = true)
                if (!isChallenge && hasContent) {
                    mainUrl       = candidate
                    domainVerified = true
                    android.util.Log.d("9kMovies", "✅ Working domain: $mainUrl")
                    return
                }
                android.util.Log.d("9kMovies", "❌ $candidate — isChallenge=$isChallenge hasContent=$hasContent")
            } catch (e: Exception) {
                android.util.Log.d("9kMovies", "❌ $candidate — ${e.message}")
            }
        }
        // Fall back to first entry if none verified
        domainVerified = true
        android.util.Log.w("9kMovies", "⚠ No domain verified, using default: $mainUrl")
    }

    // ── Main page / homepage ──────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureWorkingDomain()
        val url = if (request.data.isEmpty()) "$mainUrl/page/$page"
                  else "$mainUrl/${request.data.trimStart('/')}page/$page"
        val doc  = app.get(url, headers = ua).document
        val home = doc.select(
            // Multiple selector fallbacks in case the site changed class names
            "article.thumb-block, article.post, article, .post-thumbnail, .movie-item"
        ).mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun toResult(post: Element): SearchResponse? {
        // Try multiple link patterns
        val url = post.selectFirst("a[href]")?.attr("abs:href")
                  ?: post.selectFirst("a")?.attr("href")
                  ?: return null
        if (!url.startsWith("http")) return null

        // Multiple title fallbacks
        val title = post.selectFirst("header.entry-header span")?.text()
                    ?: post.selectFirst(".entry-title, .post-title, h2, h3, h4")?.text()
                    ?: post.selectFirst("a")?.attr("title")
                    ?: post.selectFirst("img")?.attr("alt")
                    ?: ""
        if (title.isBlank()) return null

        // Multiple thumbnail fallbacks
        val imageUrl = post.selectFirst("img.video-main-thumb")?.attr("src")
                       ?: post.selectFirst("img[src]")?.attr("abs:src")
                       ?: post.selectFirst("img")?.attr("data-src")
                       ?: ""

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = imageUrl
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        ensureWorkingDomain()
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return doc.select(
            "article.thumb-block, article.post, article, .movie-item"
        ).mapNotNull { toResult(it) }
    }

    // ── Find the indimega/9klinks hub URL from a movie page ───────────────────
    private fun findHubUrl(html: String, doc: Document): String? {
        val hubKeywords = listOf(
            "indimega", "indimovie", "9klinks", "9kmirror",
            "9kmoviez", "indimovies", "indilinks", "9klink", "indihub"
        )
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href")
            if (hubKeywords.any { href.contains(it, ignoreCase = true) }) return href
        }
        doc.select("a#tracking-url, a#go-link, a#get-link, a.go-link, a.hub-link")
            .firstOrNull { it.attr("href").startsWith("http") }
            ?.attr("abs:href")?.let { return it }

        Regex(
            """["'](https?://(?:[a-z0-9-]+\.)*(?:${hubKeywords.joinToString("|")})\.[a-z]+/[^"'\s]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    // ── Scan raw HTML/JS for video URLs ───────────────────────────────────────
    fun extractUrlsFromHtml(html: String): List<String> {
        val found = mutableListOf<String>()
        fun isVideo(u: String) =
            supportedHosts.any { u.contains(it, ignoreCase = true) } ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mkv", ignoreCase = true)

        Regex("""(https?://[^\s"'<>\[\]{}\\]+)""").findAll(html).forEach { m ->
            val u = m.groupValues[1].trimEnd(')', ';', ',', '\\', '\'', '"', '\n')
            if (isVideo(u)) found.add(u)
        }
        Regex("""window\.location(?:\.href)?\s*[=:]\s*["'`](https?://[^"'`\s]+)""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }
        Regex("""(?:var|let|const)\s+\w+\s*=\s*["'`](https?://[^"'`\s]+)""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }
        Regex("""data-(?:url|href|link|src|file)\s*=\s*["'](https?://[^"']+)["']""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }
        Regex("""atob\(["'`]([A-Za-z0-9+/=]{20,})["'`]\)""").findAll(html).forEach { m ->
            try {
                val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) found.add(decoded.trim())
            } catch (_: Exception) {}
        }
        Regex(""""(?:url|link|file|src|href|download)"\s*:\s*"(https?://[^"]+)"""")
            .findAll(html).forEach { found.add(it.groupValues[1]) }

        return found.filter { isVideo(it) }.map { it.trimEnd('/') }.distinct()
    }

    // ── Resolve uptobhai link → list of video mirror URLs ─────────────────────
    //
    // Browser flow (captured from your Network tab):
    //   1. GET  uptobhai.blog/view/CODE
    //   2. POST courilblaze.cyou/cuid/?f=https://uptobhai.blog  ← unlocks links
    //   3. Links become visible on re-fetch
    //
    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()
        android.util.Log.d("9kMovies", "▶ getUptoLinks: $uptoUrl")
        try {
            val siteBase = uptoUrl.substringBefore("://") + "://" +
                           uptoUrl.substringAfter("://").substringBefore("/")

            // STEP 1 — GET the page, collect cookies
            val r1 = app.get(
                uptoUrl,
                headers = ua + mapOf(
                    "Referer"        to "https://indimega.com/",
                    "Sec-Fetch-Site" to "cross-site"
                )
            )
            val cookies = r1.cookies
            val html1   = r1.text
            val doc1    = r1.document
            android.util.Log.d("9kMovies", "uptobhai title: ${doc1.title()}")

            // STEP 2 — Find the CUID domain from page JS (falls back to known value)
            val cuidBase = Regex(
                """["'`](https?://[a-z0-9.-]+)/cuid/["'`]""", RegexOption.IGNORE_CASE
            ).find(html1)?.groupValues?.get(1) ?: "https://courilblaze.cyou"
            android.util.Log.d("9kMovies", "cuid base: $cuidBase")

            // STEP 3 — POST to CUID endpoint (this is the unlock trigger)
            try {
                app.post(
                    "$cuidBase/cuid/?f=${java.net.URLEncoder.encode(siteBase, "UTF-8")}",
                    headers = mapOf(
                        "User-Agent"      to (ua["User-Agent"] ?: ""),
                        "Accept"          to "*/*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Origin"          to siteBase,
                        "Referer"         to uptoUrl,
                        "Sec-Fetch-Dest"  to "empty",
                        "Sec-Fetch-Mode"  to "cors",
                        "Sec-Fetch-Site"  to "cross-site"
                    ),
                    cookies = cookies
                )
            } catch (e: Exception) {
                android.util.Log.w("9kMovies", "cuid POST warn: ${e.message}")
            }

            // Short pause for server-side unlock to propagate
            Thread.sleep(1500)

            // STEP 4 — Re-fetch page, links now visible
            val r2   = app.get(
                uptoUrl,
                headers = ua + mapOf("Referer" to uptoUrl, "Sec-Fetch-Site" to "same-origin"),
                cookies = cookies
            )
            val html2 = r2.text
            val doc2  = r2.document
            android.util.Log.d("9kMovies", "re-fetch size: ${html2.length}")

            // STEP 5 — Extract links from unlocked page
            doc2.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").trim()
                if (href.startsWith("http") &&
                    !href.contains("uptobhai",    ignoreCase = true) &&
                    !href.contains("uptomega",    ignoreCase = true) &&
                    !href.contains("courilblaze", ignoreCase = true)
                ) {
                    android.util.Log.d("9kMovies", "link: $href")
                    links.add(href)
                }
            }
            links.addAll(extractUrlsFromHtml(html2))

            // STEP 6 — If still empty, try parsing the original page HTML
            // (links are sometimes already in HTML, just CSS-hidden until JS runs)
            if (links.isEmpty()) {
                doc1.select("a[href]").forEach { a ->
                    val href = a.attr("abs:href").trim()
                    if (href.startsWith("http") &&
                        !href.contains("uptobhai",    ignoreCase = true) &&
                        !href.contains("courilblaze", ignoreCase = true)
                    ) links.add(href)
                }
                links.addAll(extractUrlsFromHtml(html1))
            }

            // STEP 7 — Try alternate known CUID domains
            if (links.isEmpty()) {
                for (altCuid in listOf("https://courilblaze.cyou", "https://unlocklink.xyz")) {
                    if (altCuid == cuidBase) continue
                    try {
                        app.post(
                            "$altCuid/cuid/?f=${java.net.URLEncoder.encode(siteBase, "UTF-8")}",
                            headers = mapOf(
                                "User-Agent" to (ua["User-Agent"] ?: ""),
                                "Accept"     to "*/*",
                                "Origin"     to siteBase,
                                "Referer"    to uptoUrl
                            ),
                            cookies = cookies
                        )
                        Thread.sleep(1000)
                        val r3 = app.get(uptoUrl, headers = ua + mapOf("Referer" to uptoUrl), cookies = cookies)
                        r3.document.select("a[href]").forEach { a ->
                            val href = a.attr("abs:href").trim()
                            if (href.startsWith("http") &&
                                !href.contains("uptobhai",    ignoreCase = true) &&
                                !href.contains("courilblaze", ignoreCase = true)
                            ) links.add(href)
                        }
                        links.addAll(extractUrlsFromHtml(r3.text))
                        if (links.isNotEmpty()) break
                    } catch (_: Exception) {}
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("9kMovies", "getUptoLinks error: ${e.message}")
        }

        val result = links.distinct()
        android.util.Log.d("9kMovies", "✅ ${result.size} links found")
        return result
    }

    // ── Load a movie/series page ───────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        ensureWorkingDomain()
        val response = app.get(url, headers = ua)
        val doc      = response.document
        val html     = response.text

        // Multiple title fallbacks
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text() ?: ""
        // Multiple poster fallbacks
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
                       ?: doc.selectFirst("article img, .post-thumbnail img, .entry-content img")
                           ?.attr("abs:src") ?: ""
        val story = doc.selectFirst(".video-description, .entry-content p, .post-content p")?.text()
        val eps   = mutableListOf<Episode>()

        val hubUrl = findHubUrl(html, doc)
        android.util.Log.d("9kMovies", "hubUrl: $hubUrl")

        if (hubUrl.isNullOrEmpty()) {
            extractUrlsFromHtml(html).forEach { eps.add(newEpisode(it) { this.name = "Watch" }) }
            if (eps.isEmpty()) eps.add(newEpisode(url) { this.name = "Watch" })
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
        android.util.Log.d("9kMovies", "hub page: ${hubDoc.title()}")

        // Find upto buttons — broad selector catches any naming variation
        val uptoButtons = hubDoc.select("a[href]").filter { a ->
            val h = a.attr("href")
            h.contains("uptobhai", ignoreCase = true) ||
            h.contains("uptomega", ignoreCase = true) ||
            h.contains("upto.",    ignoreCase = true)
        }
        android.util.Log.d("9kMovies", "upto buttons: ${uptoButtons.size}")

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
                            ?.split(".")?.first()
                            ?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                        eps.add(newEpisode(mirrorUrl) { this.name = "$label [$host]" })
                    }
                } else {
                    eps.add(newEpisode("upto::$uptoUrl") { this.name = label })
                }
            }
        } else {
            // Fallback: grab video links directly from hub page
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
            extractUrlsFromHtml(hubHtml).forEach { eps.add(newEpisode(it) { this.name = "Direct" }) }
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
            data.contains(".mp4",  ignoreCase = true) ||
            data.contains(".mkv",  ignoreCase = true) ||
            data.contains(".m3u8", ignoreCase = true) -> {
                callback.invoke(newExtractorLink(
                    source = name, name = name, url = data,
                    type = if (data.contains(".m3u8", ignoreCase = true))
                               ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) { this.referer = mainUrl; this.quality = quality })
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