package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Discover
import app.sideline.tv.data.Movie
import app.sideline.tv.data.MovieInfoService
import app.sideline.tv.data.ProgressEntry
import app.sideline.tv.data.Series
import app.sideline.tv.data.TmdbApi
import app.sideline.tv.data.Trakt
import app.sideline.tv.player.Play
import app.sideline.tv.player.TrailerActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** What ≡ was pressed on: a library movie or series, a TMDB title, and/or a Continue-watching entry. */
data class OptTarget(
    val movie: Movie? = null,
    val series: Series? = null,
    val tmdb: Discover.Title? = null,
    val progress: ProgressEntry? = null,
)

private class Opt(val label: String, val action: () -> Unit)

object TitleOptions {
    val target = mutableStateOf<OptTarget?>(null)
    fun show(t: OptTarget) { target.value = t }
}

/**
 * ≡ options for a movie or series, wherever it appears: trailer, watchlist, mark watched, favourite series,
 * Trakt sync and rating, remove from Continue watching, details. Only what applies is shown.
 */
@Composable
fun TitleOptionsHost() {
    val t = TitleOptions.target.value ?: return
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val movies by SidelineApp.repo.movies.collectAsState()
    val seriesAll by SidelineApp.repo.series.collectAsState()
    val p = t.progress
    val movie = t.movie ?: p?.takeIf { it.kind == "movie" }?.let { e -> movies.firstOrNull { Play.movieKey(it) == e.key } }
    val series = t.series ?: p?.takeIf { it.kind == "episode" }?.let { e -> seriesAll.firstOrNull { it.accountId == e.accountId && it.seriesId == e.seriesId } }
    val isTv = series != null || t.tmdb?.isTv == true || p?.kind == "episode"
    val rawName = t.tmdb?.title ?: movie?.name ?: series?.name ?: p?.title ?: ""
    val name = remember(rawName) { MovieInfoService.cleanTitle(rawName).first }
    val libKey = movie?.let { "movie:${it.key}" } ?: series?.let { "series:${it.accountId}:${it.seriesId}" }
    val knownIds by prefs.tmdbIds.collectAsState()

    // TMDB id and details (for the trailer and Trakt), looked up in the background.
    val tmdbId by produceState(t.tmdb?.tmdbId ?: libKey?.let { knownIds[it] }, t) {
        if (value != null || !app.sideline.tv.data.Tmdb.configured) return@produceState
        val (clean, year) = MovieInfoService.cleanTitle(rawName)
        value = TmdbApi.findId(clean, movie?.year?.toString() ?: year, isTv)
    }
    LaunchedEffect(tmdbId, libKey) { val id = tmdbId; if (id != null && libKey != null) prefs.rememberTmdbId(libKey, id) }
    val details by produceState<TmdbApi.Details?>(null, tmdbId) { tmdbId?.let { value = TmdbApi.details(it, isTv) } }
    val lookedUp by produceState(false, tmdbId) { delay(if (tmdbId == null) 5000 else 8000); value = true }

    val watch by prefs.watchlist.collectAsState()
    val favSeries by prefs.favSeries.collectAsState()
    val done by SidelineApp.progress.done.collectAsState()
    val syncSet by prefs.traktSync.collectAsState()
    val syncAll by prefs.traktSyncAll.collectAsState()
    val syncExclude by prefs.traktExclude.collectAsState()
    val traktToken by prefs.traktToken.collectAsState()
    val traktMovies by Trakt.watchedMovies.collectAsState()
    val traktEps by Trakt.watchedEps.collectAsState()
    val ratings by Trakt.ratings.collectAsState()
    var rating by remember { mutableStateOf(false) }
    fun close() { TitleOptions.target.value = null }

    val opts = mutableListOf<Opt>()
    // Resume / play first for Continue-watching cards.
    if (p != null) opts += Opt("▶  Resume") { close(); Play.resume(ctx, p) }
    val trailers = details?.trailers.orEmpty()
    when {
        trailers.isNotEmpty() -> opts += Opt("▶  Watch trailer") { close(); TrailerActivity.start(ctx, trailers.map { it.key }, trailers.map { it.name }, name) }
        !lookedUp && app.sideline.tv.data.Tmdb.configured -> opts += Opt("…  Finding trailer") {}
        app.sideline.tv.data.Tmdb.configured -> opts += Opt("No trailer found") {}
    }
    val wKey = movie?.let { "vod:${it.key}" } ?: series?.let { "series:${it.accountId}:${it.seriesId}" }
    if (wKey != null) {
        val inList = wKey in watch
        opts += Opt(if (inList) "✓  Remove from Watchlist" else "+  Add to Watchlist") { prefs.toggleWatchlist(wKey); close() }
    }
    // Mark watched: a movie, or the episode on a Continue-watching card.
    val id = tmdbId
    val synced = id != null && traktToken.isNotBlank() &&
        (if (syncAll) Trakt.syncKey(isTv, id) !in syncExclude else Trakt.syncKey(isTv, id) in syncSet)
    if (movie != null || p?.kind == "episode") {
        val key = p?.key ?: movie?.let { Play.movieKey(it) }!!
        val traktW = id != null && (if (p?.kind == "episode") "${p.season}:${p.episode}" in traktEps[id].orEmpty() else id in traktMovies)
        val watched = key in done || traktW
        opts += Opt(if (watched) "○  Mark as unwatched" else "✓  Mark as watched" + if (synced) " (and on Trakt)" else "") {
            SidelineApp.progress.setDone(key, !watched)
            if (synced && id != null) {
                if (p?.kind == "episode") Trakt.markWatched(true, id, p.season, p.episode, watched = !watched)
                else Trakt.markWatched(false, id, watched = !watched)
            }
            close()
        }
    }
    if (series != null) {
        val fk = "${series.accountId}:${series.seriesId}"
        val fav = fk in favSeries
        opts += Opt(if (fav) "☆  Remove from favourite series" else "★  Favourite series") { prefs.toggleFavSeries(fk); close() }
    }
    if (traktToken.isNotBlank() && id != null) {
        opts += Opt(if (synced) "Trakt: leave this title out" else "Trakt: sync this title") { Trakt.toggleSync(isTv, id); close() }
        if (synced) {
            val r = ratings[Trakt.syncKey(isTv, id)]
            opts += Opt(if (r != null) "♥  Your rating: $r/10 (change)" else "♡  Rate on Trakt") { rating = true }
        }
    }
    if (p != null) opts += Opt("✕  Remove from Continue watching") { SidelineApp.progress.remove(p.key); close() }
    opts += Opt("ⓘ  Details") {
        close()
        when {
            t.tmdb != null -> Pages.open(Page.Title(t.tmdb, movie, series))
            movie != null -> Pages.movie(movie)
            series != null -> Pages.series(series)
        }
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }
    TvDialog({ close() }) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Row(Modifier.width(640.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(22.dp)) {
                AsyncImage(model = details?.poster ?: t.tmdb?.poster ?: movie?.poster ?: series?.cover ?: p?.image, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (p != null && p.subtitle.isNotBlank()) Text(p.subtitle, color = C.Muted, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    opts.forEachIndexed { i, o ->
                        FocusCard(onClick = o.action, modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                            shape = RoundedCornerShape(10.dp), scaleOnFocus = 1.02f) { f ->
                            Text(o.label, color = if (f) C.Text else C.Muted, fontSize = 15.sp, fontWeight = if (f) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                    }
                }
            }
        }
    }
    if (rating && id != null) RateDialog(ratings[Trakt.syncKey(isTv, id)], onPick = { Trakt.rate(isTv, id, it); rating = false; close() }) { rating = false }
}
