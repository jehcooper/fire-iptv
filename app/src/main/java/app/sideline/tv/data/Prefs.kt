package app.sideline.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
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
    fun toggleFavChannel(key: String) = toggle(_favChannels, "favChannels", key)

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
