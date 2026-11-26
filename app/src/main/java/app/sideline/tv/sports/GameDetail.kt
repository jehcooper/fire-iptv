package app.sideline.tv.sports

import app.sideline.tv.data.Net
import app.sideline.tv.data.arr
import app.sideline.tv.data.obj
import app.sideline.tv.data.s
import kotlinx.serialization.json.JsonObject

/** Extra detail for the game screen. Everything is optional: whatever the feeds don't have is simply left out. */
data class GameDetail(
    /** Column headers ("1","2","3","OT" / "Q1"…) and each team's line: abbr, per-period values, total. */
    val periodHeaders: List<String> = emptyList(),
    val periodLines: List<Triple<String, List<String>, String>> = emptyList(),
    /** Second table (hockey: shots on goal by period). */
    val shotsLines: List<Triple<String, List<String>, String>> = emptyList(),
    /** Basketball: (team abbr, [(category, "Player · 28")]) */
    val leaders: List<Pair<String, List<Pair<String, String>>>> = emptyList(),
    /** Basketball team stats: label → (away, home). */
    val teamStats: List<Triple<String, String, String>> = emptyList(),
    /** Hockey goals grouped by period label: lines like "TOR · Matthews (12) — Marner, Nylander · 12:41 · PPG". */
    val goalsByPeriod: List<Pair<String, List<String>>> = emptyList(),
    /** Hockey penalties grouped by period label. */
    val penaltiesByPeriod: List<Pair<String, List<String>>> = emptyList(),
    /** Hockey goalies: "TOR · Woll: 27 saves on 29 (.931)". */
    val goalies: List<String> = emptyList(),
    /** Goals / touchdowns with a video to watch, grouped by period ("Goals" or "Scoring"). */
    val clips: List<Pair<String, List<Clip>>> = emptyList(),
    val clipWord: String = "Goals",
)

/** One goal/score you can watch: the NHL's own clip page when there is one, otherwise a YouTube search. */
data class Clip(val line: String, val url: String?, val query: String, val words: List<String>)

object GameDetails {
    suspend fun load(g: Game): GameDetail? {
        val l = Leagues.byId(g.leagueId) ?: return null
        return try {
            when {
                l.path.startsWith("basketball") -> basketball(l, g)
                l.id == "nhl" -> hockey(g)
                l.path.startsWith("football") || l.path.startsWith("soccer") -> scoring(l, g)
                else -> null
            }
        } catch (e: Exception) { null }
    }

    // ---------------- Basketball (ESPN game summary) ----------------

    private suspend fun basketball(l: League, g: Game): GameDetail {
        val root = Net.getJson("https://site.api.espn.com/apis/site/v2/sports/${l.path}/summary?event=${g.id}").obj() ?: return GameDetail()
        val comps = root["header"].obj()?.get("competitions").arr()?.firstOrNull().obj()?.get("competitors").arr()?.mapNotNull { it.obj() } ?: emptyList()
        val ordered = comps.sortedBy { it.s("homeAway") == "home" }
        val lines = ordered.map { c ->
            val abbr = c["team"].obj()?.s("abbreviation") ?: ""
            val per = c["linescores"].arr()?.mapNotNull { it.obj()?.let { o -> o.s("displayValue") ?: o.s("value") } } ?: emptyList()
            Triple(abbr, per, c.s("score") ?: "")
        }
        val n = lines.maxOfOrNull { it.second.size } ?: 0
        val headers = (1..n).map { if (it <= 4) "Q$it" else "OT${if (n > 5) it - 4 else ""}" }

        val wanted = listOf("points" to "PTS", "rebounds" to "REB", "assists" to "AST")
        val leaders = root["leaders"].arr()?.mapNotNull { it.obj() }?.sortedBy { t ->
            val id = t["team"].obj()?.s("id"); comps.firstOrNull { it["team"].obj()?.s("id") == id }?.s("homeAway") == "home"
        }?.map { t ->
            val abbr = t["team"].obj()?.s("abbreviation") ?: ""
            val cats = t["leaders"].arr()?.mapNotNull { it.obj() } ?: emptyList()
            abbr to wanted.mapNotNull { (key, label) ->
                val cat = cats.firstOrNull { it.s("name") == key } ?: return@mapNotNull null
                val top = cat["leaders"].arr()?.firstOrNull().obj() ?: return@mapNotNull null
                val who = top["athlete"].obj()?.let { it.s("shortName") ?: it.s("displayName") } ?: return@mapNotNull null
                label to "$who ${top.s("displayValue")?.substringBefore(",")?.trim() ?: ""}".trim()
            }
        } ?: emptyList()

        val statKeys = listOf(
            "fieldGoalPct" to "FG%", "threePointFieldGoalsMade-threePointFieldGoalsAttempted" to "3PT",
            "threePointFieldGoalPct" to "3P%", "freeThrowsMade-freeThrowsAttempted" to "FT", "totalRebounds" to "Rebounds",
            "assists" to "Assists", "turnovers" to "Turnovers", "fouls" to "Fouls", "largestLead" to "Largest lead",
        )
        val teams = root["boxscore"].obj()?.get("teams").arr()?.mapNotNull { it.obj() }
            ?.sortedBy { it.s("homeAway") == "home" } ?: emptyList()
        fun stat(t: JsonObject, key: String) = t["statistics"].arr()?.mapNotNull { it.obj() }
            ?.firstOrNull { it.s("name") == key }?.s("displayValue")
        val teamStats = if (teams.size == 2) statKeys.mapNotNull { (key, label) ->
            val a = stat(teams[0], key) ?: return@mapNotNull null
            val b = stat(teams[1], key) ?: return@mapNotNull null
            Triple(label, a, b)
        } else emptyList()
        return GameDetail(periodHeaders = headers, periodLines = lines, leaders = leaders, teamStats = teamStats)
    }

