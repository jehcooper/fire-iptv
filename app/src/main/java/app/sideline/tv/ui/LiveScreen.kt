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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.EpgItem
import app.sideline.tv.player.Play
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val FAV = "__fav"
private const val RECENT = "__recent"
private const val SEARCH = "__search"

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = C.Faint) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Surface2,
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun CategoryItem(name: String, selected: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick, onFocus = onClick, modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f, shape = RoundedCornerShape(10.dp)) { f ->
        Text(
            name, color = if (selected || f) C.Text else C.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.background(if (selected && !f) C.Surface2 else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
        )
    }
}

@Composable
fun LiveScreen() {
    val ctx = LocalContext.current
    val repo = SidelineApp.repo
    val prefs = SidelineApp.prefs
    val cats by repo.liveCategories.collectAsState()
    val channels by repo.channels.collectAsState()
    val favs by prefs.favChannels.collectAsState()
    val recent by prefs.recentChannels.collectAsState()
    val multiAccount = cats.map { it.accountId }.distinct().size > 1
    val accounts by prefs.accounts.collectAsState()

    var selected by rememberSaveable { mutableStateOf(if (favs.isNotEmpty()) FAV else "") }
    var query by rememberSaveable { mutableStateOf("") }
    var focusedCh by remember { mutableStateOf<Channel?>(null) }
    var epg by remember { mutableStateOf<List<EpgItem>>(emptyList()) }

    if (channels.isEmpty()) {
        EmptyState("No live channels yet", "Add an account with \"Live channels\" ticked in Settings, or wait for loading to finish.")
        return
    }
    if (selected.isEmpty()) selected = cats.firstOrNull()?.let { "${it.accountId}:${it.id}" } ?: FAV

    val list: List<Channel> = when (selected) {
        FAV -> channels.filter { it.key in favs }
        RECENT -> recent.mapNotNull { k -> channels.firstOrNull { it.key == k } }
        SEARCH -> if (query.length < 2) emptyList() else channels.filter { it.name.contains(query, ignoreCase = true) }.take(300)
        else -> channels.filter { "${it.accountId}:${it.categoryId}" == selected }
    }

    // Load now/next for whichever channel has focus, after a short pause so scrolling stays smooth.
    LaunchedEffect(focusedCh?.key) {
        epg = emptyList()
        val c = focusedCh ?: return@LaunchedEffect
        delay(350)
        epg = repo.epg(c, 3)
    }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 8.dp)) {
        LazyColumn(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            item { CategoryItem("🔍 Search", selected == SEARCH) { selected = SEARCH } }
            item { CategoryItem("★ Favourites (${favs.size})", selected == FAV) { selected = FAV } }
            item { CategoryItem("⟲ Recent", selected == RECENT) { selected = RECENT } }
            items(cats, key = { "${it.accountId}:${it.id}" }) { c ->
                val prefix = if (multiAccount) (accounts.firstOrNull { it.id == c.accountId }?.name?.take(10)?.plus(" · ") ?: "") else ""
                CategoryItem(prefix + c.name, selected == "${c.accountId}:${c.id}") { selected = "${c.accountId}:${c.id}" }
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            // Preview header: what's on the focused channel now and next.
            Column(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(18.dp)) {
                val c = focusedCh
                if (c == null) {
                    Text("Select a channel to watch. Menu (≡) adds or removes a favourite.", color = C.Muted, fontSize = 15.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                        if (c.hasArchive) Pill("CATCH-UP", C.Gold)
                    }
                    val now = epg.firstOrNull { it.isNow } ?: epg.firstOrNull()
                    val next = now?.let { epg.getOrNull(epg.indexOf(it) + 1) }
                    if (now != null) Text("Now  ${timeOf(now.start * 1000)}  ${now.title}", color = C.Text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                    if (next != null) Text("Next ${timeOf(next.start * 1000)}  ${next.title}", color = C.Muted, fontSize = 14.sp, maxLines = 1)
                    if (now == null) Text("No guide info for this channel", color = C.Faint, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            if (selected == SEARCH) {
                SearchBox(query, { query = it }, "Type a channel name")
                Spacer(Modifier.height(10.dp))
            }
            if (list.isEmpty()) {
                Text(
                    when (selected) { FAV -> "No favourites yet. Press Menu (≡) on any channel to add it."; SEARCH -> "Type at least 2 letters."; else -> "Nothing here." },
                    color = C.Muted, modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 60.dp, top = 4.dp, start = 8.dp, end = 8.dp)) {
                items(list, key = { it.key }) { c ->
                    FocusCard(
                        onClick = { Play.channel(ctx, c, list) },
                        onFocus = { focusedCh = c },
                        onMenu = { prefs.toggleFavChannel(c.key) },
                        modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f, shape = RoundedCornerShape(10.dp),
                    ) { _ ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 72.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(c.name, color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (c.key in favs) Text("★", color = C.Gold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}
