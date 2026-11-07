package app.sideline.tv.sports

enum class LeagueKind { TEAM, TENNIS, FIELD, CRICKET }

data class League(
    val id: String,
    val name: String,
    val group: String,
    /** ESPN path, e.g. "hockey/nhl". Empty for cricket (uses a different feed). */
    val path: String,
    val kind: LeagueKind,
    /** Words that hint a channel/category carries this sport. */
    val keywords: List<String>,
    /** Extra scoreboard query, e.g. "groups=80" for all FBS games. */
    val query: String = "",
    /** League that team favourites belong to (conference tabs share college football's teams). */
    val baseId: String = id,
    /** Short tag for tight spaces like the pinned-score ticker, e.g. "CFB". */
    val tag: String = name,
)

data class Conference(val id: String, val name: String, val short: String, val teams: List<Team>)

object Leagues {
    val all = listOf(
        League("nhl", "NHL", "North America", "hockey/nhl", LeagueKind.TEAM, listOf("nhl", "hockey", "centre ice", "center ice"), tag = "NHL"),
        League("nfl", "NFL", "North America", "football/nfl", LeagueKind.TEAM, listOf("nfl", "football", "sunday ticket", "redzone"), tag = "NFL"),
        League("nba", "NBA", "North America", "basketball/nba", LeagueKind.TEAM, listOf("nba", "basketball", "league pass"), tag = "NBA"),
        League("mlb", "MLB", "North America", "baseball/mlb", LeagueKind.TEAM, listOf("mlb", "baseball", "extra innings"), tag = "MLB"),
        League("wnba", "WNBA", "North America", "basketball/wnba", LeagueKind.TEAM, listOf("wnba", "basketball"), tag = "WNBA"),
        League("ncaaf", "College Football (all FBS)", "North America", "football/college-football", LeagueKind.TEAM, listOf("ncaa", "college", "football", "espnu", "sec network", "big ten", "acc network"), query = "groups=80&limit=300", tag = "CFB"),
        League("ncaab", "College Basketball", "North America", "basketball/mens-college-basketball", LeagueKind.TEAM, listOf("ncaa", "college", "basketball"), tag = "CBB"),

        League("epl", "Premier League", "Soccer", "soccer/eng.1", LeagueKind.TEAM, listOf("premier league", "epl", "soccer", "football"), tag = "EPL"),
        League("ucl", "Champions League", "Soccer", "soccer/uefa.champions", LeagueKind.TEAM, listOf("champions league", "ucl", "uefa", "soccer"), tag = "UCL"),
        League("uel", "Europa League", "Soccer", "soccer/uefa.europa", LeagueKind.TEAM, listOf("europa", "uefa", "soccer"), tag = "UEL"),
        League("mls", "MLS", "Soccer", "soccer/usa.1", LeagueKind.TEAM, listOf("mls", "soccer"), tag = "MLS"),
        League("laliga", "La Liga", "Soccer", "soccer/esp.1", LeagueKind.TEAM, listOf("laliga", "la liga", "soccer"), tag = "LA LIGA"),
        League("seriea", "Serie A", "Soccer", "soccer/ita.1", LeagueKind.TEAM, listOf("serie a", "soccer"), tag = "SERIE A"),
        League("bundesliga", "Bundesliga", "Soccer", "soccer/ger.1", LeagueKind.TEAM, listOf("bundesliga", "soccer"), tag = "BUNDESLIGA"),
        League("ligue1", "Ligue 1", "Soccer", "soccer/fra.1", LeagueKind.TEAM, listOf("ligue 1", "soccer"), tag = "LIGUE 1"),

        League("cricket", "Cricket (beta)", "Cricket", "", LeagueKind.CRICKET, listOf("cricket", "willow", "ipl", "star sports", "sky sports cricket"), tag = "CRICKET"),

        League("atp", "ATP Tennis", "Tennis · Golf · F1", "tennis/atp", LeagueKind.TENNIS, listOf("tennis", "atp"), tag = "ATP"),
        League("wta", "WTA Tennis", "Tennis · Golf · F1", "tennis/wta", LeagueKind.TENNIS, listOf("tennis", "wta"), tag = "WTA"),
        League("pga", "PGA Tour", "Tennis · Golf · F1", "golf/pga", LeagueKind.FIELD, listOf("golf", "pga"), tag = "PGA"),
        League("f1", "Formula 1", "Tennis · Golf · F1", "racing/f1", LeagueKind.FIELD, listOf("f1", "formula 1", "formula one", "racing"), tag = "F1"),
    )

