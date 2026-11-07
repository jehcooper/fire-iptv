package app.sideline.tv.ui

import androidx.compose.foundation.background
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
import app.sideline.tv.data.MovieInfoService
import app.sideline.tv.data.Series
import app.sideline.tv.player.Play
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val ALL = "__all"
private const val FIND = "__search"

@Composable
private fun <T> VodBrowser(
    emptyTitle: String,
    cats: List<Category>,
    all: List<T>,
    catOf: (T) -> String,
    nameOf: (T) -> String,
    keyOf: (T) -> String,
    tile: @Composable (T) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    if (all.isEmpty()) {
        EmptyState(emptyTitle, "Add an account with \"On-demand\" ticked in Settings, or wait for loading to finish.")
        return
    }
    val shown = when (selected) {
        ALL -> all.takeLast(300).reversed()
        FIND -> if (query.length < 2) emptyList() else all.filter { nameOf(it).contains(query, ignoreCase = true) }.take(300)
        else -> all.filter { catOf(it) == selected }
    }
    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 32.dp, top = 8.dp)) {
        LazyColumn(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            item { CategoryItem("🔍 Search", selected == FIND) { selected = FIND } }
            item { CategoryItem("Recently added", selected == ALL) { selected = ALL } }
            items(cats, key = { "${it.accountId}:${it.id}" }) { c ->
                CategoryItem(c.name, selected == "${c.accountId}:${c.id}") { selected = "${c.accountId}:${c.id}" }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            if (selected == FIND) {
                SearchBox(query, { query = it }, "Type a title")
                Spacer(Modifier.height(10.dp))
            }
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

@Composable
fun MoviesScreen() {
    val repo = SidelineApp.repo
    val cats by repo.vodCategories.collectAsState()
    val movies by repo.movies.collectAsState()
    var open by remember { mutableStateOf<Movie?>(null) }
    VodBrowser(
        "No movies yet", cats, movies,
        catOf = { "${it.accountId}:${it.categoryId}" }, nameOf = { it.name }, keyOf = { it.key },
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
private fun MovieSheet(m: Movie, onDismiss: () -> Unit) {
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
                    if (i.metaLine.isNotBlank()) Text(i.metaLine, color = C.Accent, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(i.plot.ifBlank { "No description found for this title." }, color = C.Text, fontSize = 16.sp,
                        maxLines = 9, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                    if (i.director.isNotBlank()) Text("Director: ${i.director}", color = C.Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                    if (i.cast.isNotBlank()) Text("Cast: ${i.cast}", color = C.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (i.plotSource.isNotBlank()) Text(i.plotSource, color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(22.dp))
                Chip("▶ Play", selected = true, onClick = { Play.movie(ctx, m) }, modifier = Modifier.focusRequester(play))
                Text("Picks up where you left off.", color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
fun SeriesScreen() {
    val repo = SidelineApp.repo
    val cats by repo.seriesCategories.collectAsState()
    val series by repo.series.collectAsState()
    var open by remember { mutableStateOf<Series?>(null) }
    VodBrowser(
        "No series yet", cats, series,
        catOf = { "${it.accountId}:${it.categoryId}" }, nameOf = { it.name }, keyOf = { "${it.accountId}:${it.seriesId}" },
    ) { s -> PosterCard(s.name, s.cover, onClick = { open = s }) }
    open?.let { SeriesSheet(it) { open = null } }
}

@Composable
private fun SeriesSheet(s: Series, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var season by remember { mutableStateOf(1) }
    LaunchedEffect(s.seriesId) {
        val eps = SidelineApp.repo.client(s.accountId)?.episodes(s.seriesId) ?: emptyList()
        episodes = eps
        season = eps.firstOrNull()?.season ?: 1
    }
    DetailSheet(s.cover, onDismiss) {
        Text(s.name, color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
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
                FocusCard(onClick = { Play.episode(ctx, e, s.name) }, modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f) { _ ->
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
