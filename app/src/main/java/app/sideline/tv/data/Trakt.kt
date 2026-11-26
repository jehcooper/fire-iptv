package app.sideline.tv.data

import app.sideline.tv.SidelineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Trakt (trakt.tv):
 * - sign-in with Trakt's TV code (trakt.tv/activate). Needs only your app's Client ID (new Trakt apps have no
 *   Client Secret; an old app's secret is still used if you entered one);
 * - scrobbling ("watching now", watched from ~80%) and playback progress, for every title unless you leave it out
 *   (or only chosen titles, if you switch "sync everything" off);
 * - watched progress, ratings, your watchlist both ways, resume points from other devices,
 *   Up Next, and a pop-up when a new episode of a show you follow reaches your library;
 * - Trakt's public lists (trending, popular, most watched, anticipated) for Discover.
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private val AUTH_HOSTS = listOf("https://api.trakt.tv", "https://auth.trakt.tv")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val JSON = "application/json".toMediaType()
    private val prefs get() = SidelineApp.prefs
    private val repo get() = SidelineApp.repo

    val hasApp get() = prefs.traktClientId.value.isNotBlank()
    val signedIn get() = prefs.traktToken.value.isNotBlank()
    private val secret get() = prefs.traktSecret.value.trim()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private fun builder(url: String) = Request.Builder().url(url)
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", prefs.traktClientId.value.trim())

    private suspend fun call(method: String, path: String, body: JsonElement? = null, auth: Boolean = true): JsonElement? = withContext(Dispatchers.IO) {
        if (!hasApp) return@withContext null
        if (auth) ensureFresh()
        val b = builder(API + path)
        if (auth && prefs.traktToken.value.isNotBlank()) b.header("Authorization", "Bearer ${prefs.traktToken.value}")
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, (body?.toString() ?: "{}").toRequestBody(JSON))
        }
        Net.http.newCall(b.build()).execute().use { r ->
            if (r.code == 401 && auth) { _status.value = "Trakt sign-in expired. Sign in again in Settings → Trakt."; return@withContext null }
            if (!r.isSuccessful) { _status.value = "Trakt answered ${r.code}"; return@withContext null }
            val t = r.body?.string().orEmpty()
            if (t.isBlank()) null else Net.json.parseToJsonElement(t)
        }
    }

    // ---------------- Sign-in (device code) ----------------

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int, val host: String)

    private class Resp(val code: Int, val body: String)

    private fun post(host: String, path: String, body: JsonObject): Resp =
        Net.http.newCall(builder(host + path).post(body.toString().toRequestBody(JSON)).build()).execute()
            .use { Resp(it.code, it.body?.string().orEmpty()) }

    /** Hosts to try for sign-in calls: the one that worked last, then the other. */
    private fun hosts() = listOf(prefs.traktAuthHost) + AUTH_HOSTS.filter { it != prefs.traktAuthHost }

    suspend fun startSignIn(): DeviceCode? = withContext(Dispatchers.IO) {
        if (!hasApp) { _status.value = "Add your Trakt Client ID first."; return@withContext null }
        var problem = "Couldn't reach Trakt"
        for (h in hosts()) {
            val r = try { post(h, "/oauth/device/code", buildJsonObject { put("client_id", prefs.traktClientId.value.trim()) }) }
                catch (e: Exception) { problem = "Couldn't reach Trakt (${e.message})"; null } ?: continue
            if (r.code == 200) {
                val o = try { Net.json.parseToJsonElement(r.body).obj() } catch (e: Exception) { null } ?: continue
                val dc = o.s("device_code") ?: continue
                prefs.traktAuthHost = h
                _status.value = ""
                return@withContext DeviceCode(dc, o.s("user_code") ?: "", o.s("verification_url") ?: "https://trakt.tv/activate",
                    o.i("interval") ?: 5, o.i("expires_in") ?: 600, h)
            }
            problem = when (r.code) {
                401, 403 -> "Trakt didn't accept the Client ID (${r.code}). Check it was copied exactly, with nothing extra."
                else -> "Trakt answered ${r.code}"
            }
        }
        _status.value = problem
        null
    }

    /** Polls until you've entered the code (true), it expired or was refused (false). */
    suspend fun waitForSignIn(dc: DeviceCode): Boolean {
        val end = System.currentTimeMillis() + dc.expiresIn * 1000L
        var interval = dc.interval.coerceAtLeast(1)
        while (System.currentTimeMillis() < end) {
            delay(interval * 1000L)
            val r = withContext(Dispatchers.IO) {
                try {
                    post(dc.host, "/oauth/device/token", buildJsonObject {
                        put("code", dc.deviceCode); put("client_id", prefs.traktClientId.value.trim())
                        if (secret.isNotBlank()) put("client_secret", secret)
                    })
                } catch (e: Exception) { null }
            } ?: continue
            when (r.code) {
                200 -> {
                    val o = try { Net.json.parseToJsonElement(r.body).obj() } catch (e: Exception) { null } ?: return false
                    val token = o.s("access_token") ?: return false
                    saveToken(o, token)
                    return true
                }
                400 -> {}                      // still waiting for you
                429 -> interval += 1           // asked to slow down
                else -> {
                    _status.value = when (r.code) {
                        404 -> "Trakt didn't recognise the code. Try Connect again."
                        409 -> "That code was already used. Try Connect again."
                        410 -> "The code ran out. Try Connect again."
                        418 -> "Sign-in was declined on Trakt."
                        401, 403 -> "Trakt refused the sign-in (${r.code}). If you entered a Client Secret, clear it and try again."
                        else -> "Trakt answered ${r.code}"
                    }
                    return false
                }
            }
        }
        _status.value = "The code ran out. Try Connect again."
        return false
    }

    private suspend fun saveToken(o: JsonObject, token: String) {
        val expires = System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 604800L) * 1000
        prefs.setTraktLogin(token, o.s("refresh_token") ?: "", expires, prefs.traktUser.value)
        val user = try { call("GET", "/users/settings").obj()?.get("user").obj()?.s("username") } catch (e: Exception) { null }
        prefs.setTraktLogin(token, o.s("refresh_token") ?: "", expires, user ?: "")
        _status.value = ""
        prefs.wlSyncedOnce = false
        syncSoon(0)
    }

    private val refreshLock = Mutex()

    /** Renews the sign-in a day before it runs out (Trakt sign-ins last 7 days; renewal keys are single-use). */
    private suspend fun ensureFresh() = refreshLock.withLock {
        if (prefs.traktToken.value.isBlank() || prefs.traktExpires - System.currentTimeMillis() > 24 * 3600_000L) return@withLock
        val refresh = prefs.traktRefresh.takeIf { it.isNotBlank() } ?: return@withLock
        fun body(withRedirect: Boolean) = buildJsonObject {
            put("refresh_token", refresh); put("client_id", prefs.traktClientId.value.trim()); put("grant_type", "refresh_token")
            if (secret.isNotBlank()) put("client_secret", secret)
            if (withRedirect) put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
        }
        for (h in hosts()) for (withRedirect in listOf(secret.isNotBlank(), secret.isBlank())) {
            val r = try { post(h, "/oauth/token", body(withRedirect)) } catch (e: Exception) { null } ?: continue
            if (r.code == 200) {
                val o = try { Net.json.parseToJsonElement(r.body).obj() } catch (e: Exception) { null } ?: continue
                val token = o.s("access_token") ?: continue
                prefs.setTraktLogin(token, o.s("refresh_token") ?: refresh,
                    System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 604800L) * 1000, prefs.traktUser.value)
                prefs.traktAuthHost = h
                return@withLock
            }
        }
    }

    fun signOut() {
        prefs.setTraktLogin("", "", 0L, "")
        _watchedMovies.value = emptySet(); _watchedEps.value = emptyMap(); _ratings.value = emptyMap()
        _shows.value = emptyList(); _upNext.value = emptyList()
    }

    // ---------------- Which titles sync ----------------

    fun syncKey(isTv: Boolean, tmdbId: String) = (if (isTv) "show:" else "movie:") + tmdbId

    /** Everything syncs unless you left it out; with "sync everything" off, only titles you chose. */
    fun isSynced(isTv: Boolean, tmdbId: String): Boolean {
        val k = syncKey(isTv, tmdbId)
        return if (prefs.traktSyncAll.value) k !in prefs.traktExclude.value else k in prefs.traktSync.value
    }

    fun toggleSync(isTv: Boolean, tmdbId: String) {
        val k = syncKey(isTv, tmdbId)
        if (prefs.traktSyncAll.value) prefs.toggleTraktExclude(k) else prefs.toggleTraktSync(k)
        refreshWatched(force = true)
    }

    // ---------------- Library ↔ TMDB ids ----------------

    /** TMDB id for a library item, remembered once found (TMDB search, or Trakt's search if TMDB isn't set up). */
    suspend fun tmdbFor(libKey: String, name: String, year: Int?, isTv: Boolean): String? {
        prefs.tmdbIds.value[libKey]?.let { return it }
        val (clean, y0) = MovieInfoService.cleanTitle(name)
        val y = year?.toString() ?: y0
        val id = (if (Tmdb.configured) try { TmdbApi.findId(clean, y, isTv) } catch (e: Exception) { null } else null)
            ?: searchTrakt(clean, y, isTv)
        id?.let { prefs.rememberTmdbId(libKey, it) }
        return id
    }

    private suspend fun searchTrakt(name: String, year: String?, isTv: Boolean): String? = try {
        val type = if (isTv) "show" else "movie"
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val hits = call("GET", "/search/$type?query=$q&fields=title" + (year?.let { "&years=$it" } ?: ""), auth = false).arr()
            ?.mapNotNull { it.obj()?.get(type).obj() }.orEmpty()
        val want = Discover.norm(name)
        (hits.firstOrNull { Discover.norm(it.s("title") ?: "") == want } ?: hits.firstOrNull())?.get("ids").obj()?.s("tmdb")
    } catch (e: Exception) { null }

    private data class Target(val isTv: Boolean, val tmdbId: String, val season: Int = 0, val episode: Int = 0)

    private suspend fun targetFor(item: app.sideline.tv.player.Play.Item.Vod): Target? {
        if (!signedIn) return null
        item.movie?.let { m ->
            val id = tmdbFor("movie:${m.key}", m.name, m.year, false) ?: return null
            return Target(false, id).takeIf { isSynced(false, id) }
        }
        val e = item.episodeObj ?: return null
        val sr = repo.series.value.firstOrNull { it.accountId == e.accountId && it.seriesId == item.seriesId }
        val id = tmdbFor("series:${e.accountId}:${item.seriesId}", sr?.name ?: item.title, sr?.year, true) ?: return null
        return Target(true, id, e.season, e.number).takeIf { isSynced(true, id) }
    }

    private fun scrobbleBody(t: Target, progress: Float) = buildJsonObject {
        if (t.isTv) {
            putJsonObject("show") { putJsonObject("ids") { put("tmdb", t.tmdbId.toInt()) } }
            putJsonObject("episode") { put("season", t.season); put("number", t.episode) }
        } else putJsonObject("movie") { putJsonObject("ids") { put("tmdb", t.tmdbId.toInt()) } }
        put("progress", (progress * 100f).coerceIn(0f, 100f))
    }

    private val scrobbleLock = Mutex()

    /**
     * From the player: "start" when playing, "pause" when paused, "stop" when you leave (≥80% = watched).
     * Pause/stop also save your place on Trakt, so other devices can carry on from there.
     */
    fun scrobble(action: String, item: app.sideline.tv.player.Play.Item.Vod, progress: Float) {
        scope.launch {
            scrobbleLock.withLock {
                try {
                    val t = targetFor(item) ?: return@withLock
                    call("POST", "/scrobble/$action", scrobbleBody(t, progress))
                    if (action == "stop" && progress >= 0.8f) refreshWatched(force = true)
                } catch (_: Exception) {}
            }
        }
    }

    // ---------------- Watched progress ----------------

    data class WatchedShow(val tmdb: String, val title: String, val year: String, val lastWatched: Long, val eps: Set<String>)

    private val _watchedMovies = MutableStateFlow<Set<String>>(emptySet())
    /** TMDB ids of movies watched (on Trakt). */
    val watchedMovies: StateFlow<Set<String>> = _watchedMovies
    private val _watchedEps = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Show TMDB id → watched episodes as "season:episode". */
    val watchedEps: StateFlow<Map<String, Set<String>>> = _watchedEps
    private val _shows = MutableStateFlow<List<WatchedShow>>(emptyList())
    @Volatile private var hiddenShows: Set<String> = emptySet()
    @Volatile private var watchedAt = 0L

    private val ISO = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    private fun time(s: String?): Long = try { if (s == null || s.length < 19) 0L else synchronized(ISO) { ISO.parse(s.take(19))?.time ?: 0L } } catch (e: Exception) { 0L }

    fun refreshWatched(force: Boolean = false) {
        if (!signedIn || (!force && System.currentTimeMillis() - watchedAt < 10 * 60_000)) return
        watchedAt = System.currentTimeMillis()
        scope.launch { try { loadWatched(); computeUpNext() } catch (_: Exception) {} }
    }

    private suspend fun loadWatched() {
        call("GET", "/sync/watched/movies").arr()?.mapNotNull { it.obj()?.get("movie").obj()?.get("ids").obj()?.s("tmdb") }
            ?.let { _watchedMovies.value = it.toSet() }
        call("GET", "/sync/watched/shows").arr()?.mapNotNull { it.obj() }?.let { list ->
            val map = HashMap<String, Set<String>>()
            val shows = ArrayList<WatchedShow>()
            for (sh in list) {
                val show = sh["show"].obj() ?: continue
                val id = show["ids"].obj()?.s("tmdb") ?: continue
                val eps = HashSet<String>()
                for (se in sh["seasons"].arr() ?: JsonArray(emptyList())) {
                    val so = se.obj() ?: continue
                    val n = so.s("number") ?: continue
                    for (ep in so["episodes"].arr() ?: JsonArray(emptyList())) ep.obj()?.s("number")?.let { eps.add("$n:$it") }
                }
                map[id] = eps
                shows += WatchedShow(id, show.s("title") ?: "", show.s("year") ?: "", time(sh.s("last_watched_at")), eps)
            }
            _watchedEps.value = map
            _shows.value = shows.sortedByDescending { it.lastWatched }
        }
        hiddenShows = try {
            call("GET", "/users/hidden/progress_watched?type=show&limit=200").arr()
                ?.mapNotNull { it.obj()?.get("show").obj()?.get("ids").obj()?.s("tmdb") }?.toSet() ?: hiddenShows
        } catch (e: Exception) { hiddenShows }
        refreshRatings()
    }

    // ---------------- Ratings ----------------

    private val _ratings = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** "movie:<tmdb>" / "show:<tmdb>" → your rating 1–10. */
    val ratings: StateFlow<Map<String, Int>> = _ratings

    private suspend fun refreshRatings() {
        val m = mutableMapOf<String, Int>()
        for ((type, key) in listOf("movies" to "movie", "shows" to "show")) {
            call("GET", "/sync/ratings/$type").arr()?.forEach { r ->
                val o = r.obj() ?: return@forEach
                val id = o[key].obj()?.get("ids").obj()?.s("tmdb") ?: return@forEach
                o.i("rating")?.let { m["$key:$id"] = it }
            }
        }
        _ratings.value = m
    }

    fun rate(isTv: Boolean, tmdbId: String, rating: Int) {
        _ratings.value = _ratings.value + (syncKey(isTv, tmdbId) to rating)
        scope.launch {
            try {
                call("POST", "/sync/ratings", buildJsonObject {
                    putJsonArray(if (isTv) "shows" else "movies") {
                        addJsonObject { put("rating", rating); putJsonObject("ids") { put("tmdb", tmdbId.toInt()) } }
                    }
                })
            } catch (_: Exception) {}
        }
    }

    /** Marks a whole movie / episode watched or unwatched by hand. */
    fun markWatched(isTv: Boolean, tmdbId: String, season: Int = 0, episode: Int = 0, watched: Boolean = true) {
        scope.launch {
            try {
                val body = buildJsonObject {
                    if (!isTv) putJsonArray("movies") { addJsonObject { putJsonObject("ids") { put("tmdb", tmdbId.toInt()) } } }
                    else putJsonArray("shows") {
                        addJsonObject {
                            putJsonObject("ids") { put("tmdb", tmdbId.toInt()) }
                            putJsonArray("seasons") { addJsonObject { put("number", season); putJsonArray("episodes") { addJsonObject { put("number", episode) } } } }
                        }
                    }
                }
                call("POST", if (watched) "/sync/history" else "/sync/history/remove", body)
                refreshWatched(force = true)
            } catch (_: Exception) {}
        }
    }

    // ---------------- Keeping everything in step ----------------

    @Volatile private var started = false
    private var soonJob: Job? = null
    @Volatile private var lastFull = 0L

    /** Starts the background sync: on app start, every 15 minutes while the app is open, and after watchlist edits. */
    fun start() {
        if (started) return
        started = true
        prefs.onWatchlistEdit = { syncSoon(3_000, watchlistOnly = true) }
        scope.launch {
            delay(20_000)   // let the library load first
            while (true) {
                if (SidelineApp.foreground && signedIn) syncAll()
                delay(15 * 60_000L)
            }
        }
    }

    /** Called when the app comes to the front. */
    fun onAppOpen() { if (signedIn && System.currentTimeMillis() - lastFull > 5 * 60_000) syncSoon(8_000) }

    fun syncSoon(delayMs: Long, watchlistOnly: Boolean = false) {
        soonJob?.cancel()
        soonJob = scope.launch {
            delay(delayMs)
            if (watchlistOnly) try { syncWatchlist() } catch (_: Exception) {} else syncAll()
        }
    }

    private val syncLock = Mutex()

    private suspend fun syncAll() = syncLock.withLock {
        if (!signedIn) return@withLock
        lastFull = System.currentTimeMillis()
        try { loadWatched(); watchedAt = System.currentTimeMillis() } catch (_: Exception) {}
        try { syncWatchlist() } catch (_: Exception) {}
        try { syncPlayback() } catch (_: Exception) {}
        try { computeUpNext() } catch (_: Exception) {}
        try { checkNewEpisodes() } catch (_: Exception) {}
    }

    private suspend fun index(): Discover.Index? {
        val m = repo.movies.value; val s = repo.series.value
        if (m.isEmpty() && s.isEmpty()) return null
        return withContext(Dispatchers.Default) { Discover.indexFor(m, s) }
    }

    private fun titleOf(o: JsonObject?, isTv: Boolean): Discover.Title? {
        o ?: return null
        val id = o["ids"].obj()?.s("tmdb") ?: return null
        return Discover.Title(id, isTv, o.s("title") ?: return null, o.s("year") ?: "", "", null, null, null)
    }

    // ---------------- Watchlist, both ways ----------------

    private val wlLock = Mutex()

    /** Empire TV watchlist key ("vod:acc:id" / "series:acc:id") → Trakt key ("movie:tmdb" / "show:tmdb"). */
    private suspend fun traktKeyOf(localKey: String, lookUp: Boolean): String? {
        if (localKey.startsWith("vod:")) {
            val k = localKey.removePrefix("vod:")
            prefs.tmdbIds.value["movie:$k"]?.let { return "movie:$it" }
            if (!lookUp) return null
            val m = repo.movies.value.firstOrNull { it.key == k } ?: return null
            return tmdbFor("movie:$k", m.name, m.year, false)?.let { "movie:$it" }
        }
        if (localKey.startsWith("series:")) {
            prefs.tmdbIds.value[localKey]?.let { return "show:$it" }
            if (!lookUp) return null
            val parts = localKey.split(':')
            val acc = parts.getOrNull(1) ?: return null; val sid = parts.getOrNull(2)?.toIntOrNull() ?: return null
            val s = repo.series.value.firstOrNull { it.accountId == acc && it.seriesId == sid } ?: return null
            return tmdbFor(localKey, s.name, s.year, true)?.let { "show:$it" }
        }
        return null
    }

    private fun idsBody(keys: Collection<String>) = buildJsonObject {
        val movies = keys.filter { it.startsWith("movie:") }.mapNotNull { it.removePrefix("movie:").toIntOrNull() }
        val shows = keys.filter { it.startsWith("show:") }.mapNotNull { it.removePrefix("show:").toIntOrNull() }
        if (movies.isNotEmpty()) putJsonArray("movies") { movies.forEach { id -> addJsonObject { putJsonObject("ids") { put("tmdb", id) } } } }
        if (shows.isNotEmpty()) putJsonArray("shows") { shows.forEach { id -> addJsonObject { putJsonObject("ids") { put("tmdb", id) } } } }
    }

    /**
     * Two-way watchlist. Adding or removing on either side does the same on the other. Trakt titles that aren't in
     * your library stay on Trakt but aren't shown here (they appear by themselves if your provider adds them later).
     */
    suspend fun syncWatchlist() = wlLock.withLock {
        if (!signedIn) return@withLock
        val index = index() ?: return@withLock
        val tm = call("GET", "/sync/watchlist/movies")?.arr() ?: return@withLock
        val ts = call("GET", "/sync/watchlist/shows")?.arr() ?: return@withLock
        val traktNow = LinkedHashMap<String, Discover.Title>()
        tm.forEach { e -> titleOf(e.obj()?.get("movie").obj(), false)?.let { traktNow["movie:${it.tmdbId}"] = it } }
        ts.forEach { e -> titleOf(e.obj()?.get("show").obj(), true)?.let { traktNow["show:${it.tmdbId}"] = it } }

        val first = !prefs.wlSyncedOnce
        val lastTrakt = prefs.wlLastTrakt; val lastLocal = prefs.wlLastLocal
        val local = prefs.watchlist.value
        val localSet = local.toSet()
        val traktKeys = traktNow.keys
        val removedT = if (first) emptySet() else lastTrakt - traktKeys
        val addedL = if (first) localSet else localSet - lastLocal
        val removedL = if (first) emptySet() else lastLocal - localSet

        // Your changes here → Trakt.
        val pushAdd = addedL.mapNotNull { traktKeyOf(it, lookUp = true) }.filter { it !in traktKeys }.toSet()
        val pushRemove = removedL.mapNotNull { traktKeyOf(it, lookUp = true) }.filter { it in traktKeys && it !in (traktKeys - lastTrakt) }.toSet()
        if (pushAdd.isNotEmpty()) call("POST", "/sync/watchlist", idsBody(pushAdd))
        if (pushRemove.isNotEmpty()) call("POST", "/sync/watchlist/remove", idsBody(pushRemove))

        // Trakt's changes → here.
        val out = local.toMutableList()
        fun localKeyOf(t: Discover.Title): String? =
            if (t.isTv) index.series(t)?.let { "series:${it.accountId}:${it.seriesId}" } else index.movie(t)?.let { "vod:${it.key}" }
        for ((k, t) in traktNow) {
            if (k in pushRemove) continue
            val lk = localKeyOf(t) ?: continue          // not in your library: hidden
            if (t.isTv) prefs.rememberTmdbId(lk, t.tmdbId) else prefs.rememberTmdbId("movie:" + lk.removePrefix("vod:"), t.tmdbId)
            if (lk !in out && lk !in removedL) out.add(0, lk)
        }
        if (removedT.isNotEmpty()) {
            val drop = HashSet<String>()
            for (lk in out) if (lk !in addedL && traktKeyOf(lk, lookUp = false)?.let { it in removedT } == true) drop += lk
            out.removeAll(drop)
        }
        if (out != local) prefs.setWatchlist(out)

        prefs.wlLastTrakt = (traktKeys + pushAdd - pushRemove).toSet()
        prefs.wlLastLocal = prefs.watchlist.value.toSet()
        prefs.wlSyncedOnce = true
    }

    // ---------------- Resume anywhere ----------------

    /** Places you paused on Trakt (from any device) become "Continue watching" here when they're newer. */
    private suspend fun syncPlayback() {
        val index = index() ?: return
        val items = call("GET", "/sync/playback?extended=full&limit=40")?.arr() ?: return
        val ctx = SidelineApp.ctx
        val resume = ctx.getSharedPreferences("resume", android.content.Context.MODE_PRIVATE)
        val local = SidelineApp.progress.entries.value.associateBy { it.key }
        val done = SidelineApp.progress.done.value
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600_000
        for (it in items) {
            val o = it.obj() ?: continue
            val pct = o.s("progress")?.toDoubleOrNull() ?: continue
            if (pct < 1 || pct > 95) continue
            val paused = time(o.s("paused_at")).takeIf { t -> t > cutoff } ?: continue
            when (o.s("type")) {
                "movie" -> {
                    val mo = o["movie"].obj() ?: continue
                    val t = titleOf(mo, false) ?: continue
                    if (t.tmdbId in _watchedMovies.value && pct < 5) continue
                    val m = index.movie(t) ?: continue
                    val key = "movie:${m.key}"
                    if (key in done) continue
                    val cur = local[key]
                    if (cur != null && cur.updated >= paused - 60_000) continue
                    val dur = (mo.i("runtime") ?: 0) * 60_000L
                    val durMs = if (dur > 0) dur else cur?.duration ?: continue
                    val pos = (durMs * pct / 100).toLong()
                    resume.edit().putLong(key, pos).apply()
                    SidelineApp.progress.merge(ProgressEntry(key = key, kind = "movie", accountId = m.accountId, title = t.title,
                        image = m.poster, position = pos, duration = durMs, updated = paused, streamId = m.streamId, ext = m.ext))
                }
                "episode" -> {
                    val sh = o["show"].obj() ?: continue
                    val ep = o["episode"].obj() ?: continue
                    val t = titleOf(sh, true) ?: continue
                    val se = ep.i("season") ?: continue; val n = ep.i("number") ?: continue
                    if ("$se:$n" in (_watchedEps.value[t.tmdbId] ?: emptySet())) continue
                    val sr = index.series(t) ?: continue
                    val e = episodesOf(sr, 6 * 3600_000L).firstOrNull { x -> x.season == se && x.number == n } ?: continue
                    val key = "ep:${e.accountId}:${e.id}"
                    if (key in done) continue
                    val cur = SidelineApp.progress.entries.value.firstOrNull { x -> x.kind == "episode" && x.accountId == sr.accountId && x.seriesId == sr.seriesId }
                    if (cur != null && cur.updated >= paused - 60_000) continue
                    val dur = ((ep.i("runtime") ?: sh.i("runtime") ?: 0) * 60_000L).takeIf { d -> d > 0 }
                        ?: local[key]?.duration?.takeIf { d -> d > 0 } ?: continue
                    val pos = (dur * pct / 100).toLong()
                    resume.edit().putLong(key, pos).apply()
                    SidelineApp.progress.merge(ProgressEntry(key = key, kind = "episode", accountId = e.accountId, title = sr.name,
                        subtitle = "S${e.season} E${e.number} · ${e.title}", image = sr.cover ?: e.image, position = pos, duration = dur,
                        updated = paused, ext = e.ext, seriesId = sr.seriesId, episodeId = e.id, season = e.season, episode = e.number, plot = e.plot))
                }
            }
        }
    }

    // ---------------- Your provider's episodes (cached) ----------------

    private val epCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Episode>>>()

    suspend fun episodesOf(sr: Series, maxAgeMs: Long): List<Episode> {
        val k = "${sr.accountId}:${sr.seriesId}"
        epCache[k]?.let { (t, l) -> if (System.currentTimeMillis() - t < maxAgeMs) return l }
        val l = try { repo.client(sr.accountId)?.episodes(sr.seriesId) } catch (e: Exception) { null } ?: return epCache[k]?.second ?: emptyList()
        val sorted = l.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.number }))
        epCache[k] = System.currentTimeMillis() to sorted
        return sorted
    }

    /** Shows you're following: watched on Trakt in the last year, not hidden there, and in your library. */
    private suspend fun following(index: Discover.Index, max: Int): List<Pair<WatchedShow, Series>> {
        val since = System.currentTimeMillis() - 365L * 24 * 3600_000
        return _shows.value.asSequence()
            .filter { it.tmdb !in hiddenShows && it.lastWatched > since }
            .mapNotNull { s -> index.series(Discover.Title(s.tmdb, true, s.title, s.year, "", null, null, null))?.let { s to it } }
            .take(max).toList()
    }

    // ---------------- Up Next ----------------

    data class UpNext(val series: Series, val episode: Episode, val tmdb: String, val lastWatched: Long) {
        val label get() = "S${episode.season} E${episode.number} · ${episode.title}"
    }

    private val _upNext = MutableStateFlow<List<UpNext>>(emptyList())
    /** Next unwatched episode of each show you follow (newest first), when your library has it. */
    val upNext: StateFlow<List<UpNext>> = _upNext

    private suspend fun computeUpNext() {
        if (!signedIn) return
        val index = index() ?: return
        val out = ArrayList<UpNext>()
        val inProgress = SidelineApp.progress.entries.value.filter { it.kind == "episode" }.map { "${it.accountId}:${it.seriesId}" }.toSet()
        for ((show, sr) in following(index, 25)) {
            if ("${sr.accountId}:${sr.seriesId}" in inProgress) continue   // already in Continue watching
            val eps = episodesOf(sr, 6 * 3600_000L)
            val last = eps.indexOfLast { "${it.season}:${it.number}" in show.eps }
            if (last < 0) continue
            val next = eps.drop(last + 1).firstOrNull { "${it.season}:${it.number}" !in show.eps } ?: continue
            out += UpNext(sr, next, show.tmdb, show.lastWatched)
        }
        _upNext.value = out
    }

    // ---------------- New episodes in your library ----------------

    data class NewEpisode(val series: Series, val episode: Episode)

    private val _newEpisodes = MutableStateFlow<List<NewEpisode>>(emptyList())
    /** New episodes that just reached your library, waiting to be shown in a pop-up. */
    val newEpisodes: StateFlow<List<NewEpisode>> = _newEpisodes
    fun dismissNewEpisodes() { _newEpisodes.value = emptyList() }

    @Volatile private var newEpCheckedAt = 0L

    private suspend fun checkNewEpisodes() {
        if (System.currentTimeMillis() - newEpCheckedAt < 3 * 3600_000L) return
        val index = index() ?: return
        val list = following(index, 30)
        if (list.isEmpty()) return
        newEpCheckedAt = System.currentTimeMillis()
        val sp = SidelineApp.ctx.getSharedPreferences("trakt_new_episodes", android.content.Context.MODE_PRIVATE)
        val found = ArrayList<NewEpisode>()
        for ((show, sr) in list) {
            val eps = episodesOf(sr, 60 * 60_000L)
            if (eps.isEmpty()) continue
            val k = "${sr.accountId}:${sr.seriesId}"
            val known = sp.getStringSet(k, null)
            val ids = eps.map { it.id }.toSet()
            if (known != null) {
                val topSeason = show.eps.mapNotNull { it.substringBefore(':').toIntOrNull() }.maxOrNull() ?: 0
                eps.filter { it.id !in known && it.season >= topSeason && "${it.season}:${it.number}" !in show.eps }
                    .forEach { found += NewEpisode(sr, it) }
            }
            sp.edit().putStringSet(k, ids).apply()
        }
        if (found.isNotEmpty()) _newEpisodes.value = (_newEpisodes.value + found).distinctBy { "${it.series.accountId}:${it.episode.id}" }.take(12)
        if (found.isNotEmpty()) computeUpNext()
    }

    // ---------------- Public lists (for Discover) ----------------

    /** Trakt list → (TMDB id, title, year). Works with just the Client ID (no sign-in). */
    suspend fun publicList(path: String, isTv: Boolean, limit: Int = 40): List<Discover.Title> = try {
        val key = if (isTv) "show" else "movie"
        call("GET", "$path${if (path.contains('?')) "&" else "?"}limit=$limit", auth = false).arr()
            ?.mapNotNull { e -> val o = e.obj(); titleOf(o?.get(key).obj() ?: o, isTv) }.orEmpty()
    } catch (e: Exception) { emptyList() }
}