    /** FBS conferences, refreshed from ESPN; this built-in list is only a fallback. */
    @Volatile var conferences: List<Conference> = listOf(
        "1" to "ACC", "151" to "American", "4" to "Big 12", "5" to "Big Ten", "12" to "Conference USA",
        "18" to "FBS Independents", "15" to "MAC", "17" to "Mountain West", "9" to "Pac-12", "8" to "SEC", "37" to "Sun Belt",
    ).map { (id, n) -> Conference(id, n, n, emptyList()) }

    const val CONF_PREFIX = "cfb-conf-"

    fun conferenceLeague(c: Conference) = League(
        id = CONF_PREFIX + c.id, name = c.short, group = "College Football", path = "football/college-football",
        kind = LeagueKind.TEAM, keywords = listOf("ncaa", "college", "football", c.short.lowercase()),
        query = "groups=${c.id}&limit=300", baseId = "ncaaf", tag = "CFB",
    )

    fun byId(id: String): League? = all.firstOrNull { it.id == id }
        ?: if (id.startsWith(CONF_PREFIX)) {
            val cid = id.removePrefix(CONF_PREFIX)
            conferenceLeague(conferences.firstOrNull { it.id == cid } ?: Conference(cid, "Conference $cid", "Conf $cid", emptyList()))
        } else null

    /** Key used for favourite teams: "ncaaf|<teamId>" even for games shown under a conference tab. */
    fun teamKey(leagueId: String, teamId: String) = "${byId(leagueId)?.baseId ?: leagueId}|$teamId"
}

data class Competitor(
    val id: String,
    val name: String,
    val shortName: String,
    val abbr: String,
    val logo: String?,
    val color: String?,
    val score: String,
    val home: Boolean,
)

data class Game(
    val id: String,
    val leagueId: String,
    val title: String,
    val startMillis: Long,
    /** "pre", "in" or "post" */
    val state: String,
    val detail: String,
    val competitors: List<Competitor>,
    val broadcasts: List<String>,
    /** Latest scoring play from the scoreboard feed, when ESPN includes it (soccer mostly). */
    val lastScoring: String = "",
    /** Live football: down & distance, ball position, possession (like ESPN Gamecast). */
    val situation: Situation? = null,
    /** Home team's chance of winning, 0–1, when ESPN publishes it; and the previous value, for the ▲▼ arrow. */
    val homeWinPct: Double? = null,
    val prevHomeWinPct: Double? = null,
    val soccer: SoccerInfo? = null,
    val hockey: HockeyInfo? = null,
) {
    val isLive get() = state == "in"
    val isFinal get() = state == "post"
    val isTeamGame get() = competitors.size == 2
    val away get() = competitors.firstOrNull { !it.home } ?: competitors.getOrNull(0)
    val home get() = competitors.firstOrNull { it.home && it != away } ?: competitors.getOrNull(1)
    val scoreKey get() = competitors.joinToString("|") { it.score }
}

data class Team(val leagueId: String, val id: String, val name: String, val abbr: String, val logo: String?, val color: String?)

data class Situation(
    /** e.g. "2nd & 7 at MIZ 35" */
    val downDistance: String,
    /** ESPN team id of the team with the ball. */
    val possessionTeamId: String?,
    /** Which side of the field the ball is on, e.g. "MIZ", and the yard line there, e.g. 35. */
    val territory: String?,
    val yardLine: Int?,
    val redZone: Boolean,
    val lastPlay: String,
)

data class GoalEvent(val minute: String, val scorer: String, val assist: String?, val home: Boolean, val kind: String)

data class SoccerInfo(
    val possAway: Double?, val possHome: Double?,
    val shotsAway: Int?, val shotsHome: Int?,
    val onTargetAway: Int?, val onTargetHome: Int?,
    val cornersAway: Int?, val cornersHome: Int?,
    val redAway: Int, val redHome: Int,
    val goals: List<GoalEvent>,
)

/** From the NHL's own live feed: shots on goal and the on-ice situation. */
data class HockeyInfo(
    val sogAway: Int?, val sogHome: Int?,
    /** Team on the power play (ESPN-style abbreviation of that side: "away"/"home"), e.g. "home". */
    val ppSide: String?,
    /** e.g. "5v4", "5v3", "4v4", "6v5" */
    val strength: String?,
    /** Time left on the power play, e.g. "1:24", when the NHL feed has it. */
    val ppTime: String?,
    /** "away"/"home" side whose goalie is pulled. */
    val emptyNetSide: String?,
)
