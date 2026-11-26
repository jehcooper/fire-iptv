package app.sideline.tv.data

import app.sideline.tv.SidelineApp
import app.sideline.tv.sports.Game
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Highlight / recap videos for finished games, found with the YouTube Data API (your own free key).
 * Prefers official league and broadcaster channels, and only videos that are allowed to play inside apps.
 */
object Recaps {
    data class Video(val id: String, val title: String, val channel: String, val seconds: Int? = null) {
        val length: String get() = seconds?.let { if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60) } ?: ""
    }
    class Result(val videos: List<Video>, val error: String?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Video>>()

    /** Channels whose recaps are usually the real thing (lower case, matched as "contains"). */
    private val official = listOf(
        "nhl", "nfl", "nba", "mlb", "wnba", "mls", "espn", "sportsnet", "tsn", "tnt", "fox sports", "cbs sports", "nbc sports",
        "big ten network", "sec network", "acc digital", "house of highlights", "premier league", "sky sports", "bein", "dazn",
        "uefa", "laliga", "bundesliga", "ligue 1", "serie a", "cricket", "icc", "atp", "wta", "pga tour", "formula 1", "f1",
    )

    private val RX_DUR = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")

    /** Adds each video's length (one cheap request for all of them). */
    private suspend fun withLengths(vids: List<Video>, key: String): List<Video> {
        if (vids.isEmpty()) return vids
        return try {
            val o = Net.getJson("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=" + vids.joinToString(",") { it.id } + "&key=" + key).obj()
            val len = o?.get("items").arr()?.mapNotNull { it.obj() }?.associate { v ->
                val m = RX_DUR.find(v["contentDetails"].obj()?.s("duration") ?: "")
                (v.s("id") ?: "") to m?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 3600 + (it.groupValues[2].toIntOrNull() ?: 0) * 60 + (it.groupValues[3].toIntOrNull() ?: 0) }
            } ?: emptyMap()
            vids.map { it.copy(seconds = len[it.id]) }
        } catch (e: Exception) { vids }
    }

    /** Free-text YouTube search (used for individual goals and touchdowns). */
    suspend fun search(query: String, afterMs: Long, mustHave: List<String>): Result {
        cache["q:$query"]?.let { return Result(it, null) }
        val key = SidelineApp.prefs.youtubeKey.value
        if (key.isBlank()) return Result(emptyList(), "Add a free YouTube key in Settings → YouTube (game recaps) for goal videos.")
        return try {
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=10&order=relevance" +
                "&q=" + URLEncoder.encode(query, "UTF-8") + "&publishedAfter=" + iso(afterMs - 2 * 3600_000L) + "&key=" + key
            val items = Net.getJson(url).obj()?.get("items").arr()?.mapNotNull { it.obj() } ?: emptyList()
            val words = mustHave.map { it.lowercase() }.filter { it.length >= 3 }
            val vids = items.mapNotNull { o ->
                val id = o["id"].obj()?.s("videoId") ?: return@mapNotNull null
                val sn = o["snippet"].obj() ?: return@mapNotNull null
                Video(id, unescape(sn.s("title") ?: ""), sn.s("channelTitle") ?: "")
            }.sortedByDescending { v ->
                val t = v.title.lowercase(); val c = v.channel.lowercase()
                words.count { t.contains(it) } * 2 + (if (official.any { c.contains(it) }) 3 else 0)
            }
            val withLen = withLengths(vids, key)
            if (withLen.isNotEmpty()) cache["q:$query"] = withLen
            Result(withLen, if (withLen.isEmpty()) "No video of this one on YouTube yet." else null)
        } catch (e: Exception) {
            Result(emptyList(), if ((e.message ?: "").contains("403")) "YouTube refused the key (or today's free search limit is used up)." else "Couldn't reach YouTube.")
        }
    }

    private fun unescape(t: String) = t.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    suspend fun find(g: Game): Result {
        cache[g.id]?.let { return Result(it, null) }
        val key = SidelineApp.prefs.youtubeKey.value
        if (key.isBlank()) return Result(emptyList(), "Add a free YouTube key in Settings → YouTube (game recaps) to watch recaps.")
        val teams = g.competitors.map { it.name }
        val q = if (teams.size == 2) "${g.away?.name ?: teams[0]} vs ${g.home?.name ?: teams[1]} highlights" else "${g.title} highlights"
        val start = if (g.startMillis > 0) g.startMillis else System.currentTimeMillis() - 86_400_000L
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=15&order=relevance" +
            "&q=" + URLEncoder.encode(q, "UTF-8") +
            "&publishedAfter=" + iso(start - 2 * 3600_000L) + "&publishedBefore=" + iso(start + 4 * 86_400_000L) + "&key=" + key
        return try {
            val items = Net.getJson(url).obj()?.get("items").arr()?.mapNotNull { it.obj() } ?: emptyList()
            val nicks = g.competitors.map { it.shortName.lowercase() }.filter { it.length >= 3 }
            val vids = items.mapNotNull { o ->
                val id = o["id"].obj()?.s("videoId") ?: return@mapNotNull null
                val sn = o["snippet"].obj() ?: return@mapNotNull null
                Video(id, unescape(sn.s("title") ?: ""), sn.s("channelTitle") ?: "")
            }.sortedByDescending { v ->
                val t = v.title.lowercase(); val c = v.channel.lowercase()
                (if (nicks.all { t.contains(it) }) 4 else nicks.count { t.contains(it) }) +
                    (if (t.contains("highlight") || t.contains("recap")) 2 else 0) +
                    (if (official.any { c.contains(it) }) 3 else 0)
            }
            val withLen = withLengths(vids, key)
            if (withLen.isNotEmpty()) cache[g.id] = withLen
            Result(withLen, if (vids.isEmpty()) "No recap found on YouTube yet. They usually appear within a few hours of the final whistle." else null)
        } catch (e: Exception) {
            val m = e.message ?: ""
            Result(emptyList(), when {
                m.contains("403") -> "YouTube refused the key (or today's free search limit is used up). Check the key in Settings."
                m.contains("400") -> "YouTube didn't accept the key. Check it in Settings → YouTube."
                else -> "Couldn't reach YouTube: $m"
            })
        }
    }
}
