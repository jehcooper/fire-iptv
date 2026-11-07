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
            val root = Net.getJson("$SITE/${league.path}/scoreboard").obj()
            root?.get("events").arr()?.mapNotNull { it.obj() }?.flatMap { parseEvent(league, it) } ?: emptyList()
        }
    } catch (e: Exception) { emptyList() }

    suspend fun teams(league: League): List<Team> = try {
        if (league.kind != LeagueKind.TEAM) emptyList() else {
            val root = Net.getJson("$SITE/${league.path}/teams").obj()
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
        return Game(
            id = id,
            leagueId = league.id,
            title = name,
            startMillis = parseDate(c.s("date") ?: ev.s("date")),
            state = state,
            detail = detail,
            competitors = competitors,
            broadcasts = broadcasts.distinct(),
        )
    }

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

