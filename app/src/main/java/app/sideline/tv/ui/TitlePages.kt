package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Discover
import app.sideline.tv.data.Episode
import app.sideline.tv.data.MovieInfoService
import app.sideline.tv.data.Movie
import app.sideline.tv.data.Series
import app.sideline.tv.data.TmdbApi
import app.sideline.tv.data.Trakt
import app.sideline.tv.player.Play
import app.sideline.tv.player.TrailerActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.withContext

/** Full-screen pages that stack on top of the tabs: a title, a person's work, or a list (genre/keyword). Back goes back one. */
sealed class Page {
    /** [t] may be null for a library item we haven't matched to TMDB yet. */
    data class Title(val t: Discover.Title?, val movie: Movie? = null, val series: Series? = null) : Page()
    data class Person(val id: String, val name: String, val photo: String?) : Page()
    data class Browse(val title: String, val load: suspend () -> List<Discover.Title>) : Page()
}

object Pages {
    val stack = mutableStateListOf<Page>()
    fun open(p: Page) { if (stack.size > 12) stack.removeAt(0); stack.add(p) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    fun movie(m: Movie) = open(Page.Title(null, movie = m))
    fun series(s: Series) = open(Page.Title(null, series = s))
    fun title(t: Discover.Title) = open(Page.Title(t))
}

@Composable
fun PagesHost() {
    val top = Pages.stack.lastOrNull() ?: return
    TvDialog({ Pages.back() }) {
        key(Pages.stack.size, top) {
            Box(Modifier.fillMaxSize().background(C.Bg)) {
                when (top) {
                    is Page.Title -> TitlePage(top)
                    is Page.Person -> PersonPage(top)
                    is Page.Browse -> BrowsePage(top)
                }
            }
        }
    }
}

/** Your library matched against TMDB titles (built in the background, cached). */
@Composable
fun rememberLibraryIndex(): Discover.Index? {
    val movies by SidelineApp.repo.movies.collectAsState()
    val series by SidelineApp.repo.series.collectAsState()
    val idx by produceState(Discover.cachedIndex(movies, series), movies, series) {
        value = withContext(Dispatchers.Default) { Discover.indexFor(movies, series) }
    }
    return idx
}

/** TMDB poster; titles you can play get a red ▶ badge. */
@Composable
fun TitlePoster(t: Discover.Title, index: Discover.Index?, modifier: Modifier = Modifier) {
    val m = if (!t.isTv) index?.movie(t) else null
    val sr = if (t.isTv) index?.series(t) else null
    val inLib = m != null || sr != null
    PosterCard(
        t.title, t.poster,
        onClick = { Pages.open(Page.Title(t, m, sr)) },
        onMenu = { TitleOptions.show(OptTarget(movie = m, series = sr, tmdb = t)) },
        modifier = modifier,
        badge = if (inLib) "▶" + (t.score?.let { " %.1f".format(it) } ?: "") else t.score?.let { "★ %.1f".format(it) },
        badgeColor = if (inLib) C.Live else C.Accent, badgeTextColor = if (inLib) Color.White else C.OnAccent,
        meta = listOfNotNull(t.year.takeIf { it.isNotBlank() }, TmdbApi.genreNames(t).firstOrNull()).joinToString(" · "),
    )
}

/** Library titles first (they can be played), then the rest. */
fun libraryFirst(list: List<Discover.Title>, index: Discover.Index?): List<Discover.Title> =
    if (index == null) list else list.sortedBy { t -> if ((if (t.isTv) index.series(t) else index.movie(t)) != null) 0 else 1 }

// ------------------------------------------------------------------------------------------------
// Title page
// ------------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TitlePage(page: Page.Title) {
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val index = rememberLibraryIndex()
    val isTv = page.t?.isTv ?: (page.series != null)
    val libMovie = page.movie ?: page.t?.takeIf { !it.isTv }?.let { index?.movie(it) }
    val libSeries = page.series ?: page.t?.takeIf { it.isTv }?.let { index?.series(it) }
    val libKey = libMovie?.let { "movie:${it.key}" } ?: libSeries?.let { "series:${it.accountId}:${it.seriesId}" }
    val knownIds by prefs.tmdbIds.collectAsState()

