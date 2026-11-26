package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import kotlinx.coroutines.delay

/**
 * Menu (≡) on a channel, like TiviMate's long-press: favourite, move within favourites, rename, hide.
 * Changes that add, remove or hide always ask first.
 */
object ChannelMenu {
    var target by mutableStateOf<Channel?>(null)
    var inFavourites by mutableStateOf(false)
    fun open(c: Channel, inFavourites: Boolean = false) { this.inFavourites = inFavourites; target = c }
}

@Composable
fun ChannelMenuHost() {
    val c = ChannelMenu.target ?: return
    val prefs = SidelineApp.prefs
    val favs by prefs.favChannels.collectAsState()
    val names by prefs.channelNames.collectAsState()
    var renaming by remember(c.key) { mutableStateOf(false) }
    var choosingGuide by remember(c.key) { mutableStateOf(false) }
    val overrides by prefs.guideOverrides.collectAsState()
    val first = remember(c.key) { FocusRequester() }
    val isFav = c.key in favs
    val renamed = names.containsKey(c.key)
    fun close() { ChannelMenu.target = null }
    LaunchedEffect(c.key) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }

    if (renaming) {
        SearchDialog(
            initial = c.name, title = "Rename ${c.baseName}",
            placeholder = "New name", actionLabel = "Save",
            onSearch = { n -> renaming = false; close(); if (n.isNotBlank() && n != c.name) prefs.renameChannel(c.key, n) },
            onDismiss = { renaming = false },
        )
        return
    }
    if (choosingGuide) {
        GuideChooser(c, onDone = { choosingGuide = false; close() }, onDismiss = { choosingGuide = false })
        return
    }
    val guideName = when {
        c.epgId == app.sideline.tv.data.NO_GUIDE -> "None"
        c.epgId.isNullOrBlank() -> "None from provider"
        else -> app.sideline.tv.data.FullGuide.displayName(c.epgId) ?: c.epgId!!
    }
    val wrongGuide = app.sideline.tv.data.FullGuide.mismatch(c)
    TvDialog({ close() }) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(460.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (renamed) Text("Provider's name: ${c.baseName}", color = C.Faint, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Chip(if (isFav) "★ Remove from favourites" else "☆ Add to favourites", false, modifier = Modifier.focusRequester(first), onClick = {
                    close()
                    Confirm.ask(if (isFav) "Remove ${c.name} from favourites?" else "Add ${c.name} to favourites?",
                        yes = if (isFav) "Remove" else "Add", destructive = isFav) { prefs.toggleFavChannel(c.key) }
                })
                if (isFav && ChannelMenu.inFavourites) {
                    Chip("⤒ Move to top of favourites", false, onClick = { prefs.moveFavourite(c.key, Int.MIN_VALUE); close() })
                    Chip("▲ Move up", false, onClick = { prefs.moveFavourite(c.key, -1) })
                    Chip("▼ Move down", false, onClick = { prefs.moveFavourite(c.key, 1) })
                }
                Chip("📺 Choose guide… (now: $guideName" + (if (overrides.containsKey(c.key)) ", your choice)" else ")"), false, onClick = { choosingGuide = true })
                if (wrongGuide) Text("⚠ The provider's guide for this channel looks like it belongs to another channel.", color = C.Gold, fontSize = 12.sp)
                Chip("✎ Rename", false, onClick = { renaming = true })
                if (renamed) Chip("↺ Use provider's name", false, onClick = { prefs.renameChannel(c.key, null); close() })
                Chip("🚫 Hide this channel", false, onClick = {
                    close()
                    Confirm.ask("Hide ${c.name}?", "It disappears from every list. Show it again in Settings → Hidden channels.",
                        yes = "Hide", destructive = true) { prefs.toggleHiddenChannel(c.key) }
                })
                Chip("Close", false, onClick = { close() })
            }
        }
    }
}

/**
 * Pick which guide (TV listings) a channel uses: guide channels with similar names first, each with what's on now,
 * plus "No guide" and "Provider's choice". Needs the full guide (Settings → TV guide).
 */
@Composable
private fun GuideChooser(c: Channel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val prefs = SidelineApp.prefs
    val G = app.sideline.tv.data.FullGuide
    val first = remember { FocusRequester() }
    val cands = remember(c.key) { G.candidates(c, 30) }
    LaunchedEffect(Unit) { delay(200); try { first.requestFocus() } catch (_: Exception) {} }
    fun pick(id: String?) {
        prefs.setGuideOverride(c.key, id)
        G.reload()
        onDone()
    }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(640.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Guide for ${c.name}", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Pick the listing that matches what the channel shows. \"Now:\" helps you tell them apart.",
                    color = C.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(380.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                    item { Chip("↺ Provider's choice" + (if (!prefs.guideOverrides.value.containsKey(c.key)) "  ✓" else ""), false,
                        modifier = Modifier.focusRequester(first), onClick = { pick(null) }) }
                    item { Chip("🚫 No guide for this channel", false, onClick = { pick("-") }) }
                    if (!G.hasNames) item {
                        Text("Turn on the full guide (Settings → TV guide) to see guide channels to choose from.", color = C.Gold, fontSize = 13.sp)
                    } else if (cands.isEmpty()) item {
                        Text("No guide channels with a similar name.", color = C.Faint, fontSize = 13.sp)
                    }
                    items(cands.size) { i ->
                        val k = cands[i]
                        val mark = if (c.epgId?.lowercase() == k.id) "  ✓" else ""
                        Chip(k.name + mark + (k.now?.let { "   ·   Now: $it" } ?: ""), false, onClick = { pick(k.id) })
                    }
                }
                Chip("Cancel", false, onClick = onDismiss)
            }
        }
    }
}
