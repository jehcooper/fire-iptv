package app.sideline.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Everything the user chooses, stored on the stick. Exposed as StateFlows so screens update live. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sideline", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _accounts = MutableStateFlow(loadAccounts())
    val accounts: StateFlow<List<Account>> = _accounts

    private val _favLeagues = MutableStateFlow(sp.getStringSet("favLeagues", null)?.toSet() ?: DEFAULT_LEAGUES)
    val favLeagues: StateFlow<Set<String>> = _favLeagues

    /** FBS conference ids, e.g. "5" for the Big Ten. */
    private val _favConfs = MutableStateFlow(sp.getStringSet("favConfs", emptySet())!!.toSet())
    val favConferences: StateFlow<Set<String>> = _favConfs

    /** "leagueId|teamId" */
    private val _favTeams = MutableStateFlow(sp.getStringSet("favTeams", emptySet())!!.toSet())
    val favTeams: StateFlow<Set<String>> = _favTeams

    /** ESPN event ids the user asked to get score pop-ups for. */
    private val _followed = MutableStateFlow(sp.getStringSet("followed", emptySet())!!.toSet())
    val followedGames: StateFlow<Set<String>> = _followed

    /** Series you've starred ("acc:seriesId"). */
    private val _favSeries = MutableStateFlow(sp.getStringSet("favSeries", emptySet())!!.toSet())
    val favSeries: StateFlow<Set<String>> = _favSeries
    fun toggleFavSeries(key: String) = toggle(_favSeries, "favSeries", key)

    /** Series you've watched an episode of, most recent first ("acc:seriesId"). */
    private val _watchedSeries = MutableStateFlow(sp.getString("watchedSeries", "")!!.split(",").filter { it.isNotBlank() })
    val watchedSeries: StateFlow<List<String>> = _watchedSeries
    fun pushWatchedSeries(key: String) {
        val l = (listOf(key) + _watchedSeries.value.filter { it != key }).take(60)
        _watchedSeries.value = l
        sp.edit().putString("watchedSeries", l.joinToString(",")).apply()
    }

    // ---- Channel edits ----
    /** Called after a rename/hide so the channel list can refresh. */
    var onChannelEdit: (() -> Unit)? = null

    private val _hiddenChannels = MutableStateFlow(sp.getStringSet("hiddenChannels", emptySet())!!.toSet())
    val hiddenChannels: StateFlow<Set<String>> = _hiddenChannels
    fun toggleHiddenChannel(key: String) { toggle(_hiddenChannels, "hiddenChannels", key); onChannelEdit?.invoke() }

    private val _channelNames = MutableStateFlow(loadMap("channelNames"))
    val channelNames: StateFlow<Map<String, String>> = _channelNames
    fun renameChannel(key: String, name: String?) {
        val m = _channelNames.value.toMutableMap()
        if (name.isNullOrBlank()) m.remove(key) else m[key] = name.trim()
        _channelNames.value = m
        sp.edit().putString("channelNames", json.encodeToString(kotlinx.serialization.builtins.MapSerializer(
            String.serializer(), String.serializer()), m)).apply()
        onChannelEdit?.invoke()
    }
    private fun loadMap(key: String): Map<String, String> = try {
        sp.getString(key, null)?.let {
            json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(
                String.serializer(), String.serializer()), it)
        } ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    /** Your order for favourite channels (keys); favourites not listed go at the end. */
    private val _favOrder = MutableStateFlow(sp.getString("favOrder", "")!!.split(",").filter { it.isNotBlank() })
    val favOrder: StateFlow<List<String>> = _favOrder
    private fun saveFavOrder(l: List<String>) { _favOrder.value = l; sp.edit().putString("favOrder", l.joinToString(",")).apply() }
    /** Move a favourite up (-1), down (+1), or to the top (Int.MIN_VALUE). */
    fun moveFavourite(key: String, delta: Int) {
        val order = (_favOrder.value.filter { it in _favChannels.value } + _favChannels.value.filter { it !in _favOrder.value }).toMutableList()
        val i = order.indexOf(key)
        if (i < 0) return
        order.removeAt(i)
        val j = if (delta == Int.MIN_VALUE) 0 else (i + delta).coerceIn(0, order.size)
        order.add(j, key)
        saveFavOrder(order)
    }

    /** Hidden categories: "live:acc:cat", "vod:acc:cat", "series:acc:cat". */
    private val _hiddenCats = MutableStateFlow(sp.getStringSet("hiddenCats", emptySet())!!.toSet())
    val hiddenCats: StateFlow<Set<String>> = _hiddenCats
    fun toggleHidden(key: String) = toggle(_hiddenCats, "hiddenCats", key)
    fun showAllCats(prefix: String) {
        val s = _hiddenCats.value.filterNot { it.startsWith(prefix) }.toSet()
        _hiddenCats.value = s
        sp.edit().putStringSet("hiddenCats", s).apply()
    }

    /** Games pinned to the bottom of the screen while watching. */
    private val _pinned = MutableStateFlow(sp.getStringSet("pinned", emptySet())!!.toSet())
    val pinnedGames: StateFlow<Set<String>> = _pinned

    private val _favChannels = MutableStateFlow(sp.getStringSet("favChannels", emptySet())!!.toSet())
    val favChannels: StateFlow<Set<String>> = _favChannels

    private val _autoFollowTeams = MutableStateFlow(sp.getBoolean("autoFollowTeams", true))
    val autoFollowTeams: StateFlow<Boolean> = _autoFollowTeams

    private val _spoilerFree = MutableStateFlow(sp.getBoolean("spoilerFree", false))
    val spoilerFree: StateFlow<Boolean> = _spoilerFree

    // ---- Subtitles ----
    private fun str(key: String, def: String) = MutableStateFlow(sp.getString(key, def) ?: def)
    private val _subsOn = MutableStateFlow(sp.getBoolean("subsOn", false))
    /** Subtitles on by default for movies & series. */
    val subsOn: StateFlow<Boolean> = _subsOn
    private val _subsLang = str("subsLang", "en")
    val subsLang: StateFlow<String> = _subsLang
    private val _osKey = str("osKey", "")
    val osKey: StateFlow<String> = _osKey
    private val _osUser = str("osUser", "")
    val osUser: StateFlow<String> = _osUser
    private val _osPass = str("osPass", "")
    val osPass: StateFlow<String> = _osPass

    /** TV guide: "full" (provider's xmltv.php), "basic" (provider's now & next), or "custom" (guideUrl). */
    private val _guideSource = str("guideSource", "full")
    val guideSource: StateFlow<String> = _guideSource
    private val _guideUrl = str("guideUrl", "")
    val guideUrl: StateFlow<String> = _guideUrl
    fun setGuideSource(v: String) { _guideSource.value = v; sp.edit().putString("guideSource", v).apply() }
    fun setGuideUrl(v: String) { _guideUrl.value = v.trim(); sp.edit().putString("guideUrl", v.trim()).apply() }

    /** How see-through the player's side panels are, 0–100 % in steps of 5 (0 = solid). */
    private val _panelTransparency = MutableStateFlow(sp.getInt("panelTransparency", 30))
    val panelTransparency: StateFlow<Int> = _panelTransparency
    fun setPanelTransparency(v: Int) {
        val c = (v / 5 * 5).coerceIn(0, 100)
        _panelTransparency.value = c
        sp.edit().putInt("panelTransparency", c).apply()
    }

    private val _omdbKey = str("omdbKey", "")
    val omdbKey: StateFlow<String> = _omdbKey
    fun setOmdbKey(v: String) { _omdbKey.value = v.trim(); sp.edit().putString("omdbKey", v.trim()).apply() }

    private val _tmdbKey = str("tmdbKey", "")
    val tmdbKey: StateFlow<String> = _tmdbKey
    fun setTmdbKey(v: String) { _tmdbKey.value = v.trim(); sp.edit().putString("tmdbKey", v.trim()).apply() }

    fun setSubsOn(v: Boolean) { _subsOn.value = v; sp.edit().putBoolean("subsOn", v).apply() }
    fun setSubsLang(v: String) { _subsLang.value = v; sp.edit().putString("subsLang", v).apply() }
    fun setOpenSubtitles(key: String, user: String, pass: String) {
        _osKey.value = key.trim(); _osUser.value = user.trim(); _osPass.value = pass
        sp.edit().putString("osKey", key.trim()).putString("osUser", user.trim()).putString("osPass", pass).apply()
    }

    /** Most recent first, channel keys. */
    private val _recent = MutableStateFlow(sp.getString("recent", "")!!.split(",").filter { it.isNotBlank() })
    val recentChannels: StateFlow<List<String>> = _recent

    private fun loadAccounts(): List<Account> = try {
        sp.getString("accounts", null)?.let { json.decodeFromString(ListSerializer(Account.serializer()), it) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun saveAccount(a: Account) {
        val list = _accounts.value.filter { it.id != a.id } + a
        _accounts.value = list
        sp.edit().putString("accounts", json.encodeToString(ListSerializer(Account.serializer()), list)).apply()
    }

    fun deleteAccount(id: String) {
        val list = _accounts.value.filter { it.id != id }
        _accounts.value = list
        sp.edit().putString("accounts", json.encodeToString(ListSerializer(Account.serializer()), list)).apply()
    }

    private fun toggle(flow: MutableStateFlow<Set<String>>, key: String, value: String) {
        val s = flow.value.toMutableSet()
        if (!s.add(value)) s.remove(value)
        flow.value = s
        sp.edit().putStringSet(key, s).apply()
    }

    fun toggleLeague(id: String) = toggle(_favLeagues, "favLeagues", id)
    fun toggleConference(id: String) = toggle(_favConfs, "favConfs", id)
    fun toggleTeam(leagueId: String, teamId: String) = toggle(_favTeams, "favTeams", "$leagueId|$teamId")
    fun toggleFollow(eventId: String) = toggle(_followed, "followed", eventId)
    fun togglePin(eventId: String) = toggle(_pinned, "pinned", eventId)
    fun toggleFavChannel(key: String) {
        toggle(_favChannels, "favChannels", key)
        if (key in _favChannels.value) saveFavOrder(_favOrder.value.filter { it != key } + key)
        else saveFavOrder(_favOrder.value.filter { it != key })
    }

    /** Favourite channel keys in your chosen order. */
    fun orderedFavs(): List<String> {
        val favs = _favChannels.value
        return _favOrder.value.filter { it in favs } + favs.filter { it !in _favOrder.value }.sorted()
    }

    fun setAutoFollowTeams(v: Boolean) { _autoFollowTeams.value = v; sp.edit().putBoolean("autoFollowTeams", v).apply() }
    fun setSpoilerFree(v: Boolean) { _spoilerFree.value = v; sp.edit().putBoolean("spoilerFree", v).apply() }

    fun pushRecent(channelKey: String) {
        val l = (listOf(channelKey) + _recent.value.filter { it != channelKey }).take(20)
        _recent.value = l
        sp.edit().putString("recent", l.joinToString(",")).apply()
    }

    /** Drop followed games that have ended, so the list doesn't grow forever. */
    fun unfollowFinished(finished: Set<String>) {
        val p = _pinned.value - finished
        if (p.size != _pinned.value.size) { _pinned.value = p; sp.edit().putStringSet("pinned", p).apply() }
        val s = _followed.value - finished
        if (s.size != _followed.value.size) {
            _followed.value = s
            sp.edit().putStringSet("followed", s).apply()
        }
    }

    companion object {
        val DEFAULT_LEAGUES = setOf("nhl", "nfl", "nba", "mlb", "epl")
    }
}
