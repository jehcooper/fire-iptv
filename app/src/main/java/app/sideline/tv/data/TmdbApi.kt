package app.sideline.tv.data

import kotlinx.serialization.json.JsonObject

/**
 * Everything the title pages, search and "More like this" need from TMDB:
 * full details (cast, trailers, recommendations, seasons), episode lists, people, genres and keywords.
 * Results are cached in memory for the session.
 */
object TmdbApi {
    data class Person(val id: String, val name: String, val role: String, val photo: String?)
    data class Video(val key: String, val name: String, val type: String, val official: Boolean)
    data class SeasonInfo(val number: Int, val name: String, val episodes: Int, val poster: String?, val year: String)
    data class Details(
        val id: String, val isTv: Boolean, val title: String, val year: String, val overview: String, val tagline: String,
        val genres: List<Pair<String, String>>, val runtime: Int?, val score: Double?, val poster: String?, val backdrop: String?,
        val certification: String, val cast: List<Person>, val crew: String, val trailers: List<Video>,
        val more: List<Discover.Title>, val seasons: List<SeasonInfo>, val status: String, val network: String,
    ) {
        fun asTitle() = Discover.Title(id, isTv, title, year, overview, poster, backdrop, score)
    }
    data class Ep(val season: Int, val number: Int, val name: String, val overview: String, val still: String?, val air: String,
                  val runtime: Int?, val score: Double?)
    data class Genre(val id: String, val name: String, val isTv: Boolean)
    data class PersonHit(val id: String, val name: String, val photo: String?, val knownFor: String, val dept: String)
    data class Keyword(val id: String, val name: String)

    private val details = java.util.concurrent.ConcurrentHashMap<String, Details>()
    private val seasons = java.util.concurrent.ConcurrentHashMap<String, List<Ep>>()
    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var genreCache: List<Genre>? = null

