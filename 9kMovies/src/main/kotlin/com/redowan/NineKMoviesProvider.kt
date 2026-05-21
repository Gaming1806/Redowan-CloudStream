package com.redowan

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    // ── Scan raw HTML/JS for any video URLs ───────────────────────────────────
    private fun extractUrlsFromHtml(html: String): List<String> {
        val found = mutableListOf<String>()

        fun isVideoUrl(u: String) =
            supportedHosts.any { u.contains(it, ignoreCase = true) } ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mkv", ignoreCase = true)

        // Plain URLs
        Regex("""(https?://[^\s"'<>\[\]{}\\]+)""").findAll(html).forEach { m ->
            val u = m.groupValues[1].trimEnd(')', ';', ',', '\\', '\'', '"', '\n')
            if (isVideoUrl(u)) found.add(u)
        }
        // window.location
        Regex("""window\.location(?:\.href)?\s*[=:]\s*["'`](https?://[^"'`\s]+)""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        // var/let/const x = "url"
        Regex("""(?:var|let|const)\s+\w+\s*=\s*["'`](https?://[^"'`\s]+)""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        // data-url / data-href etc.
        Regex("""data-(?:url|href|link|src|file)\s*=\s*["'](https?://[^"']+)["']""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        // meta refresh
        Regex("""content=["'][0-9]*;\s*url=(https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)
            .forEach { found.add(it.groupValues[1]) }
        // atob() base64
        Regex("""atob\(["'`]([A-Za-z0-9+/=]{20,})["'`]\)""").findAll(html).forEach { m ->
            try {
                val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) found.add(decoded.trim())
            } catch (_: Exception) {}
        }
        // decodeURIComponent("%68%74%74...")
        Regex("""decodeURIComponent\(["'`](https?%3[Aa][^"'`\s]+)["'`]\)""").findAll(html).forEach { m ->
            try {
                val decoded = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                if (decoded.startsWith("http") && isVideoUrl(decoded)) found.add(decoded)
            } catch (_: Exception) {}
        }
        // JSON "url":"..." "link":"..."
        Regex(""""(?:url|link|file|src|href|download)"\s*:\s*"(https?://[^"]+)"""").findAll(html)
            .forEach { found.add(it.groupValues[1]) }

        return found.filter { isVideoUrl(it) }.map { it.trimEnd('/') }.distinct()
    }

private suspend fun getUptoLinks(uptoUrl: String): List<String> {
    val links = mutableListOf<String>()
    android.util.Log.d("9kMovies", "getUptoLinks: $uptoUrl")

    try {
        val fileCode = uptoUrl.trimEnd('/').substringAfterLast("/")
        val siteBase = uptoUrl.substringBefore("://") + "://" +
                       uptoUrl.substringAfter("://").substringBefore("/")

        // ── Step 1: GET the page to collect cookies + CSRF token ─────────────
        val r1 = app.get(
            uptoUrl,
            headers = ua + mapOf(
                "Referer"        to "https://indimega.com/",
                "Accept"         to "text/html,application/xhtml+xml,*/*",
                "Cache-Control"  to "no-cache"
            )
        )
        val cookies   = r1.cookies
        val html1     = r1.text
        val doc1      = r1.document

        android.util.Log.d("9kMovies", "uptobhai page title: ${doc1.title()}")

        // Extract CSRF token — uptobhai uses Laravel so it's always present
        val csrfToken =
            doc1.selectFirst("meta[name='csrf-token']")?.attr("content")
            ?: doc1.selectFirst("input[name='_token']")?.attr("value")
            ?: Regex(""""csrf-token"\s+content="([^"]+)"""").find(html1)?.groupValues?.get(1)
            ?: Regex("""_token['"]\s*:\s*['"]([^'"]+)""").find(html1)?.groupValues?.get(1)

        android.util.Log.d("9kMovies", "csrf: $csrfToken")

        // ── Step 2: Look for the unlock button and its POST target ────────────
        // uptobhai.blog unlock button usually POSTs to /unlock or same page
        // with specific hidden fields
        val unlockForm = doc1.selectFirst("form")
        val formAction = unlockForm?.attr("action")?.ifBlank { uptoUrl } ?: uptoUrl
        val formFields = mutableMapOf<String, String>()
        unlockForm?.select("input[type=hidden]")?.forEach { input ->
            val n = input.attr("name")
            val v = input.attr("value")
            if (n.isNotBlank()) formFields[n] = v
        }
        if (csrfToken != null) formFields["_token"] = csrfToken
        formFields["file_code"] = fileCode

        android.util.Log.d("9kMovies", "form action: $formAction, fields: $formFields")

        // ── Step 3: Try every known unlock endpoint pattern ───────────────────

        val unlockEndpoints = listOf(
            // Pattern A: POST to /unlock with file code
            Triple("$siteBase/unlock",         mapOf("code" to fileCode, "_token" to (csrfToken ?: "")), "form"),
            // Pattern B: POST to same /view/CODE page (Laravel form submit)
            Triple(uptoUrl,                    formFields,                                                "form"),
            // Pattern C: POST to /view/CODE with op=unlock
            Triple(uptoUrl,                    mapOf("_token" to (csrfToken ?: ""), "op" to "unlock", "id" to fileCode), "form"),
            // Pattern D: POST to /get-links
            Triple("$siteBase/get-links",      mapOf("code" to fileCode, "_token" to (csrfToken ?: "")), "form"),
            // Pattern E: AJAX JSON POST to /api/unlock or /api/links
            Triple("$siteBase/api/unlock",     mapOf("code" to fileCode),                                "json"),
            Triple("$siteBase/api/links",      mapOf("code" to fileCode),                                "json"),
            Triple("$siteBase/api/get-links",  mapOf("file_code" to fileCode),                          "json"),
        )

        for ((endpoint, data, type) in unlockEndpoints) {
            try {
                android.util.Log.d("9kMovies", "trying: $endpoint ($type)")

                val resp = if (type == "json") {
                    app.post(
                        endpoint,
                        headers = ua + mapOf(
                            "Referer"          to uptoUrl,
                            "X-CSRF-TOKEN"     to (csrfToken ?: ""),
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept"           to "application/json",
                            "Content-Type"     to "application/json"
                        ),
                        requestBody = org.json.JSONObject(data.toMap()).toString()
                            .toRequestBody("application/json".toMediaType()),
                        cookies = cookies
                    )
                } else {
                    app.post(
                        endpoint,
                        headers = ua + mapOf(
                            "Referer" to uptoUrl,
                            "Accept"  to "text/html,application/xhtml+xml,*/*"
                        ),
                        data    = data,
                        cookies = cookies
                    )
                }

                val respHtml = resp.text
                val respDoc  = resp.document
                android.util.Log.d("9kMovies", "response length: ${respHtml.length}, url: ${resp.url}")

                // ── Parse all anchor links from the response ──────────────────
                respDoc.select("a[href]").forEach { a ->
                    val href = a.attr("abs:href").trim()
                    if (href.startsWith("http") &&
                        !href.contains("uptobhai", ignoreCase = true) &&
                        !href.contains("uptomega", ignoreCase = true)
                    ) {
                        android.util.Log.d("9kMovies", "found link: $href")
                        links.add(href)
                    }
                }

                // ── Parse JSON response if present ────────────────────────────
                try {
                    val json = org.json.JSONObject(respHtml)
                    listOf("url","link","file","download","src","href","redirect","links").forEach { key ->
                        val v = json.optString(key, "")
                        if (v.startsWith("http")) links.add(v)
                        // handle array of links
                        val arr = json.optJSONArray(key)
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val item = arr.optString(i, "")
                                if (item.startsWith("http")) links.add(item)
                            }
                        }
                    }
                } catch (_: Exception) {}

                // ── Scan raw HTML for video URLs ──────────────────────────────
                links.addAll(extractUrlsFromHtml(respHtml))

                if (links.isNotEmpty()) {
                    android.util.Log.d("9kMovies", "✅ found ${links.size} links at $endpoint")
                    return links.distinct()
                }

            } catch (e: Exception) {
                android.util.Log.e("9kMovies", "endpoint $endpoint failed: ${e.message}")
            }
        }

        // ── Step 4: Final fallback — re-fetch page after a short delay ────────
        // Some sites use JS timers (wait 5s, then button appears).
        // We simulate this by waiting and re-fetching.
        kotlinx.coroutines.delay(3000)
        val r2   = app.get(uptoUrl, headers = ua + mapOf("Referer" to "https://indimega.com/"), cookies = cookies)
        val doc2 = r2.document
        val html2 = r2.text

        doc2.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").trim()
            if (href.startsWith("http") &&
                !href.contains("uptobhai", ignoreCase = true) &&
                !href.contains("uptomega", ignoreCase = true)) {
                links.add(href)
            }
        }
        links.addAll(extractUrlsFromHtml(html2))

    } catch (e: Exception) {
        android.util.Log.e("9kMovies", "getUptoLinks error: ${e.message}")
    }

    android.util.Log.d("9kMovies", "getUptoLinks result: $links")
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

        // Confirmed class from debug: "buttn direct"
        val uptoButtons = hubDoc.select("a.buttn.direct, a.buttn, a[href*='upto']")
            .filter { it.attr("href").contains("upto", ignoreCase = true) }
            .ifEmpty {
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
                    // Store upto URL — user can at least "Play in Browser" on uptobhai page
                    eps.add(newEpisode("upto::$uptoUrl") { this.name = label })
                }
            }
        } else {
            // Fallback: scan hub page directly
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
