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

    private val DEBUG = true

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

    private fun dbg(eps: MutableList<Episode>, msg: String) {
        if (DEBUG) eps.add(newEpisode("debug::noop") { this.name = ">> $msg" })
    }

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

    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val r1      = app.get(uptoUrl, headers = ua + mapOf("Referer" to "https://indimega.com/"))
            val cookies = r1.cookies
            val html1   = r1.text

            val cuidDomain = Regex("""https://([^/"'\s]+)/cuid/""").find(html1)?.groupValues?.get(1)
            if (!cuidDomain.isNullOrBlank()) {
                val base = "https://" + uptoUrl.substringAfter("://").substringBefore("/")
                try {
                    app.get("https://$cuidDomain/cuid/?f=$base",
                        headers = ua + mapOf("Referer" to uptoUrl), cookies = cookies)
                } catch (_: Exception) {}
            }

            val r2    = app.get(uptoUrl, headers = ua + mapOf("Referer" to "https://indimega.com/"), cookies = cookies)
            val doc2  = r2.document
            val html2 = r2.text

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
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*)""")
                .findAll(html2).forEach { links.add(it.groupValues[1]) }
            Regex("""file\s*[=:]\s*["'](https?://[^"']+)["']""")
                .findAll(html2).forEach {
                    val u = it.groupValues[1]
                    if (supportedHosts.any { h -> u.contains(h, ignoreCase = true) } || u.contains(".mp4") || u.contains(".m3u8"))
                        links.add(u)
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

        // ── STEP 1: Find hub URL ──────────────────────────────────────────────
        // Only show anchors that contain external URLs (skip nav/hash links) - max 5
        if (DEBUG) {
            val externalLinks = doc.select("a[href]")
                .filter { it.attr("href").startsWith("http") && !it.attr("href").contains("9kmovies") }
                .map { "${it.text().take(25)} → ${it.attr("href").take(60)}" }
                .take(5)
            dbg(eps, "STEP1: External links on page (${externalLinks.size}):")
            externalLinks.forEach { dbg(eps, "  $it") }
        }

        val hubUrl = findHubUrl(html, doc)
        dbg(eps, "STEP1 result: hubUrl = $hubUrl")

        if (hubUrl.isNullOrEmpty()) {
            dbg(eps, "STEP1 FAILED: No hub URL found!")
            eps.add(newEpisode(url) { this.name = "Fallback" })
            return buildResponse(title, url, imageUrl, story, eps)
        }

        // ── STEP 2: Fetch hub page ────────────────────────────────────────────
        val hubResponse = try {
            app.get(hubUrl, headers = ua + mapOf("Referer" to mainUrl))
        } catch (e: Exception) {
            dbg(eps, "STEP2 FAILED: fetch error: ${e.message?.take(80)}")
            return buildResponse(title, url, imageUrl, story, eps)
        }

        val hubDoc  = hubResponse.document
        val hubHtml = hubResponse.text
        val hubStatusCode = hubResponse.code

        dbg(eps, "STEP2: Fetched hub. HTTP=$hubStatusCode, bodyLen=${hubHtml.length}")

        // Show ALL anchors on the hub page (this is the critical info)
        if (DEBUG) {
            val allHubAnchors = hubDoc.select("a[href]")
                .map { "  [${it.className().take(20)}] ${it.text().take(25)} → ${it.attr("href").take(70)}" }
            dbg(eps, "STEP2: Hub page has ${allHubAnchors.size} anchors:")
            allHubAnchors.take(15).forEach { dbg(eps, it) }

            // Also show all unique href domains to spot what host is used
            val domains = hubDoc.select("a[href]")
                .map { it.attr("href") }
                .filter { it.startsWith("http") }
                .map { it.substringAfter("://").substringBefore("/") }
                .distinct()
            dbg(eps, "STEP2: Unique href domains: $domains")

            // Show first 200 chars of raw HTML to spot JS onclick patterns
            val bodySnippet = hubHtml.take(500).replace("\n", " ")
            dbg(eps, "STEP2: HTML start: $bodySnippet")
        }

        // Check if Cloudflare challenge page
        if (hubHtml.contains("cf-browser-verification") || hubHtml.contains("Checking your browser") || hubStatusCode == 403) {
            dbg(eps, "STEP2 BLOCKED: Cloudflare/403 on hub page!")
            eps.add(newEpisode(hubUrl) { this.name = "Open Hub in Browser" })
            return buildResponse(title, url, imageUrl, story, eps)
        }

        // ── STEP 3: Find download buttons on hub page ─────────────────────────
        // Strategy A: buttons linking to upto* domains
        val uptoButtons = hubDoc.select("a[href]").filter { a ->
            val h = a.attr("href")
            h.contains("upto", ignoreCase = true)
        }
        dbg(eps, "STEP3A: upto-href buttons found: ${uptoButtons.size}")

        // Strategy B: onclick JS handlers that contain URLs
        val onclickLinks = mutableListOf<Pair<String, String>>()
        hubDoc.select("[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            Regex("""https?://[^\s"'()]+""").findAll(onclick).forEach { m ->
                onclickLinks.add(m.value to (el.text().trim().ifEmpty { "onclick" }))
            }
        }
        dbg(eps, "STEP3B: onclick URL links found: ${onclickLinks.size}")
        onclickLinks.forEach { (u, label) -> dbg(eps, "  onclick: $label → $u") }

        // Strategy C: any supported host links directly on hub page
        val directHostLinks = hubDoc.select("a[href]").filter { a ->
            val h = a.attr("abs:href")
            h.startsWith("http") && supportedHosts.any { h.contains(it, ignoreCase = true) }
        }
        dbg(eps, "STEP3C: direct host links on hub: ${directHostLinks.size}")
        directHostLinks.forEach { a ->
            dbg(eps, "  ${a.text().take(20)} → ${a.attr("abs:href").take(60)}")
        }

        // Strategy D: iframes
        val iframes = hubDoc.select("iframe[src]").filter { iframe ->
            val src = iframe.attr("abs:src")
            src.startsWith("http") && supportedHosts.any { src.contains(it, ignoreCase = true) }
        }
        dbg(eps, "STEP3D: embedded iframes: ${iframes.size}")

        // ── STEP 4: Resolve links ─────────────────────────────────────────────
        var foundAny = false

        // From upto buttons
        uptoButtons.forEach { btn ->
            val uptoUrl = btn.attr("href").trim()
            val label   = btn.text().trim().ifEmpty { "Download" }
            if (uptoUrl.isEmpty()) return@forEach

            dbg(eps, "STEP4: resolving upto: $label → $uptoUrl")
            val mirrors = getUptoLinks(uptoUrl)
            dbg(eps, "STEP4: got ${mirrors.size} mirror(s)")
            mirrors.forEach { mirrorUrl ->
                val host = supportedHosts.firstOrNull { mirrorUrl.contains(it, ignoreCase = true) }
                    ?.split(".")?.first()?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                eps.add(newEpisode(mirrorUrl) { this.name = "$label [$host]" })
                foundAny = true
            }
            if (mirrors.isEmpty()) {
                eps.add(newEpisode("upto::$uptoUrl") { this.name = "$label (lazy)" })
                foundAny = true
            }
        }

        // From onclick
        onclickLinks.forEach { (href, label) ->
            if (supportedHosts.any { href.contains(it, ignoreCase = true) } ||
                href.contains(".mp4") || href.contains(".m3u8") || href.contains("upto", ignoreCase = true)) {
                eps.add(newEpisode(href) { this.name = "$label [onclick]" })
                foundAny = true
            }
        }

        // From direct host links
        directHostLinks.forEach { a ->
            val href = a.attr("abs:href")
            eps.add(newEpisode(href) { this.name = a.text().trim().ifEmpty { "Watch" } })
            foundAny = true
        }

        // From iframes
        iframes.forEach { iframe ->
            val src = iframe.attr("abs:src")
            eps.add(newEpisode(src) { this.name = "Embed" })
            foundAny = true
        }

        if (!foundAny) {
            dbg(eps, "STEP4 FAILED: No playable links found on hub page")
            // Last resort: store hub URL for manual open
            eps.add(newEpisode(hubUrl) { this.name = "Open Hub in Browser" })
        }

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
        if (data == "debug::noop") return true

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
            data.contains(".mp4", ignoreCase = true) ||
            data.contains(".mkv", ignoreCase = true) ||
            data.contains(".m3u8", ignoreCase = true) -> {
                callback.invoke(newExtractorLink(
                    source = name, name = name, url = data,
                    type   = if (data.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
                             else ExtractorLinkType.VIDEO
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