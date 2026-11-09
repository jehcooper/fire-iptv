package app.sideline.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Movie Database (themoviedb.org) — free with an API key (Settings → Movie & series info).
 * Used for audience ratings (PG-13, TV-MA, 14A…) and as a better description source than Wikipedia.
 * Accepts either the short "API key" or the long "API Read Access Token" from TMDB's settings page.
 */
object Tmdb {
    data class Result(val certification: String?, val overview: String?, val backdrop: String?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Result>()
    private val http by lazy { Net.http.newBuilder().apply { interceptors().clear() }.build() }

    val configured: Boolean get() = app.sideline.tv.SidelineApp.prefs.tmdbKey.value.isNotBlank()

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        val key = app.sideline.tv.SidelineApp.prefs.tmdbKey.value.trim()
        val b = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val bearer = key.length > 60   // read-access tokens are long JWTs; v3 keys are 32 chars
        if (!bearer) b.addQueryParameter("api_key", key)
        val req = Request.Builder().url(b.build()).header("Accept", "application/json")
        if (bearer) req.header("Authorization", "Bearer $key")
        http.newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("TMDB answered ${r.code}")
            Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj()
        }
    }

    /** US rating first, then Canadian (labelled), then any. */
    private fun pick(byCountry: Map<String, String>): String? =
        byCountry["US"]?.takeIf { it.isNotBlank() }
            ?: byCountry["CA"]?.takeIf { it.isNotBlank() }?.let { "$it (CA)" }
            ?: byCountry.entries.firstOrNull { it.value.isNotBlank() }?.let { "${it.value} (${it.key})" }

    suspend fun movie(title: String, year: String?): Result? {
        if (!configured || title.isBlank()) return null
        val ck = "m|$title|$year"
        cache[ck]?.let { return it }
        return try {
            val p = mutableMapOf("query" to title)
            if (!year.isNullOrBlank()) p["year"] = year
            val hit = get("search/movie", p)?.get("results").arr()?.firstOrNull().obj() ?: return null
            val id = hit.s("id") ?: return null
            val dates = get("movie/$id/release_dates")?.get("results").arr()?.mapNotNull { it.obj() } ?: emptyList()
            val byCountry = dates.associate { c ->
                (c.s("iso_3166_1") ?: "") to (c["release_dates"].arr()?.mapNotNull { it.obj()?.s("certification") }?.firstOrNull { it.isNotBlank() } ?: "")
            }
            Result(pick(byCountry), hit.s("overview"), hit.s("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" })
                .also { cache[ck] = it }
        } catch (e: Exception) { null }
    }

    suspend fun series(title: String): Result? {
        if (!configured || title.isBlank()) return null
        val ck = "t|$title"
        cache[ck]?.let { return it }
        return try {
            val hit = get("search/tv", mapOf("query" to title))?.get("results").arr()?.firstOrNull().obj() ?: return null
            val id = hit.s("id") ?: return null
            val ratings = get("tv/$id/content_ratings")?.get("results").arr()?.mapNotNull { it.obj() } ?: emptyList()
            val byCountry = ratings.associate { (it.s("iso_3166_1") ?: "") to (it.s("rating") ?: "") }
            Result(pick(byCountry), hit.s("overview"), hit.s("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" })
                .also { cache[ck] = it }
        } catch (e: Exception) { null }
    }

    /** Some providers put the rating in their own data under one of these names. */
    fun providerRating(o: kotlinx.serialization.json.JsonObject?): String? =
        listOf("mpaa_rating", "mpaa", "certification", "age_rating", "age", "rated", "parental_rating")
            .firstNotNullOfOrNull { k -> o?.s(k)?.takeIf { it.isNotBlank() && it != "0" && !it.equals("N/A", true) } }
}
