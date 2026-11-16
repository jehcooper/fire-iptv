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
import androidx.compose.foundation.lazy.LazyRow
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

@Composable
private fun StandingsCard(g: StandGroup, favIds: Set<String>) {
    val l = Leagues.byId(g.leagueId)
    val points = l?.path?.let { it.startsWith("hockey") || it.startsWith("soccer") } == true
    FocusCard(onClick = {}, modifier = Modifier.width(330.dp), scaleOnFocus = 1.02f) { _ ->
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l?.tag ?: "", color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(g.name, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                Text("#", color = C.Faint, fontSize = 10.sp, modifier = Modifier.width(34.dp))
                Text("Team", color = C.Faint, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("Record", color = C.Faint, fontSize = 10.sp, modifier = Modifier.width(72.dp))
                Text(if (points) "PTS" else "PCT", color = C.Faint, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                Text("Live", color = C.Faint, fontSize = 10.sp, modifier = Modifier.width(40.dp))
            }
            g.rows.forEachIndexed { i, r ->
                val mine = r.teamId in favIds
                val move = if (r.officialRank > 0) r.officialRank - (i + 1) else 0
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (mine) C.Accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = C.Muted, fontSize = 11.sp)
                        if (move > 0) Text("▲", color = C.Accent, fontSize = 9.sp)
                        if (move < 0) Text("▼", color = C.Live, fontSize = 9.sp)
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = r.logo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(r.abbr.ifBlank { r.name }, color = if (mine) C.Text else C.Muted, fontSize = 12.sp,
                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        if (r.playing) Text(" ●", color = C.Live, fontSize = 9.sp)
                    }
                    Text(r.record, color = C.Muted, fontSize = 11.sp, modifier = Modifier.width(72.dp))
                    Text(if (points) "${r.points ?: "-"}" else ".%03d".format((r.pct * 1000).toInt()).replace(".1000", "1.000"),
                        color = C.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
                    Text(r.liveDelta, color = if (r.liveDelta.startsWith("+0") || r.liveDelta == "L") C.Live else C.Accent, fontSize = 10.sp,
                        modifier = Modifier.width(40.dp))
                }
            }
        }
    }
}
