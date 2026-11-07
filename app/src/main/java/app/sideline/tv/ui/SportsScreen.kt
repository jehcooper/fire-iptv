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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 60.dp, top = 8.dp)) {
            listOf("Live" to live, "Coming up" to upcoming, "Final" to finals).forEach { (title, list) ->
                if (list.isNotEmpty()) item(key = title) {
                    Column {
                        SectionTitle("$title · ${list.size}")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(list, key = { it.id }) { g ->
                                GameCard(g, g.id in followed, spoilerFree, onClick = { openGame = g }, onMenu = { prefs.toggleFollow(g.id) })
                            }
                        }
                    }
                }
            }
        }
    }
    openGame?.let { g -> GameSheet(g, onDismiss = { openGame = null }) }
}

/** Game details: follow for pop-ups, and "Take me to the game". */
@Composable
fun GameSheet(game: Game, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = SidelineApp.prefs
    val followed by prefs.followedGames.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<ChannelMatch>?>(null) }
    val first = remember { FocusRequester() }
    val live = SidelineApp.scores.game(game.id) ?: game

    TvDialog(onDismiss) {
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(560.dp).background(C.Surface).padding(36.dp),
        ) {
            Text(Leagues.byId(live.leagueId)?.name ?: "", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (live.competitors.size == 2) {
                live.competitors.sortedBy { it.home }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(c.name, color = C.Text, fontSize = 22.sp, modifier = Modifier.weight(1f))
                        if (live.state != "pre") Text(if (spoilerFree) "•" else c.score, color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(live.title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (live.state == "pre" && live.startMillis > 0) "Starts ${dayTimeOf(live.startMillis)}" else live.detail,
                color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (live.broadcasts.isNotEmpty()) Text("On: ${live.broadcasts.joinToString(", ")}", color = C.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(
                    "▶ Take me to the game", selected = true, modifier = Modifier.focusRequester(first),
                    onClick = {
                        if (!searching) {
                            searching = true
                            scope.launch {
                                val m = GameMatcher.find(live, SidelineApp.repo)
                                matches = m
                                searching = false
                                // Confident single answer → just go.
                                val top = m.firstOrNull()
                                if (top != null && top.score >= 100 && (m.getOrNull(1)?.score ?: 0) < top.score) {
                                    Play.channel(ctx, top.channel, m.map { it.channel })
                                }
                            }
                        }
                    },
                )
                Chip(if (live.id in followed) "🔔 Pop-ups on" else "🔕 Pop-ups off", selected = live.id in followed,
                    onClick = { prefs.toggleFollow(live.id) })
            }
            Spacer(Modifier.height(18.dp))

            when {
                searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Checking your channels and TV guide…", color = C.Muted)
                }
                matches != null && matches!!.isEmpty() -> Text(
                    "Couldn't find this game on your channels. Your provider's guide may not list it. Try the Live TV tab's sports categories.",
                    color = C.Muted, fontSize = 15.sp,
                )
                matches != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Best matches", color = C.Muted, fontSize = 13.sp) }
                    items(matches!!, key = { it.channel.key }) { m ->
                        FocusCard(onClick = { Play.channel(ctx, m.channel, matches!!.map { it.channel }) }, modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f) { _ ->
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = m.channel.logo, contentDescription = null, modifier = Modifier.size(width = 64.dp, height = 36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.channel.name, color = C.Text, fontSize = 16.sp, maxLines = 1)
                                    Text(m.reason, color = C.Faint, fontSize = 12.sp, maxLines = 2)
                                }
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (m.score >= 100) C.Accent else C.Surface2).padding(6.dp)) {
                                    Text(if (m.score >= 100) "likely" else "maybe", color = if (m.score >= 100) C.Bg else C.Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
