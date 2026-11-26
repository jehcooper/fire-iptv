package app.sideline.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One Xtream Codes login. useLive / useVod decide what this account supplies to the app. */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    val useLive: Boolean = true,
    val useVod: Boolean = true,
    /** "ts" or "m3u8" for live streams. */
    val liveFormat: String = "ts",
)

data class AccountStatus(
    val ok: Boolean,
    val message: String,
    val expires: String? = null,
    val maxConnections: Int? = null,
    val formats: List<String> = emptyList(),
    /** Expiry as epoch millis (null = provider gave none). */
    val expiresAt: Long? = null,
    val activeConnections: Int? = null,
)

data class Category(val accountId: String, val id: String, val name: String)

/** Guide id meaning "no guide for this channel" (your choice). */
const val NO_GUIDE = "-none-"

data class Channel(
    val accountId: String,
    val streamId: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val hasArchive: Boolean,
    val archiveDays: Int,
    /** The provider's own name, kept when you rename a channel (used for matching games and backups). */
    val origName: String = "",
) {
    val key: String get() = "$accountId:$streamId"
    val baseName: String get() = origName.ifBlank { name }
}

data class Movie(
    val accountId: String,
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val ext: String,
    val rating: String?,
    /** Release year when the provider gives it (or it's in the title), for "Latest releases". */
    val year: Int? = null,
    /** When the provider added it (epoch seconds). */
    val added: Long = 0,
) {
    val key: String get() = "$accountId:$streamId"
}

/** What we know about a movie, from the provider and (if that's thin) Wikipedia. */
data class MovieInfo(
    val title: String,
    val plot: String = "",
    val year: String = "",
    val genre: String = "",
    val runtime: String = "",
    val rating: String = "",
    val director: String = "",
    val cast: String = "",
    val backdrop: String? = null,
    val poster: String? = null,
    /** Shown as a credit when the description came from outside the provider. */
    val plotSource: String = "",
    /** Audience rating, e.g. "PG-13", "TV-MA", "14A (CA)". */
    val certification: String = "",
) {
    val metaLine: String get() = listOf(year, genre, runtime, rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "")
        .filter { it.isNotBlank() }.joinToString("  ·  ")
}

data class Series(
    val accountId: String,
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    /** Release year and last-updated time (epoch seconds), when the provider sends them. */
    val year: Int? = null,
    val added: Long = 0,
)

data class Episode(
    val accountId: String,
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val ext: String,
    val image: String?,
    val plot: String?,
)

data class EpgItem(
    val title: String,
    val description: String,
    /** Server-local start time as the provider sends it, e.g. "2026-11-07 08:00:00". Used for catch-up URLs. */
    val startRaw: String,
    val start: Long,
    val end: Long,
) {
    val isNow: Boolean get() {
        val now = System.currentTimeMillis() / 1000
        return now in start until end
    }
}

// ---- Lenient JSON helpers: Xtream panels mix strings, numbers and nulls freely. ----

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
fun JsonObject.i(key: String): Int? = s(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
fun JsonObject.l(key: String): Long? = s(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
fun JsonObject.b(key: String): Boolean = when (s(key)?.lowercase()) {
    "1", "true", "yes" -> true
    else -> false
}

/** Normalises a channel name so "CA| TSN 1 FHD" and "TSN 1 HD" compare as the same channel. */
fun normalizeChannelName(name: String): String {
    var n = name.lowercase()
    n = n.replace(Regex("^[a-z]{2,3}\\s*[|:\\-]\\s*"), "")          // country prefixes like "CA|", "US:"
    n = n.replace(Regex("[\\[(].*?[\\])]"), " ")                     // bracketed tags
    n = n.replace(Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p?|720p?|50fps|60fps|raw|backup|vip|plus)\\b"), " ")
    n = n.replace(Regex("[^a-z0-9]+"), " ")
    return n.trim().replace(Regex("\\s+"), " ")
}

private val RX_SLOT_PREFIX = Regex("^[a-z]{2,3}\\s*[|:\\-]\\s*")
private val RX_SLOT_BRACKETS = Regex("[\\[(].*?[\\])]")
private val RX_SLOT_QUALITY = Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p?|720p?|50fps|60fps|raw|backup|vip|plus)\\b")
private val RX_SLOT = Regex("^([a-z][a-z0-9 &+.']*?)\\s*(?:#|no\\.?|ch\\.?|channel)?\\s*0*(\\d{1,3})(?!\\d)(.*)$")
private val RX_SLOT_SEP = Regex("\\s*[:|]|\\s-\\s|\\s–\\s")
private val RX_SLOT_GAME = Regex("\\b(vs?|at)\\b|@")

/**
 * "Same channel number" key, so "NHL 05 FHD: Leafs vs Bruins" and "NHL 05 SD" count as the same feed.
 * Text after a separator (":", "|", " - ") or that names a game ("vs", "@") is ignored; other text stays,
 * so "FOX 5 New York" and "FOX 5 Atlanta" stay different. Null when the name has no number.
 */
fun channelSlot(name: String): String? {
    var n = name.lowercase().trim()
    n = n.replace(RX_SLOT_PREFIX, "").replace(RX_SLOT_BRACKETS, " ").replace(RX_SLOT_QUALITY, " ")
        .replace(Regex("\\s+"), " ").trim()
    val m = RX_SLOT.find(n) ?: return null
    val base = m.groupValues[1].replace(Regex("[^a-z0-9]+"), " ").trim()
    if (base.isEmpty()) return null
    var tail = m.groupValues[3]
    RX_SLOT_SEP.find(tail)?.let { tail = tail.substring(0, it.range.first) }
    if (RX_SLOT_GAME.containsMatchIn(tail)) tail = ""
    tail = tail.replace(Regex("[^a-z0-9]+"), " ").trim()
    return "$base#${m.groupValues[2].toInt()}" + if (tail.isNotEmpty()) "#$tail" else ""
}

private val RX_EVENT_SLOT = Regex(
    "^(?:[a-z]{2,3}\\s*[|:\\-]\\s*)?(?:nhl|nfl|nba|mlb|mls|wnba|ncaa[fb]?|cfb|cbb|epl|ufc|pga|nascar|f1|espn\\s*\\+|espn plus|" +
        "nfl sunday ticket|nhl cent(?:re|er) ice|nba league pass|mlb extra innings|mls season pass|ppv|live event|events?|sports? events?|" +
        "dazn|peacock|paramount\\s*\\+|fubo|flo\\w*|b1g\\s*\\+|big ten\\s*\\+|sec\\s*\\+|acc\\s*(?:n|nx|extra)|tsn\\s*\\+|sn\\s*\\+)" +
        "[^a-z0-9]*(?:#|no\\.?|ch\\.?)?\\s*\\d{1,3}\\b",
    RegexOption.IGNORE_CASE,
)
private val RX_EVENT_GAME = Regex("\\s(?:vs?\\.?|at|@)\\s.*\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)\\b|\\b\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)\\s?(?:et|pt|ct|mt|est|pst|gmt|uk)\\b",
    RegexOption.IGNORE_CASE)

/**
 * Channels that only exist for one game or event ("NHL 07: Oilers at Lightning 7:30PM ET", "ESPN+ 12", "NFL 03 SD").
 * They're not kept in Recent, because once the game is over the channel shows something else.
 */
fun isEventChannel(name: String): Boolean = RX_EVENT_SLOT.containsMatchIn(name.trim()) || RX_EVENT_GAME.containsMatchIn(name)
