package app.sideline.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Discover
import app.sideline.tv.data.Movie
import app.sideline.tv.data.Series
import app.sideline.tv.data.Tmdb
import coil.compose.AsyncImage

/** Netflix-style Discover: a featured title on top, then rows of trending, new, popular and top-rated titles. */
@Composable
fun DiscoverScreen(goTo: (Tab) -> Unit) {
    val prefs = SidelineApp.prefs
    val tmdbKey by prefs.tmdbKey.collectAsState()
    val raw by Discover.raw.collectAsState()
    val status by Discover.status.collectAsState()
    val movies by SidelineApp.repo.movies.collectAsState()
    val series by SidelineApp.repo.series.collectAsState()
    var onlyPlayable by rememberSaveable { mutableStateOf(true) }
    var openMovie by remember { mutableStateOf<Movie?>(null) }
    var openSeries by remember { mutableStateOf<Series?>(null) }
    var openInfo by remember { mutableStateOf<Discover.Title?>(null) }

    LaunchedEffect(tmdbKey) { Discover.load() }
    val index = remember(movies, series) { Discover.Index(movies, series) }
    val rows = remember(raw, index, onlyPlayable) { Discover.rows(raw, index, onlyPlayable) }

    fun open(i: Discover.Item) {
        when {
            i.movie != null -> openMovie = i.movie
            i.series != null -> openSeries = i.series
            else -> openInfo = i.t
        }
    }

    if (tmdbKey.isBlank() || !Tmdb.configured) {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Text("Discover what's trending", color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Discover shows trending, new, popular and top-rated movies and series, matched against your own library so you can press Play. " +
                    "The lists come from TMDB (The Movie Database), which needs a free key: sign up at themoviedb.org, then Settings → API. " +
                    "Add it in Cooper TV under Settings → Movie & series info.",
                color = C.Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp, bottom = 20.dp).width(820.dp),
            )
            Chip("Open Settings", true, onClick = { goTo(Tab.Settings) })
        }
        return
    }

    val hero = rows.firstOrNull()?.items?.firstOrNull { it.t.backdrop != null }
    LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (hero != null) item { Hero(hero, onClick = { open(hero) }) }
        item {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(if (onlyPlayable) "✓ Only what I can play" else "Showing everything", onlyPlayable, onClick = { onlyPlayable = !onlyPlayable })
                Chip("⟳ Refresh", false, onClick = { Discover.load(force = true) })
                if (status.isNotBlank()) Text(status, color = C.Muted, fontSize = 13.sp)
            }
        }
        if (rows.isEmpty()) item {
            EmptyState(
                if (raw.isEmpty()) "Loading Discover…" else "Nothing from these lists is in your library yet",
                if (raw.isEmpty()) "Fetching what's trending." else "Turn off \"Only what I can play\" to see everything that's trending.",
            )
        }
        items(rows, key = { it.title }) { row ->
            Column(Modifier.padding(start = 48.dp)) {
                SectionTitle(row.title)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 48.dp, top = 4.dp, bottom = 4.dp)) {
                    items(row.items, key = { it.t.tmdbId + it.t.isTv }) { i ->
                        PosterCard(
                            i.t.title, i.movie?.poster?.takeIf { i.t.poster == null } ?: i.t.poster,
                            onClick = { open(i) },
                            badge = if (!i.playable) "Not in library" else i.t.score?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        )
                    }
                }
            }
        }
        item {
            Text("Lists from TMDB, updated every 12 hours. Matching to your library is by title and year, so the odd one may be missed.",
                color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 48.dp))
        }
    }

    openMovie?.let { MovieSheet(it) { openMovie = null } }
    openSeries?.let { SeriesSheet(it) { openSeries = null } }
    openInfo?.let { t -> NotInLibrarySheet(t) { openInfo = null } }
}

/** Big featured title at the top, like streaming apps do. */
@Composable
private fun Hero(i: Discover.Item, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        AsyncImage(model = i.t.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.Bg, C.Bg.copy(alpha = 0.75f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, C.Bg))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 48.dp).width(560.dp)) {
            Text("TRENDING NOW", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(i.t.title, color = C.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(listOfNotNull(i.t.year.ifBlank { null }, i.t.score?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, if (i.t.isTv) "Series" else "Movie")
                .joinToString("  ·  "), color = C.Muted, fontSize = 14.sp)
            Text(i.t.overview, color = C.Text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Chip(if (i.playable) "▶ Watch" else "More info", true, onClick = onClick)
        }
    }
}

@Composable
private fun NotInLibrarySheet(t: Discover.Title, onDismiss: () -> Unit) {
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
            Row(Modifier.width(860.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(28.dp)) {
                AsyncImage(model = t.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 180.dp, height = 270.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(t.title, color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(t.year.ifBlank { null }, t.score?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, if (t.isTv) "Series" else "Movie")
                        .joinToString("  ·  "), color = C.Accent, fontSize = 14.sp)
                    Text(t.overview, color = C.Text, fontSize = 15.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text("This title isn't in your movie or series library (or its name there is different).", color = C.Gold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 14.dp))
                    Spacer(Modifier.height(14.dp))
                    Chip("Close", true, onClick = onDismiss)
                }
            }
        }
    }
}