    // ---------------- Hockey (NHL play-by-play) ----------------

    private fun periodLabel(p: JsonObject?): String {
        val n = p?.s("number")?.toIntOrNull() ?: 0
        return when (p?.s("periodType")) {
            "OT" -> if (n > 4) "OT${n - 3}" else "OT"
            "SO" -> "Shootout"
            else -> when (n) { 1 -> "1st period"; 2 -> "2nd period"; 3 -> "3rd period"; else -> "Period $n" }
        }
    }

    private suspend fun hockey(g: Game): GameDetail? {
        val id = NhlLive.gameIdFor(g) ?: return null
        val root = Net.getJson("https://api-web.nhle.com/v1/gamecenter/$id/play-by-play").obj() ?: return null
        val plays = root["plays"].arr()?.mapNotNull { it.obj() } ?: emptyList()
        val names = root["rosterSpots"].arr()?.mapNotNull { it.obj() }?.associate { p ->
            val first = (p["firstName"] as? JsonObject)?.s("default") ?: ""
            val last = (p["lastName"] as? JsonObject)?.s("default") ?: ""
            (p.s("playerId") ?: "") to (if (first.isNotBlank()) "${first.first()}. $last" else last)
        } ?: emptyMap()
        val home = root["homeTeam"].obj(); val away = root["awayTeam"].obj()
        val homeId = home?.s("id"); val homeAbbr = home?.s("abbrev") ?: "HOME"; val awayAbbr = away?.s("abbrev") ?: "AWAY"
        fun abbr(teamId: String?) = if (teamId == homeId) homeAbbr else awayAbbr
        fun who(d: JsonObject?, idKey: String, totalKey: String? = null): String? {
            val pid = d?.s(idKey) ?: return null
            val n = names[pid] ?: return null
            val tot = totalKey?.let { d.s(it) }
            return if (tot != null) "$n ($tot)" else n
        }

        // Periods seen, in order.
        val periods = plays.mapNotNull { it["periodDescriptor"].obj() }.distinctBy { it.s("number") }
            .filter { it.s("periodType") != "SO" }

        // Each goal's NHL video (when the league has posted it) and a YouTube search as a fallback.
        val dateText = java.text.SimpleDateFormat("MMM d yyyy", java.util.Locale.US).format(java.util.Date(if (g.startMillis > 0) g.startMillis else System.currentTimeMillis()))
        val clipsByPeriod = periods.map { pd ->
            val num = pd.s("number")
            periodLabel(pd) to plays.filter { it.s("typeDescKey") == "goal" && it["periodDescriptor"].obj()?.s("number") == num }.map { p ->
                val d = p["details"].obj()
                val scorerFull = d?.s("scoringPlayerId")?.let { names[it] } ?: "Goal"
                val last = scorerFull.substringAfter(". ")
                val team = abbr(d?.s("eventOwnerTeamId"))
                val url = d?.s("highlightClipSharingUrl")?.takeIf { it.startsWith("http") }
                    ?: d?.s("highlightClip")?.let { "https://www.nhl.com/video/$it" }
                Clip("$team · $scorerFull · ${p.s("timeInPeriod") ?: ""}", url,
                    "$last goal ${g.away?.shortName ?: ""} vs ${g.home?.shortName ?: ""} $dateText", listOf(last))
            }
        }.filter { it.second.isNotEmpty() }

        // Goals by period.
        val goalsByPeriod = periods.map { pd ->
            val num = pd.s("number")
            periodLabel(pd) to plays.filter { it.s("typeDescKey") == "goal" && it["periodDescriptor"].obj()?.s("number") == num }.map { p ->
                val d = p["details"].obj()
                val team = abbr(d?.s("eventOwnerTeamId"))
                val scorer = who(d, "scoringPlayerId", "scoringPlayerTotal") ?: "Goal"
                val assists = listOfNotNull(who(d, "assist1PlayerId"), who(d, "assist2PlayerId"))
                val sc = p.s("situationCode")
                val isHome = d?.s("eventOwnerTeamId") == homeId
                val tag = sc?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.let {
                    val aG = it[0].digitToInt(); val aS = it[1].digitToInt(); val hS = it[2].digitToInt(); val hG = it[3].digitToInt()
                    val mine = if (isHome) hS else aS; val theirs = if (isHome) aS else hS; val theirG = if (isHome) aG else hG
                    when { theirG == 0 -> "EN"; mine > theirs -> "PPG"; mine < theirs -> "SHG"; else -> null }
                }
                listOfNotNull(team, scorer + if (assists.isEmpty()) " (unassisted)" else " — " + assists.joinToString(", "),
                    p.s("timeInPeriod"), tag).joinToString(" · ")
            }
        }.filter { it.second.isNotEmpty() }

        // Shots on goal by period (shots on goal + goals).
        val shotTypes = setOf("shot-on-goal", "goal")
        fun shots(teamIsHome: Boolean) = periods.map { pd ->
            plays.count { p ->
                p.s("typeDescKey") in shotTypes && p["periodDescriptor"].obj()?.s("number") == pd.s("number") &&
                    (p["details"].obj()?.s("eventOwnerTeamId") == homeId) == teamIsHome
            }
        }
        val periodHeaders = periods.map { pd -> pd.s("number")?.toIntOrNull()?.let { if (it <= 3) "$it" else "OT" } ?: "?" }
        val awayShots = shots(false); val homeShots = shots(true)
        val shotsLines = listOf(
            Triple(awayAbbr, awayShots.map { "$it" }, "${awayShots.sum()}"),
            Triple(homeAbbr, homeShots.map { "$it" }, "${homeShots.sum()}"),
        )
        // Goals by period table.
        fun goals(teamIsHome: Boolean) = periods.map { pd ->
            plays.count { p ->
                p.s("typeDescKey") == "goal" && p["periodDescriptor"].obj()?.s("number") == pd.s("number") &&
                    (p["details"].obj()?.s("eventOwnerTeamId") == homeId) == teamIsHome
            }
        }
        val ag = goals(false); val hg = goals(true)
        val periodLines = listOf(
            Triple(awayAbbr, ag.map { "$it" }, g.away?.score ?: "${ag.sum()}"),
            Triple(homeAbbr, hg.map { "$it" }, g.home?.score ?: "${hg.sum()}"),
        )

        // Penalties.
        val penaltiesByPeriod = periods.map { pd ->
            periodLabel(pd) to plays.filter { it.s("typeDescKey") == "penalty" && it["periodDescriptor"].obj()?.s("number") == pd.s("number") }.map { p ->
                val d = p["details"].obj()
                val what = (d?.s("descKey") ?: "penalty").replace('-', ' ')
                val mins = d?.s("duration")?.let { "$it min" }
                val player = who(d, "committedByPlayerId") ?: who(d, "servedByPlayerId") ?: "Bench"
                listOfNotNull(abbr(d?.s("eventOwnerTeamId")), player, what, mins, p.s("timeInPeriod")).joinToString(" · ")
            }
        }.filter { it.second.isNotEmpty() }

        // Goalies: shots faced and goals against, from who was in net for each shot.
        data class Gk(var shots: Int = 0, var goals: Int = 0, var team: String = "")
        val gk = LinkedHashMap<String, Gk>()
        for (p in plays) {
            val type = p.s("typeDescKey")
            if (type !in shotTypes) continue
            val d = p["details"].obj() ?: continue
            val goalie = d.s("goalieInNetId") ?: continue
            val e = gk.getOrPut(goalie) { Gk(team = abbr(if (d.s("eventOwnerTeamId") == homeId) away?.s("id") else homeId)) }
            e.shots++
            if (type == "goal") e.goals++
        }
        val goalies = gk.map { (pid, e) ->
            val saves = e.shots - e.goals
            val pct = if (e.shots > 0) ".%03d".format(saves * 1000 / e.shots).replace(".1000", "1.000") else "-"
            "${e.team} · ${names[pid] ?: "Goalie"}: $saves saves on ${e.shots} ($pct)"
        }
        return GameDetail(
            periodHeaders = periodHeaders, periodLines = periodLines, shotsLines = shotsLines,
            goalsByPeriod = goalsByPeriod, penaltiesByPeriod = penaltiesByPeriod, goalies = goalies,
            clips = clipsByPeriod, clipWord = "Goals",
        )
    }

