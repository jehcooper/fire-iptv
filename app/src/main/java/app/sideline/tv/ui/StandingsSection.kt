package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.League
import app.sideline.tv.sports.StandRow
import app.sideline.tv.sports.LeagueKind
import app.sideline.tv.sports.Leagues
import app.sideline.tv.sports.StandGroup
import app.sideline.tv.sports.Standings
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * League tables on Home: the leagues your teams play in (just their division/conference), plus leagues you follow
 * with "standings" (all groups). Live games move teams "as if it ended now" with ▲▼ arrows and the live change.
 */
@Composable
fun StandingsSection(games: List<Game>, favTeams: Set<String>, leagueOpts: Set<String>) {
    // Which leagues: base leagues of your teams + leagues followed with standings on.
    val teamLeagues = remember(favTeams) { favTeams.map { it.substringBefore('|') }.toSet() }
    val optLeagues = remember(leagueOpts) { leagueOpts.filter { it.endsWith("|standings") }.map { it.substringBefore('|') }.toSet() }
    val leagueIds = remember(teamLeagues, optLeagues) {
        (teamLeagues + optLeagues).mapNotNull { Leagues.byId(it) }.filter { it.kind == LeagueKind.TEAM }.map { it.baseId }.distinct()
    }
    if (leagueIds.isEmpty()) return
    var official by remember { mutableStateOf<Map<String, List<StandGroup>>>(emptyMap()) }
    LaunchedEffect(leagueIds) {
        while (true) {
            official = leagueIds.mapNotNull { id -> Leagues.byId(id)?.let { l -> id to Standings.official(l) } }.toMap()
            delay(15 * 60_000L)
        }
    }
    val favIds = remember(favTeams) { favTeams.map { it.substringAfter('|') }.toSet() }
    // Groups to show: those with your teams; for a followed league with no team of yours, every group.
    val cards = remember(official, games, favIds, optLeagues) {
        leagueIds.flatMap { id ->
            val l = Leagues.byId(id) ?: return@flatMap emptyList()
            val groups = official[id].orEmpty()
            val projected = Standings.project(l, groups, games.filter { Leagues.byId(it.leagueId)?.baseId == id })
            val mineGroups = projected.filter { g -> g.rows.any { it.teamId in favIds && "$id|${it.teamId}" in favTeams } }
            if (mineGroups.isNotEmpty() && id !in optLeagues) mineGroups else projected
        }
    }
    if (cards.isEmpty()) return
    Column {
        SectionTitle("Standings · live")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { it.leagueId + it.name }) { g -> StandingsCard(g, favIds) }
        }
    }
}

/** Short table for Home: your team and 3 places either side (or the top 7). OK opens the full table. */
@Composable
private fun StandingsCard(g: StandGroup, favIds: Set<String>) {
    val l = Leagues.byId(g.leagueId)
    var open by remember { mutableStateOf(false) }
    val mineAt = g.rows.indexOfFirst { it.teamId in favIds }
    val window = if (mineAt < 0) 0 until minOf(7, g.rows.size) else {
        val from = (mineAt - 3).coerceAtLeast(0)
        val to = minOf(g.rows.size, from + 7)
        (to - 7).coerceAtLeast(0) until to
    }
    FocusCard(onClick = { open = true }, modifier = Modifier.width(340.dp), scaleOnFocus = 1.02f) { focused ->
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l?.tag ?: "", color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(g.name, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            StandHeader(l)
            window.forEach { i -> StandLine(l, i, g.rows[i], favIds) }
            Text(if (focused) "OK: full table (${g.rows.size} teams)" else "${g.rows.size} teams", color = C.Faint, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (open) FullStandingsDialog(g, favIds) { open = false }
}

private fun usesPoints(l: League?) = l?.path?.let { it.startsWith("hockey") || it.startsWith("soccer") } == true

@Composable
fun StandHeader(l: League?) {
    Row(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        Text("#", color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(36.dp))
        Text("Team", color = C.Faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("GP", color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(32.dp))
        Text("Record", color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(76.dp))
        Text(if (usesPoints(l)) "PTS" else "PCT", color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(44.dp))
        Text("Live", color = C.Faint, fontSize = 11.sp, modifier = Modifier.width(44.dp))
    }
}

/** One table line: position (▲▼ if live results move it), logo, team, GP, record, points/PCT, live change. */
@Composable
fun StandLine(l: League?, i: Int, r: StandRow, favIds: Set<String>, full: Boolean = false) {
    val mine = r.teamId in favIds
    val move = if (r.officialRank > 0) r.officialRank - (i + 1) else 0
    val fs = if (full) 14.sp else 12.sp
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(if (mine) C.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .padding(vertical = if (full) 4.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", color = C.Muted, fontSize = fs)
            if (move > 0) Text("▲", color = C.Accent, fontSize = 10.sp)
            if (move < 0) Text("▼", color = C.Live, fontSize = 10.sp)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = r.logo, contentDescription = null, modifier = Modifier.size(if (full) 22.dp else 18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (full) r.name else r.abbr.ifBlank { r.name }, color = if (mine) C.Text else C.Muted, fontSize = fs,
                fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.playing) Text(" ●", color = C.Live, fontSize = 10.sp)
        }
        Text("${r.gp}", color = C.Muted, fontSize = fs, modifier = Modifier.width(32.dp))
        Text(r.record, color = C.Muted, fontSize = fs, modifier = Modifier.width(76.dp))
        Text(if (usesPoints(l)) "${r.points ?: "-"}" else ".%03d".format((r.pct * 1000).toInt()).replace(".1000", "1.000"),
            color = C.Text, fontSize = fs, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
        Text(r.liveDelta, color = if (r.liveDelta.startsWith("+0") || r.liveDelta == "L") C.Live else C.Accent, fontSize = fs,
            modifier = Modifier.width(44.dp))
    }
}

/** Whole table in a panel; ▲▼ moves line by line and the list scrolls with you. */
@Composable
fun FullStandingsDialog(g: StandGroup, favIds: Set<String>, onDismiss: () -> Unit) {
    val l = Leagues.byId(g.leagueId)
    val first = remember { FocusRequester() }
    val start = g.rows.indexOfFirst { it.teamId in favIds }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 4).coerceAtLeast(0))
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(720.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l?.tag ?: "", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(g.name, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text("Live games are counted \"as if they ended now\" (● playing, ▲▼ moved). Back to close.",
                    color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                StandHeader(l)
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(g.rows, key = { _, r -> r.teamId }) { i, r ->
                        FocusCard(onClick = {}, shape = RoundedCornerShape(6.dp), scaleOnFocus = 1f,
                            modifier = if (i == start) Modifier.focusRequester(first) else Modifier) { _ ->
                            Box(Modifier.padding(horizontal = 6.dp)) { StandLine(l, i, r, favIds, full = true) }
                        }
                    }
                }
            }
        }
    }
}
