package app.sideline.tv.player

import android.content.Context
import android.content.Intent
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.Episode
import app.sideline.tv.data.Movie

/** Hands what to play to PlayerActivity. Kept in memory so long channel lists don't go through an Intent. */
object Play {
    sealed class Item {
        data class Live(val channel: Channel, val queue: List<Channel>) : Item()
        data class Vod(
            val key: String, val title: String, val url: String, val subtitle: String = "",
            /** Set for movies so the pause screen can show the description. */
            val movie: Movie? = null,
            /** Set for episodes: their own description and still image. */
            val plot: String? = null, val image: String? = null,
            val fromStart: Boolean = false,
            /** For subtitle search: series name + season/episode for episodes. */
            val season: Int? = null, val episode: Int? = null,
            /** For "Continue watching" on episodes. */
            val episodeObj: Episode? = null, val seriesId: Int = 0, val seriesCover: String? = null,
        ) : Item()
        data class CatchUp(val channel: Channel, val title: String, val url: String) : Item()
    }

    var pending: Item? = null

    private fun launch(ctx: Context, item: Item) {
        pending = item
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }

    fun channel(ctx: Context, c: Channel, queue: List<Channel> = listOf(c)) =
        launch(ctx, Item.Live(c, queue.ifEmpty { listOf(c) }))

    /** Watch a past programme from the start (catch-up channels only). */
    fun catchUp(ctx: Context, c: Channel, item: app.sideline.tv.data.EpgItem) {
        val minutes = ((item.end - item.start) / 60).toInt().coerceAtLeast(30)
        val url = SidelineApp.repo.client(c.accountId)?.timeshiftUrl(c, item.startRaw, minutes) ?: return
        launch(ctx, Item.CatchUp(c, "From the start: ${item.title}", url))
    }

    fun movie(ctx: Context, m: Movie, fromStart: Boolean = false) {
        val url = SidelineApp.repo.client(m.accountId)?.movieUrl(m) ?: return
        launch(ctx, Item.Vod(movieKey(m), m.name, url, movie = m, fromStart = fromStart))
    }

    fun movieKey(m: Movie) = "movie:${m.key}"

    /** Saved position (ms) for a movie or episode key, 0 if none. */
    fun resumePosition(ctx: Context, key: String): Long = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE).getLong(key, 0L)

    fun episode(ctx: Context, e: Episode, seriesName: String, seriesId: Int = 0, seriesCover: String? = null, fromStart: Boolean = false) {
        val url = SidelineApp.repo.client(e.accountId)?.episodeUrl(e) ?: return
        if (seriesId != 0) SidelineApp.prefs.pushWatchedSeries("${e.accountId}:$seriesId")
        launch(ctx, Item.Vod(episodeKey(e), seriesName, url, "S${e.season} E${e.number} · ${e.title}", plot = e.plot, image = e.image,
            season = e.season, episode = e.number, episodeObj = e, seriesId = seriesId, seriesCover = seriesCover, fromStart = fromStart))
    }

    fun episodeKey(e: Episode) = "ep:${e.accountId}:${e.id}"

    /** Pick up a "Continue watching" entry where it was left. */
    fun resume(ctx: Context, p: app.sideline.tv.data.ProgressEntry) {
        if (p.kind == "movie") movie(ctx, p.toMovie())
        else episode(ctx, p.toEpisode(), p.title, p.seriesId, p.image)
    }
}
