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
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    // Full browser-like headers to pass Cloudflare checks
    private val ua = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"           to "en-US,en;q=0.9",
        "Accept-Encoding"           to "gzip, deflate, br",
        "Connection"                to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest"            to "document",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "none",
        "Sec-Fetch-User"            to "?1",
        "sec-ch-ua"                 to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile"          to "?0",
        "sec-ch-ua-platform"        to "\"Windows\""
    )

    // All known video hosts
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

    // ── Find the indimega/9klinks hub URL from the movie page ─────────────────
    private fun findHubUrl(html: String, doc: Document): String? {
        val hubPatterns = listOf(
            "indimega", "indimovie", "9klinks", "9kmirror",
            "9kmoviez", "indimovies", "indilinks", "9klink", "indihub"
        )
        // Check all anchor tags
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href")
            if (hubPatterns.any { href.contains(it, ignoreCase = true) }) return href
        }
        // Check by element id/class
        doc.select("a#tracking-url, a#go-link, a#get-link, a.go-link, a.hub-link")
            .firstOrNull { it.attr("href").startsWith("http") }
            ?.attr("abs:href")?.let { return it }
        // Regex scan raw HTML
        Regex(
            """["'](https?://(?:[a-z0-9-]+\.)*(?:${hubPatterns.joinToString("|")})\.[a-z]+/[^"'\s]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    // ── Scan raw HTML/JS for any video URLs ───────────────────────────────────
    fun extractUrlsFromHtml(html: String): List<String> {
        val found = mutableListOf<String>()
        fun isVideoUrl(u: String) =
            supportedHosts.any { u.contains(it, ignoreCase = true) } ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mkv", ignoreCase = true)

        Regex("""(https?://[^\s"'<>\[\]{}\\]+)""").findAll(html).forEach { m ->
            val u = m.groupValues[1].trimEnd(')', ';', ',', '\\', '\'', '"', '\n')
            if (isVideoUrl(u)) found.add(u)
        }
        Regex("""window\.location(?:\.href)?\s*[=:]\s*["'`](https?://[^"'`\s]+)""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""(?:var|let|const)\s+\w+\s*=\s*["'`](https?://[^"'`\s]+)""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""data-(?:url|href|link|src|file)\s*=\s*["'](https?://[^"']+)["']""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""content=["'][0-9]*;\s*url=(https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""atob\(["'`]([A-Za-z0-9+/=]{20,})["'`]\)""").findAll(html).forEach { m ->
            try {
                val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) found.add(decoded.trim())
            } catch (_: Exception) {}
        }
        Regex(""""(?:url|link|file|src|href|download)"\s*:\s*"(https?://[^"]+)"""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }

        return found.filter { isVideoUrl(it) }.map { it.trimEnd('/') }.distinct()
    }

    // ── THE KEY FIX: Properly simulate the browser CUID unlock flow ───────────
    //
    // What the browser actually does (from your Network tab screenshots):
    //   1. GET  uptobhai.blog/view/CODE           → page HTML (links hidden)
    //   2. POST courilblaze.cyou/cuid/?f=https://uptobhai.blog  → unlocks links
    //   3. Links are now visible in a re-fetch or embedded in original HTML
    //
    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()
        android.util.Log.d("9kMovies", "▶ getUptoLinks: $uptoUrl")

        try {
            // The base domain of the locker site (e.g. https://uptobhai.blog)
            val siteBase = uptoUrl.substringBefore("://") + "://" +
                           uptoUrl.substringAfter("://").substringBefore("/")

            // ── STEP 1: GET the page — collect cookies + hidden fields ────────
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
            android.util.Log.d("9kMovies", "page title: ${doc1.title()}, cookies: ${cookies.keys}")

            // ── STEP 2: Extract the CUID domain from the page JS ─────────────
            // The browser calls: POST https://courilblaze.cyou/cuid/?f=https://uptobhai.blog
            // The cuid domain changes occasionally — always read it from page JS
            val cuidDomain = Regex(
                """["'`](https?://[a-z0-9.-]+)/cuid/["'`]""",
                RegexOption.IGNORE_CASE
            ).find(html1)?.groupValues?.get(1)
                ?: Regex("""cuid['":\s]+["'`](https?://[^"'`\s]+)""")
                    .find(html1)?.groupValues?.get(1)
                    ?: "https://courilblaze.cyou"  // hardcoded fallback from your screenshots

            android.util.Log.d("9kMovies", "cuid domain: $cuidDomain")

            // ── STEP 3: POST to CUID endpoint — this is what unlocks the links ─
            // Exact request seen in your Network tab:
            //   POST https://courilblaze.cyou/cuid/?f=https%3A%2F%2Fuptobhai.blog
            try {
                app.post(
                    "$cuidDomain/cuid/?f=${java.net.URLEncoder.encode(siteBase, "UTF-8")}",
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
                android.util.Log.d("9kMovies", "cuid POST done")
            } catch (e: Exception) {
                android.util.Log.w("9kMovies", "cuid POST failed (may still work): ${e.message}")
            }

            // Small delay to let server-side unlock propagate
            delay(1500)

            // ── STEP 4: Re-fetch the page — links should now be visible ───────
            val r2   = app.get(
                uptoUrl,
                headers = ua + mapOf(
                    "Referer"        to uptoUrl,
                    "Sec-Fetch-Site" to "same-origin"
                ),
                cookies = cookies
            )
            val html2 = r2.text
            val doc2  = r2.document
            android.util.Log.d("9kMovies", "re-fetch done, html size: ${html2.length}")

            // ── STEP 5: Extract all links from the unlocked page ──────────────
            // From your screenshot, links are plain <a href="..."> tags in a table/list
            doc2.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").trim()
                if (href.startsWith("http") &&
                    !href.contains("uptobhai",  ignoreCase = true) &&
                    !href.contains("uptomega",  ignoreCase = true) &&
                    !href.contains("courilblaze", ignoreCase = true)
                ) {
                    android.util.Log.d("9kMovies", "link found: $href")
                    links.add(href)
                }
            }

            // Also scan raw HTML for any embedded video URLs
            links.addAll(extractUrlsFromHtml(html2))

            // ── STEP 6: If still empty, try the first fetch HTML too ──────────
            // Sometimes links are already in the original HTML, just CSS-hidden
            if (links.isEmpty()) {
                android.util.Log.d("9kMovies", "trying original HTML...")
                doc1.select("a[href]").forEach { a ->
                    val href = a.attr("abs:href").trim()
                    if (href.startsWith("http") &&
                        !href.contains("uptobhai",    ignoreCase = true) &&
                        !href.contains("courilblaze", ignoreCase = true)
                    ) links.add(href)
                }
                links.addAll(extractUrlsFromHtml(html1))
            }

            // ── STEP 7: Try alternate CUID domains if still empty ─────────────
            if (links.isEmpty()) {
                val alternateCuidDomains = listOf(
                    "https://courilblaze.cyou",
                    "https://unlocklink.xyz",
                    "https://cuid.xyz"
                )
                for (domain in alternateCuidDomains) {
                    if (domain == cuidDomain) continue
                    try {
                        app.post(
                            "$domain/cuid/?f=${java.net.URLEncoder.encode(siteBase, "UTF-8")}",
                            headers = mapOf(
                                "User-Agent" to (ua["User-Agent"] ?: ""),
                                "Accept"     to "*/*",
                                "Origin"     to siteBase,
                                "Referer"    to uptoUrl
                            ),
                            cookies = cookies
                        )
                        delay(1000)
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
        android.util.Log.d("9kMovies", "✅ getUptoLinks returning ${result.size} links: $result")
        return result
    }

    // ── Load movie/series page ────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = ua)
        val doc      = response.document
        val html     = response.text
        val title    = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text() ?: ""
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
                       ?: doc.selectFirst("article img, .post-thumbnail img")?.attr("src") ?: ""
        val story    = doc.selectFirst(".video-description, .entry-content p")?.text()
        val eps      = mutableListOf<Episode>()

        val hubUrl = findHubUrl(html, doc)
        android.util.Log.d("9kMovies", "hub url: $hubUrl")

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
        android.util.Log.d("9kMovies", "hub page title: ${hubDoc.title()}")

        // Find upto buttons on hub page — try multiple selector patterns
        val uptoButtons = hubDoc.select("a[href]").filter { a ->
            val h = a.attr("href")
            h.contains("uptobhai", ignoreCase = true) ||
            h.contains("uptomega", ignoreCase = true) ||
            h.contains("upto.",    ignoreCase = true)
        }
        android.util.Log.d("9kMovies", "upto buttons found: ${uptoButtons.size}")

        if (uptoButtons.isNotEmpty()) {
            uptoButtons.forEach { btn ->
                val uptoUrl = btn.attr("href").trim()
                val label   = btn.text().trim().ifEmpty { "Download" }
                if (uptoUrl.isEmpty()) return@forEach
                android.util.Log.d("9kMovies", "processing upto: $uptoUrl")

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
            // Fallback: scan hub page directly for video links
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