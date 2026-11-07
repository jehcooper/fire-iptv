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

    fun movie(ctx: Context, m: Movie) {
        val url = SidelineApp.repo.client(m.accountId)?.movieUrl(m) ?: return
        launch(ctx, Item.Vod("movie:${m.key}", m.name, url, movie = m))
    }

    fun episode(ctx: Context, e: Episode, seriesName: String) {
        val url = SidelineApp.repo.client(e.accountId)?.episodeUrl(e) ?: return
        launch(ctx, Item.Vod("ep:${e.accountId}:${e.id}", seriesName, url, "S${e.season} E${e.number} · ${e.title}", plot = e.plot, image = e.image))
    }
}
