package app.sideline.tv.sports

import app.sideline.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AlertKind { SCORE, STARTING_SOON, STARTED, FINAL }

data class ScoreAlert(val game: Game, val kind: AlertKind, val headline: String, val synopsis: String = "")

/**
 * Polls scores for the user's favourite leagues and raises pop-up alerts for games they follow
 * (and, if enabled, any game involving a favourite team).
 */
class ScoreWatcher(private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _board = MutableStateFlow<Map<String, List<Game>>>(emptyMap())
    val board: StateFlow<Map<String, List<Game>>> = _board

    private val _alerts = MutableSharedFlow<ScoreAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<ScoreAlert> = _alerts

    private val _teams = MutableStateFlow<Map<String, List<Team>>>(emptyMap())
    val teams: StateFlow<Map<String, List<Team>>> = _teams

    private val _conferences = MutableStateFlow(Leagues.conferences)
    val conferences: StateFlow<List<Conference>> = _conferences
    private var confsLoadedAt = 0L

    private val last = mutableMapOf<String, Game>()
    private val soonSent = mutableSetOf<String>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                refreshOnce()
                // Faster polling while something the user cares about is live.
                val anyLiveFollowed = _board.value.values.flatten().any { it.isLive && (isWatched(it) || it.id in prefs.pinnedGames.value) }
                delay(if (anyLiveFollowed) 20_000 else 60_000)
            }
        }
    }

    fun refreshNow() { scope.launch { refreshOnce() } }

    /** Recent scoring summaries per game (text, time), shown briefly on pinned scores. */
    private val _recentScoring = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    val recentScoring: StateFlow<Map<String, Pair<String, Long>>> = _recentScoring

    fun isWatched(g: Game): Boolean {
        if (g.id in prefs.followedGames.value || g.id in prefs.pinnedGames.value) return true
        if (!prefs.autoFollowTeams.value) return false
        val fav = prefs.favTeams.value
        return g.competitors.any { Leagues.teamKey(g.leagueId, it.id) in fav }
    }

    /** FBS conference list (with teams) — refreshed at most every 12 hours. */
    suspend fun ensureConferences() {
        if (System.currentTimeMillis() - confsLoadedAt < 12 * 3600_000L && _conferences.value.any { it.teams.isNotEmpty() }) return
        val fresh = EspnClient.fbsConferences() ?: return
        Leagues.conferences = fresh
        _conferences.value = fresh
        confsLoadedAt = System.currentTimeMillis()
        _teams.value = _teams.value + ("ncaaf" to fresh.flatMap { it.teams }.distinctBy { it.id }.sortedBy { it.name })
    }

    private suspend fun refreshOnce() {
        val ids = prefs.favLeagues.value
        if (prefs.favConferences.value.isNotEmpty() || "ncaaf" in ids) ensureConferences()
        val leagues = Leagues.all.filter { it.id in ids } +
            prefs.favConferences.value.mapNotNull { Leagues.byId(Leagues.CONF_PREFIX + it) }
        val results = kotlinx.coroutines.coroutineScope {
            leagues.map { l -> async { l.id to EspnClient.scoreboard(l) } }.awaitAll()
        }
        val pinnedNow = prefs.pinnedGames.value
        val map = results.toMap().mapValues { (lid, games) ->
            // Win probability: remember the previous value so the UI can show which way it moved.
            var list = games.map { g ->
                val prev = last[g.id]
                val before = when {
                    prev == null -> null
                    prev.homeWinPct != g.homeWinPct -> prev.homeWinPct
                    else -> prev.prevHomeWinPct
                }
                g.copy(prevHomeWinPct = before)
            }
            if (lid == "nhl") list = NhlLive.enrich(list, list.filter { it.id in pinnedNow || isWatched(it) }.map { it.id }.toSet())
            list
        }
        _board.value = map
        val finished = mutableSetOf<String>()
        // The same game can appear under "College Football" and a conference tab; handle it once.
        map.values.flatten().distinctBy { it.id }.forEach { g ->
            val prev = last[g.id]
            if (isWatched(g)) detect(prev, g)
            if (g.isFinal && prev?.isFinal == true) finished += g.id
            last[g.id] = g
        }
        if (finished.isNotEmpty()) prefs.unfollowFinished(finished)

        // Pre-load "Take me to the game" for games you're most likely to open: yours first, then other live games.
        val all = map.values.flatten().distinctBy { it.id }
        val soon = System.currentTimeMillis() + 2 * 3600_000L
        val pinned = prefs.pinnedGames.value
        val priority = all.filter { (it.isLive || (it.state == "pre" && it.startMillis in 1..soon)) }
            .sortedWith(compareBy({ !(isWatched(it) || it.id in pinned) }, { !it.isLive }, { it.startMillis }))
        GameMatcher.prefetch(priority, app.sideline.tv.SidelineApp.repo)
    }

    private suspend fun detect(prev: Game?, g: Game) {
        val now = System.currentTimeMillis()
        if (g.state == "pre" && g.startMillis > 0 && g.startMillis - now in 0L..300_000L && soonSent.add(g.id)) {
            emit(g, AlertKind.STARTING_SOON, "Starting in ${((g.startMillis - now) / 60_000).coerceAtLeast(1)} min")
        }
        if (prev == null) return
        when {
            prev.state == "pre" && g.state == "in" -> emit(g, AlertKind.STARTED, "Underway")
            prev.state != "post" && g.state == "post" -> emit(g, AlertKind.FINAL, "Final")
            // Basketball scores change every few seconds, so no score pop-ups there (start/final still show).
            g.isLive && prev.scoreKey != g.scoreKey && g.isTeamGame && !isBasketball(g) -> {
                val scorer = g.competitors.firstOrNull { c ->
                    val old = prev.competitors.firstOrNull { it.id == c.id }?.score
                    (c.score.toDoubleOrNull() ?: 0.0) > (old?.toDoubleOrNull() ?: 0.0)
                }
                // Who scored / assisted: soccer has it on the scoreboard; other sports need one extra request.
                // Look up who scored without holding up the next score refresh.
                scope.launch {
                    // NHL: the league's own play-by-play (scorer, assists, PPG/SHG/EN) when it has caught up with the score.
                    val total = g.competitors.sumOf { it.score.toIntOrNull() ?: 0 }
                    val nhlGoal = g.hockey?.lastGoal?.takeIf { g.hockey.goalsCounted >= total }
                        ?: if (g.leagueId == "nhl") NhlLive.freshGoal(g.id, total) else null
                    val synopsis = nhlGoal ?: g.lastScoring.takeIf { it.isNotBlank() } ?: Leagues.byId(g.leagueId)?.let { l ->
                        kotlinx.coroutines.withTimeoutOrNull(6_000) { EspnClient.lastScoringPlay(l, g.id) }
                    } ?: ""
                    if (synopsis.isNotBlank()) _recentScoring.value = _recentScoring.value + (g.id to (synopsis to System.currentTimeMillis()))
                    _alerts.tryEmit(ScoreAlert(g, AlertKind.SCORE, if (scorer != null) "${scorer.shortName} score!" else "Score update", synopsis))
                }
            }
        }
    }

    private fun isBasketball(g: Game) = Leagues.byId(g.leagueId)?.path?.startsWith("basketball") == true

    private fun emit(g: Game, kind: AlertKind, headline: String) {
        _alerts.tryEmit(ScoreAlert(g, kind, headline))
    }

    fun loadTeams(leagueId: String) {
        if (_teams.value.containsKey(leagueId)) return
        val l = Leagues.byId(leagueId) ?: return
        scope.launch {
            if (leagueId == "ncaaf") {
                ensureConferences()
                if (_teams.value.containsKey("ncaaf")) return@launch
            }
            val t = EspnClient.teams(l)
            _teams.value = _teams.value + (leagueId to t)
        }
    }

    fun game(id: String): Game? = _board.value.values.flatten().firstOrNull { it.id == id }

    fun loadConferences() { scope.launch { ensureConferences() } }
}
