package app.sideline.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Netflix-style Discover: what's trending, popular, new and top-rated (from TMDB), matched against your own
 * movies and series so you can press Play. Lists refresh at most every 12 hours.
 */
object Discover {
    data class Title(
        val tmdbId: String, val isTv: Boolean, val title: String, val year: String, val overview: String,
        val poster: String?, val backdrop: String?, val score: Double?,
    )

    /** A TMDB title, plus the matching item in your library (if any). */
    data class Item(val t: Title, val movie: Movie? = null, val series: Series? = null) {
        val playable get() = movie != null || series != null
    }

    data class Row(val title: String, val items: List<Item>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_AGE = 12 * 3600_000L

    private val _raw = MutableStateFlow<List<Pair<String, List<Title>>>>(emptyList())
    /** TMDB lists by row title (before matching to your library). */
    val raw: StateFlow<List<Pair<String, List<Title>>>> = _raw
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    @Volatile private var loadedAt = 0L
    @Volatile private var loading = false

    private fun img(path: String?, size: String) = path?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun load(force: Boolean = false) {
        if (!Tmdb.configured || loading) return
        if (!force && _raw.value.isNotEmpty() && System.currentTimeMillis() - loadedAt < MAX_AGE) return
        loading = true
        scope.launch {
            try {
                _status.value = "Updating what's trending…"
                val since = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(System.currentTimeMillis() - 150L * 24 * 3600_000))
                val lists = listOf(
                    Triple("Trending movies this week", "trending/movie/week", emptyMap<String, String>()),
                    Triple("Trending series this week", "trending/tv/week", emptyMap()),
                    Triple("New movie releases", "discover/movie", mapOf("sort_by" to "popularity.desc", "primary_release_date.gte" to since, "with_release_type" to "4|5|6")),
                    Triple("Popular movies", "movie/popular", emptyMap()),
                    Triple("Popular series", "tv/popular", emptyMap()),
                    Triple("Top-rated movies", "movie/top_rated", emptyMap()),
                    Triple("Top-rated series", "tv/top_rated", emptyMap()),
                )
                val result = coroutineScope {
                    lists.map { (name, path, params) ->
                        async {
                            val tv = path.contains("tv")
                            val titles = (1..3).flatMap { page ->
                                try {
                                    Tmdb.get(path, params + ("page" to page.toString()))?.get("results").arr()
                                        ?.mapNotNull { it.obj() }?.mapNotNull { o ->
                                            Title(
                                                tmdbId = o.s("id") ?: return@mapNotNull null,
                                                isTv = tv || o.s("media_type") == "tv",
                                                title = o.s("title") ?: o.s("name") ?: return@mapNotNull null,
                                                year = (o.s("release_date") ?: o.s("first_air_date") ?: "").take(4),
                                                overview = o.s("overview") ?: "",
                                                poster = img(o.s("poster_path"), "w342"),
                                                backdrop = img(o.s("backdrop_path"), "w1280"),
                                                score = o.s("vote_average")?.toDoubleOrNull(),
                                            )
                                        } ?: emptyList()
                                } catch (e: Exception) { emptyList() }
                            }.distinctBy { it.tmdbId }
                            name to titles
                        }
                    }.awaitAll()
                }
                if (result.any { it.second.isNotEmpty() }) {
                    _raw.value = result
                    loadedAt = System.currentTimeMillis()
                    _status.value = ""
                } else _status.value = "Couldn't reach TMDB. Check the TMDB key in Settings."
            } finally { loading = false }
        }
    }

    // ---------------- Matching TMDB titles to your library ----------------

    /** "The Batman (2022)", "EN - Batman, The" → "batman" */
    fun norm(s: String): String {
        var t = MovieInfoService.cleanTitle(s).first.lowercase()
        t = t.replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("the ").removeSuffix(" the")
        return t
    }

    class Index(movies: List<Movie>, series: List<Series>) {
        private val m: Map<String, List<Movie>> = movies.groupBy { norm(it.name) }
        private val s: Map<String, Series> = series.associateBy { norm(it.name) }
        fun movie(t: Title): Movie? {
            val cands = m[norm(t.title)] ?: return null
            val y = t.year.toIntOrNull() ?: return cands.first()
            // Same title, release year within a year (providers' years are often off by one).
            return cands.firstOrNull { it.year == null || kotlin.math.abs((it.year) - y) <= 1 }
        }
        fun series(t: Title): Series? = s[norm(t.title)]
    }

    /** Rows for the screen; [onlyPlayable] hides titles that aren't in your library. */
    fun rows(raw: List<Pair<String, List<Title>>>, index: Index, onlyPlayable: Boolean): List<Row> =
        raw.map { (name, titles) ->
            Row(name, titles.map { t ->
                if (t.isTv) Item(t, series = index.series(t)) else Item(t, movie = index.movie(t))
            }.filter { !onlyPlayable || it.playable })
        }.filter { it.items.isNotEmpty() }
}
