package app.sideline.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/**
 * Merges every account into one library. Live channels come from accounts marked "live",
 * movies and series from accounts marked "on-demand". Same screen, either subscription.
 */
class Repository(private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val clients = mutableMapOf<String, XtreamClient>()
    fun client(accountId: String): XtreamClient? {
        val acc = prefs.accounts.value.firstOrNull { it.id == accountId } ?: return null
        val existing = clients[accountId]
        if (existing != null && existing.account == acc) return existing
        return XtreamClient(acc).also { clients[accountId] = it }
    }

    val liveCategories = MutableStateFlow<List<Category>>(emptyList())
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val vodCategories = MutableStateFlow<List<Category>>(emptyList())
    val movies = MutableStateFlow<List<Movie>>(emptyList())
    val seriesCategories = MutableStateFlow<List<Category>>(emptyList())
    val series = MutableStateFlow<List<Series>>(emptyList())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors

    /** Channels grouped by normalised name, for auto-failover. */
    private var twins: Map<String, List<Channel>> = emptyMap()

    /** Pre-computed lowercase and normalised channel names, so game matching doesn't redo regex work. */
    @Volatile var lowerNames: Map<String, String> = emptyMap(); private set
    @Volatile var normNames: Map<String, String> = emptyMap(); private set
    @Volatile private var byKey: Map<String, Channel> = emptyMap()

    /** At most 6 guide requests at once, so a fast scroll through the TV guide doesn't flood the server. */
    private val epgGate = kotlinx.coroutines.sync.Semaphore(6)

    /** TV guide cache: one fetch per channel serves every game we check for 10 minutes. */
    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<EpgItem>>>()

    val catchupAvailable: Boolean get() = channels.value.any { it.hasArchive }

    fun refresh() {
        if (_loading.value) return
        scope.launch {
            _loading.value = true
            // Saved copy first (instant), then the provider's latest.
            if (channels.value.isEmpty() && movies.value.isEmpty()) load(fromCache = true)
            load(fromCache = false)
        }
    }

    private suspend fun load(fromCache: Boolean) {
        _loading.value = true
        val errs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val accounts = prefs.accounts.value
        coroutineScope {
            val live = accounts.filter { it.useLive }.map { a ->
                async {
                    try {
                        val c = client(a.id)!!
                        c.liveCategories(fromCache) to c.liveStreams(fromCache)
                    } catch (e: Exception) {
                        if (!fromCache) errs += "${a.name} (live): ${e.message}"
                        emptyList<Category>() to emptyList<Channel>()
                    }
                }
            }
            val vod = accounts.filter { it.useVod }.map { a ->
                async {
                    try {
                        val c = client(a.id)!!
                        Triple(c.vodCategories(fromCache), c.vodStreams(fromCache), c.seriesCategories(fromCache) to c.series(fromCache))
                    } catch (e: Exception) {
                        if (!fromCache) errs += "${a.name} (on-demand): ${e.message}"
                        Triple(emptyList<Category>(), emptyList<Movie>(), emptyList<Category>() to emptyList<Series>())
                    }
                }
            }
            val liveRes = live.awaitAll()
            val newChannels = liveRes.flatMap { it.second }
            // Keep what's on screen if a refresh comes back empty (e.g. provider briefly down).
            if (newChannels.isNotEmpty() || (!fromCache && errs.isEmpty())) {
            liveCategories.value = liveRes.flatMap { it.first }
            channels.value = newChannels
            val norm = channels.value.associate { it.key to normalizeChannelName(it.name) }
            normNames = norm
            lowerNames = channels.value.associate { it.key to it.name.lowercase() }
            byKey = channels.value.associateBy { it.key }
            twins = channels.value.groupBy { norm[it.key] ?: "" }
            }

            val vodRes = vod.awaitAll()
            val newMovies = vodRes.flatMap { it.second }
            val newSeries = vodRes.flatMap { it.third.second }
            if (newMovies.isNotEmpty() || newSeries.isNotEmpty() || (!fromCache && errs.isEmpty())) {
                vodCategories.value = vodRes.flatMap { it.first }
                movies.value = newMovies
                seriesCategories.value = vodRes.flatMap { it.third.first }
                series.value = newSeries
            }
        }
        if (!fromCache) {
            _errors.value = errs.toList()
            _loading.value = false
        }
    }

    fun channel(key: String): Channel? = byKey[key] ?: channels.value.firstOrNull { it.key == key }

    fun channelsIn(cat: Category): List<Channel> =
        channels.value.filter { it.accountId == cat.accountId && it.categoryId == cat.id }

    /** Other copies of the same channel (e.g. HD / FHD versions) to fall back to if one dies. */
    fun backupsFor(c: Channel): List<Channel> =
        twins[normalizeChannelName(c.name)].orEmpty().filter { it.key != c.key }

    fun categoryName(c: Channel): String =
        liveCategories.value.firstOrNull { it.accountId == c.accountId && it.id == c.categoryId }?.name ?: ""

    suspend fun epg(c: Channel, limit: Int = 4): List<EpgItem> = client(c.accountId)?.shortEpg(c.streamId, limit) ?: emptyList()

    suspend fun epgCached(c: Channel): List<EpgItem> {
        val now = System.currentTimeMillis()
        epgCache[c.key]?.let { (t, items) -> if (now - t < 10 * 60_000) return items }
        val items = epgGate.withPermit { epg(c, 12) }
        epgCache[c.key] = now to items
        return items
    }
}
