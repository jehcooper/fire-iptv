package app.sideline.tv.data

import kotlinx.coroutines.sync.withPermit

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
        val genreIds: List<String> = emptyList(), val popularity: Double = 0.0,
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
                                                genreIds = o["genre_ids"].arr()?.mapNotNull { it.s() } ?: emptyList(),
                                            )
                                        } ?: emptyList()
                                } catch (e: Exception) { emptyList() }
                            }.distinctBy { it.tmdbId }
                            name to titles
                        }
                    }.awaitAll()
                }
                val merged = try { withTrakt(result) } catch (e: Exception) { result }
                if (merged.any { it.second.isNotEmpty() }) {
                    _raw.value = merged
                    loadedAt = System.currentTimeMillis()
                    _status.value = ""
                } else _status.value = Tmdb.lastError.value ?: "Couldn't get lists from TMDB. Try Refresh in a minute."
            } catch (e: Exception) {
                _status.value = Tmdb.lastError.value ?: (e.message ?: "Couldn't reach TMDB")
            } finally { loading = false }
        }
    }

    // ---------------- Trakt's lists, mixed in ----------------

    private val tmdbTitles = java.util.concurrent.ConcurrentHashMap<String, Title>()

    /** A Trakt title filled in from TMDB (poster, score, overview), looked up once. */
    private suspend fun fill(t: Title, known: Map<String, Title>, gate: kotlinx.coroutines.sync.Semaphore): Title? {
        val k = (if (t.isTv) "tv:" else "m:") + t.tmdbId
        known[k]?.let { return it }
        tmdbTitles[k]?.let { return it }
        return gate.withPermit {
            try {
                val o = Tmdb.get(if (t.isTv) "tv/${t.tmdbId}" else "movie/${t.tmdbId}").obj() ?: return@withPermit null
                Title(
                    tmdbId = t.tmdbId, isTv = t.isTv,
                    title = o.s("title") ?: o.s("name") ?: t.title,
                    year = (o.s("release_date") ?: o.s("first_air_date") ?: t.year).take(4),
                    overview = o.s("overview") ?: "",
                    poster = img(o.s("poster_path"), "w342"), backdrop = img(o.s("backdrop_path"), "w1280"),
                    score = o.s("vote_average")?.toDoubleOrNull(),
                    genreIds = o["genres"].arr()?.mapNotNull { it.obj()?.s("id") } ?: emptyList(),
                    popularity = o.s("popularity")?.toDoubleOrNull() ?: 0.0,
                ).also { tmdbTitles[k] = it }
            } catch (e: Exception) { null }
        }
    }

    /** One from each list in turn, no repeats. */
    private fun interleave(a: List<Title>, b: List<Title>): List<Title> {
        val out = LinkedHashMap<String, Title>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out.putIfAbsent((if (it.isTv) "tv:" else "m:") + it.tmdbId, it) }
            b.getOrNull(i)?.let { out.putIfAbsent((if (it.isTv) "tv:" else "m:") + it.tmdbId, it) }
        }
        return out.values.toList()
    }

    /**
     * Adds Trakt's trending and popular titles to the matching TMDB rows, plus Trakt's "most watched this week" and
     * "most anticipated" rows. Only when a Trakt Client ID is set (no sign-in needed).
     */
    private suspend fun withTrakt(tmdb: List<Pair<String, List<Title>>>): List<Pair<String, List<Title>>> {
        if (!Trakt.hasApp) return tmdb
        val known = HashMap<String, Title>()
        tmdb.forEach { (_, l) -> l.forEach { known[(if (it.isTv) "tv:" else "m:") + it.tmdbId] = it } }
        val gate = kotlinx.coroutines.sync.Semaphore(8)
        suspend fun list(path: String, tv: Boolean): List<Title> = coroutineScope {
            Trakt.publicList(path, tv).map { t -> async { fill(t, known, gate) } }.awaitAll().filterNotNull()
        }
        val lists = coroutineScope {
            listOf(
                "tm" to async { list("/movies/trending", false) }, "ts" to async { list("/shows/trending", true) },
                "pm" to async { list("/movies/popular", false) }, "ps" to async { list("/shows/popular", true) },
                "wm" to async { list("/movies/watched/weekly", false) }, "ws" to async { list("/shows/watched/weekly", true) },
                "am" to async { list("/movies/anticipated", false) }, "as" to async { list("/shows/anticipated", true) },
            ).associate { (k, d) -> k to d.await() }
        }
        val mix = mapOf(
            "Trending movies this week" to "tm", "Trending series this week" to "ts",
            "Popular movies" to "pm", "Popular series" to "ps",
        )
        val out = tmdb.map { (name, l) -> mix[name]?.let { k -> name to interleave(l, lists[k].orEmpty()) } ?: (name to l) }.toMutableList()
        fun add(name: String, k: String, after: String?) {
            val l = lists[k].orEmpty(); if (l.isEmpty()) return
            val i = after?.let { a -> out.indexOfFirst { it.first == a } } ?: -1
            if (i >= 0) out.add(i + 1, name to l) else out.add(name to l)
        }
        add("Most watched movies this week · Trakt", "wm", "Trending movies this week")
        add("Most watched series this week · Trakt", "ws", "Trending series this week")
        add("Most anticipated movies · Trakt", "am", null)
        add("Most anticipated series · Trakt", "as", null)
        return out
    }

    // ---------------- Matching TMDB titles to your library ----------------

    private val RX_NON_ALNUM = Regex("[^a-z0-9 ]")
    private val RX_SPACES = Regex("\\s+")

    /** "The Batman (2022)", "EN - Batman, The" → "batman" */
    fun norm(s: String): String {
        var t = MovieInfoService.cleanTitle(s).first.lowercase()
        t = RX_SPACES.replace(RX_NON_ALNUM.replace(t.replace("&", " and "), " "), " ").trim()
        t = t.removePrefix("the ").removeSuffix(" the")
        return t
    }

    // The library index is built once per library (in the background) and reused, so the tab opens instantly.
    @Volatile private var cached: Triple<List<Movie>, List<Series>, Index>? = null

    /** Index for these lists; reuses the last one if the library hasn't changed. Call off the main thread. */
    fun indexFor(movies: List<Movie>, series: List<Series>): Index {
        cached?.let { (m, s, idx) -> if (m === movies && s === series) return idx }
        return Index(movies, series).also { cached = Triple(movies, series, it) }
    }

    /** Prepares the index in the background whenever the library changes, so Discover opens instantly. */
    fun startWarming(repo: Repository) {
        scope.launch {
            kotlinx.coroutines.flow.combine(repo.movies, repo.series) { m, s -> m to s }
                .collect { (m, s) ->
                    if (!Tmdb.configured || (m.isEmpty() && s.isEmpty())) return@collect
                    kotlinx.coroutines.delay(3_000)  // let start-up finish first
                    kotlinx.coroutines.withContext(Dispatchers.Default) { indexFor(m, s) }
                }
        }
    }

    /** Already-built index for these lists, if any (lets the screen show rows immediately). */
    fun cachedIndex(movies: List<Movie>, series: List<Series>): Index? =
        cached?.let { (m, s, idx) -> if (m === movies && s === series) idx else null }

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
