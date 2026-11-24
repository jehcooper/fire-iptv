package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import app.sideline.tv.sports.LeagueKind
import app.sideline.tv.sports.StandGroup
import app.sideline.tv.sports.StandRow
import app.sideline.tv.sports.Standings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.player.Play
import app.sideline.tv.sports.ChannelMatch
import app.sideline.tv.sports.MatchTier
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.GameMatcher
import app.sideline.tv.sports.Leagues
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class StateFilter(val label: String) { ALL("All games"), LIVE("Live"), UPCOMING("Upcoming"), FINAL("Final") }
private enum class FavFilter(val label: String) { ALL("All teams"), MINE("My teams"), FOLLOWED("Pop-ups & pinned") }

/** Rough game length, only for the estimated end time and progress bar (shown with "~"). */
fun estDurationMin(leagueId: String): Int {
    val p = Leagues.byId(leagueId)?.path ?: ""
    return when {
        p.startsWith("football/college") -> 210
        p.startsWith("football") -> 190
        p.startsWith("hockey") -> 160
        p.startsWith("basketball/nba") || p.startsWith("basketball/wnba") -> 145
        p.startsWith("basketball") -> 125
        p.startsWith("baseball") -> 175
        p.startsWith("soccer") -> 115
        p.startsWith("tennis") -> 150
        p.startsWith("golf") -> 300
        p.startsWith("racing") -> 120
        else -> 240
    }
}

