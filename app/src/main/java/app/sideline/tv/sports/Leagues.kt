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
)

object Leagues {
    val all = listOf(
        League("nhl", "NHL", "North America", "hockey/nhl", LeagueKind.TEAM, listOf("nhl", "hockey", "centre ice", "center ice")),
        League("nfl", "NFL", "North America", "football/nfl", LeagueKind.TEAM, listOf("nfl", "football", "sunday ticket", "redzone")),
        League("nba", "NBA", "North America", "basketball/nba", LeagueKind.TEAM, listOf("nba", "basketball", "league pass")),
        League("mlb", "MLB", "North America", "baseball/mlb", LeagueKind.TEAM, listOf("mlb", "baseball", "extra innings")),
        League("wnba", "WNBA", "North America", "basketball/wnba", LeagueKind.TEAM, listOf("wnba", "basketball")),
        League("ncaaf", "College Football", "North America", "football/college-football", LeagueKind.TEAM, listOf("ncaa", "college", "football")),
        League("ncaab", "College Basketball", "North America", "basketball/mens-college-basketball", LeagueKind.TEAM, listOf("ncaa", "college", "basketball")),

        League("epl", "Premier League", "Soccer", "soccer/eng.1", LeagueKind.TEAM, listOf("premier league", "epl", "soccer", "football")),
        League("ucl", "Champions League", "Soccer", "soccer/uefa.champions", LeagueKind.TEAM, listOf("champions league", "ucl", "uefa", "soccer")),
        League("uel", "Europa League", "Soccer", "soccer/uefa.europa", LeagueKind.TEAM, listOf("europa", "uefa", "soccer")),
        League("mls", "MLS", "Soccer", "soccer/usa.1", LeagueKind.TEAM, listOf("mls", "soccer")),
        League("laliga", "La Liga", "Soccer", "soccer/esp.1", LeagueKind.TEAM, listOf("laliga", "la liga", "soccer")),
        League("seriea", "Serie A", "Soccer", "soccer/ita.1", LeagueKind.TEAM, listOf("serie a", "soccer")),
        League("bundesliga", "Bundesliga", "Soccer", "soccer/ger.1", LeagueKind.TEAM, listOf("bundesliga", "soccer")),
        League("ligue1", "Ligue 1", "Soccer", "soccer/fra.1", LeagueKind.TEAM, listOf("ligue 1", "soccer")),

        League("cricket", "Cricket (beta)", "Cricket", "", LeagueKind.CRICKET, listOf("cricket", "willow", "ipl", "star sports", "sky sports cricket")),

        League("atp", "ATP Tennis", "Tennis · Golf · F1", "tennis/atp", LeagueKind.TENNIS, listOf("tennis", "atp")),
        League("wta", "WTA Tennis", "Tennis · Golf · F1", "tennis/wta", LeagueKind.TENNIS, listOf("tennis", "wta")),
        League("pga", "PGA Tour", "Tennis · Golf · F1", "golf/pga", LeagueKind.FIELD, listOf("golf", "pga")),
        League("f1", "Formula 1", "Tennis · Golf · F1", "racing/f1", LeagueKind.FIELD, listOf("f1", "formula 1", "formula one", "racing")),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
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
) {
    val isLive get() = state == "in"
    val isFinal get() = state == "post"
    val isTeamGame get() = competitors.size == 2
    val away get() = competitors.firstOrNull { !it.home } ?: competitors.getOrNull(0)
    val home get() = competitors.firstOrNull { it.home && it != away } ?: competitors.getOrNull(1)
    val scoreKey get() = competitors.joinToString("|") { it.score }
}

data class Team(val leagueId: String, val id: String, val name: String, val abbr: String, val logo: String?, val color: String?)
