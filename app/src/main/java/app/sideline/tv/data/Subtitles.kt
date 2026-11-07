package app.sideline.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * OpenSubtitles.com REST API (api.opensubtitles.com/api/v1).
 * Needs a free API key from opensubtitles.com (Profile → API consumers).
 * Downloads per day: 5 with just the key, 20 if you also enter your free account's username/password.
 */
object OpenSubtitles {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val APP_UA = "Sideline v1.0"

    // Separate client: OpenSubtitles wants the app's own User-Agent, not the IPTV one.
    private val http: OkHttpClient by lazy { Net.http.newBuilder().apply { interceptors().clear() }.build() }

    data class Result(val fileId: Int, val release: String, val language: String, val downloads: Int, val hearingImpaired: Boolean)

    private var token: String? = null
    private var tokenFor: String? = null

    val configured: Boolean get() = SidelineAppPrefs.osKey().isNotBlank()

    private fun req(url: String) = Request.Builder().url(url)
        .header("Api-Key", SidelineAppPrefs.osKey())
        .header("User-Agent", APP_UA)
        .header("Accept", "application/json")

    private suspend fun login(): String? = withContext(Dispatchers.IO) {
        val user = SidelineAppPrefs.osUser()
        val pass = SidelineAppPrefs.osPass()
        if (user.isBlank() || pass.isBlank()) return@withContext null
        if (tokenFor == user && token != null) return@withContext token
        val body = buildJsonObject { put("username", user); put("password", pass) }.toString()
            .toRequestBody("application/json".toMediaType())
        http.newCall(req("$BASE/login").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val t = Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj()?.s("token")
            token = t; tokenFor = user
            t
        }
    }

    /** Search by title (+ year), or by series + season/episode. Most-downloaded first. */
    suspend fun search(title: String, year: String?, season: Int?, episode: Int?, language: String): List<Result> = withContext(Dispatchers.IO) {
        val b = "$BASE/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", title)
            .addQueryParameter("languages", language)
            .addQueryParameter("order_by", "download_count")
        if (season != null && episode != null) {
            b.addQueryParameter("type", "episode")
            b.addQueryParameter("season_number", season.toString())
            b.addQueryParameter("episode_number", episode.toString())
        } else {
            b.addQueryParameter("type", "movie")
            if (!year.isNullOrBlank()) b.addQueryParameter("year", year)
        }
        http.newCall(req(b.build().toString()).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IllegalStateException("OpenSubtitles rejected the API key")
            if (!r.isSuccessful) throw IllegalStateException("OpenSubtitles answered ${r.code}")
            val root = Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj()
            root?.get("data").arr()?.mapNotNull { it.obj()?.get("attributes").obj() }?.mapNotNull { a ->
                val file = a["files"].arr()?.firstOrNull().obj() ?: return@mapNotNull null
                Result(
                    fileId = file.i("file_id") ?: return@mapNotNull null,
                    release = a.s("release") ?: file.s("file_name") ?: "Subtitle",
                    language = a.s("language") ?: language,
                    downloads = a.i("download_count") ?: 0,
                    hearingImpaired = a.b("hearing_impaired"),
                )
            } ?: emptyList()
        }
    }

    /** Downloads one subtitle as .srt into the app's cache. Returns the file, or throws with a readable reason. */
    suspend fun download(ctx: Context, fileId: Int): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        val out = File(dir, "$fileId.srt")
        if (out.exists() && out.length() > 0) return@withContext out
        val jwt = try { login() } catch (_: Exception) { null }
        val body = buildJsonObject { put("file_id", fileId); put("sub_format", "srt") }.toString()
            .toRequestBody("application/json".toMediaType())
        val rb = req("$BASE/download").post(body)
        if (jwt != null) rb.header("Authorization", "Bearer $jwt")
        val link = http.newCall(rb.build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val o = try { Net.json.parseToJsonElement(txt).obj() } catch (_: Exception) { null }
            if (r.code == 406 || (o?.i("remaining") ?: 0) < 0) {
                throw IllegalStateException("Today's OpenSubtitles download limit is used up" +
                    if (jwt == null) " (5/day without an account; add your free account in Settings for 20/day)" else "")
            }
            if (!r.isSuccessful) throw IllegalStateException(o?.s("message") ?: "OpenSubtitles answered ${r.code}")
            o?.s("link") ?: throw IllegalStateException("OpenSubtitles didn't return a download link")
        }
        http.newCall(Request.Builder().url(link).header("User-Agent", APP_UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Subtitle download failed (${r.code})")
            out.writeBytes(r.body?.bytes() ?: ByteArray(0))
        }
        out
    }
}

/** Small bridge so this file doesn't need the Application class at construction time. */
object SidelineAppPrefs {
    fun osKey() = app.sideline.tv.SidelineApp.prefs.osKey.value
    fun osUser() = app.sideline.tv.SidelineApp.prefs.osUser.value
    fun osPass() = app.sideline.tv.SidelineApp.prefs.osPass.value
}

