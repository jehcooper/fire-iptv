package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.isEventChannel
import app.sideline.tv.data.EpgItem
import app.sideline.tv.player.Play
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.LazyRow

private const val FAV = "__fav"
private const val RECENT = "__recent"
private const val SEARCH = "__search"


@Composable
fun CategoryItem(
    name: String, selected: Boolean, onMenu: (() -> Unit)? = null, selectOnFocus: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    // Ordinary categories open as you move over them; special entries (guide, search) need an OK press.
    // Name on the left, count on the right; the open category is shown in the accent colour.
    val m = remember(name) { Regex("^(.*?)\\s*\\((\\d+)\\)\\s*$").find(name) }
    val label = m?.groupValues?.get(1) ?: name
    val count = m?.groupValues?.get(2)
    FocusCard(onClick = onClick, onFocus = if (selectOnFocus) onClick else null, onMenu = onMenu, modifier = modifier.fillMaxWidth(), scaleOnFocus = 1.0f, shape = RoundedCornerShape(10.dp)) { f ->
        Box(Modifier.matchParentSize().background(when { f -> C.Surface2; selected -> C.Accent.copy(alpha = 0.14f); else -> C.Bg }))
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = when { f -> C.Text; selected -> C.Accent; else -> C.Muted }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
            if (count != null) Text(count, color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun LiveScreen() {
    val ctx = LocalContext.current
    val repo = SidelineApp.repo
    val prefs = SidelineApp.prefs
    val allCats by repo.liveCategories.collectAsState()
    val hidden by prefs.hiddenCats.collectAsState()
    val cats = remember(allCats, hidden) { allCats.filter { "live:${it.accountId}:${it.id}" !in hidden } }
    val channels by repo.channels.collectAsState()
    val favs by prefs.favChannels.collectAsState()
    val recentRaw by prefs.recentChannels.collectAsState()
    // Game/event channels ("NHL 07: Oilers at Lightning") are left out: they're useless once the game ends.
    val recent = remember(recentRaw, channels) { recentRaw.filter { k -> repo.channel(k)?.let { !isEventChannel(it.baseName) } ?: false } }
    val accounts by prefs.accounts.collectAsState()
    val multiAccount = remember(cats) { cats.map { it.accountId }.distinct().size > 1 }

    var selected by rememberSaveable { mutableStateOf(if (favs.isNotEmpty()) FAV else "") }
    var query by rememberSaveable { mutableStateOf("") }
    var guide by rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val searchBar = remember { FocusRequester() }
    var focusedCh by remember { mutableStateOf<Channel?>(null) }
    val counts = remember(channels) { channels.groupingBy { "${it.accountId}:${it.categoryId}" }.eachCount() }
    var epg by remember { mutableStateOf<List<EpgItem>>(emptyList()) }

    if (channels.isEmpty()) {
        EmptyState("No live channels yet", "Add an account with \"Live channels\" ticked in Settings, or wait for loading to finish.")
        return
    }
    if (selected.isEmpty()) selected = cats.firstOrNull()?.let { "${it.accountId}:${it.id}" } ?: FAV

    // Channel list for the selection, worked out off the main thread (search waits for you to stop typing).
    val favOrder by prefs.favOrder.collectAsState()
    var searchProgrammes by rememberSaveable { mutableStateOf(false) }
    val guideVersion by app.sideline.tv.data.FullGuide.version.collectAsState()
    // Programme search over the full TV guide: (channel, programme) pairs.
    val progResults by produceState(initialValue = emptyList<Pair<Channel, EpgItem>>(), query, guideVersion, channels) {
        value = withContext(Dispatchers.Default) {
            app.sideline.tv.data.FullGuide.search(query).mapNotNull { (epg, item) ->
                repo.channelsForEpg(epg).firstOrNull()?.let { it to item }
            }
        }
    }
    val list by produceState(initialValue = emptyList<Channel>(), selected, query, channels, favs, recent, hidden, favOrder) {
        if (selected == SEARCH) delay(250)
        value = withContext(Dispatchers.Default) {
            when (selected) {
                FAV -> prefs.orderedFavs().mapNotNull { repo.channel(it) }
                RECENT -> recent.mapNotNull { repo.channel(it) }
                SEARCH -> if (query.length < 2) emptyList() else channels.asSequence()
                    .filter { "live:${it.accountId}:${it.categoryId}" !in hidden && it.name.contains(query, ignoreCase = true) }
                    .take(300).toList()
                else -> channels.filter { "${it.accountId}:${it.categoryId}" == selected }
            }
        }
    }

    // Load now/next for whichever channel has focus, after a short pause so scrolling stays smooth.
    LaunchedEffect(focusedCh?.key) {
        epg = emptyList()
        val c = focusedCh ?: return@LaunchedEffect
        delay(350)
        epg = repo.epgCached(c)
    }

    fun catLabel(c: app.sideline.tv.data.Category): String {
        val prefix = if (multiAccount) (accounts.firstOrNull { it.id == c.accountId }?.name?.take(10)?.plus(" · ") ?: "") else ""
        return "$prefix${c.name}  (${counts["${c.accountId}:${c.id}"] ?: 0})"
    }

    if (guide) {
        // Full-width TV guide; categories become a row of chips across the top.
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 4.dp)) {
            LazyRow(Modifier.rememberRowPosition(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                item { Chip("☰ List view", false, onClick = { guide = false }) }
                item { Chip("★ Favourites", selected == FAV, onClick = { selected = FAV }) }
                items(cats, key = { "${it.accountId}:${it.id}" }) { c ->
                    Chip(catLabel(c), selected == "${c.accountId}:${c.id}", onClick = { selected = "${c.accountId}:${c.id}" })
                }
            }
            if (list.isEmpty()) Text("No channels in this category.", color = C.Muted, modifier = Modifier.padding(16.dp))
            else ProgramGuide(list)
        }
        return
    }

    if (searching) SearchDialog(query, "Search channels and programmes", onSearch = { query = it; selected = SEARCH; searching = false }, onDismiss = { searching = false })

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 4.dp)) {
        LazyColumn(Modifier.width(270.dp), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 60.dp)) {
            item { CategoryItem("TV guide view", false, selectOnFocus = false) { guide = true } }
            item {
                // OK always opens the search window (with the last search filled in);
                // ▶ goes straight to the search bar on the right.
                CategoryItem("Search", selected == SEARCH, selectOnFocus = false,
                    modifier = Modifier.focusProperties { if (selected == SEARCH) right = searchBar }) {
                    selected = SEARCH
                    searching = true
                }
            }
            item { CategoryItem("Favourites (${favs.size})", selected == FAV) { selected = FAV } }
            item { CategoryItem("Recent (${recent.size})", selected == RECENT) { selected = RECENT } }
            items(cats, key = { "${it.accountId}:${it.id}" }) { c ->
                CategoryItem(catLabel(c), selected == "${c.accountId}:${c.id}", onMenu = {
                    Confirm.ask("Hide \"${c.name}\"?", "You can show it again in Settings → Show or hide categories.", "Hide", destructive = true) {
                        prefs.toggleHidden("live:${c.accountId}:${c.id}")
                    }
                }) {
                    selected = "${c.accountId}:${c.id}"
                }
            }
            item {
                Text("Menu (≡) on a category hides it. Bring hidden ones back in Settings.", color = C.Faint, fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp))
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            // Preview: muted live picture of the focused channel, with now and next beside it.
            Row(Modifier.fillMaxWidth().height(126.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface)) {
                LivePreview(focusedCh, Modifier.width(224.dp).fillMaxHeight())
                Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val c = focusedCh
                    if (c == null) {
                        Text("Move through the channels to preview them. OK plays; Menu (≡): favourite, rename, reorder or hide.",
                            color = C.Muted, fontSize = 13.sp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                            if (c.hasArchive) Pill("CATCH-UP", C.Gold)
                        }
                        val nowS = System.currentTimeMillis() / 1000
                        val now = epg.firstOrNull { nowS in it.start until it.end } ?: epg.firstOrNull { it.end > nowS }
                        val next = now?.let { epg.getOrNull(epg.indexOf(it) + 1) }
                        if (now != null) {
                            Text("Now  ${timeOf(now.start * 1000)}  ${now.title}", color = C.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                            val frac = ((nowS - now.start).toFloat() / (now.end - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(2.dp).background(C.Surface2)) {
                                Box(Modifier.fillMaxWidth(frac).height(2.dp).background(C.Accent))
                            }
                            if (now.description.isNotBlank()) Text(now.description, color = C.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (next != null) Text("Next ${timeOf(next.start * 1000)}  ${next.title}", color = C.Muted, fontSize = 12.sp, maxLines = 1)
                        if (now == null) Text("No guide info for this channel", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        if (app.sideline.tv.data.FullGuide.mismatch(c)) Text("⚠ This guide may belong to another channel (${app.sideline.tv.data.FullGuide.displayName(c.epgId) ?: c.epgId}). ≡ → Choose guide",
                            color = C.Gold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (selected == SEARCH) {
                SearchButton(query, "Search channels and TV programmes", onClick = { searching = true }, modifier = Modifier.focusRequester(searchBar))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Chip("Channels (${list.size})", !searchProgrammes, onClick = { searchProgrammes = false })
                    Chip(if (app.sideline.tv.data.FullGuide.loaded) "Programmes (${progResults.size})" else "Programmes (needs full guide)",
                        searchProgrammes, onClick = { searchProgrammes = true })
                }
                Spacer(Modifier.height(10.dp))
            }
            if (selected == SEARCH && searchProgrammes) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 60.dp)) {
                    if (progResults.isEmpty()) item {
                        Text(if (app.sideline.tv.data.FullGuide.loaded) "No programmes match." else
                            "Programme search uses the full TV guide (Settings → TV guide).", color = C.Muted, modifier = Modifier.padding(16.dp))
                    }
                    items(progResults, key = { "${it.first.key}@${it.second.start}" }) { (c, item) ->
                        ProgrammeRow(c, item, onClick = { pickProgramme(ctx, c, item, listOf(c)) })
                    }
                }
                return@Column
            }
            if (list.isEmpty()) {
                Text(
                    when (selected) { FAV -> "No favourites yet. Press Menu (≡) on any channel to add it."; SEARCH -> "Select the search bar above and type at least 2 letters."; else -> "Nothing here." },
                    color = C.Muted, modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 60.dp)) {
                items(list, key = { it.key }) { c ->
                    FocusCard(
                        onClick = { Play.channel(ctx, c, list) },
                        onFocus = { focusedCh = c },
                        onMenu = { ChannelMenu.open(c, inFavourites = selected == FAV) },
                        modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f, shape = RoundedCornerShape(10.dp),
                    ) { _ ->
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 52.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (c.key in favs) Text("★", color = C.Gold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A programme search result: title, channel, time, and what OK will do. */
@Composable
fun ProgrammeRow(c: Channel, item: EpgItem, onClick: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val (tag, color) = when {
        now in item.start until item.end -> "ON NOW" to C.Live
        item.end <= now && c.hasArchive -> "CATCH-UP" to C.Gold
        item.end <= now -> "ENDED" to C.Faint
        SidelineApp.reminders.has(c.key, item.start) -> "🔔 SET" to C.Accent
        else -> "UPCOMING" to C.Surface2
    }
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f, shape = RoundedCornerShape(10.dp)) { _ ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${c.name} · ${dayTimeOf(item.start * 1000)} – ${timeOf(item.end * 1000)}", color = C.Muted, fontSize = 13.sp, maxLines = 1)
            }
            Pill(tag, color, if (color == C.Surface2 || color == C.Faint) C.Text else C.Bg)
        }
    }
}