private fun dayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = ms
    val d = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = System.currentTimeMillis() + 86_400_000L
    val tomorrow = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    return when (d) {
        today -> "Today"
        tomorrow -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

/**
 * Sports tab: a list of games on the left (time + progress, league and clock, both teams with their table position,
 * scores) and the highlighted game's details on the right (channels showing it, pop-ups/pin, live stats).
 * Filters along the top; "Tables" switches to full league tables.
 */
@Composable
fun SportsScreen(goTo: (Tab) -> Unit) {
    val prefs = SidelineApp.prefs
    val board by SidelineApp.scores.board.collectAsState()
    val favLeagues by prefs.favLeagues.collectAsState()
    val followed by prefs.followedGames.collectAsState()
    val pinned by prefs.pinnedGames.collectAsState()
    val favTeams by prefs.favTeams.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val favConfs by prefs.favConferences.collectAsState()
    val confs by SidelineApp.scores.conferences.collectAsState()
    val leagues = Leagues.all.filter { it.id in favLeagues } +
        confs.filter { it.id in favConfs }.map { Leagues.conferenceLeague(it) }
    val order = leagues.map { it.id }

    var stateF by rememberSaveable { mutableStateOf(StateFilter.ALL) }
    var sport by rememberSaveable { mutableStateOf("all") }
    var favF by rememberSaveable { mutableStateOf(FavFilter.ALL) }
    var tables by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var shownId by remember { mutableStateOf<String?>(null) }
    var openGame by remember { mutableStateOf<Game?>(null) }
    val detailsFirst = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Wait until the highlight settles before loading details, so scrolling stays smooth.
    LaunchedEffect(focusedId) { delay(350); if (focusedId != null) shownId = focusedId }

    BackHandler(enabled = tables != null) { tables = null }
    // Event channels ("NHL 03: Flames at Senators") are renamed daily, so use a fresh channel list.
    LaunchedEffect(Unit) { SidelineApp.repo.refreshLiveIfOlder(60 * 60_000L) }

    val all = remember(board) { board.values.flatten().distinctBy { it.id } }
    // League tables give each team's position "(3)"; downloaded in the background, cached 15 minutes.
    var standTick by remember { mutableStateOf(0) }
    val tableLeagues = remember(all) {
        all.mapNotNull { Leagues.byId(it.leagueId) }.filter { it.kind == LeagueKind.TEAM }.map { it.baseId }.distinct()
    }
    LaunchedEffect(tableLeagues) {
        tableLeagues.forEach { id -> Leagues.byId(id)?.let { Standings.official(it) }; standTick++ }
    }

    fun isMine(g: Game) = g.competitors.any { Leagues.teamKey(g.leagueId, it.id) in favTeams }
    val bySport = if (sport == "all") all else board[sport].orEmpty()
    val byFav = when (favF) {
        FavFilter.ALL -> bySport
        FavFilter.MINE -> bySport.filter { isMine(it) }
        FavFilter.FOLLOWED -> bySport.filter { it.id in followed || it.id in pinned }
    }
    val counts = mapOf(
        StateFilter.ALL to byFav.size,
        StateFilter.LIVE to byFav.count { it.isLive },
        StateFilter.UPCOMING to byFav.count { it.state == "pre" },
        StateFilter.FINAL to byFav.count { it.isFinal },
    )
    fun sortKey(list: List<Game>, newestFirst: Boolean) = list.sortedWith(
        compareByDescending<Game> { isMine(it) }
            .thenBy { order.indexOf(it.leagueId).let { i -> if (i < 0) 999 else i } }
            .thenBy { if (newestFirst) -it.startMillis else it.startMillis },
    )
    val sections = listOf(
        "Live" to sortKey(byFav.filter { it.isLive }, false),
        "Upcoming" to sortKey(byFav.filter { it.state == "pre" }, false),
        "Final" to sortKey(byFav.filter { it.isFinal }, true),
    ).filter { (t, l) ->
        l.isNotEmpty() && when (stateF) {
            StateFilter.ALL -> true; StateFilter.LIVE -> t == "Live"; StateFilter.UPCOMING -> t == "Upcoming"; StateFilter.FINAL -> t == "Final"
        }
    }
    val shownGames = sections.flatMap { it.second }
    val shown = shownGames.firstOrNull { it.id == shownId } ?: shownGames.firstOrNull()
    val sportName = if (sport == "all") "All sports" else Leagues.byId(sport)?.name ?: sport

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 10.dp)) {
            if (tables == null) {
                Chip("${stateF.label} (${counts[stateF] ?: 0}) ▾", stateF != StateFilter.ALL, onClick = { picker = "state" }, compact = true)
                Chip("$sportName ▾", sport != "all", onClick = { picker = "sport" }, compact = true)
            }
            Chip(if (tables != null) "Tables: ${Leagues.byId(tables!!)?.name ?: ""} ▾" else "Tables ▾", tables != null,
                onClick = { picker = "tables" }, compact = true)
            if (tables == null) Chip("${favF.label} ▾", favF != FavFilter.ALL, onClick = { picker = "fav" }, compact = true)
            Chip("⚙", false, onClick = { goTo(Tab.Settings) }, compact = true)
            Spacer(Modifier.weight(1f))
            Text(if (tables != null) "Back: return to games" else "OK: actions  ·  ≡: pop-ups  ·  ~ end time is an estimate",
                color = C.Faint, fontSize = 12.sp)
        }

        val t = tables
        if (t != null) {
            TablesView(t, remember(favTeams) { favTeams.map { it.substringAfter('|') }.toSet() })
        } else if (shownGames.isEmpty()) {
            EmptyState("No games here", if (board.isEmpty()) "Loading scores…" else "Nothing matches these filters right now. Try “All games” or “All sports”.")
        } else Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(470.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp)) {
                sections.forEach { (title, list) ->
                    item(key = "h-$title") {
                        Text("$title · ${list.size}", color = if (title == "Live") C.Live else C.Muted, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp))
                    }
                    items(list, key = { it.id }) { g ->
                        GameListRow(
                            g, standTick, g.id in followed, g.id in pinned, spoilerFree, selected = g.id == shown?.id,
                            onFocus = { focusedId = g.id },
                            onClick = {
                                shownId = g.id
                                scope.launch { delay(250); try { detailsFirst.requestFocus() } catch (_: Exception) {} }
                            },
                            onMenu = { prefs.toggleFollow(g.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                shown?.let { SportsDetails(liveGame(it), detailsFirst, onMore = { openGame = it }) }
            }
        }
    }

    when (picker) {
        "state" -> PickDialog("Show", StateFilter.values().map { it.name to "${it.label} (${counts[it] ?: 0})" }, stateF.name,
            onPick = { stateF = StateFilter.valueOf(it); picker = null }, onDismiss = { picker = null })
        "sport" -> PickDialog("Sport / league", listOf("all" to "All sports (${all.size})") + leagues.map { l ->
                val n = board[l.id].orEmpty(); l.id to "${l.name} (${n.size}${n.count { it.isLive }.let { if (it > 0) " · $it live" else "" }})"
            }, sport, onPick = { sport = it; picker = null }, onDismiss = { picker = null })
        "fav" -> PickDialog("Teams", FavFilter.values().map { it.name to it.label }, favF.name,
            onPick = { favF = FavFilter.valueOf(it); picker = null }, onDismiss = { picker = null })
        "tables" -> {
            val opts = (Leagues.all.filter { it.kind == LeagueKind.TEAM }
                .sortedByDescending { l -> l.id in favLeagues || favTeams.any { it.startsWith(l.id + "|") } })
                .map { it.id to it.name }
            PickDialog("League tables", (if (tables != null) listOf("" to "← Back to games") else emptyList()) + opts, tables ?: "",
                onPick = { tables = it.ifBlank { null }; picker = null }, onDismiss = { picker = null })
        }
    }
    openGame?.let { g -> GameSheet(g, onDismiss = { openGame = null }) }
}

/** One game line, like a sports-bar table: time + progress | league · clock, both teams (position) | scores. */
@Composable
private fun GameListRow(
    g: Game, standTick: Int, followed: Boolean, pinned: Boolean, spoilerFree: Boolean, selected: Boolean,
    onFocus: () -> Unit, onClick: () -> Unit, onMenu: () -> Unit,
) {
    val tag = Leagues.byId(g.leagueId)?.tag ?: ""
    val end = if (g.startMillis > 0) g.startMillis + estDurationMin(g.leagueId) * 60_000L else 0L
    val progress = when {
        g.isFinal -> 1f
        g.isLive && end > g.startMillis -> ((System.currentTimeMillis() - g.startMillis).toFloat() / (end - g.startMillis)).coerceIn(0.03f, 0.97f)
        else -> 0f
    }
    FocusCard(onClick = onClick, onFocus = onFocus, onMenu = onMenu, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp), scaleOnFocus = 1.01f) { focused ->
        if (selected && !focused) Box(Modifier.matchParentSize().background(C.Accent.copy(alpha = 0.08f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(76.dp)) {
                if (g.startMillis > 0) {
                    Text(timeOf(g.startMillis), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("~" + timeOf(end), color = C.Faint, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Surface2)) {
                    if (progress > 0f) Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(if (g.isLive) C.Live else C.Faint))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tag, color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            g.isLive -> "● " + g.detail
                            g.state == "pre" -> listOf(if (g.startMillis > 0) dayLabel(g.startMillis) else "", g.broadcasts.firstOrNull() ?: "")
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            else -> g.detail
                        },
                        color = if (g.isLive) C.Live else C.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (followed) Text("🔔", fontSize = 11.sp)
                    if (pinned) Text("📌", fontSize = 11.sp)
                }
                if (g.competitors.size == 2) {
                    val a = g.away; val h = g.home
                    val sa = a?.score?.toIntOrNull(); val sh = h?.score?.toIntOrNull()
                    listOfNotNull(a, h).forEach { c ->
                        val won = g.isFinal && sa != null && sh != null && sa != sh && ((c == a && sa > sh) || (c == h && sh > sa))
                        val lost = g.isFinal && sa != null && sh != null && sa != sh && !won
                        val rank = remember(standTick, c.id, g.leagueId) { c.pollRank ?: Standings.rankOf(g.leagueId, c.id) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            // Name + (position) take all the room up to the score; the name only shortens if it truly can't fit.
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name.ifBlank { c.shortName }, color = if (lost) C.Muted else C.Text, fontSize = 15.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                rank?.let { Text("  ($it)", color = C.Faint, fontSize = 13.sp, maxLines = 1, softWrap = false) }
                            }
                            if (g.state != "pre") Text(if (spoilerFree) "•" else c.score, color = if (lost) C.Muted else C.Text,
                                fontSize = 17.sp, fontWeight = if (won || g.isLive) FontWeight.Bold else FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else {
                    Text(g.title, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    g.competitors.firstOrNull()?.let { c ->
                        Text("1. ${c.name}  ${if (spoilerFree) "" else c.score}", color = C.Muted, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Right-hand panel for the highlighted game. Every block can take focus, so ▲▼ scrolls through all of it. */
@Composable
private fun SportsDetails(game: Game, first: FocusRequester, onMore: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val followed by prefs.followedGames.collectAsState()
    val pinned by prefs.pinnedGames.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val matches = rememberMatches(game)
    var detail by remember(game.id) { mutableStateOf<app.sideline.tv.sports.GameDetail?>(null) }
    LaunchedEffect(game.id) {
        if (game.state == "pre") return@LaunchedEffect
        while (true) {
            app.sideline.tv.sports.GameDetails.load(SidelineApp.scores.game(game.id) ?: game)?.let { detail = it }
            delay(if ((SidelineApp.scores.game(game.id) ?: game).isLive) 30_000 else 5 * 60_000)
        }
    }
    val end = if (game.startMillis > 0) game.startMillis + estDurationMin(game.leagueId) * 60_000L else 0L
    LazyColumn(
        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(C.Surface.copy(alpha = 0.7f)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp),
    ) {
        item(key = "head") { Column { ScoreHeader(game, spoilerFree, compact = true) } }
        item(key = "info") {
            Column {
                val info = listOfNotNull(
                    if (game.startMillis > 0) "${dayLabel(game.startMillis)} ${timeOf(game.startMillis)} – ~${timeOf(end)}" else null,
                    game.venue.takeIf { it.isNotBlank() },
                )
                info.forEach { Text(it, color = C.Muted, fontSize = 13.sp) }
                if (game.competitors.size == 2) {
                    val recs = listOfNotNull(game.away, game.home).filter { it.record.isNotBlank() }
                    if (recs.isNotEmpty()) Text("Records: " + recs.joinToString("  ·  ") { "${it.abbr.ifBlank { it.shortName }} ${it.record}" },
                        color = C.Muted, fontSize = 13.sp)
                }
            }
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (game.id in followed) "🔔 Pop-ups on" else "🔕 Pop-ups off", game.id in followed,
                    onClick = { prefs.toggleFollow(game.id) }, modifier = Modifier.focusRequester(first), compact = true)
                Chip(if (game.id in pinned) "📌 Pinned" else "📌 Pin", game.id in pinned, onClick = { prefs.togglePin(game.id) }, compact = true)
                Chip("Full screen", false, onClick = onMore, compact = true)
            }
        }
        item(key = "ch-title") { Text("Channels showing it — OK to watch", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        when {
            matches == null -> item(key = "ch-wait") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Searching your channels…", color = C.Muted, fontSize = 13.sp)
                }
            }
            matches.isEmpty() -> item(key = "ch-none") {
                Text("No channel found yet. Still checking the TV guide; or try Live TV's sports categories.", color = C.Muted, fontSize = 13.sp)
            }
            else -> items(matches.take(8), key = { "ch-" + it.channel.key }) { m ->
                MatchRow(m, onClick = { Play.channel(ctx, m.channel, matches.map { it.channel }) })
            }
        }
        if (!spoilerFree) {
            if (game.isLive || !game.soccer?.goals.isNullOrEmpty()) item(key = "live") {
                FocusCard(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), scaleOnFocus = 1f) { _ ->
                    Box(Modifier.padding(10.dp)) { LiveDetailsFull(game) }
                }
            }
            detail?.let { d ->
                item(key = "detail") {
                    GameDetailView(d)
                }
            }
        }
    }
}

/** Simple ▾ menu: a short list in a panel; OK picks, Back closes. */
@Composable
private fun PickDialog(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val start = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(440.dp).fillMaxHeight(0.85f).clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(18.dp)) {
                Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    itemsIndexed(options, key = { _, o -> o.first }) { i, (key, label) ->
                        FocusCard(onClick = { onPick(key) }, modifier = Modifier.fillMaxWidth().let { if (i == start) it.focusRequester(first) else it },
                            shape = RoundedCornerShape(10.dp), scaleOnFocus = 1.02f) { _ ->
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (key == selected) "✓" else "", color = C.Accent, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                                Text(label, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Sports → Tables: every group of one league, live games counted "as if they ended now". ▲▼ scrolls line by line. */
@Composable
private fun TablesView(leagueId: String, favIds: Set<String>) {
    var groups by remember(leagueId) { mutableStateOf<List<StandGroup>?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(leagueId) {
        val l = Leagues.byId(leagueId) ?: return@LaunchedEffect
        while (true) {
            val off = Standings.official(l)
            val games = SidelineApp.scores.board.value.values.flatten().filter { Leagues.byId(it.leagueId)?.baseId == l.baseId }
            groups = Standings.project(l, off, games)
            delay(60_000)
        }
    }
    val l = Leagues.byId(leagueId)
    val g = groups
    when {
        g == null -> EmptyState("Loading tables…", "")
        g.isEmpty() -> EmptyState("No table available", "ESPN didn't return standings for this league right now.")
        else -> {
            // Flat list (group headers + lines) so we can scroll straight to your team.
            val entries = remember(g) { g.flatMapIndexed { gi, grp -> listOf(Triple(gi, -1, null as StandRow?)) + grp.rows.mapIndexed { i, r -> Triple(gi, i, r) } } }
            val target = remember(g, favIds) {
                entries.indexOfFirst { e -> e.third?.teamId?.let { it in favIds } == true }.takeIf { it >= 0 } ?: entries.indexOfFirst { it.third != null }.coerceAtLeast(0)
            }
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (target - 4).coerceAtLeast(0))
            LaunchedEffect(leagueId) { delay(250); try { first.requestFocus() } catch (_: Exception) {} }
            LazyColumn(state = state, modifier = Modifier.width(760.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp)) {
                itemsIndexed(entries, key = { _, e -> "${e.first}-${e.third?.teamId ?: "h"}" }) { idx, (gi, i, r) ->
                    if (r == null) {
                        Column(Modifier.padding(top = if (gi == 0) 0.dp else 14.dp)) {
                            Text(g[gi].name, color = C.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            StandHeader(l)
                        }
                    } else {
                        FocusCard(onClick = {}, shape = RoundedCornerShape(6.dp), scaleOnFocus = 1f,
                            modifier = if (idx == target) Modifier.focusRequester(first) else Modifier) { _ ->
                            Box(Modifier.padding(horizontal = 6.dp)) { StandLine(l, i, r, favIds, full = true) }
                        }
                    }
                }
            }
        }
    }
}

/** Live view of one game: updates as scores come in. */
@Composable
fun liveGame(game: Game): Game {
    val board by SidelineApp.scores.board.collectAsState()
    return remember(board, game.id) { board.values.flatten().firstOrNull { it.id == game.id } ?: game }
}

/** Channel matches for a game: instant results first, TV-guide results fill in without blocking. */
@Composable
fun rememberMatches(game: Game): List<ChannelMatch>? {
    val updates by GameMatcher.updates.collectAsState()
    var quick by remember(game.id) { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(game.id) {
        if (GameMatcher.cached(game.id) == null) {
            quick = GameMatcher.quick(game, SidelineApp.repo)
            GameMatcher.prefetch(listOf(game), SidelineApp.repo)
        }
    }
    return remember(updates, quick, game.id) { GameMatcher.cached(game.id) ?: quick }
}

@Composable
fun MatchRow(m: ChannelMatch, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth(), scaleOnFocus = 1.02f) { _ ->
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = m.channel.logo, contentDescription = null, modifier = Modifier.size(width = 64.dp, height = 36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.channel.name, color = C.Text, fontSize = 16.sp, maxLines = 1)
                Text(m.reason, color = C.Faint, fontSize = 12.sp, maxLines = 1)
            }
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (m.strong) C.Accent else C.Surface2).padding(6.dp)) {
                Text(if (m.strong) "likely" else "maybe", color = if (m.strong) C.Bg else C.Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun ScoreHeader(live: Game, spoilerFree: Boolean, compact: Boolean = false) {
    Text(Leagues.byId(live.leagueId)?.name ?: "", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (live.competitors.size == 2) {
        live.competitors.sortedBy { it.home }.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(if (compact) 30.dp else 44.dp))
                Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
                Text(c.name, color = C.Text, fontSize = if (compact) 17.sp else 21.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (live.state != "pre") Text(if (spoilerFree) "•" else c.score, color = C.Text, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Text(live.title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        if (live.isLive) { Pill("LIVE", C.Live, Color.White); Spacer(Modifier.width(8.dp)) }
        Text(
            if (live.state == "pre" && live.startMillis > 0) "Starts ${dayTimeOf(live.startMillis)}" else live.detail,
            color = C.Muted, fontSize = 15.sp,
        )
    }
    if (live.broadcasts.isNotEmpty()) Text("On: ${live.broadcasts.joinToString(", ")}", color = C.Muted, fontSize = 14.sp)
}

/** Game details: channels showing it (pre-loaded), pop-ups and pin. */
@Composable
fun GameSheet(game: Game, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val followed by prefs.followedGames.collectAsState()
    val pinned by prefs.pinnedGames.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val live = liveGame(game)
    val matches = rememberMatches(game)
    val first = remember { FocusRequester() }
    var detail by remember(game.id) { mutableStateOf<app.sideline.tv.sports.GameDetail?>(null) }

    // Keep the clock, score and game detail fresh while this panel is open.
    LaunchedEffect(game.id) {
        while (true) { SidelineApp.scores.refreshNow(); delay(15_000) }
    }
    LaunchedEffect(game.id) {
        while (true) {
            app.sideline.tv.sports.GameDetails.load(SidelineApp.scores.game(game.id) ?: game)?.let { detail = it }
            delay(if ((SidelineApp.scores.game(game.id) ?: game).isLive) 30_000 else 5 * 60_000)
        }
    }
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }

    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.CenterEnd) {
            Row(Modifier.fillMaxHeight().width(860.dp).background(C.Surface).padding(start = 28.dp, top = 24.dp, end = 20.dp)) {
                // Left: score and everything about the game.
                // ◀ moves in here from the buttons; ▲▼ then steps through every block and scrolls.
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 60.dp)) {
                    item { ScoreHeader(live, spoilerFree, compact = true) }
                    if (!spoilerFree) {
                        if (live.isLive || !live.soccer?.goals.isNullOrEmpty()) item {
                            FocusCard(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), scaleOnFocus = 1f) { _ ->
                                Box(Modifier.padding(10.dp)) { LiveDetailsFull(live) }
                            }
                        }
                        detail?.let { d -> item { GameDetailView(d) } }
                    }
                }
                Spacer(Modifier.width(20.dp))
                // Right: actions and channels showing it.
                Column(Modifier.width(300.dp)) {
                    Chip(if (live.id in followed) "🔔 Pop-ups on" else "🔕 Pop-ups off", live.id in followed,
                        onClick = { prefs.toggleFollow(live.id) }, modifier = Modifier.focusRequester(first))
                    Spacer(Modifier.height(8.dp))
                    Chip(if (live.id in pinned) "📌 Pinned" else "📌 Pin score", live.id in pinned,
                        onClick = { prefs.togglePin(live.id) })
                    Text("Pinned scores show along the bottom while you watch.", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Channels showing this game", color = C.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    when {
                        matches == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Searching your channels…", color = C.Muted, fontSize = 13.sp)
                        }
                        matches.isEmpty() -> Text(
                            "No channel found yet. Still checking the TV guide; or try Live TV's sports categories.",
                            color = C.Muted, fontSize = 13.sp,
                        )
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp)) {
                            items(matches, key = { it.channel.key }) { m ->
                                MatchRow(m, onClick = { Play.channel(ctx, m.channel, matches.map { it.channel }) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Basketball/hockey extras: period table, shots, leaders, team stats, goals and penalties by period, goalies. */
@Composable
fun GameDetailView(d: app.sideline.tv.sports.GameDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.periodLines.isNotEmpty()) PeriodTable(if (d.shotsLines.isNotEmpty()) "Goals" else "Score", d.periodHeaders, d.periodLines)
        if (d.shotsLines.isNotEmpty()) PeriodTable("Shots on goal", d.periodHeaders, d.shotsLines)
        // One block per period, so ▲▼ can step through them and the list scrolls with you.
        d.goalsByPeriod.forEach { (p, lines) ->
            DetailBlock("Goals · $p") { lines.forEach { Text(it, color = C.Text, fontSize = 13.sp) } }
        }
        d.penaltiesByPeriod.forEach { (p, lines) ->
            DetailBlock("Penalties · $p") { lines.forEach { Text(it, color = C.Muted, fontSize = 13.sp) } }
        }
        if (d.goalies.isNotEmpty()) DetailBlock("Goalies") { d.goalies.forEach { Text(it, color = C.Text, fontSize = 12.sp) } }
        if (d.leaders.isNotEmpty()) DetailBlock("Leaders") {
            d.leaders.forEach { (team, cats) ->
                Text("$team  " + cats.joinToString("   ") { "${it.first}: ${it.second}" }, color = C.Text, fontSize = 12.sp)
            }
        }
        if (d.teamStats.isNotEmpty()) DetailBlock("Team stats") {
            val (a, h) = d.periodLines.map { it.first }.let { (it.getOrNull(0) ?: "Away") to (it.getOrNull(1) ?: "Home") }
            Row { Text("", modifier = Modifier.width(110.dp)); Text(a, color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(80.dp)); Text(h, color = C.Faint, fontSize = 11.sp) }
            d.teamStats.forEach { (label, x, y) ->
                Row {
                    Text(label, color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                    Text(x, color = C.Text, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                    Text(y, color = C.Text, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, content: @Composable () -> Unit) {
    // Each block can be selected (white outline), so the remote can reach and scroll through all of them.
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusable()
            .clip(RoundedCornerShape(10.dp)).background(C.Surface2)
            .border(2.dp, if (focused) C.Accent else Color.Transparent, RoundedCornerShape(10.dp)).padding(10.dp),
    ) {
        Text(title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun PeriodTable(title: String, headers: List<String>, lines: List<Triple<String, List<String>, String>>) {
    DetailBlock(title) {
        Row {
            Text("", modifier = Modifier.width(60.dp))
            headers.forEach { Text(it, color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(38.dp)) }
            Text("T", color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        lines.forEach { (team, vals, total) ->
            Row {
                Text(team, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(60.dp))
                headers.indices.forEach { i -> Text(vals.getOrNull(i) ?: "-", color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(38.dp)) }
                Text(total, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
