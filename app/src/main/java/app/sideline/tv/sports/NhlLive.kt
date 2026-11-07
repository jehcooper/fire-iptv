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

    /** Adds HockeyInfo to the live NHL games in [games]. Leaves them untouched if the feed can't be read. */
    suspend fun enrich(games: List<Game>, detailIds: Set<String>): List<Game> {
        if (games.none { it.isLive }) return games
        val live = try { fetch() } catch (_: Exception) { return games }
        return games.map { g ->
            if (!g.isLive || g.competitors.size != 2) return@map g
            val away = g.away ?: return@map g
            val home = g.home ?: return@map g
            val l = live.firstOrNull { matches(it.awayAbbr, it.awayName, away) && matches(it.homeAbbr, it.homeName, home) } ?: return@map g
            // Play-by-play is a big download, so only for games you've pinned or follow.
            val code = l.situationCode ?: if (g.id in detailIds) (try { lastSituationCode(l.gameId) } catch (_: Exception) { null }) else null
            g.copy(hockey = decode(l, code))
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

    private suspend fun lastSituationCode(gameId: Long): String? {
        val root = Net.getJson("https://api-web.nhle.com/v1/gamecenter/$gameId/play-by-play").obj()
        return root?.get("plays").arr()?.mapNotNull { it.obj() }?.lastOrNull { it.s("situationCode") != null }?.s("situationCode")
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
