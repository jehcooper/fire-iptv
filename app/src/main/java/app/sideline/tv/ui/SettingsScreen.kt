package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Account
import app.sideline.tv.data.AccountStatus
import app.sideline.tv.data.XtreamClient
import app.sideline.tv.sports.LeagueKind
import app.sideline.tv.sports.Team
import androidx.compose.foundation.lazy.grid.GridItemSpan
import app.sideline.tv.sports.Leagues
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.util.UUID

@Composable
fun SettingsScreen() {
    val prefs = SidelineApp.prefs
    val accounts by prefs.accounts.collectAsState()
    val favLeagues by prefs.favLeagues.collectAsState()
    val autoFollow by prefs.autoFollowTeams.collectAsState()
    val spoilerFree by prefs.spoilerFree.collectAsState()
    val channels by SidelineApp.repo.channels.collectAsState()
    var editing by remember { mutableStateOf<Account?>(null) }
    var teamsFor by remember { mutableStateOf<String?>(null) }
    val confs by SidelineApp.scores.conferences.collectAsState()
    val favConfs by prefs.favConferences.collectAsState()
    LaunchedEffect(Unit) { SidelineApp.scores.loadConferences() }
    val subsOn by prefs.subsOn.collectAsState()
    val subsLang by prefs.subsLang.collectAsState()
    val osKey by prefs.osKey.collectAsState()
    val osUser by prefs.osUser.collectAsState()
    var editOs by remember { mutableStateOf(false) }
    var manageCats by remember { mutableStateOf<String?>(null) }
    var editTmdb by remember { mutableStateOf(false) }
    var editOmdb by remember { mutableStateOf(false) }
    val omdbKey by prefs.omdbKey.collectAsState()
    val tmdbKey by prefs.tmdbKey.collectAsState()
    val hidden by prefs.hiddenCats.collectAsState()

    LazyColumn(
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item {
            Column {
                SectionTitle("IPTV accounts")
                Text("Add each subscription and choose what it supplies. Everything shows together.", color = C.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(accounts, key = { it.id }) { a ->
                        FocusCard(onClick = { editing = a }, modifier = Modifier.width(280.dp).height(110.dp)) { _ ->
                            Column(Modifier.padding(16.dp)) {
                                Text(a.name, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(if (a.useLive) "Live channels" else null, if (a.useVod) "Movies & series" else null).joinToString(" + "),
                                    color = C.Accent, fontSize = 13.sp)
                                Text(a.server.removePrefix("http://").removePrefix("https://"), color = C.Faint, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                    item {
                        FocusCard(onClick = {
                            editing = Account(UUID.randomUUID().toString(), "Account ${accounts.size + 1}", "", "", "",
                                useLive = accounts.none { it.useLive }, useVod = accounts.none { it.useVod } || accounts.isEmpty())
                        }, modifier = Modifier.width(200.dp).height(110.dp)) { _ ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("＋ Add account", color = C.Accent, fontSize = 17.sp) }
                        }
                    }
                }
            }
        }

        item {
            Column {
                SectionTitle("Your leagues")
                Text("Scores for these show on the Sports and Home screens.", color = C.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Leagues.all.groupBy { it.group }.forEach { (group, list) ->
                    Text(group, color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(list, key = { it.id }) { l -> Chip(l.name, l.id in favLeagues, onClick = { prefs.toggleLeague(l.id); SidelineApp.scores.refreshNow() }) }
                    }
                }
            }
        }

        item {
            Column {
                SectionTitle("College football conferences")
                Text("Each conference you pick gets its own tab on the Sports screen with all its games.", color = C.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(confs, key = { it.id }) { c ->
                        Chip(c.short, c.id in favConfs, onClick = { prefs.toggleConference(c.id); SidelineApp.scores.refreshNow() })
                    }
                }
            }
        }

        item {
            Column {
                SectionTitle("Your teams")
                Text("Games involving these teams appear in My Teams.", color = C.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                val teamLeagues = Leagues.all.filter { it.kind == LeagueKind.TEAM && (it.id in favLeagues || (it.id == "ncaaf" && favConfs.isNotEmpty())) }
                if (teamLeagues.isEmpty()) Text("Turn on a team league above first.", color = C.Faint)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(teamLeagues, key = { it.id }) { l -> Chip("Pick ${l.name} teams", false, onClick = { teamsFor = l.id }) }
                }
            }
        }

        item {
            Column {
                SectionTitle("Pop-ups & scores")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (autoFollow) "✓ Pop-ups for my teams' games" else "Pop-ups for my teams' games", autoFollow,
                        onClick = { prefs.setAutoFollowTeams(!autoFollow) })
                    Chip(if (spoilerFree) "✓ Spoiler-free (hide scores)" else "Spoiler-free (hide scores)", spoilerFree,
                        onClick = { prefs.setSpoilerFree(!spoilerFree) })
                }
                Text("You can also turn pop-ups on for any single game from the Sports screen.", color = C.Faint, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }

        item {
            Column {
                SectionTitle("Show or hide categories")
                Text("Hide categories you never watch. They also drop out of search and \"All\".", color = C.Muted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("live" to "Live TV", "vod" to "Movies", "series" to "Series").forEach { (k, label) ->
                        val n = hidden.count { it.startsWith("$k:") }
                        Chip(if (n > 0) "$label ($n hidden)" else label, false, onClick = { manageCats = k })
                    }
                }
            }
        }

        item {
            Column {
                SectionTitle("Movie & series info")
                Text("Audience ratings (PG-13, TV-MA, 14A…) come from your provider when it has them. Otherwise they come from TMDB, " +
                    "which needs a free key. TMDB also fills in missing descriptions.", color = C.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (tmdbKey.isBlank()) "Add TMDB key" else "TMDB: key saved", false, onClick = { editTmdb = true })
                    Chip(if (omdbKey.isBlank()) "Add OMDb key (IMDb ratings)" else "OMDb: key saved", false, onClick = { editOmdb = true })
                }
                Text("IMDb ratings on series tiles come from OMDb (free key, 1,000 lookups a day; results are saved for a week). " +
                    "Without it, tiles show your provider's own score marked ★.", color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item {
            Column {
                SectionTitle("Subtitles (movies & series)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (subsOn) "✓ Subtitles on" else "Subtitles off", subsOn, onClick = { prefs.setSubsOn(!subsOn) })
                }
                Text("Language:", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(listOf("en" to "English", "fr" to "French", "es" to "Spanish", "hi" to "Hindi", "de" to "German", "pt-br" to "Portuguese", "ar" to "Arabic")) { (code, name) ->
                        Chip(name, subsLang == code, onClick = { prefs.setSubsLang(code) })
                    }
                }
                Text(
                    "Subtitles built into the video are used first. If there are none, they're fetched from OpenSubtitles.com, " +
                        "which needs a free API key (5 downloads a day; 20 with a free account). " +
                        "While watching, press ▲ to switch subtitles on/off or pick another.",
                    color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Chip(if (osKey.isBlank()) "Add OpenSubtitles key" else "OpenSubtitles: key saved${if (osUser.isNotBlank()) " · $osUser" else ""}", false,
                    onClick = { editOs = true })
            }
        }

        item {
            Column {
                SectionTitle("About")
                val catchup = when {
                    channels.isEmpty() -> "Not checked yet (no channels loaded)"
                    SidelineApp.repo.catchupAvailable -> "Yes. ${channels.count { it.hasArchive }} channels support catch-up (look for the CATCH-UP tag)"
                    else -> "No. Your live provider doesn't offer catch-up"
                }
                Text("Catch-up: $catchup", color = C.Muted, fontSize = 14.sp)
                Text("Scores come from ESPN's public feed. It's unofficial and may change.", color = C.Faint, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Chip("⟳ Reload channels & movies", false, onClick = { SidelineApp.repo.refresh() })
            }
        }
    }

    editing?.let { a -> AccountEditor(a, isNew = accounts.none { it.id == a.id }, onDone = { editing = null }) }
    teamsFor?.let { id -> TeamPicker(id) { teamsFor = null } }
    if (editOs) OpenSubtitlesEditor { editOs = false }
    manageCats?.let { k -> CategoryManager(k) { manageCats = null } }
    if (editTmdb) KeyEditor(
        "TMDB (The Movie Database)",
        "Free key: sign up at themoviedb.org, then Settings → API → create a key (personal use). Paste either the API Key or the API Read Access Token.",
        "TMDB API key or Read Access Token", prefs.tmdbKey.value, { prefs.setTmdbKey(it) },
    ) { editTmdb = false }
    if (editOmdb) KeyEditor(
        "OMDb (IMDb ratings)",
        "Free key: go to omdbapi.com → API Key → choose FREE, enter your email, and click the activation link they send. Then paste the key here.",
        "OMDb API key", prefs.omdbKey.value, { prefs.setOmdbKey(it) },
    ) { editOmdb = false }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Surface2,
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
            focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccountEditor(start: Account, isNew: Boolean, onDone: () -> Unit) {
    val prefs = SidelineApp.prefs
    var a by remember { mutableStateOf(start) }
    var status by remember { mutableStateOf<AccountStatus?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { firstField.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDone) {

    Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        LazyColumn(
            Modifier.width(720.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface),
            contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(if (isNew) "Add IPTV account" else "Edit ${start.name}", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            item { Field("Nickname (e.g. Live TV, Movies)", a.name, { a = a.copy(name = it) }, modifier = Modifier.focusRequester(firstField)) }
            item { Field("Server address (e.g. http://example.com:8080)", a.server, { a = a.copy(server = it.trim()) }) }
            item { Field("Username", a.username, { a = a.copy(username = it.trim()) }) }
            item { Field("Password", a.password, { a = a.copy(password = it.trim()) }, secret = true) }
            item {
                Column {
                    Text("This account supplies:", color = C.Muted, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Chip(if (a.useLive) "✓ Live channels" else "Live channels", a.useLive, onClick = { a = a.copy(useLive = !a.useLive) })
                        Chip(if (a.useVod) "✓ Movies & series" else "Movies & series", a.useVod, onClick = { a = a.copy(useVod = !a.useVod) })
                    }
                }
            }
            item {
                Column {
                    Text("Live stream format (try HLS if channels stutter):", color = C.Muted, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Chip("MPEG-TS (default)", a.liveFormat == "ts", onClick = { a = a.copy(liveFormat = "ts") })
                        Chip("HLS (m3u8)", a.liveFormat == "m3u8", onClick = { a = a.copy(liveFormat = "m3u8") })
                    }
                }
            }
            item {
                status?.let { s ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface2).padding(14.dp)) {
                        Text(if (s.ok) "✓ Connected" else "✗ Problem", color = if (s.ok) C.Accent else C.Live, fontWeight = FontWeight.Bold)
                        Text(s.message, color = C.Text, fontSize = 14.sp)
                        s.expires?.let { Text("Expires: $it", color = C.Muted, fontSize = 13.sp) }
                        s.maxConnections?.let { Text("Devices allowed at once: $it", color = C.Muted, fontSize = 13.sp) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (testing) "Testing…" else "Test & save", true, onClick = {
                        if (testing) return@Chip
                        testing = true
                        scope.launch {
                            val s = XtreamClient(a).login()
                            status = s
                            testing = false
                            if (s.ok) {
                                prefs.saveAccount(a)
                                SidelineApp.repo.refresh()
                                onDone()
                            }
                        }
                    })
                    Chip("Save without testing", false, onClick = { prefs.saveAccount(a); SidelineApp.repo.refresh(); onDone() })
                    if (!isNew) Chip("Delete", false, onClick = {
                        Confirm.ask("Delete the account \"${start.name}\"?", "Its channels, movies and series disappear from the app.", "Delete", destructive = true) {
                            prefs.deleteAccount(a.id); SidelineApp.repo.refresh(); onDone()
                        }
                    })
                    Chip("Cancel", false, onClick = onDone)
                }
            }
        }
    }
    }
}

@Composable
private fun TeamPicker(leagueId: String, onDone: () -> Unit) {
    val prefs = SidelineApp.prefs
    val teamsMap by SidelineApp.scores.teams.collectAsState()
    val fav by prefs.favTeams.collectAsState()
    val league = Leagues.byId(leagueId)
    val confs by SidelineApp.scores.conferences.collectAsState()
    LaunchedEffect(leagueId) { SidelineApp.scores.loadTeams(leagueId) }
    TvDialog(onDone) {
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
            Text("${league?.name ?: ""} teams", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Select to add or remove. Press Back when done.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
            val teams = teamsMap[leagueId]
            if (teams == null) { Text("Loading teams…", color = C.Muted); return@Column }
            if (teams.isEmpty()) { Text("Couldn't load teams right now. Try again later.", color = C.Muted); return@Column }
            // College football: show teams under their conference headings.
            val sections: List<Pair<String, List<Team>>> =
                if (leagueId == "ncaaf" && confs.any { it.teams.isNotEmpty() }) confs.filter { it.teams.isNotEmpty() }.map { it.name to it.teams }
                else listOf("" to teams)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 60.dp, top = 8.dp),
            ) {
                sections.forEach { (title, list) ->
                    if (title.isNotBlank()) item(key = "h-$title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(title, color = C.Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    }
                    items(list, key = { "$title-${it.id}" }) { t ->
                        val on = "$leagueId|${t.id}" in fav
                        FocusCard(onClick = {
                            Confirm.ask(if (on) "Remove ${t.name} from your teams?" else "Add ${t.name} to your teams?",
                                yes = if (on) "Remove" else "Add", destructive = on) { prefs.toggleTeam(leagueId, t.id) }
                        }, modifier = Modifier.height(72.dp)) { _ ->
                            Row(
                                Modifier.fillMaxSize().background(if (on) C.hex(t.color).copy(alpha = 0.45f) else C.Surface).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(model = t.logo, contentDescription = null, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(t.name, color = C.Text, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
                                if (on) Text("★", color = C.Gold, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun OpenSubtitlesEditor(onDone: () -> Unit) {
    val prefs = SidelineApp.prefs
    var key by remember { mutableStateOf(prefs.osKey.value) }
    var user by remember { mutableStateOf(prefs.osUser.value) }
    var pass by remember { mutableStateOf(prefs.osPass.value) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { firstField.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDone) {
        Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
            LazyColumn(
                Modifier.width(720.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface),
                contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("OpenSubtitles", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                item {
                    Text("Get a free key: sign up at opensubtitles.com, then open your profile → API consumers → New consumer, and copy the API key.",
                        color = C.Muted, fontSize = 14.sp)
                }
                item { Field("API key (required)", key, { key = it.trim() }, modifier = Modifier.focusRequester(firstField)) }
                item { Field("Username (optional, raises the daily limit to 20)", user, { user = it.trim() }) }
                item { Field("Password (optional)", pass, { pass = it }, secret = true) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Save", true, onClick = { prefs.setOpenSubtitles(key, user, pass); onDone() })
                        Chip("Cancel", false, onClick = onDone)
                    }
                }
            }
        }
    }
}

/** Tick/untick categories to show or hide them. */
@Composable
private fun CategoryManager(kind: String, onDone: () -> Unit) {
    val prefs = SidelineApp.prefs
    val repo = SidelineApp.repo
    val hidden by prefs.hiddenCats.collectAsState()
    val cats by when (kind) {
        "live" -> repo.liveCategories
        "vod" -> repo.vodCategories
        else -> repo.seriesCategories
    }.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDone) {
        Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.97f))) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
                Text(when (kind) { "live" -> "Live TV categories"; "vod" -> "Movie categories"; else -> "Series categories" },
                    color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("✓ = shown. Select to switch. Back when done.", color = C.Muted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                    Chip("Show all", false, onClick = { prefs.showAllCats("$kind:") }, modifier = Modifier.focusRequester(first))
                    Chip("Hide all", false, onClick = {
                        Confirm.ask("Hide every category here?", yes = "Hide all", destructive = true) {
                            cats.forEach { c -> val k = "$kind:${c.accountId}:${c.id}"; if (k !in hidden) prefs.toggleHidden(k) }
                        }
                    })
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(260.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 60.dp),
                ) {
                    items(cats, key = { "${it.accountId}:${it.id}" }) { c ->
                        val k = "$kind:${c.accountId}:${c.id}"
                        val shown = k !in hidden
                        Chip(if (shown) "✓ ${c.name}" else c.name, shown, onClick = {
                            if (shown) Confirm.ask("Hide \"${c.name}\"?", yes = "Hide", destructive = true) { prefs.toggleHidden(k) }
                            else prefs.toggleHidden(k)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyEditor(title: String, help: String, label: String, current: String, save: (String) -> Unit, onDone: () -> Unit) {
    var key by remember { mutableStateOf(current) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); try { firstField.requestFocus() } catch (_: Exception) {} }
    TvDialog(onDone) {
        Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(720.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(help, color = C.Muted, fontSize = 14.sp)
                Field(label, key, { key = it.trim() }, modifier = Modifier.focusRequester(firstField))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Save", true, onClick = { save(key); onDone() })
                    Chip("Cancel", false, onClick = onDone)
                }
            }
        }
    }
}