    // 1) Which TMDB title this is.
    val tmdbId by produceState(page.t?.tmdbId ?: libKey?.let { knownIds[it] }, page) {
        if (value != null || !app.sideline.tv.data.Tmdb.configured) return@produceState
        val name = libMovie?.name ?: libSeries?.name ?: return@produceState
        val (clean, year) = MovieInfoService.cleanTitle(name)
        value = TmdbApi.findId(clean, libMovie?.year?.toString() ?: year, isTv)
    }
    LaunchedEffect(tmdbId, libKey) { val id = tmdbId; if (id != null && libKey != null) prefs.rememberTmdbId(libKey, id) }
    // 2) Its details.
    val d by produceState<TmdbApi.Details?>(null, tmdbId) { tmdbId?.let { value = TmdbApi.details(it, isTv) } }
    val lookedUp by produceState(false, tmdbId) { delay(if (tmdbId == null) 6000 else 12000); value = true }

    val title = d?.title ?: page.t?.title ?: (libMovie?.name ?: libSeries?.name)?.let { MovieInfoService.cleanTitle(it).first } ?: ""
    val backdrop = d?.backdrop ?: page.t?.backdrop
    val poster = d?.poster ?: page.t?.poster ?: libMovie?.poster ?: libSeries?.cover

    // Series: your provider's episodes (what you can actually play).
    val providerEps by produceState<List<Episode>?>(null, libSeries) {
        val s = libSeries ?: run { value = emptyList(); return@produceState }
        value = SidelineApp.repo.client(s.accountId)?.episodes(s.seriesId) ?: emptyList()
    }
    val traktWatched by Trakt.watchedEps.collectAsState()
    val traktMovies by Trakt.watchedMovies.collectAsState()
    val done by SidelineApp.progress.done.collectAsState()
    val syncSet by prefs.traktSync.collectAsState()
    val ratings by Trakt.ratings.collectAsState()
    val traktToken by prefs.traktToken.collectAsState()
    LaunchedEffect(Unit) { Trakt.refreshWatched() }

    fun epWatched(e: Episode): Boolean =
        Play.episodeKey(e) in done || (tmdbId != null && "${e.season}:${e.number}" in traktWatched[tmdbId].orEmpty())

    val seasonNums = remember(d, providerEps) {
        val fromTmdb = d?.seasons?.map { it.number }.orEmpty()
        val fromProvider = providerEps?.map { it.season }.orEmpty()
        (fromTmdb + fromProvider).distinct().sortedWith(compareBy { if (it == 0) 999 else it })
    }
    val nextEp = remember(providerEps, done, traktWatched, tmdbId) {
        providerEps?.sortedWith(compareBy({ if (it.season == 0) 999 else it.season }, { it.number }))?.let { eps ->
            val lastWatched = eps.indexOfLast { epWatched(it) }
            eps.getOrNull(lastWatched + 1) ?: eps.firstOrNull()
        }
    }
    var season by remember(seasonNums) { mutableStateOf(nextEp?.season ?: seasonNums.firstOrNull { it > 0 } ?: seasonNums.firstOrNull() ?: 1) }
    val tmdbEps by produceState<List<TmdbApi.Ep>?>(null, tmdbId, season, d) {
        value = null
        val id = tmdbId
        value = if (id != null && isTv && d != null) TmdbApi.season(id, season) ?: emptyList() else emptyList()
    }

