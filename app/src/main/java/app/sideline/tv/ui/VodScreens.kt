package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Category
import app.sideline.tv.data.Episode
import app.sideline.tv.data.Movie
import app.sideline.tv.data.MovieInfo
import app.sideline.tv.data.ProgressEntry
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.sideline.tv.data.MovieInfoService
import app.sideline.tv.data.Series
import app.sideline.tv.player.Play
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val ALL = "__all"
private const val FIND = "__search"
private const val EVERYTHING = "__everything"
private const val CONTINUE = "__continue"

@Composable
private fun <T> VodBrowser(
    kind: String,
    emptyTitle: String,
    cats: List<Category>,
    all: List<T>,
    catOf: (T) -> String,
    nameOf: (T) -> String,
    keyOf: (T) -> String,
    continueItems: List<ProgressEntry>,
    tile: @Composable (T) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val hidden by prefs.hiddenCats.collectAsState()
    var selected by rememberSaveable { mutableStateOf(if (continueItems.isNotEmpty()) CONTINUE else ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchBar = remember { FocusRequester() }
    if (all.isEmpty()) {
        EmptyState(emptyTitle, "Add an account with \"On-demand\" ticked in Settings, or wait for loading to finish.")
        return
    }
    val visibleCats = remember(cats, hidden) { cats.filter { "$kind:${it.accountId}:${it.id}" !in hidden } }
    val counts = remember(all) { all.groupingBy { catOf(it) }.eachCount() }
    // Everything in a hidden category is left out of "All", "Recently added" and search.
    val visibleAll = remember(all, hidden) {
        if (hidden.none { it.startsWith("$kind:") }) all else all.filter { "$kind:${catOf(it)}" !in hidden }
    }

    // Worked out off the main thread so the remote stays responsive with big libraries; search waits for a pause in typing.
    val shown by produceState(initialValue = emptyList<T>(), selected, query, visibleAll) {
        if (selected == FIND) delay(250)
        value = withContext(Dispatchers.Default) {
            when (selected) {
                ALL -> visibleAll.takeLast(300).reversed()
                EVERYTHING -> visibleAll.sortedBy { nameOf(it).lowercase() }
                FIND -> if (query.length < 2) emptyList() else visibleAll.asSequence().filter { nameOf(it).contains(query, ignoreCase = true) }.take(300).toList()
                CONTINUE -> emptyList()
                else -> all.filter { catOf(it) == selected }
            }
        }
    }
    if (searching) SearchDialog(query, if (kind == "vod") "Search movies" else "Search series",
        onSearch = { query = it; selected = FIND; searching = false }, onDismiss = { searching = false })

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 32.dp, top = 8.dp)) {
        LazyColumn(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            if (continueItems.isNotEmpty()) item { CategoryItem("▶ Continue watching  (${continueItems.size})", selected == CONTINUE) { selected = CONTINUE } }
            item {
                // OK always opens the search window (with the last search filled in);
                // ▶ goes straight to the search bar on the right.
                CategoryItem("🔍 Search", selected == FIND, selectOnFocus = false,
                    modifier = Modifier.focusProperties { if (selected == FIND) right = searchBar }) {
                    selected = FIND
                    searching = true
                }
            }
            item { CategoryItem("All A–Z  (${visibleAll.size})", selected == EVERYTHING) { selected = EVERYTHING } }
            item { CategoryItem("Recently added", selected == ALL) { selected = ALL } }
            items(visibleCats, key = { "${it.accountId}:${it.id}" }) { c ->
                val k = "${c.accountId}:${c.id}"
                CategoryItem("${c.name}  (${counts[k] ?: 0})", selected == k, onMenu = {
                    Confirm.ask("Hide \"${c.name}\"?", "You can show it again in Settings → Show or hide categories.", "Hide", destructive = true) {
                        prefs.toggleHidden("$kind:$k")
                    }
                }) { selected = k }
            }
            item {
                Text("Menu (≡) on a category hides it. Bring hidden ones back in Settings.", color = C.Faint, fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp))
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            if (selected == FIND) {
                SearchButton(query, "Search all titles in every category", onClick = { searching = true }, modifier = Modifier.focusRequester(searchBar))
                Spacer(Modifier.height(10.dp))
            }
            if (selected == CONTINUE) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 60.dp),
                ) {
                    items(continueItems, key = { it.key }) { p -> ProgressCard(p, onClick = { Play.resume(ctx, p) }) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 60.dp),
                ) {
                    items(shown, key = keyOf) { tile(it) }
                }
            }
        }
    }
}

