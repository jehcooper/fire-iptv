package app.sideline.tv.data

import android.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Talks to one Xtream Codes panel via player_api.php. */
class XtreamClient(val account: Account) {

    val base: String = account.server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private fun apiUrl(action: String?, extra: Map<String, String> = emptyMap()): String {
        val b = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("That server address doesn't look right")
        b.addQueryParameter("username", account.username)
        b.addQueryParameter("password", account.password)
        if (action != null) b.addQueryParameter("action", action)
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private suspend fun call(action: String?, extra: Map<String, String> = emptyMap()): JsonElement =
        Net.getJson(apiUrl(action, extra))

    suspend fun login(): AccountStatus = try {
        val root = call(null).obj()
        val ui = root?.get("user_info").obj()
        if (ui == null || ui.i("auth") == 0) {
            AccountStatus(false, "Login refused. Check the username, password and server address.")
        } else {
            val exp = ui.l("exp_date")?.let {
                java.text.DateFormat.getDateInstance().format(java.util.Date(it * 1000))
            }
            val formats = ui["allowed_output_formats"].arr()?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: emptyList()
            AccountStatus(
                ok = ui.s("status")?.equals("Active", ignoreCase = true) ?: true,
                message = "Status: ${ui.s("status") ?: "unknown"}",
                expires = exp,
                maxConnections = ui.i("max_connections"),
                formats = formats,
            )
        }
    } catch (e: Exception) {
        AccountStatus(false, "Couldn't reach the server: ${e.message}")
    }

    private fun categories(el: JsonElement) = el.arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
        val id = o.s("category_id") ?: return@mapNotNull null
        Category(account.id, id, o.s("category_name") ?: "Other")
    } ?: emptyList()

    suspend fun liveCategories() = categories(call("get_live_categories"))
    suspend fun vodCategories() = categories(call("get_vod_categories"))
    suspend fun seriesCategories() = categories(call("get_series_categories"))

    suspend fun liveStreams(): List<Channel> = call("get_live_streams").arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
        val id = o.i("stream_id") ?: return@mapNotNull null
        Channel(
            accountId = account.id,
            streamId = id,
            name = o.s("name") ?: "Channel $id",
            logo = o.s("stream_icon"),
            categoryId = o.s("category_id") ?: "",
            epgId = o.s("epg_channel_id"),
            hasArchive = o.b("tv_archive"),
            archiveDays = o.i("tv_archive_duration") ?: 0,
        )
    } ?: emptyList()

    suspend fun vodStreams(): List<Movie> = call("get_vod_streams").arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
        val id = o.i("stream_id") ?: return@mapNotNull null
        Movie(account.id, id, o.s("name") ?: "Movie $id", o.s("stream_icon"), o.s("category_id") ?: "",
            o.s("container_extension") ?: "mp4", o.s("rating"))
    } ?: emptyList()

    suspend fun series(): List<Series> = call("get_series").arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
        val id = o.i("series_id") ?: return@mapNotNull null
        Series(account.id, id, o.s("name") ?: "Series $id", o.s("cover"), o.s("category_id") ?: "", o.s("plot"), o.s("rating"))
    } ?: emptyList()

    /** Audience rating from the provider's series info, if it has one. */
    var lastSeriesRating: String? = null
        private set

    suspend fun episodes(seriesId: Int): List<Episode> {
        val root = call("get_series_info", mapOf("series_id" to seriesId.toString())).obj() ?: return emptyList()
        lastSeriesRating = Tmdb.providerRating(root["info"].obj())
        val eps = root["episodes"]
        val out = mutableListOf<Episode>()
        // Usually {"1":[...],"2":[...]}, but some panels send a plain array of season arrays.
        val seasonLists: List<List<JsonElement>> = when (eps) {
            is JsonObject -> eps.values.mapNotNull { it.arr()?.toList() }
            else -> eps.arr()?.mapNotNull { it.arr()?.toList() } ?: emptyList()
        }
        seasonLists.flatten().mapNotNull { it.obj() }.forEach { e ->
            val info = e["info"].obj()
            out += Episode(
                accountId = account.id,
                id = e.s("id") ?: return@forEach,
                title = e.s("title") ?: "Episode",
                season = e.i("season") ?: 1,
                number = e.i("episode_num") ?: 0,
                ext = e.s("container_extension") ?: "mp4",
                image = info?.s("movie_image"),
                plot = info?.s("plot"),
            )
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    /** Provider's own movie details (many panels copy these from TMDB). */
    suspend fun movieInfo(m: Movie): MovieInfo? = try {
        val info = call("get_vod_info", mapOf("vod_id" to m.streamId.toString())).obj()?.get("info").obj()
        if (info == null) null else {
            val backdrop = info["backdrop_path"].arr()?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: info.s("backdrop_path")
            val date = info.s("releasedate") ?: info.s("releaseDate") ?: info.s("release_date") ?: info.s("year") ?: ""
            val runtime = info.s("duration")?.let { d ->
                val p = d.split(":").mapNotNull { it.toIntOrNull() }
                if (p.size >= 2 && (p[0] > 0 || p[1] > 0)) (if (p[0] > 0) "${p[0]}h ${p[1]}m" else "${p[1]}m") else null
            } ?: info.s("episode_run_time")?.let { "$it min" } ?: ""
            MovieInfo(
                title = info.s("name") ?: info.s("o_name") ?: m.name,
                plot = info.s("plot") ?: info.s("description") ?: "",
                year = Regex("(19|20)\\d{2}").find(date)?.value ?: "",
                genre = info.s("genre") ?: "",
                runtime = runtime,
                rating = info.s("rating")?.takeIf { it != "0" && it != "0.0" } ?: "",
                director = info.s("director") ?: "",
                cast = info.s("cast") ?: info.s("actors") ?: "",
                backdrop = backdrop?.takeIf { it.startsWith("http") },
                poster = info.s("movie_image") ?: info.s("cover_big") ?: m.poster,
                certification = Tmdb.providerRating(info) ?: "",
            )
        }
    } catch (e: Exception) { null }

    suspend fun shortEpg(streamId: Int, limit: Int = 4): List<EpgItem> = try {
        call("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString()))
            .obj()?.get("epg_listings").arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
                EpgItem(
                    title = decode(o.s("title")),
                    description = decode(o.s("description")),
                    startRaw = o.s("start") ?: "",
                    start = o.l("start_timestamp") ?: 0,
                    end = o.l("stop_timestamp") ?: 0,
                )
            } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun decode(v: String?): String {
        if (v.isNullOrBlank()) return ""
        return try { String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8).trim() } catch (e: Exception) { v }
    }

    // ---- Playback URLs ----
    fun liveUrl(c: Channel) = "$base/live/${account.username}/${account.password}/${c.streamId}.${account.liveFormat}"
    fun movieUrl(m: Movie) = "$base/movie/${account.username}/${account.password}/${m.streamId}.${m.ext}"
    fun episodeUrl(e: Episode) = "$base/series/${account.username}/${account.password}/${e.id}.${e.ext}"

    /** Catch-up: start is the EPG's server-local "yyyy-MM-dd HH:mm:ss". */
    fun timeshiftUrl(c: Channel, startRaw: String, durationMin: Int): String? {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}):(\\d{2})").find(startRaw) ?: return null
        val (d, h, mi) = m.destructured
        return "$base/timeshift/${account.username}/${account.password}/$durationMin/$d:$h-$mi/${c.streamId}.ts"
    }
}