    var rating by remember { mutableStateOf(false) }
    var traktHint by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) {
            AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.Bg.copy(alpha = 0.96f), 0.55f to C.Bg.copy(alpha = 0.7f), 1f to C.Bg.copy(alpha = 0.15f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to C.Bg.copy(alpha = 0.3f), 1f to C.Bg)))
        }
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(start = 64.dp, end = 48.dp, top = 40.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---- Header ----
            item(key = "head") {
                Row {
                    Column(Modifier.width(620.dp)) {
                        Text(title, color = C.Text, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 1.2.em)
                        val meta = listOfNotNull(
                            d?.genres?.take(3)?.joinToString(", ") { it.second }?.takeIf { it.isNotBlank() },
                            (d?.year ?: page.t?.year)?.takeIf { it.isNotBlank() },
                            (d?.score ?: page.t?.score)?.let { "★ %.1f".format(it) },
                            d?.runtime?.let { if (isTv) "${it} min episodes" else if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                            d?.seasons?.count { it.number > 0 }?.takeIf { isTv && it > 0 }?.let { "$it season" + if (it > 1) "s" else "" },
                            d?.network?.takeIf { it.isNotBlank() },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            d?.certification?.takeIf { it.isNotBlank() }?.let { RatingBadge(it); Spacer(Modifier.width(10.dp)) }
                            Text(meta.joinToString("  ·  "), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                        d?.tagline?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = C.Muted, fontSize = 15.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
                        }
                        val overview = d?.overview?.takeIf { it.isNotBlank() } ?: page.t?.overview?.takeIf { it.isNotBlank() } ?: libSeries?.plot
                        when {
                            overview != null -> Text(overview, color = C.Text, fontSize = 16.sp, lineHeight = 1.45.em, maxLines = 5,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            tmdbId == null && !lookedUp && app.sideline.tv.data.Tmdb.configured ->
                                Text("Looking up details…", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                            !app.sideline.tv.data.Tmdb.configured ->
                                Text("Add a TMDB key in Settings for descriptions, cast, trailers and more.", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                        d?.crew?.takeIf { it.isNotBlank() }?.let { Text(it, color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                        if (libMovie == null && libSeries == null) {
                            Text("Not in your IPTV library — you can still watch the trailer and explore.", color = C.Gold, fontSize = 14.sp,
                                modifier = Modifier.padding(top = 10.dp))
                        }

                        // ---- Buttons ----
                        Spacer(Modifier.height(16.dp))
                        val trailers = d?.trailers.orEmpty()
                        val id = tmdbId
                        val firstKind = when {
                            libMovie != null || (libSeries != null && nextEp != null) -> "play"
                            trailers.isNotEmpty() -> "trailer"
                            id != null -> "trakt"
                            else -> "back"
                        }
                        LaunchedEffect(firstKind) { delay(250); try { first.requestFocus() } catch (_: Exception) {} }
                        fun fm(kind: String) = if (kind == firstKind) Modifier.focusRequester(first) else Modifier
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                            // Coming back up to the buttons scrolls right to the top, so the title, year and runtime show again.
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
                                .androidx_onFocusTop { scope.launch { listState.animateScrollToItem(0) } },
                        ) {
                            if (libMovie != null) {
                                val resumeAt = Play.resumePosition(ctx, Play.movieKey(libMovie))
                                if (resumeAt > 60_000) {
                                    Chip("▶  Resume ${fmtTime(resumeAt)}", true, onClick = { Play.movie(ctx, libMovie) }, modifier = fm("play"))
                                    Chip("⟲  Start over", false, onClick = { Play.movie(ctx, libMovie, fromStart = true) })
                                } else Chip("▶  Play", true, onClick = { Play.movie(ctx, libMovie, fromStart = true) }, modifier = fm("play"))
                            }
                            if (libSeries != null && nextEp != null) {
                                val e = nextEp
                                val resumeAt = Play.resumePosition(ctx, Play.episodeKey(e))
                                Chip((if (resumeAt > 60_000) "▶  Resume" else "▶  Play") + " S${e.season} E${e.number}", true,
                                    onClick = { Play.episode(ctx, e, libSeries.name, libSeries.seriesId, libSeries.cover) }, modifier = fm("play"))
                            }
                            if (trailers.isNotEmpty()) {
                                Chip("▶  Trailer", false, onClick = { TrailerActivity.start(ctx, trailers.map { it.key }, trailers.map { it.name }, title) },
                                    modifier = fm("trailer"))
                            }
                            val wKey = libMovie?.let { "vod:${it.key}" } ?: libSeries?.let { "series:${it.accountId}:${it.seriesId}" }
                            if (wKey != null) {
                                val inList = wKey in prefs.watchlist.collectAsState().value
                                Chip(if (inList) "✓ On watchlist" else "+ Watchlist", inList, onClick = { prefs.toggleWatchlist(wKey) })
                            }
                            if (id != null) {
                                val synced = Trakt.syncKey(isTv, id) in syncSet
                                Chip(if (traktToken.isBlank()) "Trakt: not connected" else if (synced) "✓ Syncing to Trakt" else "Sync to Trakt", synced,
                                    onClick = {
                                        if (traktToken.isBlank()) traktHint = "Connect Trakt in Settings → Trakt first."
                                        else {
                                            prefs.toggleTraktSync(Trakt.syncKey(isTv, id))
                                            traktHint = if (!synced) "This title now syncs to Trakt: watching, watched progress and ratings." else "Stopped syncing this title."
                                            Trakt.refreshWatched(force = true)
                                        }
                                    }, modifier = fm("trakt"))
                                if (synced && traktToken.isNotBlank()) {
                                    val r = ratings[Trakt.syncKey(isTv, id)]
                                    Chip(if (r != null) "♥ Your rating $r/10" else "♡ Rate", r != null, onClick = { rating = true })
                                    if (!isTv) {
                                        val w = id in traktMovies
                                        Chip(if (w) "✓ Watched" else "Mark watched", w, onClick = { Trakt.markWatched(false, id, watched = !w) })
                                    }
                                }
                            }
                            if (libSeries != null) {
                                val favs by prefs.favSeries.collectAsState()
                                val fk = "${libSeries.accountId}:${libSeries.seriesId}"
                                val fav = fk in favs
                                Chip(if (fav) "★ Favourite" else "☆ Favourite", fav, onClick = { prefs.toggleFavSeries(fk) })
                            }
                            if (firstKind == "back") Chip("Back", false, onClick = { Pages.back() }, modifier = fm("back"))
                        }
                        if (traktHint.isNotBlank()) Text(traktHint, color = C.Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (poster != null) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 190.dp, height = 285.dp).clip(RoundedCornerShape(14.dp)))
                }
            }

            // ---- Seasons & episodes (TMDB names, pictures and descriptions; ▶ where your provider has it) ----
            if (isTv && seasonNums.isNotEmpty()) {
                item(key = "seasons") {
                    LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                        items(seasonNums) { n ->
                            val name = d?.seasons?.firstOrNull { it.number == n }?.name?.takeIf { it.isNotBlank() } ?: if (n == 0) "Specials" else "Season $n"
                            Chip(name, n == season, onClick = { season = n }, compact = true)
                        }
                    }
                }
                val provider = providerEps.orEmpty().filter { it.season == season }
                val te = tmdbEps
                val numbers = ((te?.map { it.number }.orEmpty()) + provider.map { it.number }).distinct().sorted()
                if (te == null && providerEps == null) item(key = "eps-wait") { Text("Loading episodes…", color = C.Muted) }
                items(numbers, key = { "ep-$season-$it" }) { n ->
                    val info = te?.firstOrNull { it.number == n }
                    val pe = provider.firstOrNull { it.number == n }
                    EpisodeRow(season, n, info, pe, libSeries, watched = pe?.let { epWatched(it) } ?: (tmdbId != null && "$season:$n" in traktWatched[tmdbId].orEmpty()))
                }
            }

            // ---- Cast ----
            val cast = d?.cast.orEmpty()
            if (cast.isNotEmpty()) item(key = "cast") {
                Column {
                    Text("Cast", color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                        items(cast, key = { it.id + it.role }) { p ->
                            // Every cast card is the same size: two lines for the name, one for the character.
                            FocusCard(onClick = { Pages.open(Page.Person(p.id, p.name, p.photo)) }, modifier = Modifier.width(124.dp).height(182.dp),
                                shape = RoundedCornerShape(14.dp)) { _ ->
                                Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    AsyncImage(model = p.photo, contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(84.dp).clip(CircleShape).background(C.Surface2))
                                    Text(p.name, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                    Text(p.role.ifBlank { " " }, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }

            // ---- More like this ----
            val more = d?.more.orEmpty()
            if (more.isNotEmpty()) item(key = "more") {
                Column {
                    Text("More like this", color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                        items(libraryFirst(more, index), key = { it.tmdbId }) { t -> TitlePoster(t, index) }
                    }
                }
            }
        }
    }

    val id = tmdbId
    if (rating && id != null) RateDialog(ratings[Trakt.syncKey(isTv, id)], onPick = { Trakt.rate(isTv, id, it); rating = false }) { rating = false }
}

@Composable
private fun EpisodeRow(season: Int, n: Int, info: TmdbApi.Ep?, pe: Episode?, s: Series?, watched: Boolean) {
    val ctx = LocalContext.current
    val playable = pe != null && s != null
    FocusCard(
        onClick = { if (playable) Play.episode(ctx, pe!!, s!!.name, s.seriesId, s.cover) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), scaleOnFocus = 1.015f,
    ) { focused ->
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(model = info?.still ?: pe?.image ?: s?.cover, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2))
                if (watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Pill("✓ Watched", C.Accent, C.OnAccent) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("$n. " + (info?.name?.takeIf { it.isNotBlank() } ?: pe?.title ?: "Episode $n"), color = if (playable) C.Text else C.Muted,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    info?.air?.takeIf { it.isNotBlank() }?.let { prettyDate(it) },
                    info?.runtime?.let { "$it min" },
                    info?.score?.let { "★ %.1f".format(it) },
                    if (!playable) "Not in your library" else null,
                )
                if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = if (playable) C.Muted else C.Gold, fontSize = 13.sp)
                val plot = info?.overview?.takeIf { it.isNotBlank() } ?: pe?.plot
                if (!plot.isNullOrBlank()) Text(plot, color = C.Muted, fontSize = 14.sp, maxLines = if (focused) 4 else 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun prettyDate(iso: String): String = try {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(d!!)
} catch (e: Exception) { iso }

@Composable
internal fun RateDialog(current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(24.dp)) {
                Text("Your rating (sent to Trakt)", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("10 = loved it · 1 = hated it", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..10).forEach { r ->
                        Chip("$r", r == current, onClick = { onPick(r) },
                            modifier = if (r == (current ?: 7)) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Person and list pages
// ------------------------------------------------------------------------------------------------

@Composable
private fun PersonPage(p: Page.Person) {
    val index = rememberLibraryIndex()
    val credits by produceState<List<Discover.Title>?>(null, p.id) { value = TmdbApi.personCredits(p.id) }
    GridPage(p.name, "Movies and series with ${p.name}", p.photo, credits, index)
}

@Composable
private fun BrowsePage(p: Page.Browse) {
    val index = rememberLibraryIndex()
    val list by produceState<List<Discover.Title>?>(null, p) { value = p.load() }
    GridPage(p.title, "Most popular first · ▶ = in your library", null, list, index)
}

@Composable
private fun GridPage(title: String, subtitle: String, photo: String?, list: List<Discover.Title>?, index: Discover.Index?) {
    val first = remember { FocusRequester() }
    val sorted = remember(list, index) { list?.let { libraryFirst(it, index) } }
    LaunchedEffect(sorted?.isNotEmpty()) { if (sorted?.isNotEmpty() == true) { delay(250); try { first.requestFocus() } catch (_: Exception) {} } }
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, top = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photo != null) {
                AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(CircleShape).border(2.dp, C.Accent, CircleShape))
                Spacer(Modifier.width(18.dp))
            }
            Column {
                Text(title, color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = C.Muted, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            sorted == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp)); Text("Loading…", color = C.Muted)
            }
            sorted.isEmpty() -> Text(app.sideline.tv.data.Tmdb.lastError.value ?: "Nothing found.", color = C.Muted)
            else -> androidx.compose.runtime.CompositionLocalProvider(LocalPosterFill provides true) { LazyVerticalGrid(
                GridCells.Adaptive(130.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 60.dp),
            ) {
                items(sorted, key = { (if (it.isTv) "t" else "m") + it.tmdbId }) { t ->
                    TitlePoster(t, index, modifier = if (t === sorted.first()) Modifier.focusRequester(first) else Modifier)
                }
            } }
        }
    }
}

/** Calls [onFocus] whenever focus moves into this element (or its children). */
private fun Modifier.androidx_onFocusTop(onFocus: () -> Unit): Modifier =
    this.onFocusChanged { if (it.hasFocus) onFocus() }
