package app.sideline.tv.sports

import app.sideline.tv.data.Net
import app.sideline.tv.data.arr
import app.sideline.tv.data.i
import app.sideline.tv.data.obj
import app.sideline.tv.data.s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The NHL's own public feed (api-web.nhle.com, no key): shots on goal and the on-ice situation,
 * which ESPN's scoreboard doesn't reliably carry. Merged onto ESPN's NHL games by team.
 */
object NhlLive {
    private data class Live(
        val gameId: Long, val awayAbbr: String, val homeAbbr: String, val awayName: String, val homeName: String,
        val sogAway: Int?, val sogHome: Int?, val situationCode: String?, val timeRemaining: String?,
    )

    // ESPN and the NHL abbreviate a few teams differently.
    private val espnToNhl = mapOf("TB" to "TBL", "NJ" to "NJD", "SJ" to "SJS", "LA" to "LAK", "UTAH" to "UTA", "MON" to "MTL")

    private fun nameOf(t: JsonObject?): String {
        val n = t?.get("name")
        return ((n as? JsonObject)?.s("default") ?: (n as? JsonPrimitive)?.contentOrNull
            ?: (t?.get("commonName") as? JsonObject)?.s("default") ?: "").lowercase()
    }

    private val idMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * For a goal that just happened: wait briefly for the NHL play-by-play to record it, then return
     * "TOR · Matthews (12) · assists: …". Null if it doesn't catch up within ~20 seconds.
     */
    suspend fun freshGoal(espnGameId: String, totalScore: Int): String? {
        val id = idMap[espnGameId] ?: return null
        repeat(3) { attempt ->
            try {
                val pbp = playByPlay(id)
                if (pbp.third >= totalScore && pbp.first != null) return pbp.first
            } catch (_: Exception) { }
            if (attempt < 2) kotlinx.coroutines.delay(8_000)
        }
        return null
    }

    /** Last goal per NHL game id, refreshed only when that game's score changes. */
    private val goalCache = java.util.concurrent.ConcurrentHashMap<Long, Triple<Int, String?, String?>>() // totalScore → (lastGoal, situationCode)

    /** Adds HockeyInfo to the live NHL games in [games]. Leaves them untouched if the feed can't be read. */
    suspend fun enrich(games: List<Game>, detailIds: Set<String>): List<Game> {
        if (games.none { it.isLive }) return games
        val live = try { fetch() } catch (_: Exception) { return games }
        return games.map { g ->
            if (!g.isLive || g.competitors.size != 2) return@map g
            val away = g.away ?: return@map g
            val home = g.home ?: return@map g
            val l = live.firstOrNull { matches(it.awayAbbr, it.awayName, away) && matches(it.homeAbbr, it.homeName, home) } ?: return@map g
            idMap[g.id] = l.gameId
            // Play-by-play is a big download, so only for games you've pinned or follow,
            // and only re-fetched when the score changes (for the goal) or the feed lacks the situation.
            var code = l.situationCode
            var lastGoal: String? = null
            var counted = 0
            if (g.id in detailIds) {
                val total = g.competitors.sumOf { it.score.toIntOrNull() ?: 0 }
                val cached = goalCache[l.gameId]
                if (cached != null && cached.first == total && code != null) {
                    lastGoal = cached.second
                } else try {
                    val pbp = playByPlay(l.gameId)
                    lastGoal = pbp.first
                    counted = pbp.third
                    if (code == null) code = pbp.second
                    // Only trust the cache once the play-by-play has caught up with the score.
                    if (pbp.third >= total) goalCache[l.gameId] = Triple(total, lastGoal, pbp.second) else goalCache.remove(l.gameId)
                } catch (_: Exception) { lastGoal = cached?.second }
                if (counted == 0) counted = total
            }
            g.copy(hockey = decode(l, code).copy(lastGoal = lastGoal, goalsCounted = counted))
        }
    }

    private fun matches(nhlAbbr: String, nhlName: String, c: Competitor): Boolean {
        val a = (espnToNhl[c.abbr.uppercase()] ?: c.abbr).uppercase()
        return a == nhlAbbr.uppercase() || (nhlName.isNotBlank() && (c.shortName.lowercase() == nhlName || c.name.lowercase().endsWith(nhlName)))
    }

    private suspend fun fetch(): List<Live> {
        val root = Net.getJson("https://api-web.nhle.com/v1/score/now").obj()
        return root?.get("games").arr()?.mapNotNull { it.obj() }?.mapNotNull { g ->
            val away = g["awayTeam"].obj() ?: return@mapNotNull null
            val home = g["homeTeam"].obj() ?: return@mapNotNull null
            val sit = g["situation"].obj()
            Live(
                gameId = g.s("id")?.toLongOrNull() ?: return@mapNotNull null,
                awayAbbr = away.s("abbrev") ?: "", homeAbbr = home.s("abbrev") ?: "",
                awayName = nameOf(away), homeName = nameOf(home),
                sogAway = away.i("sog"), sogHome = home.i("sog"),
                situationCode = sit?.s("situationCode"),
                timeRemaining = sit?.s("timeRemaining"),
            )
        } ?: emptyList()
    }

