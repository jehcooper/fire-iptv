package app.sideline.tv.ui

import androidx.compose.foundation.layout.Arrangement
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
    val favList = remember(favCh, channels) { channels.filter { it.key in favCh } }
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
        if (favList.isNotEmpty()) item {
            Column {
                SectionTitle("Favourite channels")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(favList, key = { it.key }) { c -> PosterCard(c.name, c.logo, wide = true, onClick = { Play.channel(ctx, c, favList) }) }
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
