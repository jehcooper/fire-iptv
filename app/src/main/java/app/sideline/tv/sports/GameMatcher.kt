package app.sideline.tv.sports

import app.sideline.tv.data.Channel
import app.sideline.tv.data.Repository
import app.sideline.tv.data.normalizeChannelName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ChannelMatch(val channel: Channel, val score: Int, val reason: String)

/**
 * "Take me to the game": finds which of your live channels is most likely showing a game.
 * Best effort. It is only as good as the provider's channel names and TV guide.
 */
object GameMatcher {

    /** ESPN broadcast codes → how IPTV providers usually name the channel. */
    private val broadcastAliases = mapOf(
        "sn" to listOf("sportsnet"), "sn1" to listOf("sportsnet one", "sportsnet 1"), "sn360" to listOf("sportsnet 360"),
        "sneast" to listOf("sportsnet east"), "snwest" to listOf("sportsnet west"), "snontario" to listOf("sportsnet ontario"),
        "snpacific" to listOf("sportsnet pacific"), "sn now" to listOf("sportsnet"),
        "tvas" to listOf("tva sports"), "rds" to listOf("rds"),
        "espn+" to listOf("espn plus", "espn+"), "abc" to listOf("abc"), "tnt" to listOf("tnt"), "tbs" to listOf("tbs"),
        "nhl net" to listOf("nhl network"), "mlb net" to listOf("mlb network"), "nfl net" to listOf("nfl network"),
        "nba tv" to listOf("nba tv"), "usa net" to listOf("usa network"), "peacock" to listOf("peacock", "nbc"),
        "prime video" to listOf("prime video", "amazon prime", "tnf"), "fs1" to listOf("fs1", "fox sports 1"),
        "fs2" to listOf("fs2", "fox sports 2"), "cbssn" to listOf("cbs sports network"),
    )

    private fun teamTerms(g: Game): List<List<String>> = g.competitors.map { c ->
        listOf(c.shortName, c.name)
            .map { it.lowercase().trim() }
            .filter { it.length >= 4 }
            .distinct()
    }

    suspend fun find(game: Game, repo: Repository): List<ChannelMatch> = coroutineScope {
        val league = Leagues.byId(game.leagueId)
        val channels = repo.channels.value
        if (channels.isEmpty()) return@coroutineScope emptyList()
        val terms = teamTerms(game)
        val scores = mutableMapOf<String, Pair<Int, MutableList<String>>>()
        fun add(c: Channel, pts: Int, why: String) {
            val cur = scores.getOrPut(c.key) { 0 to mutableListOf() }
            cur.second += why
            scores[c.key] = (cur.first + pts) to cur.second
        }

        // 1) Event channels named after the game, e.g. "NHL 04: Maple Leafs vs Canadiens"
        channels.forEach { c ->
            val n = c.name.lowercase()
            val hits = terms.count { t -> t.any { n.contains(it) } }
            if (hits > 0) add(c, 60 * hits, "channel name mentions the teams")
        }

        // 2) Broadcaster ESPN lists for the game
        game.broadcasts.forEach { b ->
            val key = b.lowercase().trim()
            val wanted = broadcastAliases[key] ?: listOf(key)
            channels.forEach { c ->
                val n = normalizeChannelName(c.name)
                if (wanted.any { w -> n == w || n.startsWith("$w ") || n.contains(" $w ") || n.endsWith(" $w") }) {
                    add(c, 30, "listed broadcaster ($b)")
                }
            }
        }

        // 3) TV-guide check on the strongest candidates plus sports-category channels
        val sportCats = repo.liveCategories.value.filter { cat ->
            val n = cat.name.lowercase()
            (league?.keywords ?: emptyList()).any { n.contains(it) } || n.contains("sport")
        }.map { it.accountId + ":" + it.id }.toSet()
        val byScore = scores.entries.sortedByDescending { it.value.first }.mapNotNull { repo.channel(it.key) }
        val inSportCats = channels.filter { (it.accountId + ":" + it.categoryId) in sportCats }
        val toCheck = (byScore.take(25) + inSportCats.take(40)).distinctBy { it.key }.take(50)

        val gate = Semaphore(8)
        val windowStart = (game.startMillis / 1000) - 3 * 3600
        val windowEnd = (game.startMillis / 1000) + 4 * 3600
        val epgHits = toCheck.map { c ->
            async {
                gate.withPermit {
                    val items = repo.epg(c, 6)
                    val hit = items.firstOrNull { it ->
                        val inWindow = game.startMillis == 0L || (it.end >= windowStart && it.start <= windowEnd)
                        val text = (it.title + " " + it.description).lowercase()
                        inWindow && terms.count { t -> t.any { text.contains(it) } } >= 1
                    }
                    if (hit != null) c to hit.title else null
                }
            }
        }.awaitAll().filterNotNull()
        epgHits.forEach { (c, title) -> add(c, 100, "TV guide: \"$title\"") }

        scores.entries
            .mapNotNull { (k, v) -> repo.channel(k)?.let { ChannelMatch(it, v.first, v.second.distinct().joinToString(" · ")) } }
            .sortedByDescending { it.score }
            .take(12)
    }
}
