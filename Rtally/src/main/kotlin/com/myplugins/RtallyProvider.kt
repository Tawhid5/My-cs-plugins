package com.myplugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class RtallyProvider : MainAPI() {
    override var mainUrl = "https://rtally.lol"
    override var name = "Rtally"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/categories/trending" to "Trending",
        "$mainUrl/categories/featured" to "Featured",
        "$mainUrl/categories/hollywood" to "Hollywood",
        "$mainUrl/categories/bengali" to "Bangla",
        "$mainUrl/categories/bollywood" to "Bollywood",
        "$mainUrl/categories/tv-shows" to "Tv Shows",
        "$mainUrl/categories/korean" to "Korean",
        "$mainUrl/genres/animation" to "Animation"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = headers).document
        val homeItems = document.select("div.grid a[href*='/post/'], article.item, div.item").mapNotNull {
            toResult(it)
        }
        return newHomePageResponse(request.name, homeItems)
    }

    private fun toResult(post: Element): SearchResponse? {
        val title = post.selectFirst("h4, h2, .title")?.text()?.trim() ?: return null
        val href = post.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val poster = post.selectFirst("img")?.attr("src") 
            ?: post.selectFirst("div[style*=background-image]")?.attr("style")?.let {
                it.substringAfter("url(", "").substringBefore(")", "").substringBefore("?")
            }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl, headers = headers).document
        return document.select("div.grid a[href*='/post/'], article.item, div.item").mapNotNull {
            toResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.font-josefn, h1[class*='font-josefn'], h1")?.text()?.trim()
            ?: doc.title().substringBefore("|").trim()
        val image = doc.selectFirst(".w-\\[200px\\] img, article img, div.poster img")?.attr("src")
        val plot = doc.selectFirst("p.mt-2, article p[class*='text-sm'], div.description")?.text()?.trim()
        val year = doc.select("div.infoDiv span:last-child, .infoDiv span").mapNotNull { it.text().trim().toIntOrNull() }.firstOrNull()

        val episodeDivs = doc.select("div[id^='episode-']")
        if (episodeDivs.isNotEmpty()) {
            val episodes = episodeDivs.mapNotNull { epDiv ->
                val epId = epDiv.attr("id")
                val epNum = Regex("\\d+").find(epId)?.value?.toIntOrNull() ?: 1

                val links = mutableListOf<String>()
                epDiv.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) links.add("Stream|$src")
                }
                epDiv.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    val actual = extractRedirectLink(href) ?: href
                    if (actual.startsWith("http") && !actual.contains(mainUrl)) {
                        links.add("${a.text().trim()}|${embedify(actual)}")
                    }
                }

                if (links.isNotEmpty()) {
                    newEpisode(links.joinToString(" ; ")) {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                    }
                } else null
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
            }
        } else {
            val links = mutableListOf<String>()

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val fullSrc = if (src.startsWith("http")) src else "$mainUrl$src"
                    links.add("Stream|$fullSrc")
                }
            }

            doc.select("section a[href*='/download/'], a[href*='/download/']").forEach { a ->
                val href = a.attr("href")
                val quality = href.substringAfterLast("/").uppercase()
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                links.add("$quality|dlpage:$fullUrl")
            }

            doc.select("a[target='_blank'][href]").forEach { a ->
                val href = a.attr("href")
                if (isValidVideoHost(href)) {
                    val label = a.text().trim().ifBlank { "Stream" }
                    links.add("$label|${embedify(href)}")
                }
            }

            val data = links.joinToString(" ; ")
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun extractRedirectLink(href: String): String? {
        if (!href.contains("/redirect?")) return null
        return try {
            val raw = href.substringAfter("link=").substringBefore("&")
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidVideoHost(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val blocked = listOf("rtally", "facebook.com", "t.me", "telegram", "google.com")
        return blocked.none { url.contains(it) }
    }

    private fun embedify(url: String): String {
        return when {
            url.contains("vidhide") && url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("playerwish") && url.contains("/d/") -> url.replace("/d/", "/e/")
            url.contains("filemoon") && url.contains("/d/") -> url.replace("/d/", "/e/")
            url.contains("filemoon") && url.contains("/download/") -> url.replace("/download/", "/e/")
            else -> url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").forEach { item ->
            val parts = item.split("|")
            val linkUrl = if (parts.size >= 2) parts[1].trim() else item.trim()

            if (linkUrl.startsWith("dlpage:")) {
                val dlPage = linkUrl.removePrefix("dlpage:")
                resolveDownloadPage(dlPage, subtitleCallback, callback)
            } else if (linkUrl.isNotEmpty()) {
                try {
                    loadExtractor(linkUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
        return true
    }

    private suspend fun resolveDownloadPage(
        dlPageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(dlPageUrl, headers = headers).document
            val links = doc.select("a[target='_blank'][href], a[href*='download'], iframe").mapNotNull {
                it.attr("src").ifBlank { it.attr("href") }
            }
            links.forEach { href ->
                if (isValidVideoHost(href)) {
                    val embedUrl = embedify(href)
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }
}
