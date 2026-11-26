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
    /** College: conference record (null for pro leagues). */
    val confW: Int? = null, val confL: Int? = null, val confT: Int = 0,
) {
    val confRecord: String get() = if (confW == null) "" else "$confW-$confL" + if (confT > 0) "-$confT" else ""
    val confPct: Double get() { val w = confW ?: return 0.0; val l = confL ?: 0; return if (w + l + confT == 0) 0.0 else (w + confT * 0.5) / (w + l + confT) }
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
                if (!entries.isNullOrEmpty()) groups += StandGroup(base.id, name, entries.mapNotNull { row(it, isCollege(base)) })
                node["children"].arr()?.mapNotNull { it.obj() }?.forEach { walk(it, name) }
            }
            walk(root, null)
            // Some ESPN feeds leave out the conference splits; ask the fuller feed the ESPN website uses.
            if (isCollege(base) && groups.isNotEmpty() && groups.all { g -> g.rows.all { it.confW == null } }) {
                try {
                    val web = Net.getJson("https://site.web.api.espn.com/apis/v2/sports/${base.path}/standings?region=us&lang=en&contentorigin=espn&type=0&level=1" +
                        (if (base.id == "ncaaf") "&group=80" else "")).obj()
                    if (web != null) {
                        val before = groups.toList(); groups.clear()
                        walk(web, null)
                        if (groups.isEmpty() || groups.all { g -> g.rows.all { it.confW == null } }) { groups.clear(); groups += before }
                    }
                } catch (_: Exception) {}
            }
            val sorted = groups.map { g -> g.copy(rows = sort(base, g.rows).mapIndexed { i, r -> r.copy(officialRank = i + 1) }) }
            cache[base.id] = System.currentTimeMillis() to sorted
            sorted
        } catch (e: Exception) { cache[base.id]?.second ?: emptyList() }
    }

    /** Tables already downloaded (no network), or empty. */
    fun cached(leagueId: String): List<StandGroup> {
        val base = Leagues.byId(leagueId)?.baseId ?: leagueId
        return cache[base]?.second ?: emptyList()
    }

    /** A team's position in its table (division/conference/league as ESPN groups it), from downloaded tables. */
    fun rankOf(leagueId: String, teamId: String): Int? {
        for (g in cached(leagueId)) {
            val i = g.rows.indexOfFirst { it.teamId == teamId }
            if (i >= 0) return i + 1
        }
        return null
    }

    private val RX_REC = Regex("^(\\d+)-(\\d+)(?:-(\\d+))?$")

    /**
     * One team line. ESPN lists several records per team (overall, conference, home, away…), some sharing the
     * same stat names, so each value is taken from the right record rather than whichever comes last.
     */
    private fun row(e: JsonObject, college: Boolean): StandRow? {
        val t = e["team"].obj() ?: return null
        val list = e["stats"].arr()?.mapNotNull { it.obj() } ?: emptyList()
        fun stat(vararg keys: String) = list.firstOrNull { o ->
            keys.any { k -> o.s("name").equals(k, true) || o.s("type").equals(k, true) }
        }
        fun num(vararg keys: String) = stat(*keys)?.let { it.s("value") ?: it.s("displayValue") }?.toDoubleOrNull()?.toInt()
        fun rec(o: JsonObject?) = (o?.s("summary") ?: o?.s("displayValue"))?.trim()?.let { RX_REC.find(it) }?.groupValues
        val overall = rec(stat("overall", "total"))
        // Conference record: ESPN lists several conference numbers (record "5-1", win %, games behind…). Use the one
        // that is actually a record; failing that, build it from the separate conference win/loss counts.
        fun isConf(o: JsonObject) = listOf(o.s("type"), o.s("name"), o.s("abbreviation"), o.s("displayName"), o.s("description"))
            .any { it?.contains("conf", true) == true }
        val conf = list.filter { isConf(it) }.firstNotNullOfOrNull { rec(it) }
            ?: run {
                fun confNum(word: String) = list.firstOrNull { o ->
                    isConf(o) && (o.s("name") ?: o.s("type") ?: "").let { n -> n.contains(word, true) && !n.contains("percent", true) && !n.contains("behind", true) }
                }?.let { (it.s("value") ?: it.s("displayValue"))?.toDoubleOrNull()?.toInt() }
                val cw = confNum("wins"); val cl = confNum("losses")
                if (cw != null && cl != null) listOf("$cw-$cl", cw.toString(), cl.toString(), (confNum("ties") ?: 0).toString()) else null
            }
        var wins = num("wins"); var losses = num("losses"); var ties = num("ties") ?: 0
        // College tables: overall record from its own summary (the plain "wins" can be a different split).
        if (college && overall != null) { wins = overall[1].toInt(); losses = overall[2].toInt(); ties = overall[3].toIntOrNull() ?: 0 }
        if (wins == null && overall != null) { wins = overall[1].toInt(); losses = overall[2].toInt() }
        val otl = num("otLosses", "overtimeLosses") ?: 0
        val w = wins ?: 0; val l = losses ?: 0
        return StandRow(
            teamId = t.s("id") ?: return null,
            name = t.s("displayName") ?: t.s("name") ?: "",
            abbr = t.s("abbreviation") ?: "",
            logo = t["logos"].arr()?.firstOrNull().obj()?.s("href"),
            wins = w, losses = l, otl = otl, ties = ties,
            points = num("points"),
            gp = num("gamesPlayed")?.takeIf { it > 0 } ?: (w + l + ties + otl),
            confW = if (college) conf?.get(1)?.toInt() else null,
            confL = if (college) conf?.get(2)?.toInt() else null,
            confT = if (college) conf?.get(3)?.toIntOrNull() ?: 0 else 0,
        )
    }

    private fun usesPoints(l: League) = l.path.startsWith("hockey") || l.path.startsWith("soccer")
    fun isCollege(l: League) = l.path.contains("college")

    private fun sort(l: League, rows: List<StandRow>): List<StandRow> =
        if (isCollege(l)) rows.sortedWith(compareByDescending<StandRow> { it.confPct }.thenByDescending { it.confW ?: 0 }
            .thenByDescending { it.pct }.thenByDescending { it.wins })
        else if (usesPoints(l)) rows.sortedWith(compareByDescending<StandRow> { it.points ?: 0 }.thenByDescending { it.wins }.thenBy { it.gp })
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
            val ids = grp.rows.map { it.teamId }.toSet()
            val confGame = live.filter { g -> g.competitors.all { it.id in ids } }.flatMap { g -> g.competitors.map { it.id } }.toSet()
            val rows = grp.rows.map { r ->
                val c = change[r.teamId] ?: return@map r
                val inConf = r.confW != null && r.teamId in confGame
                r.copy(
                    confW = if (inConf) r.confW!! + c.first else r.confW, confL = if (inConf) (r.confL ?: 0) + c.second else r.confL,
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
