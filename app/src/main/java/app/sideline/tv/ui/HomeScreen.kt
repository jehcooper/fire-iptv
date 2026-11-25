package app.sideline.tv.ui

import app.sideline.tv.data.Series
import app.sideline.tv.data.Movie
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.onFocusChanged
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
            EmptyState("Welcome to Empire TV", "Add your IPTV logins in Settings to get started.")
            Chip("Open Settings", selected = true, onClick = { goTo(Tab.Settings) }, modifier = Modifier.padding(start = 48.dp))
        }
        return
    }

    // Worked out only when the underlying data changes, not on every recomposition.
    val allGames = remember(board) { board.values.flatten().distinctBy { it.id } }
    val leagueOpts by prefs.leagueOpts.collectAsState()
    val mine = remember(allGames, followed, favTeams, leagueOpts) {
        allGames.filter { g ->
            g.id in followed || g.competitors.any { app.sideline.tv.sports.Leagues.teamKey(g.leagueId, it.id) in favTeams } ||
                // Followed leagues with "live games on Home": their live games join this row.
                (g.isLive && "${g.leagueId}|live" in leagueOpts)
        }
            .sortedWith(compareBy({ !it.isLive }, { it.isFinal }, { it.startMillis }))
    }
    val liveNow = remember(allGames, mine) { allGames.filter { it.isLive && it !in mine }.take(15) }
    val watch by prefs.watchlist.collectAsState()
    val moviesW by SidelineApp.repo.movies.collectAsState()
    val seriesW by SidelineApp.repo.series.collectAsState()
    val watchItems: List<Any> = remember(watch, moviesW, seriesW) {
        watch.mapNotNull { k ->
            when {
                k.startsWith("vod:") -> moviesW.firstOrNull { "vod:${it.key}" == k }
                k.startsWith("series:") -> seriesW.firstOrNull { "series:${it.accountId}:${it.seriesId}" == k }
                else -> null
            }
        }.take(30)
    }
    val recentCh = remember(recent, channels) { recent.mapNotNull { repo.channel(it) }.filterNot { app.sideline.tv.data.isEventChannel(it.baseName) } }
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
        contentPadding = PaddingValues(start = 48.dp, top = 8.dp, end = 48.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (app.sideline.tv.data.Tmdb.configured) item(key = "hero") { ForYouHero() }
        if (errors.isNotEmpty()) item { EmptyState("Some content didn't load", errors.joinToString("\n")) }
        val contMovies = progress.filter { it.kind == "movie" }
        val contSeries = progress.filter { it.kind == "episode" }
        if (contMovies.isNotEmpty()) item {
            Column {
                SectionTitle("Continue watching · Movies")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(contMovies.take(20), key = { it.key }) { p -> ProgressCard(p, onClick = { Play.resume(ctx, p) }) }
                }
            }
        }
        if (contSeries.isNotEmpty()) item {
            Column {
                SectionTitle("Continue watching · Series")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(contSeries.take(20), key = { it.key }) { p -> ProgressCard(p, onClick = { Play.resume(ctx, p) }) }
                }
            }
        }
        if (watchItems.isNotEmpty()) item {
            Column {
                SectionTitle("Your watchlist")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(watchItems, key = { (if (it is Movie) "m" else "s") + it.hashCode() }) { x ->
                        when (x) {
                            is Movie -> PosterCard(x.name, x.poster, onClick = { Pages.movie(x) }, kind = "movie", onMenu = { TitleOptions.show(OptTarget(movie = x)) })
                            is Series -> PosterCard(x.name, x.cover, onClick = { Pages.series(x) }, kind = "series", onMenu = { TitleOptions.show(OptTarget(series = x)) })
                        }
                    }
                }
            }
        }
        if (favList.isNotEmpty()) item {
            Column {
                SectionTitle("On now · your favourites")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(favList, key = { it.key }) { c -> OnNowCard(c, onClick = { Play.channel(ctx, c, favList) }) }
                }
            }
        }
        if (mine.isNotEmpty()) item {
            Column {
                SectionTitle("My Teams & followed games")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(mine, key = { it.id }) { g -> GameCard(g, g.id in followed, spoilerFree, onClick = { openGame = g }, compact = true) }
                }
            }
        }
        item { StandingsSection(allGames, favTeams, leagueOpts) }
        if (liveNow.isNotEmpty()) item {
            Column {
                SectionTitle("Live in your leagues")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(liveNow, key = { it.id }) { g -> GameCard(g, g.id in followed, spoilerFree, onClick = { openGame = g }, compact = true) }
                }
            }
        }
        if (recentCh.isNotEmpty()) item {
            Column {
                SectionTitle("Recently watched")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(recentCh, key = { it.key }) { c -> PosterCard(c.name, c.logo, wide = true, onClick = { Play.channel(ctx, c, recentCh) }) }
                }
            }
        }
        if (movies.isNotEmpty()) item {
            Column {
                SectionTitle("Latest movie releases")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                    items(latestMovies, key = { it.key }) { m -> PosterCard(m.name, m.poster, onClick = { openMovie = m }, kind = "movie", onMenu = { TitleOptions.show(OptTarget(movie = m)) }) }
                }
            }
        }
        if (yourSeries.isNotEmpty()) item {
            Column {
                SectionTitle("Your series")
                LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
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
                Text("Ends ${timeOf(p.end * 1000)}" + (next?.let { "  ·  Next: ${it.title}" } ?: ""), color = C.Faint, fontSize = 12.sp,
                    maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Big banner at the top of Home: first what you were last watching (with Resume), then what's trending this week
 * that you can watch. The picture fills the screen behind. › moves on; it also moves on by itself every 9 s
 * while you're elsewhere on the page.
 */
@Composable
private fun ForYouHero() {
    val ctx = LocalContext.current
    val raw by app.sideline.tv.data.Discover.raw.collectAsState()
    val progress by SidelineApp.progress.entries.collectAsState()
    val movies by SidelineApp.repo.movies.collectAsState()
    val seriesAll by SidelineApp.repo.series.collectAsState()
    val index = rememberLibraryIndex()
    LaunchedEffect(Unit) { app.sideline.tv.data.Discover.load(); app.sideline.tv.data.TmdbApi.genres() }
    val last = progress.firstOrNull()
    val lastArt = rememberTmdbArt(last?.title ?: "", last?.kind == "episode")
    val picks = remember(raw, index) {
        val lists = raw.filter { it.first.startsWith("Trending") || it.first.startsWith("New") }.map { it.second }
        val all = lists.flatten().filter { it.backdrop != null }.distinctBy { (if (it.isTv) "t" else "m") + it.tmdbId }
        val playable = if (index == null) emptyList() else all.filter { t -> if (t.isTv) index.series(t) != null else index.movie(t) != null }
        (playable.ifEmpty { all }).take(7)
    }
    val count = picks.size + (if (last != null) 1 else 0)
    if (count == 0) return
    var i by remember { mutableStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val k = i % count
    val showLast = last != null && k == 0
    val cur = if (showLast) null else picks[k - (if (last != null) 1 else 0)]
    val backdrop = if (showLast) lastArt?.backdrop ?: last?.image else cur?.backdrop
    LaunchedEffect(backdrop) { Backdrop.url.value = backdrop }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Backdrop.url.value = null } }
    LaunchedEffect(focused, count) {
        while (!focused) { kotlinx.coroutines.delay(9000); i = (i + 1) % count }
    }
    val m = cur?.let { if (!it.isTv) index?.movie(it) else null }
    val sr = cur?.let { if (it.isTv) index?.series(it) else null }

    val label: String; val kindLabel: String; val title: String; val meta: String; val overview: String
    if (showLast && last != null) {
        label = "CONTINUE WATCHING"; kindLabel = if (last.kind == "episode") "Series" else "Movie"
        title = lastArt?.title ?: app.sideline.tv.data.MovieInfoService.cleanTitle(last.title).first
        meta = listOfNotNull(last.subtitle.takeIf { it.isNotBlank() }, "${fmtLeft((last.duration - last.position).coerceAtLeast(0))} left",
            lastArt?.year?.takeIf { it.isNotBlank() }).joinToString(" · ")
        overview = lastArt?.overview ?: ""
    } else {
        val c = cur!!
        label = "TRENDING THIS WEEK"; kindLabel = if (c.isTv) "Series" else "Movie"
        title = c.title
        meta = listOfNotNull(app.sideline.tv.data.TmdbApi.genreNames(c).take(2).joinToString(", ").takeIf { it.isNotBlank() },
            c.year.takeIf { it.isNotBlank() }, c.score?.let { "★ %.1f".format(it) }).joinToString(" · ")
        overview = c.overview
    }

    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(top = 40.dp), verticalArrangement = Arrangement.Bottom) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, color = C.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            Text(kindLabel, color = C.Muted, fontSize = 12.sp)
        }
        Text(title, color = C.Text, fontSize = 44.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).width(700.dp))
        Text(meta, color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        if (showLast && last != null) androidx.compose.foundation.layout.Box(
            Modifier.padding(top = 8.dp).width(220.dp).height(4.dp).background(C.Surface2, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(last.fraction).height(4.dp).background(C.Accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
        }
        if (overview.isNotBlank()) Text(overview, color = C.Text, fontSize = 15.sp, lineHeight = 1.4.em, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(620.dp))
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 14.dp).onFocusChanged { focused = it.hasFocus },
        ) {
            if (showLast && last != null) {
                Chip("▶  Resume", true, onClick = { Play.resume(ctx, last) })
                Chip("Details", false, onClick = {
                    if (last.kind == "movie") movies.firstOrNull { Play.movieKey(it) == last.key }?.let { Pages.movie(it) }
                    else seriesAll.firstOrNull { it.accountId == last.accountId && it.seriesId == last.seriesId }?.let { Pages.series(it) }
                })
            } else {
                if (m != null) Chip("▶  Play", true, onClick = { Play.movie(ctx, m) })
                Chip("Details", m == null, onClick = { Pages.open(Page.Title(cur!!, m, sr)) })
            }
            Chip("›  More", false, onClick = { i = (i + 1) % count })
            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until count).forEach { d ->
                    androidx.compose.foundation.layout.Box(Modifier.height(4.dp).width(if (d == k) 18.dp else 6.dp)
                        .background(if (d == k) C.Accent else C.Faint, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
