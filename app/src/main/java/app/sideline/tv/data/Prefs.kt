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

    /** "leagueId|teamId" */
    private val _favTeams = MutableStateFlow(sp.getStringSet("favTeams", emptySet())!!.toSet())
    val favTeams: StateFlow<Set<String>> = _favTeams

    /** ESPN event ids the user asked to get score pop-ups for. */
    private val _followed = MutableStateFlow(sp.getStringSet("followed", emptySet())!!.toSet())
    val followedGames: StateFlow<Set<String>> = _followed

    private val _favChannels = MutableStateFlow(sp.getStringSet("favChannels", emptySet())!!.toSet())
    val favChannels: StateFlow<Set<String>> = _favChannels

    private val _autoFollowTeams = MutableStateFlow(sp.getBoolean("autoFollowTeams", true))
    val autoFollowTeams: StateFlow<Boolean> = _autoFollowTeams

    private val _spoilerFree = MutableStateFlow(sp.getBoolean("spoilerFree", false))
    val spoilerFree: StateFlow<Boolean> = _spoilerFree

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
    fun toggleTeam(leagueId: String, teamId: String) = toggle(_favTeams, "favTeams", "$leagueId|$teamId")
    fun toggleFollow(eventId: String) = toggle(_followed, "followed", eventId)
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
