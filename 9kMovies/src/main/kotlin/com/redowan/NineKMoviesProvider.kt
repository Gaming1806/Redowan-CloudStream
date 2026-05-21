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

    // ─── Set DEBUG = true to expose each step as an episode name ───────────────
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

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private fun dbg(episodes: MutableList<Episode>, msg: String) {
        if (DEBUG) episodes.add(newEpisode("debug::noop") { this.name = "⚙ $msg" })
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

    // ─── Step 1: Find the intermediate hub URL on the 9kmovies page ─────────────
    private fun findHubUrl(html: String, doc: Document): String? {
        val selectors = listOf(
            doc.selectFirst("a#tracking-url")?.attr("href"),
            doc.selectFirst("a.button[href*='indimega']")?.attr("href"),
            doc.selectFirst("a[href*='indimega']")?.attr("href"),
            doc.selectFirst("a[href*='indimovie']")?.attr("href"),
            doc.selectFirst("a[href*='9klinks']")?.attr("href"),
            doc.selectFirst("a[href*='9kmirror']")?.attr("href"),
            doc.selectFirst("a.download-btn")?.attr("href"),
            doc.selectFirst("a.btn-download")?.attr("href"),
            doc.selectFirst("a[rel='nofollow'][target='_blank']")?.attr("href"),
            // Regex fallback over raw HTML
            Regex("""["'](https?://(?:[a-z0-9-]+\.)*(?:indimega|9klinks|9kmirror|indimovie)\.[a-z]+/[^"']+)["']""")
                .find(html)?.groupValues?.get(1)
        )
        return selectors.firstOrNull { !it.isNullOrBlank() }
    }

    // ─── Step 2: On the hub page find buttons that link to an "upto*" domain ───
    private fun findUptoButtons(doc: Document): List<Pair<String, String>> {
        // Returns list of (url, label)
        val results = mutableListOf<Pair<String, String>>()

        // Try many class/attribute combinations
        val candidates = doc.select(
            "a.buttn, a.buttn.direct, a.direct, a.btn, a.button, " +
            "a.download, a.btn-download, a.wp-block-button__link, " +
            "a[href*='upto'], a[href*='uptobhai'], a[href*='uptomega']"
        )

        candidates.forEach { a ->
            val href  = a.attr("abs:href").trim()
            val label = a.text().trim().ifEmpty { "Download" }
            if (href.startsWith("http") &&
                (href.contains("upto", ignoreCase = true) ||
                 href.contains("uptobhai", ignoreCase = true) ||
                 href.contains("uptomega", ignoreCase = true))
            ) {
                results.add(href to label)
            }
        }

        // If still empty, grab ALL anchors and dump them for debug
        return results
    }

    // ─── Step 3: Resolve upto page → actual video mirror links ──────────────────
    private suspend fun getUptoLinks(uptoUrl: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val r1      = app.get(uptoUrl, headers = ua + mapOf("Referer" to "https://indimega.com/"))
            val cookies = r1.cookies
            val html1   = r1.text

            // cuid unlock
            val cuidDomain = Regex("""https://([^/"'\s]+)/cuid/""").find(html1)?.groupValues?.get(1)
            if (!cuidDomain.isNullOrBlank()) {
                val base = "https://" + uptoUrl.substringAfter("://").substringBefore("/")
                try {
                    app.get(
                        "https://$cuidDomain/cuid/?f=$base",
                        headers = ua + mapOf("Referer" to uptoUrl),
                        cookies = cookies
                    )
                } catch (_: Exception) {}
            }

            val r2   = app.get(uptoUrl, headers = ua + mapOf("Referer" to "https://indimega.com/"), cookies = cookies)
            val doc2 = r2.document
            val html2 = r2.text

            // Anchors
            doc2.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith("http") &&
                    !href.contains("uptobhai", ignoreCase = true) &&
                    !href.contains("uptomega", ignoreCase = true) &&
                    supportedHosts.any { href.contains(it, ignoreCase = true) }
                ) links.add(href)
            }
            // iframes
            doc2.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.startsWith("http") &&
                    supportedHosts.any { src.contains(it, ignoreCase = true) }
                ) links.add(src)
            }
            // Direct video files
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*)""")
                .findAll(html2).forEach { links.add(it.groupValues[1]) }
            // JS file: "url"
            Regex("""file\s*[=:]\s*["'](https?://[^"']+)["']""")
                .findAll(html2).forEach {
                    val u = it.groupValues[1]
                    if (supportedHosts.any { h -> u.contains(h, ignoreCase = true) } ||
                        u.contains(".mp4") || u.contains(".m3u8")
                    ) links.add(u)
                }
        } catch (e: Exception) {
            android.util.Log.e("9kMovies", "getUptoLinks($uptoUrl): ${e.message}")
        }
        return links.distinct()
    }

    // ─── load() ─────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = ua)
        val doc      = response.document
        val html     = response.text
        val title    = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val story    = doc.selectFirst(".video-description")?.text()
        val eps      = mutableListOf<Episode>()

        // ── DEBUG: show every <a> on the main page ────────────────────────────
        if (DEBUG) {
            val allLinks = doc.select("a[href]")
                .map { "${it.text().take(30)} → ${it.attr("href").take(80)}" }
                .take(30) // limit to first 30 so it's readable
            dbg(eps, "STEP1: Page has ${allLinks.size} anchors (showing first 30)")
            allLinks.forEachIndexed { i, l -> dbg(eps, "  [$i] $l") }
        }

        val hubUrl = findHubUrl(html, doc)
        dbg(eps, "STEP1 hubUrl = $hubUrl")

        if (hubUrl.isNullOrEmpty()) {
            dbg(eps, "STEP1 FAILED: no hub URL found — check anchor list above")
            eps.add(newEpisode(url) { this.name = "Fallback: original page" })
            return buildResponse(title, url, imageUrl, story, eps)
        }

        // ── Fetch hub page ────────────────────────────────────────────────────
        val hubResponse = try {
            app.get(hubUrl, headers = ua + mapOf("Referer" to mainUrl))
        } catch (e: Exception) {
            dbg(eps, "STEP2 FAILED fetching hub: ${e.message}")
            return buildResponse(title, url, imageUrl, story, eps)
        }
        val hubDoc  = hubResponse.document
        val hubHtml = hubResponse.text

        // ── DEBUG: show every <a> on the hub page ─────────────────────────────
        if (DEBUG) {
            val hubLinks = hubDoc.select("a[href]")
                .map { "${it.text().take(30)} → ${it.attr("href").take(80)}" }
                .take(30)
            dbg(eps, "STEP2: Hub page has ${hubLinks.size} anchors")
            hubLinks.forEachIndexed { i, l -> dbg(eps, "  [$i] $l") }
        }

        val uptoButtons = findUptoButtons(hubDoc)
        dbg(eps, "STEP2: found ${uptoButtons.size} upto button(s)")

        if (uptoButtons.isEmpty()) {
            // No upto buttons – try extracting links directly from hub page
            dbg(eps, "STEP2 WARN: no upto buttons, scanning hub page directly")

            hubDoc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith("http") &&
                    supportedHosts.any { href.contains(it, ignoreCase = true) }
                ) {
                    eps.add(newEpisode(href) { this.name = a.text().trim().ifEmpty { "Watch" } })
                }
            }
            hubDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.startsWith("http") &&
                    supportedHosts.any { src.contains(it, ignoreCase = true) }
                ) eps.add(newEpisode(src) { this.name = "Embed" })
            }
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*)""")
                .findAll(hubHtml).forEach {
                    eps.add(newEpisode(it.groupValues[1]) { this.name = "Direct" })
                }

            if (eps.none { it.data?.startsWith("debug") == false }) {
                eps.add(newEpisode(hubUrl) { this.name = "Hub page (manual)" })
            }
            return buildResponse(title, url, imageUrl, story, eps)
        }

        // ── Resolve each upto URL ─────────────────────────────────────────────
        uptoButtons.forEach { (uptoUrl, label) ->
            dbg(eps, "STEP3: resolving $label → $uptoUrl")
            val mirrors = getUptoLinks(uptoUrl)
            dbg(eps, "STEP3: got ${mirrors.size} mirror(s) for $label")

            if (mirrors.isNotEmpty()) {
                mirrors.forEach { mirrorUrl ->
                    val host = supportedHosts
                        .firstOrNull { mirrorUrl.contains(it, ignoreCase = true) }
                        ?.split(".")?.first()?.replaceFirstChar { it.uppercase() } ?: "Mirror"
                    eps.add(newEpisode(mirrorUrl) { this.name = "$label [$host]" })
                }
            } else {
                eps.add(newEpisode("upto::$uptoUrl") { this.name = "$label (lazy)" })
            }
        }

        if (eps.none { it.data?.startsWith("debug") == false && it.data?.startsWith("upto") == false }) {
            eps.add(newEpisode(url) { this.name = "Fallback" })
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

    // ─── loadLinks() ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data == "debug::noop") return true   // skip debug episodes silently

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
                    source = name,
                    name   = name,
                    url    = data,
                    type   = if (data.contains(".m3u8", ignoreCase = true))
                                 ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                })
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