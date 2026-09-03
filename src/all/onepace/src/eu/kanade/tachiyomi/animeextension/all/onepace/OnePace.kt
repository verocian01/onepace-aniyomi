package eu.kanade.tachiyomi.animeextension.all.onepace

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class OnePace : AnimeHttpSource() {

    override val name = "One Pace"
    override val baseUrl = "https://onepace.net"
    override val lang = "all"
    override val supportsLatest = false

    private val watchUrl = "$baseUrl/ja/watch"
    private val pixeldrainApi = "https://pixeldrain.com/api"

    override fun popularAnimeRequest(page: Int): Request = GET(watchUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val entries = document.select("h2").mapNotNull { heading ->
            val card = findArcCard(heading) ?: return@mapNotNull null
            val title = heading.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
                    ?.takeIf { it.isNotEmpty() }
                    ?: card.selectFirst("img")?.attr("src")
                description = card.select("p").joinToString("\n") { it.text() }.ifBlank { null }
                url = encodeArc(title)
            }
        }
        return AnimesPage(entries, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(watchUrl, headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val query = response.request.url.queryParameter("q")?.lowercase().orEmpty()
        val all = popularAnimeParse(response).animes
        val filtered = if (query.isBlank()) all else all.filter { it.title.lowercase().contains(query) }
        return AnimesPage(filtered, false)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET(watchUrl, headers)).awaitSuccess()
        return response.use {
            val document = it.useAsJsoup()
            val entries = document.select("h2").mapNotNull { heading ->
                val card = findArcCard(heading) ?: return@mapNotNull null
                val title = heading.text().trim()
                if (title.isEmpty() || !title.contains(query, ignoreCase = true)) return@mapNotNull null
                SAnime.create().apply {
                    this.title = title
                    thumbnail_url = card.selectFirst("img")?.absUrl("src")
                        ?.takeIf { src -> src.isNotEmpty() }
                        ?: card.selectFirst("img")?.attr("src")
                    description = card.select("p").joinToString("\n") { p -> p.text() }.ifBlank { null }
                    url = encodeArc(title)
                }
            }
            AnimesPage(entries, false)
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(watchUrl, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        return SAnime.create().apply {
            title = "One Pace"
            status = SAnime.ONGOING
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val title = decodeArc(anime.url)
        val response = client.newCall(GET(watchUrl, headers)).awaitSuccess()
        return response.use {
            val document = it.useAsJsoup()
            val heading = document.select("h2").firstOrNull { h -> h.text().equals(title, ignoreCase = true) }
            val card = heading?.let(::findArcCard)
            anime.apply {
                status = SAnime.ONGOING
                if (card != null) {
                    thumbnail_url = card.selectFirst("img")?.absUrl("src")
                        ?.takeIf { src -> src.isNotEmpty() }
                        ?: card.selectFirst("img")?.attr("src")
                    description = card.select("p").joinToString("\n") { p -> p.text() }.ifBlank { description }
                }
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(watchUrl, headers)
    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val arcTitle = decodeArc(anime.url)
        val response = client.newCall(GET(watchUrl, headers)).awaitSuccess()
        val variants = response.use {
            val document = it.useAsJsoup()
            val heading = document.select("h2").firstOrNull { h -> h.text().equals(arcTitle, ignoreCase = true) }
                ?: return emptyList()
            val card = findArcCard(heading) ?: return emptyList()
            extractVariants(card)
        }

        if (variants.isEmpty()) return emptyList()

        val primary = variants.maxWithOrNull(compareBy<Variant> { qualityValue(it.label) }.thenBy { subtitlePriority(it.label) })
            ?: return emptyList()

        val listJson = client.newCall(GET("$pixeldrainApi/list/${primary.listId}", headers)).awaitSuccess().use { it.body.string() }
        val files = parsePixeldrainFiles(listJson)

        return files.mapIndexed { index, file ->
            SEpisode.create().apply {
                name = cleanEpisodeName(file.name, index + 1)
                episode_number = (index + 1).toFloat()
                url = encodeEpisode(index, variants)
            }
        }.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(watchUrl, headers)
    override fun videoListParse(response: Response): List<Video> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val payload = decodeEpisode(episode.url) ?: return emptyList()
        val videos = mutableListOf<Video>()

        payload.variants.forEach { variant ->
            runCatching {
                val json = client.newCall(GET("$pixeldrainApi/list/${variant.listId}", headers)).awaitSuccess().use { it.body.string() }
                val files = parsePixeldrainFiles(json)
                val file = files.getOrNull(payload.index) ?: return@runCatching
                val direct = "$pixeldrainApi/file/${file.id}"
                videos += Video(
                    direct,
                    variant.label,
                    direct,
                    headers,
                )
            }
        }

        return videos
    }

    private fun findArcCard(heading: Element): Element? {
        return heading.parents().firstOrNull { parent ->
            parent.select("a[href*='pixeldrain']").isNotEmpty() &&
                parent.select("h2").count { it != heading } == 0
        } ?: heading.parent()
    }

    private fun extractVariants(card: Element): List<Variant> {
        return card.select("a[href*='pixeldrain']").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifEmpty { anchor.attr("href") }
            val listId = Regex("/(?:l|api/list)/([A-Za-z0-9_-]+)").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null

            val quality = Regex("(480p|720p|1080p|2160p)", RegexOption.IGNORE_CASE)
                .find(anchor.text())?.value ?: "Video"
            val context = anchor.parents().take(3).joinToString(" ") { it.ownText() }.trim()
            val subtitle = when {
                context.contains("日本語") -> "JP Sub"
                context.contains("英語") -> "EN Sub"
                context.contains("Spanish", true) || context.contains("スペイン", true) -> "ES Sub"
                else -> "Sub"
            }
            Variant("$subtitle - $quality", listId)
        }.distinctBy { it.listId }
    }

    private fun parsePixeldrainFiles(json: String): List<PdFile> {
        val root = JSONObject(json)
        val files = root.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val obj = files.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isNotEmpty()) add(PdFile(id, name))
            }
        }
    }

    private fun cleanEpisodeName(fileName: String, fallback: Int): String {
        val base = fileName.substringBeforeLast('.', fileName).replace('_', ' ').trim()
        return base.ifEmpty { "Episode $fallback" }
    }

    private fun qualityValue(label: String): Int = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun subtitlePriority(label: String): Int = when {
        label.contains("JP Sub", true) -> 2
        label.contains("EN Sub", true) -> 1
        else -> 0
    }

    private fun encodeArc(title: String): String = "onepace:arc:${URLEncoder.encode(title, "UTF-8")}"
    private fun decodeArc(url: String): String = URLDecoder.decode(url.substringAfter("onepace:arc:"), "UTF-8")

    private fun encodeEpisode(index: Int, variants: List<Variant>): String {
        val packed = variants.joinToString(";") {
            URLEncoder.encode(it.label, "UTF-8") + "," + URLEncoder.encode(it.listId, "UTF-8")
        }
        return "onepace:episode:$index|$packed"
    }

    private fun decodeEpisode(url: String): EpisodePayload? {
        if (!url.startsWith("onepace:episode:")) return null
        val body = url.removePrefix("onepace:episode:")
        val index = body.substringBefore('|').toIntOrNull() ?: return null
        val variants = body.substringAfter('|', "")
            .split(';')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val label = part.substringBefore(',', "")
                val id = part.substringAfter(',', "")
                if (label.isBlank() || id.isBlank()) null else Variant(
                    URLDecoder.decode(label, "UTF-8"),
                    URLDecoder.decode(id, "UTF-8"),
                )
            }
        return EpisodePayload(index, variants)
    }

    private data class Variant(val label: String, val listId: String)
    private data class PdFile(val id: String, val name: String)
    private data class EpisodePayload(val index: Int, val variants: List<Variant>)
}
