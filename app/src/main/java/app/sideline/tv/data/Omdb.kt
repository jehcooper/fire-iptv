package app.sideline.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * IMDb ratings via OMDb (omdbapi.com), which serves IMDb's rating. Free key: 1,000 lookups a day.
 * Results are saved on the stick for a week, so scrolling the same tiles doesn't use up the allowance.
 */
object Omdb {
    private lateinit var sp: android.content.SharedPreferences
    private val gate = Semaphore(4)
    private val http by lazy { Net.http.newBuilder().apply { interceptors().clear() }.build() }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val WEEK = 7 * 24 * 3600_000L

    fun init(ctx: Context) { sp = ctx.getSharedPreferences("omdb", Context.MODE_PRIVATE) }

    val configured: Boolean get() = app.sideline.tv.SidelineApp.prefs.omdbKey.value.isNotBlank()

    private fun key(title: String, type: String) = "$type|${title.lowercase()}"

    /** Saved rating if we have a fresh one: "8.4", "" (looked up, none), or null (never looked up). */
    fun cached(title: String, type: String): String? {
        val k = key(title, type)
        memory[k]?.let { return it }
        val v = sp.getString(k, null) ?: return null
        val (rating, at) = v.substringBefore('|') to (v.substringAfter('|').toLongOrNull() ?: 0L)
        if (System.currentTimeMillis() - at > WEEK) return null
        memory[k] = rating
        return rating
    }

    /** IMDb rating for a series or movie title, e.g. "8.4"; "" when IMDb has none. */
    suspend fun rating(title: String, type: String, year: String? = null): String? {
        if (!configured || title.isBlank()) return null
        cached(title, type)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val b = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
                        .addQueryParameter("apikey", app.sideline.tv.SidelineApp.prefs.omdbKey.value.trim())
                        .addQueryParameter("t", title)
                        .addQueryParameter("type", type)
                    if (!year.isNullOrBlank()) b.addQueryParameter("y", year)
                    http.newCall(Request.Builder().url(b.build()).build()).execute().use { r ->
                        if (!r.isSuccessful) return@withContext null
                        val o = Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj()
                        // Bad key / daily limit: don't cache, so it retries tomorrow.
                        if (o?.s("Response") == "False" && o.s("Error")?.contains("not found", true) != true) return@withContext null
                        val rating = o?.s("imdbRating")?.takeIf { it != "N/A" } ?: ""
                        val k = key(title, type)
                        memory[k] = rating
                        sp.edit().putString(k, "$rating|${System.currentTimeMillis()}").apply()
                        rating
                    }
                } catch (e: Exception) { null }
            }
        }
    }
}
