package app.sideline.tv.sports

import app.sideline.tv.data.Net
import app.sideline.tv.data.arr
import app.sideline.tv.data.obj
import app.sideline.tv.data.s
import kotlinx.serialization.json.JsonObject

/** One team's line in a league table. */
data class StandRow(
    val teamId: String, val name: String, val abbr: String, val logo: String?,
    val wins: Int, val losses: Int, val otl: Int, val ties: Int, val points: Int?, val gp: Int,
    /** Projected change while a game is on ("if it ended now"), e.g. +2 points or +1 win. */
    val liveDelta: String = "",
    val playing: Boolean = false,
    /** Official position (1-based) before live projection, for the ▲▼ arrow. */
    val officialRank: Int = 0,
) {
    val record: String get() = when {
        otl > 0 || points != null && ties == 0 -> "$wins-$losses-$otl"
        ties > 0 -> "$wins-$losses-$ties"
        else -> "$wins-$losses"
    }
    val pct: Double get() = if (wins + losses + ties == 0) 0.0 else (wins + ties * 0.5) / (wins + losses + ties)
}

data class StandGroup(val leagueId: String, val name: String, val rows: List<StandRow>)

/**
 * League tables from ESPN's standings feed (refreshed every 15 minutes), plus an "if it ended now" projection
 * from the live scores: hockey +2 for a win (a tie heading to OT gives both teams +1), soccer +3/+1,
 * other sports a win or loss on the record.
 */
object Standings {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StandGroup>>>()

    suspend fun official(league: League): List<StandGroup> {
        val base = Leagues.byId(league.baseId) ?: league
        cache[base.id]?.let { (t, g) -> if (System.currentTimeMillis() - t < 15 * 60_000) return g }
        return try {
            val q = if (base.id == "ncaaf") "?group=80" else ""
            val root = Net.getJson("https://site.api.espn.com/apis/v2/sports/${base.path}/standings$q").obj() ?: return emptyList()
            val groups = mutableListOf<StandGroup>()
            fun walk(node: JsonObject, parentName: String?) {
                val entries = node["standings"].obj()?.get("entries").arr()?.mapNotNull { it.obj() }
                val name = node.s("name") ?: parentName ?: base.name
                if (!entries.isNullOrEmpty()) groups += StandGroup(base.id, name, entries.mapNotNull { row(it) })
                node["children"].arr()?.mapNotNull { it.obj() }?.forEach { walk(it, name) }
            }
            walk(root, null)
            val sorted = groups.map { g -> g.copy(rows = sort(base, g.rows).mapIndexed { i, r -> r.copy(officialRank = i + 1) }) }
            cache[base.id] = System.currentTimeMillis() to sorted
            sorted
        } catch (e: Exception) { cache[base.id]?.second ?: emptyList() }
    }

    private fun row(e: JsonObject): StandRow? {
        val t = e["team"].obj() ?: return null
        val stats = e["stats"].arr()?.mapNotNull { it.obj() }?.associate { (it.s("name") ?: it.s("type") ?: "") to it } ?: emptyMap()
        fun n(vararg keys: String) = keys.firstNotNullOfOrNull { k -> stats[k]?.let { it.s("value") ?: it.s("displayValue") }?.toDoubleOrNull()?.toInt() }
        return StandRow(
            teamId = t.s("id") ?: return null,
            name = t.s("displayName") ?: t.s("name") ?: "",
            abbr = t.s("abbreviation") ?: "",
            logo = t["logos"].arr()?.firstOrNull().obj()?.s("href"),
            wins = n("wins") ?: 0, losses = n("losses") ?: 0,
            otl = n("otLosses", "overtimeLosses") ?: 0, ties = n("ties") ?: 0,
            points = n("points"), gp = n("gamesPlayed") ?: 0,
        )
    }

    private fun usesPoints(l: League) = l.path.startsWith("hockey") || l.path.startsWith("soccer")

    private fun sort(l: League, rows: List<StandRow>): List<StandRow> =
        if (usesPoints(l)) rows.sortedWith(compareByDescending<StandRow> { it.points ?: 0 }.thenByDescending { it.wins }.thenBy { it.gp })
        else rows.sortedWith(compareByDescending<StandRow> { it.pct }.thenByDescending { it.wins })

    /** Applies live games "as if they ended now" and re-sorts. */
    fun project(league: League, groups: List<StandGroup>, games: List<Game>): List<StandGroup> {
        val base = Leagues.byId(league.baseId) ?: league
        val live = games.filter { it.isLive && it.competitors.size == 2 }
        if (live.isEmpty()) return groups
        val change = HashMap<String, Triple<Int, Int, String>>() // teamId → (winsAdd, lossesAdd, label); points handled below
        val pointsAdd = HashMap<String, Int>()
        val tiesAdd = HashMap<String, Int>()
        for (g in live) {
            val (a, b) = g.competitors
            val sa = a.score.toIntOrNull() ?: continue
            val sb = b.score.toIntOrNull() ?: continue
            when {
                base.path.startsWith("hockey") -> when {
                    sa == sb -> listOf(a, b).forEach { pointsAdd[it.id] = 1; change[it.id] = Triple(0, 0, "+1 (OT)") }
                    else -> {
                        val (w, l) = if (sa > sb) a to b else b to a
                        pointsAdd[w.id] = 2; change[w.id] = Triple(1, 0, "+2"); change[l.id] = Triple(0, 1, "+0")
                    }
                }
                base.path.startsWith("soccer") -> when {
                    sa == sb -> listOf(a, b).forEach { pointsAdd[it.id] = 1; tiesAdd[it.id] = 1; change[it.id] = Triple(0, 0, "+1") }
                    else -> {
                        val (w, l) = if (sa > sb) a to b else b to a
                        pointsAdd[w.id] = 3; change[w.id] = Triple(1, 0, "+3"); change[l.id] = Triple(0, 1, "+0")
                    }
                }
                else -> if (sa != sb) {
                    val (w, l) = if (sa > sb) a to b else b to a
                    change[w.id] = Triple(1, 0, "W"); change[l.id] = Triple(0, 1, "L")
                } else listOf(a, b).forEach { change[it.id] = Triple(0, 0, "tied") }
            }
        }
        return groups.map { grp ->
            val rows = grp.rows.map { r ->
                val c = change[r.teamId] ?: return@map r
                r.copy(
                    wins = r.wins + c.first, losses = r.losses + c.second,
                    ties = r.ties + (tiesAdd[r.teamId] ?: 0),
                    points = r.points?.let { it + (pointsAdd[r.teamId] ?: 0) },
                    gp = r.gp + 1, liveDelta = c.third, playing = true,
                )
            }
            grp.copy(rows = sort(base, rows))
        }
    }
}
