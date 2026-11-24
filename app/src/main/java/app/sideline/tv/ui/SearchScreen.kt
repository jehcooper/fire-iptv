package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Discover
import app.sideline.tv.data.Tmdb
import app.sideline.tv.data.TmdbApi
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private data class Results(
    val lib: List<Any>, val titles: List<Discover.Title>, val people: List<TmdbApi.PersonHit>,
    val keywords: List<TmdbApi.Keyword>, val genres: List<TmdbApi.Genre>, val error: String?,
)

/**
 * Search everything: your library and all of TMDB — titles, actors and crew, keywords and genres.
 * Titles you can play come first and carry a red ▶.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    val index = rememberLibraryIndex()
    val movies by SidelineApp.repo.movies.collectAsState()
    val series by SidelineApp.repo.series.collectAsState()

    val genres by produceState<List<TmdbApi.Genre>>(emptyList()) { if (Tmdb.configured) value = TmdbApi.genres() }
    val results by produceState<Results?>(null, query, index != null) {
        value = null
        val q = query.trim()
        if (q.length < 2) return@produceState
        val n = Discover.norm(q)
        val lib = withContext(Dispatchers.Default) {
            (movies.filter { Discover.norm(it.name).contains(n) }.take(30) + series.filter { Discover.norm(it.name).contains(n) }.take(30))
        }
        if (!Tmdb.configured) { value = Results(lib, emptyList(), emptyList(), emptyList(), emptyList(), null); return@produceState }
        value = try {
            coroutineScope {
                val a = async { TmdbApi.search(q) }
                val k = async { TmdbApi.keywords(q) }
                val (titles, people) = a.await()
                // "In your library" also lists what you have from the matching titles and, when you searched a
                // person's name, everything of theirs you have (not just titles with the name in them).
                val person = people.firstOrNull { Discover.norm(it.name) == n } ?: people.firstOrNull()?.takeIf { titles.none { t -> Discover.norm(t.title) == n } }
                val credits = person?.let { TmdbApi.personCredits(it.id) }.orEmpty()
                val idx = index
                val more = if (idx == null) emptyList() else withContext(Dispatchers.Default) {
                    (titles + credits).mapNotNull { t -> if (t.isTv) idx.series(t) else idx.movie(t) }
                }
                fun libKey(x: Any) = when (x) {
                    is app.sideline.tv.data.Movie -> "m" + x.key
                    is app.sideline.tv.data.Series -> "s${x.accountId}:${x.seriesId}"
                    else -> x.hashCode().toString()
                }
                val allLib = (lib + more).distinctBy { libKey(it) }
                Results(allLib, titles, people, k.await(), genres.filter { it.name.contains(q, ignoreCase = true) }, null)
            }
        } catch (e: Exception) {
            Results(lib, emptyList(), emptyList(), emptyList(), emptyList(), Tmdb.lastError.value ?: e.message)
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 40.dp)) {
        Text("Search", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text("Titles, actors, directors, genres or keywords (e.g. \"heist\", \"Pedro Pascal\", \"Comedy\")", color = C.Muted, fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        SearchButton(query, "OK to type…", onClick = { typing = true })
        Spacer(Modifier.size(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 80.dp)) {
            val r = results
            if (query.trim().length < 2) {
                if (!Tmdb.configured) item { Text("Add a TMDB key in Settings to search actors, genres and everything that isn't in your library.", color = C.Muted) }
                else if (genres.isEmpty()) item { Text(Tmdb.lastError.value ?: "Loading genres…", color = C.Muted) }
                else {
                    listOf(false to "Movie genres", true to "Series genres").forEach { (tv, label) ->
                        item(key = label) {
                            Column {
                                Text(label, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    genres.filter { it.isTv == tv }.forEach { g -> GenreChip(g) }
                                }
                            }
                        }
                    }
                }
            } else if (r == null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp)); Text("Searching…", color = C.Muted)
                    }
                }
            } else {
                if (r.error != null) item { Text(r.error, color = C.Gold, fontSize = 14.sp) }
                if (r.lib.isNotEmpty()) item(key = "lib") {
                    Section("In your library (${r.lib.size})") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                            items(r.lib, key = { x ->
                                when (x) { is app.sideline.tv.data.Movie -> "m" + x.key; is app.sideline.tv.data.Series -> "s${x.accountId}:${x.seriesId}"; else -> x.hashCode() }
                            }) { x ->
                                when (x) {
                                    is app.sideline.tv.data.Movie -> PosterCard(x.name, x.poster, onClick = { Pages.movie(x) }, kind = "movie", onMenu = { TitleOptions.show(OptTarget(movie = x)) },
                                        badge = "▶", badgeColor = C.Live, badgeTextColor = androidx.compose.ui.graphics.Color.White)
                                    is app.sideline.tv.data.Series -> PosterCard(x.name, x.cover, onClick = { Pages.series(x) }, kind = "series", onMenu = { TitleOptions.show(OptTarget(series = x)) },
                                        badge = "▶ Series", badgeColor = C.Live, badgeTextColor = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        }
                    }
                }
                if (r.genres.isNotEmpty() || r.keywords.isNotEmpty()) item(key = "tags") {
                    Section("Genres & keywords") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.genres.forEach { g -> GenreChip(g) }
                            r.keywords.forEach { k ->
                                Chip("# ${k.name}", false, compact = true, onClick = {
                                    Pages.open(Page.Browse("“${k.name}”") {
                                        coroutineScope {
                                            val m = async { TmdbApi.discover(false, keywordId = k.id, pages = 2) }
                                            val t = async { TmdbApi.discover(true, keywordId = k.id, pages = 2) }
                                            (m.await() + t.await()).sortedByDescending { it.popularity }
                                        }
                                    })
                                })
                            }
                        }
                    }
                }
                if (r.titles.isNotEmpty()) item(key = "titles") {
                    Section("Movies & series") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                            items(libraryFirst(r.titles, index), key = { (if (it.isTv) "t" else "m") + it.tmdbId }) { t -> TitlePoster(t, index) }
                        }
                    }
                }
                if (r.people.isNotEmpty()) item(key = "people") {
                    Section("People") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                            items(r.people, key = { it.id }) { p ->
                                FocusCard(onClick = { Pages.open(Page.Person(p.id, p.name, p.photo)) }, modifier = Modifier.width(150.dp)) { _ ->
                                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        AsyncImage(model = p.photo, contentDescription = null, contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(96.dp).clip(CircleShape).background(C.Surface2))
                                        Text(p.name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                        Text(listOf(p.dept, p.knownFor).filter { it.isNotBlank() }.joinToString(" · "), color = C.Muted, fontSize = 11.sp,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                if (r.lib.isEmpty() && r.titles.isEmpty() && r.people.isEmpty() && r.keywords.isEmpty() && r.genres.isEmpty() && r.error == null)
                    item { Text("Nothing found for “$query”.", color = C.Muted) }
            }
        }
    }

    if (typing) SearchDialog(query, "Search titles, people, genres", onSearch = { query = it; typing = false }, onDismiss = { typing = false })
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun GenreChip(g: TmdbApi.Genre) {
    Chip(g.name, false, compact = true, onClick = {
        Pages.open(Page.Browse("${g.name} ${if (g.isTv) "series" else "movies"}") { TmdbApi.discover(g.isTv, genreId = g.id, pages = 3) })
    })
}