    // ---------------- Football & soccer (ESPN game summary → scoring plays) ----------------

    private suspend fun scoring(l: League, g: Game): GameDetail {
        val root = Net.getJson("https://site.api.espn.com/apis/site/v2/sports/${l.path}/summary?event=${g.id}").obj() ?: return GameDetail()
        val soccer = l.path.startsWith("soccer")
        val plays = root["scoringPlays"].arr()?.mapNotNull { it.obj() }
            ?: root["keyEvents"].arr()?.mapNotNull { it.obj() }?.filter { (it["type"].obj()?.s("type") ?: "").contains("goal", true) }
            ?: emptyList()
        val vs = "${g.away?.shortName ?: g.away?.name ?: ""} vs ${g.home?.shortName ?: g.home?.name ?: ""}"
        fun periodName(n: Int) = if (soccer) (if (n == 1) "1st half" else if (n == 2) "2nd half" else "Extra time")
            else when (n) { 1 -> "1st quarter"; 2 -> "2nd quarter"; 3 -> "3rd quarter"; 4 -> "4th quarter"; else -> "Overtime" }
        val rxScorer = Regex("\\.\\s*([^.(]+?)\\s*\\(")
        val clips = plays.mapNotNull { p ->
            val n = p["period"].obj()?.s("number")?.toIntOrNull() ?: 1
            val team = p["team"].obj()?.let { it.s("abbreviation") ?: it.s("displayName") } ?: ""
            val clock = p["clock"].obj()?.s("displayValue") ?: ""
            val text = (p.s("text") ?: p.s("shortText") ?: "").trim()
            val who = if (soccer) (p["participants"].arr()?.firstOrNull()?.obj()?.get("athlete").obj()?.s("displayName")
                ?: rxScorer.find(text)?.groupValues?.get(1)?.trim() ?: "")
                else text.substringBefore("(").trim()
            if (text.isBlank() && who.isBlank()) return@mapNotNull null
            val type = p["scoringType"].obj()?.s("displayName") ?: p["type"].obj()?.s("text") ?: if (soccer) "Goal" else ""
            val line = listOf(team, if (soccer) (who.ifBlank { "Goal" }) else (if (type.isNotBlank()) "$type: " else "") + who, clock).filter { it.isNotBlank() }.joinToString(" · ")
            val surname = who.split(" ").filter { it.length > 2 && it.first().isUpperCase() }.let { if (soccer) it.takeLast(1) else it.take(2) }
            n to Clip(line, null, if (soccer) "$who goal $vs" else "$who $vs highlights", surname)
        }
        val grouped = clips.groupBy { it.first }.toSortedMap().map { (n, l2) -> periodName(n) to l2.map { it.second } }
        return GameDetail(clips = grouped, clipWord = if (soccer) "Goals" else "Scoring")
    }
}
