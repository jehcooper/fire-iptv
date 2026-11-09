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
)

data class Category(val accountId: String, val id: String, val name: String)

data class Channel(
    val accountId: String,
    val streamId: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val hasArchive: Boolean,
    val archiveDays: Int,
) {
    val key: String get() = "$accountId:$streamId"
}

data class Movie(
    val accountId: String,
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val ext: String,
    val rating: String?,
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