    fun img(path: String?, size: String) = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun title(o: JsonObject, forceTv: Boolean? = null): Discover.Title? {
        val type = o.s("media_type")
        if (type == "person") return null
        val tv = forceTv ?: (type == "tv" || (o.s("name") != null && o.s("title") == null))
        return Discover.Title(
            tmdbId = o.s("id") ?: return null, isTv = tv,
            title = o.s("title") ?: o.s("name") ?: return null,
            year = (o.s("release_date") ?: o.s("first_air_date") ?: "").take(4),
            overview = o.s("overview") ?: "",
            poster = img(o.s("poster_path"), "w342"), backdrop = img(o.s("backdrop_path"), "w1280"),
            score = o.s("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 },
            genreIds = o["genre_ids"].arr()?.mapNotNull { it.s() } ?: emptyList(),
            popularity = o.s("popularity")?.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun pick(byCountry: Map<String, String>): String =
        byCountry["US"]?.takeIf { it.isNotBlank() }
            ?: byCountry["CA"]?.takeIf { it.isNotBlank() }
            ?: byCountry.values.firstOrNull { it.isNotBlank() } ?: ""

    /** Full details for one movie or show (one request). Null if TMDB can't be reached. */
    suspend fun details(id: String, isTv: Boolean): Details? {
        val ck = (if (isTv) "tv:" else "movie:") + id
        details[ck]?.let { return it }
        return try {
            val extra = if (isTv) "credits,videos,recommendations,similar,content_ratings,aggregate_credits"
                else "credits,videos,recommendations,similar,release_dates"
            val o = Tmdb.get((if (isTv) "tv/" else "movie/") + id, mapOf("append_to_response" to extra, "include_video_language" to "en,null"))
                ?: return null
            val cert = if (isTv) pick(o["content_ratings"].obj()?.get("results").arr()?.mapNotNull { it.obj() }
                    ?.associate { (it.s("iso_3166_1") ?: "") to (it.s("rating") ?: "") } ?: emptyMap())
                else pick(o["release_dates"].obj()?.get("results").arr()?.mapNotNull { it.obj() }?.associate { c ->
                    (c.s("iso_3166_1") ?: "") to (c["release_dates"].arr()?.mapNotNull { it.obj()?.s("certification") }?.firstOrNull { it.isNotBlank() } ?: "")
                } ?: emptyMap())
            val castJson = (o["aggregate_credits"].obj() ?: o["credits"].obj())?.get("cast").arr()?.mapNotNull { it.obj() } ?: emptyList()
            val cast = castJson.take(20).mapNotNull { c ->
                Person(c.s("id") ?: return@mapNotNull null, c.s("name") ?: return@mapNotNull null,
                    c.s("character") ?: c["roles"].arr()?.firstOrNull()?.obj()?.s("character") ?: "", img(c.s("profile_path"), "w185"))
            }
            val crewJson = o["credits"].obj()?.get("crew").arr()?.mapNotNull { it.obj() } ?: emptyList()
            val crew = if (isTv) o["created_by"].arr()?.mapNotNull { it.obj()?.s("name") }?.takeIf { it.isNotEmpty() }?.let { "Created by " + it.joinToString(", ") } ?: ""
                else crewJson.filter { it.s("job") == "Director" }.mapNotNull { it.s("name") }.takeIf { it.isNotEmpty() }?.let { "Directed by " + it.joinToString(", ") } ?: ""
            val videos = o["videos"].obj()?.get("results").arr()?.mapNotNull { it.obj() }
                ?.filter { it.s("site") == "YouTube" && it.s("key") != null }
                ?.map { Video(it.s("key")!!, it.s("name") ?: "Trailer", it.s("type") ?: "", it.s("official") == "true") }
                ?.sortedWith(compareBy({ it.type != "Trailer" }, { !it.official }, { it.type != "Teaser" })) ?: emptyList()
            val recs = o["recommendations"].obj()?.get("results").arr()?.mapNotNull { it.obj()?.let { j -> title(j, isTv) } } ?: emptyList()
            val sim = o["similar"].obj()?.get("results").arr()?.mapNotNull { it.obj()?.let { j -> title(j, isTv) } } ?: emptyList()
            val runtime = if (isTv) o["episode_run_time"].arr()?.firstOrNull()?.s()?.toIntOrNull() else o.s("runtime")?.toIntOrNull()
            Details(
                id = id, isTv = isTv,
                title = o.s("title") ?: o.s("name") ?: "",
                year = (o.s("release_date") ?: o.s("first_air_date") ?: "").take(4),
                overview = o.s("overview") ?: "", tagline = o.s("tagline") ?: "",
                genres = o["genres"].arr()?.mapNotNull { g -> g.obj()?.let { (it.s("id") ?: "") to (it.s("name") ?: "") } } ?: emptyList(),
                runtime = runtime?.takeIf { it > 0 },
                score = o.s("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 },
                poster = img(o.s("poster_path"), "w500"), backdrop = img(o.s("backdrop_path"), "w1280"),
                certification = cert, cast = cast, crew = crew, trailers = videos,
                more = (recs + sim).distinctBy { it.tmdbId }.filter { it.tmdbId != id }.take(30),
                seasons = o["seasons"].arr()?.mapNotNull { it.obj() }?.mapNotNull { s ->
                    SeasonInfo(s.s("season_number")?.toIntOrNull() ?: return@mapNotNull null, s.s("name") ?: "",
                        s.s("episode_count")?.toIntOrNull() ?: 0, img(s.s("poster_path"), "w342"), (s.s("air_date") ?: "").take(4))
                }?.filter { it.episodes > 0 } ?: emptyList(),
                status = o.s("status") ?: "",
                network = o["networks"].arr()?.firstOrNull()?.obj()?.s("name") ?: "",
            ).also { details[ck] = it }
        } catch (e: Exception) { null }
    }

    /** Episode list of one season (names, descriptions, stills, air dates). */
    suspend fun season(tvId: String, n: Int): List<Ep>? {
        val ck = "$tvId:$n"
        seasons[ck]?.let { return it }
        return try {
            val o = Tmdb.get("tv/$tvId/season/$n") ?: return null
            o["episodes"].arr()?.mapNotNull { it.obj() }?.mapNotNull { e ->
                Ep(n, e.s("episode_number")?.toIntOrNull() ?: return@mapNotNull null, e.s("name") ?: "", e.s("overview") ?: "",
                    img(e.s("still_path"), "w300"), e.s("air_date") ?: "", e.s("runtime")?.toIntOrNull(), e.s("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 })
            }?.also { seasons[ck] = it }
        } catch (e: Exception) { null }
    }

    /** TMDB id for a title in your library (by cleaned name and year). Null if not found. */
    suspend fun findId(name: String, year: String?, isTv: Boolean): String? {
        val ck = "${if (isTv) "tv" else "m"}|$name|$year"
        ids[ck]?.let { return it.ifBlank { null } }
        return try {
            fun q(withYear: Boolean): Map<String, String> = buildMap {
                put("query", name)
                if (withYear && !year.isNullOrBlank()) put(if (isTv) "first_air_date_year" else "year", year)
            }
            val path = if (isTv) "search/tv" else "search/movie"
            val hits = Tmdb.get(path, q(true))?.get("results").arr()?.mapNotNull { it.obj() }.orEmpty()
                .ifEmpty { if (!year.isNullOrBlank()) Tmdb.get(path, q(false))?.get("results").arr()?.mapNotNull { it.obj() }.orEmpty() else emptyList() }
            val want = Discover.norm(name)
            val best = hits.firstOrNull { Discover.norm(it.s("title") ?: it.s("name") ?: "") == want } ?: hits.firstOrNull()
            (best?.s("id") ?: "").also { ids[ck] = it }.ifBlank { null }
        } catch (e: Exception) { null }
    }

    /** Titles and people matching what you typed. */
    suspend fun search(query: String): Pair<List<Discover.Title>, List<PersonHit>> {
        val results = (1..2).flatMap { p ->
            try { Tmdb.get("search/multi", mapOf("query" to query, "page" to "$p", "include_adult" to "false"))?.get("results").arr()?.mapNotNull { it.obj() }.orEmpty() }
            catch (e: Exception) { if (p == 1) throw e else emptyList() }
        }
        val titles = results.mapNotNull { title(it) }.distinctBy { (if (it.isTv) "t" else "m") + it.tmdbId }
        val people = results.filter { it.s("media_type") == "person" }.mapNotNull { p ->
            PersonHit(p.s("id") ?: return@mapNotNull null, p.s("name") ?: return@mapNotNull null, img(p.s("profile_path"), "w185"),
                p["known_for"].arr()?.mapNotNull { it.obj()?.let { k -> k.s("title") ?: k.s("name") } }?.take(3)?.joinToString(", ") ?: "",
                p.s("known_for_department") ?: "")
        }
        return titles to people
    }

    suspend fun keywords(query: String): List<Keyword> = try {
        Tmdb.get("search/keyword", mapOf("query" to query))?.get("results").arr()?.mapNotNull { it.obj() }
            ?.mapNotNull { Keyword(it.s("id") ?: return@mapNotNull null, it.s("name") ?: return@mapNotNull null) }?.take(8) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    /** Everything a person acted in or made, most popular first. */
    suspend fun personCredits(personId: String): List<Discover.Title> = try {
        val o = Tmdb.get("person/$personId/combined_credits")
        val all = (o?.get("cast").arr().orEmpty() + o?.get("crew").arr().orEmpty()).mapNotNull { it.obj()?.let { j -> title(j) } }
        all.distinctBy { (if (it.isTv) "t" else "m") + it.tmdbId }.sortedByDescending { it.popularity }
    } catch (e: Exception) { emptyList() }

    /** Movie and TV genres (names from TMDB). */
    suspend fun genres(): List<Genre> {
        genreCache?.let { return it }
        return try {
            val m = Tmdb.get("genre/movie/list")?.get("genres").arr()?.mapNotNull { it.obj() }
                ?.mapNotNull { Genre(it.s("id") ?: return@mapNotNull null, it.s("name") ?: "", false) }.orEmpty()
            val t = Tmdb.get("genre/tv/list")?.get("genres").arr()?.mapNotNull { it.obj() }
                ?.mapNotNull { Genre(it.s("id") ?: return@mapNotNull null, it.s("name") ?: "", true) }.orEmpty()
            (m + t).also { if (it.isNotEmpty()) genreCache = it }
        } catch (e: Exception) { emptyList() }
    }

    /** Genre names for a title's genre ids (empty until the genre list has loaded). */
    fun genreNames(t: Discover.Title): List<String> {
        val g = genreCache ?: return emptyList()
        return t.genreIds.mapNotNull { id -> g.firstOrNull { it.id == id && it.isTv == t.isTv }?.name ?: g.firstOrNull { it.id == id }?.name }
    }

    /** Popular titles in a genre or with a keyword (several pages). */
    suspend fun discover(isTv: Boolean, genreId: String? = null, keywordId: String? = null, pages: Int = 3): List<Discover.Title> =
        (1..pages).flatMap { p ->
            try {
                val params = buildMap {
                    put("sort_by", "popularity.desc"); put("page", "$p"); put("vote_count.gte", "20")
                    genreId?.let { put("with_genres", it) }
                    keywordId?.let { put("with_keywords", it) }
                }
                Tmdb.get(if (isTv) "discover/tv" else "discover/movie", params)?.get("results").arr()?.mapNotNull { it.obj()?.let { j -> title(j, isTv) } }.orEmpty()
            } catch (e: Exception) { emptyList() }
        }.distinctBy { it.tmdbId }
}
