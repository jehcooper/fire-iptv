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
import androidx.compose.foundation.lazy.items
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

@Composable
fun SportsScreen(goTo: (Tab) -> Unit) {
    val prefs = SidelineApp.prefs
    val board by SidelineApp.scores.board.collectAsState()
    val favLeagues by prefs.favLeagues.collectAsState()
    val followed by prefs.followedGames.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val favConfs by prefs.favConferences.collectAsState()
    val confs by SidelineApp.scores.conferences.collectAsState()
    val leagues = Leagues.all.filter { it.id in favLeagues } +
        confs.filter { it.id in favConfs }.map { Leagues.conferenceLeague(it) }
    var selected by rememberSaveable { mutableStateOf("all") }
    var openGame by remember { mutableStateOf<Game?>(null) }

    val games = (if (selected == "all") board.values.flatten().distinctBy { it.id } else board[selected].orEmpty())
    val live = games.filter { it.isLive }
    val upcoming = games.filter { it.state == "pre" }.sortedBy { it.startMillis }
    val finals = games.filter { it.isFinal }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Chip("All my leagues", selected == "all", onClick = { selected = "all" }) }
            items(leagues, key = { it.id }) { l ->
                val n = board[l.id].orEmpty().count { it.isLive }
                Chip(if (n > 0) "${l.name} · $n live" else l.name, selected == l.id, onClick = { selected = l.id })
            }
            item { Chip("＋ Leagues & teams", false, onClick = { goTo(Tab.Settings) }) }
        }
        Text("Press Menu (≡) on a game to turn score pop-ups on or off. Select a game to find it on your channels.",
            color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))

        if (games.isEmpty()) {
            EmptyState("No games right now", if (board.isEmpty()) "Loading scores…" else "Nothing scheduled today in these leagues.")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 60.dp, top = 8.dp)) {
            // Each section (Live / Coming up / Final) is split into one row per league.
            val order = leagues.map { it.id }
            listOf("Live" to live, "Coming up" to upcoming, "Final" to finals).forEach { (title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h-$title") {
                        Text("$title · ${list.size}", color = if (title == "Live") C.Live else C.Text, fontSize = 22.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                    list.groupBy { it.leagueId }.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) 999 else i } }.forEach { (lid, games) ->
                        item(key = "$title-$lid") {
                            Column {
                                Text("${Leagues.byId(lid)?.name ?: lid}  (${games.size})", color = C.Accent, fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                                    items(games, key = { it.id }) { g ->
                                        GameCard(g, g.id in followed, spoilerFree, onClick = { openGame = g }, onMenu = { prefs.toggleFollow(g.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    openGame?.let { g -> GameSheet(g, onDismiss = { openGame = null }) }
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
fun ScoreHeader(live: Game, spoilerFree: Boolean) {
    Text(Leagues.byId(live.leagueId)?.name ?: "", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (live.competitors.size == 2) {
        live.competitors.sortedBy { it.home }.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Text(c.name, color = C.Text, fontSize = 21.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (live.state != "pre") Text(if (spoilerFree) "•" else c.score, color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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

    // Keep the clock and score fresh while this panel is open.
    LaunchedEffect(game.id) {
        while (true) { SidelineApp.scores.refreshNow(); delay(15_000) }
    }
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }

    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.fillMaxHeight().width(600.dp).background(C.Surface).padding(36.dp)) {
                ScoreHeader(live, spoilerFree)
                if (!spoilerFree) LiveDetailsFull(live)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (live.id in followed) "🔔 Pop-ups on" else "🔕 Pop-ups off", live.id in followed,
                        onClick = { prefs.toggleFollow(live.id) }, modifier = Modifier.focusRequester(first))
                    Chip(if (live.id in pinned) "📌 Pinned" else "📌 Pin score", live.id in pinned,
                        onClick = { prefs.togglePin(live.id) })
                }
                Text("Pinned scores show along the bottom while you watch.", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                Text("Channels showing this game", color = C.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                when {
                    matches == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Searching your channels…", color = C.Muted)
                    }
                    matches.isEmpty() -> Text(
                        "No channel found yet. Still checking the TV guide in the background; or try the Live TV tab's sports categories.",
                        color = C.Muted, fontSize = 15.sp,
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(matches, key = { it.channel.key }) { m ->
                            MatchRow(m, onClick = { Play.channel(ctx, m.channel, matches.map { it.channel }) })
                        }
                    }
                }
            }
        }
    }
}