    /** (last goal text, latest situationCode, goals counted) from the NHL play-by-play. */
    private suspend fun playByPlay(gameId: Long): Triple<String?, String?, Int> {
        val root = Net.getJson("https://api-web.nhle.com/v1/gamecenter/$gameId/play-by-play").obj()
            ?: return Triple(null, null, 0)
        val plays = root["plays"].arr()?.mapNotNull { it.obj() } ?: emptyList()
        val code = plays.lastOrNull { it.s("situationCode") != null }?.s("situationCode")
        val names = root["rosterSpots"].arr()?.mapNotNull { it.obj() }?.associate { p ->
            (p.s("playerId") ?: "") to (((p["lastName"] as? JsonObject)?.s("default")) ?: "")
        } ?: emptyMap()
        val homeId = root["homeTeam"].obj()?.s("id")
        val homeAbbr = root["homeTeam"].obj()?.s("abbrev") ?: "HOME"
        val awayAbbr = root["awayTeam"].obj()?.s("abbrev") ?: "AWAY"
        val goals = plays.filter { it.s("typeDescKey") == "goal" && it["periodDescriptor"].obj()?.s("periodType") != "SO" }
        val g = goals.lastOrNull() ?: return Triple(null, code, 0)
        val d = g["details"].obj()
        fun who(idKey: String, totalKey: String): String? {
            val id = d?.s(idKey) ?: return null
            val n = names[id]?.takeIf { it.isNotBlank() } ?: return null
            return d.s(totalKey)?.let { "$n ($it)" } ?: n
        }
        val scorer = who("scoringPlayerId", "scoringPlayerTotal") ?: "Goal"
        val assists = listOfNotNull(who("assist1PlayerId", "assist1PlayerTotal"), who("assist2PlayerId", "assist2PlayerTotal"))
        val isHome = d?.s("eventOwnerTeamId") == homeId
        // Power-play / short-handed / empty-net goal, from the skater counts at the moment of the goal.
        val tag = g.s("situationCode")?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }?.let { sc ->
            val aG = sc[0].digitToInt(); val aS = sc[1].digitToInt(); val hS = sc[2].digitToInt(); val hG = sc[3].digitToInt()
            val mine = if (isHome) hS else aS
            val theirs = if (isHome) aS else hS
            val theirGoalie = if (isHome) aG else hG
            when {
                theirGoalie == 0 -> "EN"
                mine > theirs -> "PPG"
                mine < theirs -> "SHG"
                else -> null
            }
        }
        val period = g["periodDescriptor"].obj()?.s("number")?.let { "P$it" } ?: ""
        val time = g.s("timeInPeriod") ?: ""
        val text = listOfNotNull(
            if (isHome) homeAbbr else awayAbbr,
            scorer,
            if (assists.isEmpty()) "unassisted" else "assists: " + assists.joinToString(", "),
            tag,
            "$period $time".trim().takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        return Triple(text, code, goals.size)
    }

    /**
     * situationCode is 4 digits: away goalie (1/0), away skaters, home skaters, home goalie (1/0).
     * "1551" = 5-on-5; "1451" = home power play 5v4; "0651" = away goalie pulled.
     */
    private fun decode(l: Live, code: String?): HockeyInfo {
        var pp: String? = null
        var strength: String? = null
        var en: String? = null
        if (code != null && code.length == 4 && code.all { it.isDigit() }) {
            val awayG = code[0].digitToInt(); val awayS = code[1].digitToInt()
            val homeS = code[2].digitToInt(); val homeG = code[3].digitToInt()
            if (awayG == 0) en = "away"
            if (homeG == 0) en = "home"
            // Power play = more skaters, not explained by a pulled goalie.
            val awayEff = awayS - (if (awayG == 0) 1 else 0)
            val homeEff = homeS - (if (homeG == 0) 1 else 0)
            if (awayEff > homeEff) pp = "away"
            if (homeEff > awayEff) pp = "home"
            if (awayS != 5 || homeS != 5) strength = "${maxOf(awayS, homeS)}v${minOf(awayS, homeS)}"
        }
        return HockeyInfo(
            sogAway = l.sogAway, sogHome = l.sogHome,
            ppSide = pp, strength = strength,
            ppTime = if (pp != null) l.timeRemaining else null,
            emptyNetSide = en,
        )
    }
}