/** A started movie or episode: image, how far in, and time left. Menu removes it from the list. */
@Composable
fun ProgressCard(p: ProgressEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, onMenu = {
        Confirm.ask("Remove \"${p.title}\" from Continue watching?", yes = "Remove", destructive = true) { SidelineApp.progress.remove(p.key) }
    }, modifier = modifier.width(240.dp).height(150.dp)) { f ->
        AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00B0F14)))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(p.title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.subtitle.isNotBlank()) Text(p.subtitle, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmtTime((p.duration - p.position).coerceAtLeast(0))} left" + if (f) "  ·  ≡ remove" else "", color = C.Faint, fontSize = 11.sp)
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(C.Surface2)) {
                Box(Modifier.fillMaxWidth(p.fraction).height(3.dp).background(C.Accent))
            }
        }
    }
}

@Composable
fun MoviesScreen() {
    val repo = SidelineApp.repo
    val cats by repo.vodCategories.collectAsState()
    val movies by repo.movies.collectAsState()
    var open by remember { mutableStateOf<Movie?>(null) }
    val progress by SidelineApp.progress.entries.collectAsState()
    val cont = remember(progress) { progress.filter { it.kind == "movie" } }
    VodBrowser(
        "vod", "No movies yet", cats, movies,
        catOf = { "${it.accountId}:${it.categoryId}" }, nameOf = { it.name }, keyOf = { it.key }, continueItems = cont,
    ) { m -> PosterCard(m.name, m.poster, onClick = { open = m }, badge = m.rating?.takeIf { it != "0" && it.isNotBlank() }?.let { "★ ${it.take(3)}" }) }
    open?.let { MovieSheet(it) { open = null } }
}

@Composable
private fun DetailSheet(image: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    TvDialog(onDismiss) {
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.25f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.Bg, C.Bg.copy(alpha = 0.85f), Color.Transparent))))
        Column(Modifier.fillMaxHeight().width(900.dp).padding(start = 64.dp, top = 48.dp, end = 24.dp)) { content() }
    }
    }
}

