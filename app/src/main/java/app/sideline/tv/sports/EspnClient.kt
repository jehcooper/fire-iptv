package app.sideline.tv.sports

import app.sideline.tv.data.Net
import app.sideline.tv.data.arr
import app.sideline.tv.data.obj
import app.sideline.tv.data.s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Scores from ESPN's public-but-undocumented JSON feeds. No key needed, but ESPN can change
 * these without notice; parsing is deliberately forgiving so a change degrades, not crashes.
 */
object EspnClient {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"

    suspend fun scoreboard(league: League): List<Game> = try {
        if (league.kind == LeagueKind.CRICKET) cricket(league) else {
            val q = if (league.query.isBlank()) "" else "?${league.query}"
            val root = Net.getJson("$SITE/${league.path}/scoreboard$q").obj()
            root?.get("events").arr()?.mapNotNull { it.obj() }?.flatMap { parseEvent(league, it) } ?: emptyList()
        }
    } catch (e: Exception) { emptyList() }

    /**
     * Every FBS conference with its teams, from ESPN's standings feed (one request).
     * Returns null if the feed can't be read, so callers keep the fallback list.
     */
    suspend fun fbsConferences(): List<Conference>? = try {
        val root = Net.getJson("https://site.api.espn.com/apis/v2/sports/football/college-football/standings?group=80").obj()
        val out = root?.get("children").arr()?.mapNotNull { it.obj() }?.mapNotNull { ch ->
            val id = ch.s("id") ?: ch.s("uid")?.let { Regex("g:(\\d+)").find(it)?.groupValues?.get(1) } ?: return@mapNotNull null
            // Teams can sit directly under the conference or under its divisions.
            val groups = listOf(ch) + (ch["children"].arr()?.mapNotNull { it.obj() } ?: emptyList())
            val teams = groups.flatMap { g ->
                g["standings"].obj()?.get("entries").arr()?.mapNotNull { it.obj()?.get("team").obj() } ?: emptyList()
            }.mapNotNull { t ->
                Team(
                    leagueId = "ncaaf",
                    id = t.s("id") ?: return@mapNotNull null,
                    name = t.s("displayName") ?: t.s("name") ?: "",
                    abbr = t.s("abbreviation") ?: "",
                    logo = t["logos"].arr()?.firstOrNull().obj()?.s("href"),
                    color = t.s("color"),
                )
            }.distinctBy { it.id }.sortedBy { it.name }
            Conference(id, ch.s("name") ?: "Conference", ch.s("abbreviation") ?: ch.s("shortName") ?: ch.s("name") ?: id, teams)
        }
        out?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    suspend fun teams(league: League): List<Team> = try {
        if (league.kind != LeagueKind.TEAM) emptyList() else {
            val q = if (league.id == "ncaaf") "?groups=80&limit=1000" else "?limit=1000"
            val root = Net.getJson("$SITE/${league.path}/teams$q").obj()
            root?.get("sports").arr()?.firstOrNull().obj()?.get("leagues").arr()?.firstOrNull().obj()
                ?.get("teams").arr()?.mapNotNull { it.obj()?.get("team").obj() }?.mapNotNull { t ->
                    Team(
                        leagueId = league.id,
                        id = t.s("id") ?: return@mapNotNull null,
                        name = t.s("displayName") ?: "",
                        abbr = t.s("abbreviation") ?: "",
                        logo = t["logos"].arr()?.firstOrNull().obj()?.s("href"),
                        color = t.s("color"),
                    )
                }?.sortedBy { it.name } ?: emptyList()
        }
    } catch (e: Exception) { emptyList() }

    private fun parseEvent(league: League, ev: JsonObject): List<Game> {
        val comps = (ev["competitions"].arr()?.mapNotNull { it.obj() } ?: emptyList()) +
            (ev["groupings"].arr()?.mapNotNull { it.obj() }?.flatMap { g ->
                g["competitions"].arr()?.mapNotNull { it.obj() } ?: emptyList()
            } ?: emptyList())
        val evId = ev.s("id") ?: return emptyList()
        val evName = ev.s("shortName") ?: ev.s("name") ?: ""

        return when (league.kind) {
            LeagueKind.TEAM -> comps.take(1).map { c -> buildGame(league, evId, evName, ev, c, sortByOrder = false, keep = 2) }
            LeagueKind.TENNIS -> comps
                .map { c -> buildGame(league, c.s("id") ?: evId, "", ev, c, sortByOrder = false, keep = 2) }
                .filter { it.state == "in" || (it.state == "pre" && it.startMillis - System.currentTimeMillis() < 18 * 3600_000) }
                .take(30)
            LeagueKind.FIELD -> {
                val c = comps.firstOrNull { status(it, ev).first == "in" }
                    ?: comps.lastOrNull { (it["competitors"].arr()?.size ?: 0) > 0 }
                    ?: return emptyList()
                listOf(buildGame(league, evId, ev.s("name") ?: evName, ev, c, sortByOrder = true, keep = 3))
            }
            LeagueKind.CRICKET -> emptyList()
        }
    }

    private fun status(c: JsonObject, ev: JsonObject): Triple<String, String, Boolean> {
        val t = c["status"].obj()?.get("type").obj() ?: ev["status"].obj()?.get("type").obj()
        return Triple(t?.s("state") ?: "pre", t?.s("shortDetail") ?: t?.s("detail") ?: "", t?.s("completed") == "true")
    }

    private fun buildGame(
        league: League, id: String, title: String, ev: JsonObject, c: JsonObject, sortByOrder: Boolean, keep: Int,
    ): Game {
        var comps = c["competitors"].arr()?.mapNotNull { it.obj() } ?: emptyList()
        if (sortByOrder) comps = comps.sortedBy { it.s("order")?.toIntOrNull() ?: 999 }
        val competitors = comps.take(keep).map { cp ->
            val team = cp["team"].obj()
            val athlete = cp["athlete"].obj()
            val who = team ?: athlete
            val score = cp.s("score")
                ?: cp["linescores"].arr()?.mapNotNull { (it.obj()?.get("value") as? JsonPrimitive)?.contentOrNull?.substringBefore(".") }
                    ?.joinToString(" ")
                ?: ""
            Competitor(
                id = who?.s("id") ?: cp.s("id") ?: "",
                name = who?.s("displayName") ?: who?.s("fullName") ?: "",
                shortName = team?.s("shortDisplayName") ?: athlete?.s("shortName") ?: who?.s("displayName") ?: "",
                abbr = team?.s("abbreviation") ?: "",
                logo = team?.s("logo") ?: athlete?.get("flag").obj()?.s("href"),
                color = team?.s("color"),
                score = score,
                home = cp.s("homeAway") == "home",
            )
        }
        val (state, detail, _) = status(c, ev)
        val broadcasts = (c["broadcasts"].arr()?.flatMap { b -> b.obj()?.get("names").arr()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList() }
            ?: emptyList()) +
            (c["geoBroadcasts"].arr()?.mapNotNull { it.obj()?.get("media").obj()?.s("shortName") } ?: emptyList())
        val name = title.ifBlank { competitors.joinToString(" vs ") { it.shortName.ifBlank { it.name } } }
        // Soccer scoreboards list goals in "details": scorer, minute, type (e.g. "Goal - Header", "Penalty").
        val lastGoal = c["details"].arr()?.mapNotNull { it.obj() }?.lastOrNull { d ->
            d.s("scoringPlay") == "true" || (d["type"].obj()?.s("text")?.contains("Goal", true) == true)
        }
        val lastScoring = lastGoal?.let { d ->
            val who = d["athletesInvolved"].arr()?.mapNotNull { it.obj()?.s("displayName") } ?: emptyList()
            val kind = d["type"].obj()?.s("text") ?: "Goal"
            val minute = d["clock"].obj()?.s("displayValue") ?: ""
            listOfNotNull(
                who.firstOrNull()?.let { "$kind: $it" } ?: kind,
                who.drop(1).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "assist $it" },
                minute.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        } ?: ""
        return Game(
            id = id,
            leagueId = league.id,
            title = name,
            startMillis = parseDate(c.s("date") ?: ev.s("date")),
            state = state,
            detail = detail,
            competitors = competitors,
            broadcasts = broadcasts.distinct(),
            lastScoring = lastScoring,
        )
    }

    /**
     * Who scored (and assisted) on the latest scoring play, e.g.
     * "Auston Matthews (15) Wrist Shot, assists: Mitch Marner (20) · 2nd 12:41".
     * From ESPN's game summary feed; empty if it isn't available.
     */
    suspend fun lastScoringPlay(league: League, eventId: String): String = try {
        val root = Net.getJson("$SITE/${league.path}/summary?event=$eventId").obj()
        val play = root?.get("scoringPlays").arr()?.lastOrNull().obj()
        if (play != null) {
            val text = play.s("text") ?: ""
            val period = play["period"].obj()?.s("number")?.let { n ->
                when (league.path.substringBefore("/")) {
                    "hockey" -> "P$n"
                    "football", "basketball" -> "Q$n"
                    "baseball" -> "Inning $n"
                    else -> ""
                }
            } ?: ""
            val clock = play["clock"].obj()?.s("displayValue") ?: ""
            listOf(text, listOf(period, clock).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            // Soccer summaries use "keyEvents".
            root?.get("keyEvents").arr()?.mapNotNull { it.obj() }?.lastOrNull { it.s("scoringPlay") == "true" }?.let { e ->
                listOfNotNull(e.s("text") ?: e["type"].obj()?.s("text"), e["clock"].obj()?.s("displayValue")).joinToString(" · ")
            } ?: ""
        }
    } catch (e: Exception) { "" }

    /** Cricket isn't on the standard scoreboard feed; ESPN's "header" feed lists live series. Beta. */
    private suspend fun cricket(league: League): List<Game> {
        val root = Net.getJson("https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in").obj()
        val events = root?.get("sports").arr()?.mapNotNull { it.obj() }
            ?.flatMap { it["leagues"].arr()?.mapNotNull { l -> l.obj() } ?: emptyList() }
            ?.flatMap { it["events"].arr()?.mapNotNull { e -> e.obj() } ?: emptyList() } ?: emptyList()
        return events.mapNotNull { ev ->
            val id = ev.s("id") ?: return@mapNotNull null
            val full = ev["fullStatus"].obj()?.get("type").obj()
            val state = full?.s("state") ?: ev.s("status") ?: "pre"
            val comps = ev["competitors"].arr()?.mapNotNull { it.obj() } ?: emptyList()
            Game(
                id = "cricket-$id",
                leagueId = league.id,
                title = ev.s("shortName") ?: ev.s("name") ?: "Match",
                startMillis = parseDate(ev.s("date")),
                state = if (state in setOf("pre", "in", "post")) state else "pre",
                detail = ev.s("summary") ?: full?.s("shortDetail") ?: "",
                competitors = comps.take(2).map { cp ->
                    Competitor(
                        id = cp.s("id") ?: "",
                        name = cp.s("displayName") ?: cp.s("name") ?: "",
                        shortName = cp.s("abbreviation") ?: cp.s("displayName") ?: "",
                        abbr = cp.s("abbreviation") ?: "",
                        logo = cp.s("logo"),
                        color = cp.s("color"),
                        score = cp.s("score") ?: "",
                        home = cp.s("homeAway") == "home",
                    )
                },
                broadcasts = emptyList(),
            )
        }
    }

    private fun parseDate(s: String?): Long {
        if (s == null) return 0
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX")) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(s)?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0
    }
}

