package app.sideline.tv.data

import app.sideline.tv.SidelineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Trakt (trakt.tv) sync, only for the titles you switch "Sync to Trakt" on:
 * - scrobbling: Trakt shows "watching now", and marks the movie/episode watched once you pass ~80%;
 * - your watched progress from Trakt (episodes watched elsewhere show ✓ here);
 * - ratings 1–10.
 * Sign-in uses Trakt's TV code flow (enter a code on trakt.tv/activate). Needs your own free Trakt API app.
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val JSON = "application/json".toMediaType()
    private val prefs get() = SidelineApp.prefs

    val hasApp get() = prefs.traktClientId.value.isNotBlank() && prefs.traktSecret.value.isNotBlank()
    val signedIn get() = prefs.traktToken.value.isNotBlank()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private suspend fun call(method: String, path: String, body: JsonElement? = null, auth: Boolean = true): JsonElement? = withContext(Dispatchers.IO) {
        if (auth) ensureFresh()
        val b = Request.Builder().url(API + path)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", prefs.traktClientId.value)
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

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int)

    suspend fun startSignIn(): DeviceCode? = try {
        val o = call("POST", "/oauth/device/code", buildJsonObject { put("client_id", prefs.traktClientId.value) }, auth = false).obj()
        o?.let { DeviceCode(it.s("device_code") ?: return@let null, it.s("user_code") ?: "", it.s("verification_url") ?: "https://trakt.tv/activate",
            it.i("interval") ?: 5, it.i("expires_in") ?: 600) }
    } catch (e: Exception) { _status.value = "Couldn't reach Trakt (${e.message})"; null }

    /** Polls until you've entered the code (true), it expired or was refused (false). */
    suspend fun waitForSignIn(dc: DeviceCode): Boolean {
        val end = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < end) {
            kotlinx.coroutines.delay(dc.interval * 1000L)
            val result = withContext(Dispatchers.IO) {
                try {
                    val body = buildJsonObject {
                        put("code", dc.deviceCode); put("client_id", prefs.traktClientId.value); put("client_secret", prefs.traktSecret.value)
                    }.toString().toRequestBody(JSON)
                    Net.http.newCall(Request.Builder().url("$API/oauth/device/token").post(body).build()).execute().use { r ->
                        when (r.code) {
                            200 -> Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj()
                            400 -> null   // still waiting for you
                            else -> JsonObject(emptyMap())   // expired / denied
                        }
                    }
                } catch (e: Exception) { null }
            }
            if (result != null) {
                val token = result.s("access_token") ?: return false
                saveToken(result, token)
                return true
            }
        }
        return false
    }

    private suspend fun saveToken(o: JsonObject, token: String) {
        val expires = System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 7776000L) * 1000
        prefs.setTraktLogin(token, o.s("refresh_token") ?: "", expires, prefs.traktUser.value)
        val user = try { call("GET", "/users/settings").obj()?.get("user").obj()?.s("username") } catch (e: Exception) { null }
        prefs.setTraktLogin(token, o.s("refresh_token") ?: "", expires, user ?: "")
        _status.value = ""
        refreshWatched(force = true)
    }

    /** Renews the sign-in a day before it runs out. */
    private suspend fun ensureFresh() {
        if (prefs.traktToken.value.isBlank() || prefs.traktExpires - System.currentTimeMillis() > 24 * 3600_000L) return
        val refresh = prefs.traktRefresh.takeIf { it.isNotBlank() } ?: return
        try {
            val body = buildJsonObject {
                put("refresh_token", refresh); put("client_id", prefs.traktClientId.value); put("client_secret", prefs.traktSecret.value)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
            }.toString().toRequestBody(JSON)
            Net.http.newCall(Request.Builder().url("$API/oauth/token").post(body).build()).execute().use { r ->
                if (r.isSuccessful) {
                    val o = Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj() ?: return
                    val token = o.s("access_token") ?: return
                    prefs.setTraktLogin(token, o.s("refresh_token") ?: refresh,
                        System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 7776000L) * 1000, prefs.traktUser.value)
                }
            }
        } catch (_: Exception) {}
    }

    fun signOut() {
        prefs.setTraktLogin("", "", 0L, "")
        _watchedMovies.value = emptySet(); _watchedEps.value = emptyMap(); _ratings.value = emptyMap()
    }

    // ---------------- Which titles sync ----------------

    fun syncKey(isTv: Boolean, tmdbId: String) = (if (isTv) "show:" else "movie:") + tmdbId
    fun isSynced(isTv: Boolean, tmdbId: String) = syncKey(isTv, tmdbId) in prefs.traktSync.value

    /** What a library item is on Trakt (via its TMDB id), if you've switched sync on for it. */
    private data class Target(val isTv: Boolean, val tmdbId: String, val season: Int = 0, val episode: Int = 0)

    private fun targetFor(item: app.sideline.tv.player.Play.Item.Vod): Target? {
        if (!signedIn) return null
        val ids = prefs.tmdbIds.value
        item.movie?.let { m ->
            val id = ids["movie:${m.key}"] ?: return null
            return Target(false, id).takeIf { isSynced(false, id) }
        }
        val e = item.episodeObj ?: return null
        val id = ids["series:${e.accountId}:${item.seriesId}"] ?: return null
        return Target(true, id, e.season, e.number).takeIf { isSynced(true, id) }
    }

    private fun scrobbleBody(t: Target, progress: Float) = buildJsonObject {
        if (t.isTv) {
            putJsonObject("show") { putJsonObject("ids") { put("tmdb", t.tmdbId.toInt()) } }
            putJsonObject("episode") { put("season", t.season); put("number", t.episode) }
        } else putJsonObject("movie") { putJsonObject("ids") { put("tmdb", t.tmdbId.toInt()) } }
        put("progress", (progress * 100f).coerceIn(0f, 100f))
    }

    /** From the player: "start" when playing, "pause" when paused, "stop" when you leave (≥80% = watched). */
    fun scrobble(action: String, item: app.sideline.tv.player.Play.Item.Vod, progress: Float) {
        val t = targetFor(item) ?: return
        scope.launch {
            try {
                call("POST", "/scrobble/$action", scrobbleBody(t, progress))
                if (action == "stop" && progress >= 0.8f) refreshWatched(force = true)
            } catch (_: Exception) {}
        }
    }

    // ---------------- Progress from Trakt ----------------

    private val _watchedMovies = MutableStateFlow<Set<String>>(emptySet())
    /** TMDB ids of movies watched (on Trakt). */
    val watchedMovies: StateFlow<Set<String>> = _watchedMovies
    private val _watchedEps = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Show TMDB id → watched episodes as "season:episode". */
    val watchedEps: StateFlow<Map<String, Set<String>>> = _watchedEps
    @Volatile private var watchedAt = 0L

    fun refreshWatched(force: Boolean = false) {
        if (!signedIn || (!force && System.currentTimeMillis() - watchedAt < 10 * 60_000)) return
        watchedAt = System.currentTimeMillis()
        scope.launch {
            try {
                call("GET", "/sync/watched/movies").arr()?.mapNotNull { it.obj()?.get("movie").obj()?.get("ids").obj()?.s("tmdb") }
                    ?.let { _watchedMovies.value = it.toSet() }
                call("GET", "/sync/watched/shows").arr()?.mapNotNull { it.obj() }?.let { shows ->
                    _watchedEps.value = shows.mapNotNull { sh ->
                        val id = sh["show"].obj()?.get("ids").obj()?.s("tmdb") ?: return@mapNotNull null
                        id to (sh["seasons"].arr()?.flatMap { se ->
                            val n = se.obj()?.s("number") ?: return@flatMap emptyList()
                            se.obj()?.get("episodes").arr()?.mapNotNull { ep -> ep.obj()?.s("number")?.let { "$n:$it" } } ?: emptyList()
                        }?.toSet() ?: emptySet())
                    }.toMap()
                }
                refreshRatings()
            } catch (_: Exception) {}
        }
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

}
