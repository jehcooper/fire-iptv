package app.sideline.tv.data

import app.sideline.tv.SidelineApp
import java.net.URLEncoder

/**
 * Movie details for the info screen and pause screen.
 * 1) The IPTV provider's own details (usually plot, cast, genre, rating).
 * 2) If the provider has no real description, Wikipedia's summary of the film (free, no key).
 */
object MovieInfoService {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, MovieInfo>()

    suspend fun get(m: Movie): MovieInfo {
        cache[m.key]?.let { return it }
        val provider = SidelineApp.repo.client(m.accountId)?.movieInfo(m)
        val (cleanTitle, yearHint) = cleanTitle(m.name)
        var info = provider ?: MovieInfo(title = cleanTitle, poster = m.poster)
        if (info.title.isBlank() || info.title == m.name) info = info.copy(title = cleanTitle)
        if (info.year.isBlank() && yearHint != null) info = info.copy(year = yearHint)

        // TMDB (if you've added a key): audience rating, and a description when the provider has none.
        if (info.certification.isBlank() || info.plot.length < 40) {
            Tmdb.movie(info.title.ifBlank { cleanTitle }, info.year.ifBlank { yearHint })?.let { t ->
                if (info.certification.isBlank() && !t.certification.isNullOrBlank()) info = info.copy(certification = t.certification)
                if (info.plot.length < 40 && !t.overview.isNullOrBlank()) info = info.copy(plot = t.overview, plotSource = "Description from TMDB")
                if (info.backdrop == null && t.backdrop != null) info = info.copy(backdrop = t.backdrop)
            }
        }
        if (info.plot.length < 40) {
            val wiki = Wikipedia.filmSummary(cleanTitle, info.year.ifBlank { yearHint })
            if (wiki != null) {
                info = info.copy(
                    plot = wiki.extract,
                    backdrop = info.backdrop ?: wiki.image,
                    plotSource = "Description from Wikipedia",
                )
            }
        }
        cache[m.key] = info
        return info
    }

    // Built once: compiling these for every title made big libraries slow (and froze Discover).
    private val RX_PREFIX = Regex("^\\s*[|\\[]?[A-Za-z]{2,3}[|\\]]?\\s*[-:|]\\s*")
    private val RX_BRACKETS = Regex("\\[.*?]")
    private val RX_YEAR_PARENS = Regex("\\((19|20)\\d{2}\\)")
    private val RX_YEAR_END = Regex("\\b(19|20)\\d{2}\\s*$")
    private val RX_QUALITY = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|x265|x264|1080p|720p|2160p|multi|sub|dub|vostfr|cam|ts)\\b")
    private val RX_SPACES = Regex("\\s+")

    /** "EN - The Batman (2022) [4K]" → ("The Batman", "2022") */
    fun cleanTitle(raw: String): Pair<String, String?> {
        var t = RX_PREFIX.replace(raw, "")
        t = RX_BRACKETS.replace(t, " ")
        var year = RX_YEAR_PARENS.find(t)?.value?.trim('(', ')')
        t = RX_YEAR_PARENS.replace(t, " ")
        if (year == null) {
            val m = RX_YEAR_END.find(t.trim())
            if (m != null) { year = m.value.trim(); t = t.trim().removeSuffix(m.value).trimEnd() }
        }
        t = RX_QUALITY.replace(t, " ")
        t = RX_SPACES.replace(t, " ").trim(' ', '-', '|', ':')
        return t to year
    }
}

object Wikipedia {
    data class Summary(val title: String, val extract: String, val image: String?)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun filmSummary(title: String, year: String?): Summary? = try {
        val q = listOfNotNull(title, year, "film").joinToString(" ")
        val search = Net.getJson("https://en.wikipedia.org/w/rest.php/v1/search/page?q=${enc(q)}&limit=5").obj()
        val pages = search?.get("pages").arr()?.mapNotNull { it.obj() } ?: emptyList()
        // Prefer a page Wikipedia itself describes as a film (from the right year, if we know it).
        val best = pages.firstOrNull { p ->
            val d = p.s("description")?.lowercase() ?: ""
            d.contains("film") && (year == null || d.contains(year))
        } ?: pages.firstOrNull { (it.s("description")?.lowercase() ?: "").contains("film") }
        val key = best?.s("key")
        if (key == null) null else {
            val sum = Net.getJson("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(key)}").obj()
            val extract = sum?.s("extract")
            if (extract.isNullOrBlank()) null else Summary(
                title = sum?.s("title") ?: title,
                extract = extract,
                image = sum?.get("originalimage").obj()?.s("source") ?: sum?.get("thumbnail").obj()?.s("source"),
            )
        }
    } catch (e: Exception) { null }
}
