package app.sideline.tv.data

import app.sideline.tv.SidelineApp
import app.sideline.tv.sports.LeagueKind
import app.sideline.tv.sports.Leagues
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Data behind the phone setup page: [state] sends every setting to the phone, [save] applies one section
 * the phone sends back. Served by the same small home-network web server as Backup & restore.
 */
object PhoneSetup {
    fun state(): String {
        val prefs = SidelineApp.prefs
        val repo = SidelineApp.repo
        val scores = SidelineApp.scores
        // Make sure team lists exist for leagues you follow or have teams in, so the page can show them.
        val teamLeagues = (prefs.favLeagues.value + prefs.favTeams.value.map { it.substringBefore('|') })
            .mapNotNull { Leagues.byId(it) }.filter { it.kind == LeagueKind.TEAM }.map { it.baseId }.toSet()
        teamLeagues.forEach { scores.loadTeams(it) }

        val catName = HashMap<String, String>()
        repo.liveCategories.value.forEach { catName["${it.accountId}:${it.id}"] = it.name }
        val names = prefs.channelNames.value
        val hidden = prefs.hiddenChannels.value
        val favs = prefs.favChannels.value

        return buildJsonObject {
            put("accounts", Net.json.encodeToJsonElement(ListSerializer(Account.serializer()), prefs.accounts.value))
            putJsonObject("keys") {
                put("tmdb", prefs.tmdbKey.value); put("omdb", prefs.omdbKey.value)
                put("osKey", prefs.osKey.value); put("osUser", prefs.osUser.value); put("osPass", prefs.osPass.value)
                put("traktId", prefs.traktClientId.value); put("traktSecret", prefs.traktSecret.value)
            }
            putJsonObject("guide") { put("source", prefs.guideSource.value); put("url", prefs.guideUrl.value) }
            putJsonObject("display") {
                put("panelTransparency", prefs.panelTransparency.value)
                put("popupTransparency", prefs.popupTransparency.value)
                put("theme", prefs.theme.value)
                putJsonArray("themes") { app.sideline.tv.ui.Themes.all.forEach { t -> addJsonObject { put("id", t.id); put("name", t.name) } } }
                put("spoilerFree", prefs.spoilerFree.value)
                put("autoFollowTeams", prefs.autoFollowTeams.value)
                put("subsOn", prefs.subsOn.value)
                put("subsLang", prefs.subsLang.value)
            }
            putJsonObject("sports") {
                putJsonArray("leagues") {
                    Leagues.all.forEach { l -> addJsonObject { put("id", l.id); put("name", l.name); put("group", l.group); put("team", l.kind == LeagueKind.TEAM) } }
                }
                putJsonArray("favLeagues") { prefs.favLeagues.value.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("leagueOpts") { prefs.leagueOpts.value.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("conferences") {
                    scores.conferences.value.ifEmpty { Leagues.conferences }.forEach { c -> addJsonObject { put("id", c.id); put("name", c.name) } }
                }
                putJsonArray("favConfs") { prefs.favConferences.value.forEach { add(JsonPrimitive(it)) } }
                putJsonObject("teams") {
                    scores.teams.value.forEach { (lid, list) ->
                        putJsonArray(lid) { list.forEach { t -> addJsonObject { put("id", t.id); put("name", t.name) } } }
                    }
                }
                putJsonArray("favTeams") { prefs.favTeams.value.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("categories") {
                fun cats(kind: String, list: List<Category>) = putJsonArray(kind) {
                    list.forEach { c -> addJsonObject { put("k", "$kind:${c.accountId}:${c.id}"); put("n", c.name) } }
                }
                cats("live", repo.liveCategories.value); cats("vod", repo.vodCategories.value); cats("series", repo.seriesCategories.value)
                putJsonArray("hidden") { prefs.hiddenCats.value.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("channels") {
                putJsonArray("list") {
                    repo.rawChannels.forEach { c ->
                        addJsonObject {
                            put("k", c.key); put("o", c.name)
                            names[c.key]?.let { put("n", it) }
                            put("c", catName["${c.accountId}:${c.categoryId}"] ?: "")
                            if (c.key in hidden) put("h", true)
                            if (c.key in favs) put("f", true)
                        }
                    }
                }
                putJsonArray("favOrder") { prefs.orderedFavs().forEach { add(JsonPrimitive(it)) } }
            }
        }.toString()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** Applies one section. Returns a short message for the phone. */
    fun save(body: String): String {
        val root = Net.json.parseToJsonElement(body).jsonObject
        val section = root.str("section")
        val d = root["data"]?.jsonObject ?: throw IllegalArgumentException("Nothing to save")
        val prefs = SidelineApp.prefs
        when (section) {
            "accounts" -> {
                val list = Net.json.decodeFromJsonElement(ListSerializer(Account.serializer()), d["list"] ?: JsonArray(emptyList()))
                    .map { a -> a.copy(id = a.id.ifBlank { "acc" + System.currentTimeMillis() + (0..999).random() }, server = a.server.trim().trimEnd('/'),
                        username = a.username.trim(), name = a.name.trim().ifBlank { "IPTV" }) }
                prefs.setAccounts(list)
                SidelineApp.repo.refresh()
                return "Accounts saved. The TV is reloading your channels and movies."
            }
            "keys" -> {
                prefs.setTmdbKey(d.str("tmdb")); prefs.setOmdbKey(d.str("omdb"))
                prefs.setOpenSubtitles(d.str("osKey"), d.str("osUser"), d.str("osPass"))
                prefs.setTraktApp(d.str("traktId"), d.str("traktSecret"))
            }
            "guide" -> {
                prefs.setGuideSource(d.str("source").ifBlank { "full" }); prefs.setGuideUrl(d.str("url"))
                FullGuide.load(force = true)
            }
            "display" -> {
                prefs.setPanelTransparency((d["panelTransparency"] as? JsonPrimitive)?.intOrNull ?: prefs.panelTransparency.value)
                d.str("theme").takeIf { it.isNotBlank() }?.let { prefs.setTheme(it) }
                prefs.setPopupTransparency((d["popupTransparency"] as? JsonPrimitive)?.intOrNull ?: prefs.popupTransparency.value)
                prefs.setSpoilerFree(d.bool("spoilerFree"))
                prefs.setAutoFollowTeams(d.bool("autoFollowTeams"))
                prefs.setSubsOn(d.bool("subsOn"))
                d.str("subsLang").takeIf { it.isNotBlank() }?.let { prefs.setSubsLang(it.trim().lowercase()) }
            }
            "sports" -> {
                prefs.setFavLeagues(d.strings("favLeagues").toSet())
                prefs.setLeagueOpts(d.strings("leagueOpts").toSet())
                prefs.setFavConferences(d.strings("favConfs").toSet())
                prefs.setFavTeams(d.strings("favTeams").toSet())
                SidelineApp.scores.refreshNow()
            }
            "categories" -> prefs.setHiddenCats(d.strings("hidden").toSet())
            "channels" -> {
                val names = (d["names"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
                prefs.setChannelEdits(names, d.strings("hidden").toSet(), d.strings("favs"))
            }
            else -> throw IllegalArgumentException("Unknown section")
        }
        return "Saved."
    }
}