@Composable
fun MovieSheet(m: Movie, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<MovieInfo?>(null) }
    val play = remember { FocusRequester() }
    LaunchedEffect(m.key) { info = MovieInfoService.get(m) }
    LaunchedEffect(Unit) {
        delay(200)
        try { play.requestFocus() } catch (_: Exception) {}
    }
    val i = info
    DetailSheet(i?.backdrop ?: m.poster, onDismiss) {
        Row {
            AsyncImage(model = i?.poster ?: m.poster, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 180.dp, height = 270.dp))
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(i?.title ?: MovieInfoService.cleanTitle(m.name).first, color = C.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                if (i == null) {
                    Text("Looking up details…", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        if (i.certification.isNotBlank()) { RatingBadge(i.certification); Spacer(Modifier.width(10.dp)) }
                        if (i.metaLine.isNotBlank()) Text(i.metaLine, color = C.Accent, fontSize = 15.sp)
                    }
                    Text(i.plot.ifBlank { "No description found for this title." }, color = C.Text, fontSize = 16.sp,
                        maxLines = 9, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                    if (i.director.isNotBlank()) Text("Director: ${i.director}", color = C.Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                    if (i.cast.isNotBlank()) Text("Cast: ${i.cast}", color = C.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (i.plotSource.isNotBlank()) Text(i.plotSource, color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(22.dp))
                // Re-read when coming back from the player so the resume time is current.
                var tick by remember { mutableStateOf(0) }
                androidx.lifecycle.compose.LifecycleResumeEffect(m.key) { tick++; onPauseOrDispose { } }
                val resumeAt = remember(m.key, tick) { Play.resumePosition(ctx, Play.movieKey(m)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (resumeAt > 60_000) {
                        Chip("▶ Resume from ${fmtTime(resumeAt)}", selected = true, onClick = { Play.movie(ctx, m) }, modifier = Modifier.focusRequester(play))
                        Chip("⟲ Start from the beginning", selected = false, onClick = { Play.movie(ctx, m, fromStart = true) })
                    } else {
                        Chip("▶ Play", selected = true, onClick = { Play.movie(ctx, m, fromStart = true) }, modifier = Modifier.focusRequester(play))
                    }
                }
            }
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SeriesScreen() {
    val repo = SidelineApp.repo
    val cats by repo.seriesCategories.collectAsState()
    val series by repo.series.collectAsState()
    var open by remember { mutableStateOf<Series?>(null) }
    val progress by SidelineApp.progress.entries.collectAsState()
    val cont = remember(progress) { progress.filter { it.kind == "episode" } }
    VodBrowser(
        "series", "No series yet", cats, series,
        catOf = { "${it.accountId}:${it.categoryId}" }, nameOf = { it.name }, keyOf = { "${it.accountId}:${it.seriesId}" }, continueItems = cont,
    ) { s -> SeriesTile(s, onClick = { open = s }) }
    open?.let { SeriesSheet(it) { open = null } }
}

@Composable
fun SeriesSheet(s: Series, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var season by remember { mutableStateOf(1) }
    var rating by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.seriesId) {
        val client = SidelineApp.repo.client(s.accountId)
        val eps = client?.episodes(s.seriesId) ?: emptyList()
        episodes = eps
        season = eps.firstOrNull()?.season ?: 1
        rating = client?.lastSeriesRating ?: app.sideline.tv.data.Tmdb.series(MovieInfoService.cleanTitle(s.name).first)?.certification
    }
    DetailSheet(s.cover, onDismiss) {
        Text(s.name, color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        val favs by SidelineApp.prefs.favSeries.collectAsState()
        val favKey = "${s.accountId}:${s.seriesId}"
        val imdb = ratingBadge(s.name, "series", s.rating)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            rating?.takeIf { it.isNotBlank() }?.let { RatingBadge(it); Spacer(Modifier.width(10.dp)) }
            imdb?.let { Text(it, color = C.Gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(14.dp)) }
            val fav = favKey in favs
            Chip(if (fav) "★ In favourites" else "☆ Add to favourites", fav, onClick = {
                Confirm.ask(if (fav) "Remove ${s.name} from favourite series?" else "Add ${s.name} to favourite series?",
                    yes = if (fav) "Remove" else "Add", destructive = fav) { SidelineApp.prefs.toggleFavSeries(favKey) }
            })
        }
        if (!s.plot.isNullOrBlank()) Text(s.plot, color = C.Muted, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        val eps = episodes
        if (eps == null) { Text("Loading episodes…", color = C.Muted); return@DetailSheet }
        if (eps.isEmpty()) { Text("No episodes found.", color = C.Muted); return@DetailSheet }
        val seasons = eps.map { it.season }.distinct().sorted()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(seasons) { n -> Chip("Season $n", n == season, onClick = { season = n }) }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 60.dp, end = 8.dp)) {
            items(eps.filter { it.season == season }, key = { it.id }) { e ->
                FocusCard(onClick = { Play.episode(ctx, e, s.name, s.seriesId, s.cover) }, modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f) { _ ->
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = e.image ?: s.cover, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 128.dp, height = 72.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${e.number}. ${e.title}", color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!e.plot.isNullOrBlank()) Text(e.plot, color = C.Faint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Audience rating chip, e.g. "PG-13", "TV-MA". */
@Composable
fun RatingBadge(text: String) {
    Text(text, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.5.dp, C.Muted, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** IMDb rating for a title (via OMDb, if a key is set), or the provider's own score marked ★. */
@Composable
fun ratingBadge(title: String, type: String, providerRating: String?): String? {
    val clean = remember(title) { MovieInfoService.cleanTitle(title) }
    val imdb by produceState(initialValue = app.sideline.tv.data.Omdb.cached(clean.first, type), clean) {
        if (value == null) value = app.sideline.tv.data.Omdb.rating(clean.first, type, if (type == "movie") clean.second else null)
    }
    return when {
        !imdb.isNullOrBlank() -> "IMDb $imdb"
        !providerRating.isNullOrBlank() && providerRating != "0" -> "★ ${providerRating.take(3)}"
        else -> null
    }
}

/** Series poster with its IMDb rating; Menu adds/removes it from your favourite series. */
@Composable
fun SeriesTile(s: Series, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val prefs = SidelineApp.prefs
    val favs by prefs.favSeries.collectAsState()
    val key = "${s.accountId}:${s.seriesId}"
    val fav = key in favs
    PosterCard(
        (if (fav) "★ " else "") + s.name, s.cover, onClick = onClick, modifier = modifier,
        badge = ratingBadge(s.name, "series", s.rating),
        onMenu = {
            Confirm.ask(if (fav) "Remove ${s.name} from favourite series?" else "Add ${s.name} to favourite series?",
                yes = if (fav) "Remove" else "Add", destructive = fav) { prefs.toggleFavSeries(key) }
        },
    )
}
