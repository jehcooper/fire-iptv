package app.sideline.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One movie or episode you've started but not finished. */
@Serializable
data class ProgressEntry(
    /** "movie:acc:id" or "ep:acc:episodeId" — same key the player uses for resume positions. */
    val key: String,
    /** "movie" or "episode" */
    val kind: String,
    val accountId: String,
    /** Movie name, or series name for episodes. */
    val title: String,
    val subtitle: String = "",
    val image: String? = null,
    val position: Long,
    val duration: Long,
    val updated: Long,
    val streamId: Int = 0,
    val ext: String = "mp4",
    val seriesId: Int = 0,
    val episodeId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val plot: String? = null,
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    fun toMovie() = Movie(accountId, streamId, title, image, "", ext, null)
    fun toEpisode() = Episode(accountId, episodeId, subtitle.substringAfter("· ", subtitle), season, episode, ext, image, plot)
}

/** "Continue watching": newest first; one entry per movie and one per series (its latest episode). */
class WatchProgress(context: Context) {
    private val sp = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(ProgressEntry.serializer())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ProgressEntry>> = _entries

    private fun load(): List<ProgressEntry> = try {
        sp.getString("list", null)?.let { json.decodeFromString(ser, it) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<ProgressEntry>) {
        _entries.value = list
        sp.edit().putString("list", json.encodeToString(ser, list)).apply()
    }

    /** Record progress; finished (within 5 min of the end) or barely started (< 1 min) removes it. */
    fun update(e: ProgressEntry) {
        val rest = _entries.value.filter { it.key != e.key && !(e.kind == "episode" && it.kind == "episode" && it.seriesId == e.seriesId && it.accountId == e.accountId) }
        val finished = e.duration > 0 && e.position > e.duration - 5 * 60_000
        val barely = e.position < 60_000
        if (finished) markDone(e.key)
        save(if (finished || barely) rest.filter { it.key != e.key } else (listOf(e) + rest).take(40))
    }

    fun remove(key: String) = save(_entries.value.filter { it.key != key })

    /** Adds a place saved on another device (via Trakt), kept in date order; replaces older ones for the same title. */
    fun merge(e: ProgressEntry) {
        val rest = _entries.value.filter { it.key != e.key && !(e.kind == "episode" && it.kind == "episode" && it.seriesId == e.seriesId && it.accountId == e.accountId) }
        save((rest + e).sortedByDescending { it.updated }.take(40))
    }

    private val _done = MutableStateFlow(sp.getStringSet("done", emptySet())!!.toSet())
    /** Keys of movies/episodes you watched to the end (for ✓ marks). */
    val done: StateFlow<Set<String>> = _done
    /** Mark watched (also leaves Continue watching) or unwatched, by hand. */
    fun setDone(key: String, done: Boolean) {
        if (done) { markDone(key); remove(key) }
        else if (key in _done.value) {
            val d = _done.value - key
            _done.value = d
            sp.edit().putStringSet("done", d).apply()
        }
    }
    private fun markDone(key: String) {
        if (key in _done.value) return
        val d = (_done.value + key).let { if (it.size > 3000) it.drop(it.size - 3000).toSet() else it }
        _done.value = d
        sp.edit().putStringSet("done", d).apply()
    }
}
