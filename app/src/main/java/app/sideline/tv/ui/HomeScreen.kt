package app.sideline.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sideline.tv.SidelineApp
import app.sideline.tv.player.Play
import app.sideline.tv.sports.Game

@Composable
fun HomeScreen(goTo: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val repo = SidelineApp.repo
    val accounts by prefs.accounts.collectAsState()
    val board by SidelineApp.scores.board.collectAsState()
    val followed by prefs.followedGames.collectAsState()
    val favTeams by prefs.favTeams.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val channels by repo.channels.collectAsState()
    val movies by repo.movies.collectAsState()
    val series by repo.series.collectAsState()
    val recent by prefs.recentChannels.collectAsState()
    val favCh by prefs.favChannels.collectAsState()
    val errors by repo.errors.collectAsState()
    val progress by SidelineApp.progress.entries.collectAsState()
    var openGame by remember { mutableStateOf<Game?>(null) }
    var openMovie by remember { mutableStateOf<app.sideline.tv.data.Movie?>(null) }
    var openSeries by remember { mutableStateOf<app.sideline.tv.data.Series?>(null) }

    if (accounts.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyState("Welcome to Cooper TV", "Add your IPTV logins in Settings to get started.")
            Chip("Open Settings", selected = true, onClick = { goTo(Tab.Settings) }, modifier = Modifier.padding(start = 48.dp))
        }
        return
    }

    // Worked out only when the underlying data changes, not on every recomposition.
    val allGames = remember(board) { board.values.flatten().distinctBy { it.id } }
    val mine = remember(allGames, followed, favTeams) {
        allGames.filter { g -> g.id in followed || g.competitors.any { app.sideline.tv.sports.Leagues.teamKey(g.leagueId, it.id) in favTeams } }
            .sortedWith(compareBy({ !it.isLive }, { it.isFinal }, { it.startMillis }))
    }
    val liveNow = remember(allGames, mine) { allGames.filter { it.isLive && it !in mine }.take(15) }
    val recentCh = remember(recent, channels) { recent.mapNotNull { repo.channel(it) } }
    val favOrder by prefs.favOrder.collectAsState()
    val favList = remember(favCh, favOrder, channels) { prefs.orderedFavs().mapNotNull { repo.channel(it) } }
    // Latest releases: newest release year first (then most recently added), when the provider gives years.
    val latestMovies = remember(movies) {
        if (movies.none { it.year != null }) movies.takeLast(30).reversed()
        else movies.filter { it.year != null }.sortedWith(compareByDescending<app.sideline.tv.data.Movie> { it.year }.thenByDescending { it.added }).take(30)
    }
    // Your series: ones you've watched (most recent first), then ones you've starred.
    val favSeries by prefs.favSeries.collectAsState()
    val watchedSeries by prefs.watchedSeries.collectAsState()
    val yourSeries = remember(series, favSeries, watchedSeries) {
        val byKey = series.associateBy { "${it.accountId}:${it.seriesId}" }
        (watchedSeries + favSeries).distinct().mapNotNull { byKey[it] }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (errors.isNotEmpty()) item { EmptyState("Some content didn't load", errors.joinToString("\n")) }
        if (progress.isNotEmpty()) item {
            Column {
                SectionTitle("Continue watching")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(progress.take(20), key = { it.key }) { p -> ProgressCard(p, onClick = { Play.resume(ctx, p) }) }
                }
            }
        }
        if (favList.isNotEmpty()) item {
            Column {
                SectionTitle("On now · your favourites")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(favList, key = { it.key }) { c -> OnNowCard(c, onClick = { Play.channel(ctx, c, favList) }) }
                }
            }
        }
        if (mine.isNotEmpty()) item {
            Column {
                SectionTitle("My Teams & followed games")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(mine, key = { it.id }) { g -> GameCard(g, g.id in followed, spoilerFree, onClick = { openGame = g }) }
                }
            }
        }
        if (liveNow.isNotEmpty()) item {
            Column {
                SectionTitle("Live in your leagues")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(liveNow, key = { it.id }) { g -> GameCard(g, g.id in followed, spoilerFree, onClick = { openGame = g }) }
                }
            }
        }
        if (recentCh.isNotEmpty()) item {
            Column {
                SectionTitle("Recently watched")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(recentCh, key = { it.key }) { c -> PosterCard(c.name, c.logo, wide = true, onClick = { Play.channel(ctx, c, recentCh) }) }
                }
            }
        }
        if (movies.isNotEmpty()) item {
            Column {
                SectionTitle("Latest movie releases")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(latestMovies, key = { it.key }) { m -> PosterCard(m.name, m.poster, onClick = { openMovie = m }) }
                }
            }
        }
        if (yourSeries.isNotEmpty()) item {
            Column {
                SectionTitle("Your series")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(yourSeries, key = { "${it.accountId}:${it.seriesId}" }) { s -> SeriesTile(s, onClick = { openSeries = s }) }
                }
            }
        }
        if (mine.isEmpty() && liveNow.isEmpty()) item {
            Column {
                SectionTitle("Sports")
                Chip("Pick your leagues & teams", selected = true, onClick = { goTo(Tab.Settings) })
            }
        }
    }

    openGame?.let { g -> GameSheet(g, onDismiss = { openGame = null }) }
    openMovie?.let { m -> MovieSheet(m) { openMovie = null } }
    openSeries?.let { s -> SeriesSheet(s) { openSeries = null } }
}

/** Favourite channel with what's on now and how far through it is. Menu (≡) = channel options. */
@Composable
fun OnNowCard(c: app.sideline.tv.data.Channel, onClick: () -> Unit) {
    val guideVersion by app.sideline.tv.data.FullGuide.version.collectAsState()
    var now by remember(c.key) { mutableStateOf<app.sideline.tv.data.EpgItem?>(null) }
    var next by remember(c.key) { mutableStateOf<app.sideline.tv.data.EpgItem?>(null) }
    LaunchedEffect(c.key, guideVersion) {
        val items = SidelineApp.repo.epgCached(c)
        val t = System.currentTimeMillis() / 1000
        now = items.firstOrNull { t in it.start until it.end }
        next = items.firstOrNull { it.start >= t }
    }
    FocusCard(onClick = onClick, onMenu = { ChannelMenu.open(c, inFavourites = true) },
        modifier = Modifier.width(300.dp).height(132.dp)) { _ ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(14.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                coil.compose.AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(width = 52.dp, height = 30.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                Text(c.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val p = now
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            Text(p?.title ?: "No guide info", color = if (p != null) C.Text else C.Faint, fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (p != null) {
                val frac = ((System.currentTimeMillis() / 1000 - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(C.Surface2)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(frac).height(3.dp).background(C.Accent))
                }
                Text("Ends ${timeOf(p.end * 1000)}" + (next?.let { "  ·  Next: ${it.title}" } ?: ""), color = C.Faint, fontSize = 11.sp,
                    maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
