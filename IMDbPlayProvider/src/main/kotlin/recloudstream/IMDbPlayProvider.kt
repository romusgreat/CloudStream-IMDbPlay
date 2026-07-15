package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.newExtractorLink

class IMDbPlayProvider : MainAPI() {
    override var name = "IMDbPlay"
    override var mainUrl = "https://www.imdb.com"
    override var lang = "ar"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    data class ImdbSuggestResponse(
        @JsonProperty("d") val d: List<ImdbSuggestItem>? = null
    )

    data class ImdbSuggestItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("l") val l: String? = null,
        @JsonProperty("q") val q: String? = null,
        @JsonProperty("qid") val qid: String? = null,
        @JsonProperty("i") val i: List<Any>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.startsWith("tt") && query.length >= 7) {
            val idUrl = "https://sg.media-imdb.com/suggests/t/$query.json"
            return try {
                val response = app.get(idUrl).text
                val jsonStart = response.indexOf("(") + 1
                val jsonEnd = response.lastIndexOf(")")
                if (jsonStart > 0 && jsonEnd > jsonStart) {
                    val rawJson = response.substring(jsonStart, jsonEnd)
                    val data = tryParseJson<ImdbSuggestResponse>(rawJson)
                    val item = data?.d?.firstOrNull()
                    if (item != null) {
                        val title = item.l ?: "IMDb ID: $query"
                        val isTv = item.qid == "tvSeries" || item.qid == "tvMiniSeries" || item.q == "tvSeries" || item.q == "tvMiniSeries" || item.q?.contains("TV", ignoreCase = true) == true
                        val poster = item.i?.getOrNull(0)?.toString() ?: ""
                        val type = if (isTv) TvType.TvSeries else TvType.Movie
                        if (isTv) {
                            listOf(newTvSeriesSearchResponse(title, query, type) { this.posterUrl = poster })
                        } else {
                            listOf(newMovieSearchResponse(title, query, type) { this.posterUrl = poster })
                        }
                    } else {
                        listOf(newMovieSearchResponse("IMDb ID: $query", query, TvType.Movie))
                    }
                } else {
                    listOf(newMovieSearchResponse("IMDb ID: $query", query, TvType.Movie))
                }
            } catch (e: Exception) {
                listOf(newMovieSearchResponse("IMDb ID: $query", query, TvType.Movie))
            }
        }

        val firstLetter = query.take(1).lowercase()
        val encodedQuery = query.encodeUri()
        val url = "https://sg.media-imdb.com/suggests/$firstLetter/$encodedQuery.json"
        
        val response = app.get(url).text
        val jsonStart = response.indexOf("(") + 1
        val jsonEnd = response.lastIndexOf(")")
        if (jsonStart <= 0 || jsonEnd <= jsonStart) return emptyList()
        val rawJson = response.substring(jsonStart, jsonEnd)
        
        val data = tryParseJson<ImdbSuggestResponse>(rawJson)
        return data?.d?.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.l ?: return@mapNotNull null
            val isTv = item.qid == "tvSeries" || item.qid == "tvMiniSeries" || item.q == "tvSeries" || item.q == "tvMiniSeries" || item.q?.contains("TV", ignoreCase = true) == true
            val type = if (isTv) TvType.TvSeries else TvType.Movie
            val poster = item.i?.getOrNull(0)?.toString() ?: ""
            
            if (isTv) {
                newTvSeriesSearchResponse(title, id, type) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, id, type) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val imdbId = url
        
        // 1. Fetch details from the Suggest API (fast and 100% reliable, no bot protection)
        val suggestUrl = "https://sg.media-imdb.com/suggests/t/$imdbId.json"
        var title = "Unknown Title"
        var poster = ""
        var isTv = false
        
        try {
            val response = app.get(suggestUrl).text
            val jsonStart = response.indexOf("(") + 1
            val jsonEnd = response.lastIndexOf(")")
            if (jsonStart > 0 && jsonEnd > jsonStart) {
                val rawJson = response.substring(jsonStart, jsonEnd)
                val data = tryParseJson<ImdbSuggestResponse>(rawJson)
                val item = data?.d?.firstOrNull()
                if (item != null) {
                    title = item.l ?: "Unknown Title"
                    poster = item.i?.getOrNull(0)?.toString() ?: ""
                    isTv = item.qid == "tvSeries" || item.qid == "tvMiniSeries" || item.q == "tvSeries" || item.q == "tvMiniSeries" || item.q?.contains("TV", ignoreCase = true) == true
                }
            }
        } catch (e: Exception) {
            // ignore and fallback
        }
        
        // 2. Fetch plot description from the HTML page (wrapped in try-catch)
        var description = "No Plot Found"
        try {
            val docUrl = "https://www.imdb.com/title/$imdbId/"
            val document = app.get(docUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val scrapedDesc = document.select("meta[property=og:description]").attr("content")
            if (scrapedDesc.isNotEmpty()) {
                description = scrapedDesc
            }
        } catch (e: Exception) {
            // ignore
        }
        
        return if (isTv) {
            val episodesList = mutableListOf<Episode>()
            for (season in 1..5) {
                for (ep in 1..24) {
                    episodesList.add(
                        newEpisode("$imdbId|$season|$ep") {
                            this.name = "Season $season Episode $ep"
                            this.season = season
                            this.episode = ep
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, imdbId, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, imdbId, TvType.Movie, imdbId) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val imdbId = parts[0]
        
        val embedUrl = if (parts.size > 1) {
            val season = parts[1].toIntOrNull() ?: 1
            val episode = parts[2].toIntOrNull() ?: 1
            "https://proxy.garageband.rocks/embed/tv/$imdbId?season=$season&episode=$episode&autonext=1"
        } else {
            "https://proxy.garageband.rocks/embed/movie/$imdbId"
        }

        val response = app.get(embedUrl, headers = mapOf("Referer" to "https://www.imdb.com/")).document
        val iframeSrc = response.select("iframe#player_iframe").attr("src")
        
        if (iframeSrc.isNotEmpty()) {
            val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            
            val playerDoc = app.get(
                fullIframeUrl, 
                headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )
            ).text
            
            val m3u8Regex = """file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
            val match = m3u8Regex.find(playerDoc)
            val streamUrl = match?.groups?.get(1)?.value
            
            if (streamUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        "GarageBand CDN",
                        "GarageBand CDN",
                        streamUrl
                    ) {
                        this.referer = "https://cloudorchestranova.com/"
                        this.type = ExtractorLinkType.M3U8
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }
        return false
    }
}
