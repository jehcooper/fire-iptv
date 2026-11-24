package app.sideline.tv.sports

import app.sideline.tv.data.Channel
import app.sideline.tv.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** How a channel was matched. Lower tier = shown first. */
enum class MatchTier(val label: String) { NAME_BOTH("names both teams"), NAME_ONE("names one team"), GUIDE("TV guide"), BROADCASTER("listed broadcaster") }

data class ChannelMatch(val channel: Channel, val tier: MatchTier, val reason: String) {
    val strong get() = tier == MatchTier.NAME_BOTH || tier == MatchTier.GUIDE
}

/**
 * "Take me to the game": which of your live channels is showing a game.
 * Order: channels whose name mentions the teams, then channels whose TV guide mentions them,
 * then channels ESPN lists as the broadcaster. Best effort — only as good as the provider's names & guide.
 *
 * Results are pre-loaded in the background for games you're likely to want, and cached, so the
 * game panel can show them instantly.
 */
object GameMatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ChannelMatch>>>()
    private val _updates = MutableStateFlow(0)
    /** Bumps whenever new results land, so open screens can re-read [cached]. */
    val updates: StateFlow<Int> = _updates
    private val cacheList = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val broadcastAliases = mapOf(
        "sn" to listOf("sportsnet"), "sn1" to listOf("sportsnet one", "sportsnet 1"), "sn360" to listOf("sportsnet 360"),
        "sneast" to listOf("sportsnet east"), "snwest" to listOf("sportsnet west"), "snontario" to listOf("sportsnet ontario"),
        "snpacific" to listOf("sportsnet pacific"), "sn now" to listOf("sportsnet"),
        "tvas" to listOf("tva sports"), "rds" to listOf("rds"),
        "espn+" to listOf("espn plus", "espn+"), "abc" to listOf("abc"), "tnt" to listOf("tnt"), "tbs" to listOf("tbs"),
        "nhl net" to listOf("nhl network"), "mlb net" to listOf("mlb network"), "nfl net" to listOf("nfl network"),
        "nba tv" to listOf("nba tv"), "usa net" to listOf("usa network"), "peacock" to listOf("peacock"),
        "prime video" to listOf("prime video", "amazon prime"), "fs1" to listOf("fs1", "fox sports 1"),
        "fs2" to listOf("fs2", "fox sports 2"), "cbssn" to listOf("cbs sports network"),
    )

    /** How a team can appear in a channel name or guide entry. */
    private class TeamWords(val strong: List<String>, val nick: String?, val city: String?, val abbr: Regex?)

    private fun teamWords(g: Game): List<TeamWords> = g.competitors.map { c ->
        val full = c.name.lowercase().trim()
        val nick = c.shortName.lowercase().trim().takeIf { it.length >= 3 && it != full }
        val city = if (nick != null && full.endsWith(nick)) full.removeSuffix(nick).trim().takeIf { it.length >= 4 } else null
        val ab = c.abbr.trim().takeIf { it.length in 2..4 }?.let { Regex("(^|[^a-z0-9])${Regex.escape(it.lowercase())}([^a-z0-9]|$)") }
        TeamWords(listOf(full).filter { it.length >= 4 }, nick, city, ab)
    }

    /** Old simple terms (full + short name), still used for TV-guide text. */
    private fun teamTerms(g: Game): List<List<String>> = g.competitors.map { c ->
        listOf(c.shortName, c.name).map { it.lowercase().trim() }.filter { it.length >= 4 }.distinct()
    }

    /** Your categories for this game's league ("NHL", "NHL Centre Ice", "Hockey"…), then general sports ones. */
    private fun categoriesFor(game: Game, repo: Repository): Pair<Set<String>, Set<String>> {
        val league = Leagues.byId(game.leagueId)
        val words = (league?.keywords ?: emptyList()) + listOfNotNull(league?.tag?.lowercase())
        val leagueCats = HashSet<String>(); val sportCats = HashSet<String>()
        repo.liveCategories.value.forEach { cat ->
            val n = " " + cat.name.lowercase().replace(Regex("[^a-z0-9+]+"), " ") + " "
            val k = cat.accountId + ":" + cat.id
            if (words.any { w -> n.contains(" ${w.lowercase()} ") || n.contains(" ${w.lowercase()}") && w.length >= 4 }) leagueCats += k
            else if (n.contains("sport") || n.contains(" ppv") || n.contains("event")) sportCats += k
        }
        return leagueCats to sportCats
    }

    fun cached(gameId: String): List<ChannelMatch>? = cache[gameId]?.second

    /**
     * Instant: channel names + broadcasters, no network. Safe to call for many games.
     * Both teams named (nickname, full name, or, inside the league's own categories, city or abbreviation)
     * → strongest. One team only counts if it's the full name ("Ottawa Senators"), so look-alikes such as
     * "Belleville Senators" don't show up.
     */
    suspend fun quick(game: Game, repo: Repository): List<ChannelMatch> = withContext(Dispatchers.Default) {
        val teams = teamWords(game)
        val (leagueCats, sportCats) = categoriesFor(game, repo)
        val lower = repo.lowerNames
        val norm = repo.normNames
        val best = HashMap<String, ChannelMatch>()
        fun offer(key: String, m: () -> ChannelMatch?) {
            val cur = best[key]
            val cand = m() ?: return
            if (cur == null || cand.tier < cur.tier) best[key] = cand
        }
        if (teams.isNotEmpty()) for ((key, n) in lower) {
            val ch = repo.channel(key) ?: continue
            val cat = ch.accountId + ":" + ch.categoryId
            val inLeague = cat in leagueCats
            val inSports = inLeague || cat in sportCats
            var strongHits = 0; var anyHits = 0
            teams.forEach { t ->
                val strong = t.strong.any { n.contains(it) }
                val loose = strong || (t.nick != null && n.contains(t.nick)) ||
                    (inLeague && ((t.city != null && n.contains(t.city)) || (t.abbr?.containsMatchIn(n) == true)))
                if (strong) strongHits++
                if (loose) anyHits++
            }
            when {
                anyHits >= 2 -> offer(key) { ChannelMatch(ch, MatchTier.NAME_BOTH, "Channel name mentions both teams" + if (inLeague) " (league category)" else "") }
                strongHits >= 1 && inSports -> offer(key) { ChannelMatch(ch, MatchTier.NAME_ONE, "Channel name mentions the team") }
            }
        }
        val wanted = game.broadcasts.map { b -> b to (broadcastAliases[b.lowercase().trim()] ?: listOf(b.lowercase().trim())) }
        if (wanted.isNotEmpty()) for ((key, n) in norm) {
            val hit = wanted.firstOrNull { (_, ws) -> ws.any { w -> n == w || n.startsWith("$w ") || n.contains(" $w ") || n.endsWith(" $w") } }
            if (hit != null) offer(key) { repo.channel(key)?.let { ChannelMatch(it, MatchTier.BROADCASTER, "Listed broadcaster: ${hit.first}") } }
        }
        best.values.sortedWith(compareBy({ it.tier }, { if ((it.channel.accountId + ":" + it.channel.categoryId) in leagueCats) 0 else 1 }, { it.channel.name.length })).take(25)
    }

    /**
     * Full search: quick results + the TV guide of every channel in the league's own categories
     * (then general sports categories). Cached for 10 minutes, or until the channel list changes.
     */
    suspend fun full(game: Game, repo: Repository): List<ChannelMatch> {
        val listId = System.identityHashCode(repo.channels.value)
        cache[game.id]?.let { (t, r) -> if (System.currentTimeMillis() - t < 10 * 60_000 && cacheList[game.id] == listId) return r }
        val q = quick(game, repo)
        val terms = teamTerms(game)
        val (leagueCats, sportCats) = categoriesFor(game, repo)
        val all = repo.channels.value
        val candidates = (all.filter { (it.accountId + ":" + it.categoryId) in leagueCats }.take(150) +
            q.filter { it.tier != MatchTier.NAME_BOTH }.map { it.channel } +
            all.filter { (it.accountId + ":" + it.categoryId) in sportCats }.take(60))
            .distinctBy { it.key }.take(200)

        val windowStart = (game.startMillis / 1000) - 3 * 3600
        val windowEnd = (game.startMillis / 1000) + 4 * 3600
        val gate = Semaphore(6)
        val guideHits = coroutineScope {
            candidates.map { c ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val hit = try {
                            repo.epgCached(c).firstOrNull { item ->
                                val inWindow = game.startMillis == 0L || (item.end >= windowStart && item.start <= windowEnd)
                                val text = (item.title + " " + item.description).lowercase()
                                inWindow && terms.any { t -> t.any { text.contains(it) } }
                            }
                        } catch (_: Exception) { null }
                        if (hit != null) ChannelMatch(c, MatchTier.GUIDE, "TV guide: \"${hit.title}\"") else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val merged = HashMap<String, ChannelMatch>()
        (q + guideHits).forEach { m ->
            val cur = merged[m.channel.key]
            if (cur == null || m.tier < cur.tier) merged[m.channel.key] = m
        }
        val result = merged.values.sortedWith(compareBy({ it.tier },
            { if ((it.channel.accountId + ":" + it.channel.categoryId) in leagueCats) 0 else 1 }, { it.channel.name.length })).take(20)
        cache[game.id] = System.currentTimeMillis() to result
        cacheList[game.id] = listId
        _updates.value = _updates.value + 1
        return result
    }

    /** Warm the cache in the background (one game at a time, so the TV stays responsive). */
    fun prefetch(games: List<Game>, repo: Repository) {
        if (repo.channels.value.isEmpty()) return
        games.filter { g -> cache[g.id]?.let { System.currentTimeMillis() - it.first > 10 * 60_000 } ?: true }
            .filter { inFlight.add(it.id) }
            .take(10)
            .let { todo ->
                if (todo.isEmpty()) return
                scope.launch {
                    todo.forEach { g ->
                        try { full(g, repo) } catch (_: Exception) {} finally { inFlight.remove(g.id) }
                    }
                }
            }
    }
}
