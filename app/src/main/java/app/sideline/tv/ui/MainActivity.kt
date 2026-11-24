package app.sideline.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsHockey
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.sideline.tv.SidelineApp

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Search("Search", Icons.Rounded.Search), Discover("Discover", Icons.Rounded.Explore),
    Sports("Sports", Icons.Rounded.SportsHockey), Live("Live TV", Icons.Rounded.LiveTv), Movies("Movies", Icons.Rounded.Movie),
    Series("Series", Icons.Rounded.Tv), Settings("Settings", Icons.Rounded.Settings),
}

/** Full-screen picture behind everything (Home's "For you" banner sets it). */
object Backdrop { val url = mutableStateOf<String?>(null) }

/** This app's build number (the one in "Cooper TV build N"). */
@Suppress("DEPRECATION")
fun buildNumber(ctx: android.content.Context): Int = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
} catch (e: Exception) { 0 }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SidelineTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        SidelineApp.scores.refreshNow()
        // Re-reads the full TV guide only if it's more than 12 hours old.
        if (SidelineApp.repo.channels.value.isNotEmpty()) app.sideline.tv.data.FullGuide.load()
    }
}

@Composable
private fun Root() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accounts by SidelineApp.prefs.accounts.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (accounts.isEmpty()) Tab.Settings else Tab.Home) }
    val loading by SidelineApp.repo.loading.collectAsState()
    val build = androidx.compose.runtime.remember { buildNumber(ctx) }
    val backdrop by Backdrop.url

    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        // Big background picture (Home banner), fading into the background colour.
        androidx.compose.animation.Crossfade(backdrop, label = "backdrop") { url ->
            if (url != null) Box(Modifier.fillMaxSize()) {
                coil.compose.AsyncImage(model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to C.Bg.copy(alpha = 0.3f), 0.66f to C.Bg.copy(alpha = 0.95f), 1f to C.Bg)))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.Bg.copy(alpha = 0.95f), 0.35f to C.Bg.copy(alpha = 0.6f), 0.7f to Color.Transparent)))
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 64.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { LoadingStrip() }
                if (loading) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Clock()
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.Home -> HomeScreen(goTo = { tab = it })
                    Tab.Search -> SearchScreen()
                    Tab.Discover -> DiscoverScreen(goTo = { tab = it })
                    Tab.Sports -> SportsScreen(goTo = { tab = it })
                    Tab.Live -> LiveScreen()
                    Tab.Movies -> MoviesScreen()
                    Tab.Series -> SeriesScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(start = 76.dp), contentAlignment = Alignment.BottomCenter) { HintBar(tab) }
        Sidebar(tab, onSelect = { tab = it }, build = build)
        ScorePopupHost()
        ReminderHost()
        ChannelMenuHost()
        PagesHost()
        LoadingDialog()
        ConfirmHost()
        UpdateBanner(build)
    }
}

/** Left-hand menu: a slim strip of icons that opens with names when you move onto it. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Sidebar(tab: Tab, onSelect: (Tab) -> Unit, build: Int) {
    var expanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    val width by androidx.compose.animation.core.animateDpAsState(if (expanded) 232.dp else 76.dp, label = "sidebar")
    val requesters = androidx.compose.runtime.remember { Tab.entries.associateWith { FocusRequester() } }
    Box(
        Modifier.fillMaxHeight().width(width)
            .background(C.Rail)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { enter = { requesters[tab] ?: FocusRequester.Default } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxHeight().padding(start = 12.dp, top = 18.dp, bottom = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.height(30.dp).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (expanded) androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(app.sideline.tv.R.drawable.logo_wordmark),
                    contentDescription = "Cooper TV", modifier = Modifier.height(22.dp),
                ) else Box(Modifier.size(28.dp).background(C.Accent, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Text("C", color = C.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Tab.entries.forEach { t -> SideItem(t, t == tab, expanded, requesters.getValue(t)) { onSelect(t) } }
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "Cooper TV · build $build" else "b$build", color = C.Faint, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SideItem(t: Tab, selected: Boolean, expanded: Boolean, r: FocusRequester, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick, shape = RoundedCornerShape(10.dp), scaleOnFocus = 1f,
        modifier = Modifier.focusRequester(r).height(44.dp).fillMaxWidth(),
    ) { f ->
        // Selected = accent outline and accent text on a faint accent wash; selected-and-highlighted = white text.
        Box(
            Modifier.matchParentSize()
                .background(when { f -> C.Surface2; selected -> C.Accent.copy(alpha = 0.14f); else -> C.Rail })
                .border(if (selected) 1.5.dp else 0.dp, if (selected) C.Accent else Color.Transparent, RoundedCornerShape(10.dp)),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (expanded) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            val tint = when { f -> C.Text; selected -> C.Accent; else -> C.Muted }
            androidx.compose.material3.Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(21.dp))
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(t.label, color = tint, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.weight(1f))
                if (selected) Box(Modifier.size(5.dp).background(C.Accent, androidx.compose.foundation.shape.CircleShape))
            }
        }
    }
}

/** Thin bar along the bottom with the remote keys for the current screen. */
@Composable
private fun HintBar(tab: Tab) {
    val hints = when (tab) {
        Tab.Home -> listOf("←↑↓→" to "Move", "OK" to "Play / open", "≡" to "Options", "Back" to "Home")
        Tab.Search -> listOf("OK" to "Type / open", "←↑↓→" to "Move", "Back" to "Home")
        Tab.Live -> listOf("←↑↓→" to "Move", "OK" to "Play", "≡" to "Favourite / rename / hide", "Back" to "Home")
        Tab.Movies, Tab.Series -> listOf("←↑↓→" to "Move", "OK" to "Details", "≡" to "Options", "Back" to "Home")
        Tab.Sports -> listOf("←↑↓→" to "Move", "OK" to "Game details", "≡" to "Pop-ups on/off", "Back" to "Home")
        Tab.Discover -> listOf("←↑↓→" to "Move", "OK" to "Open", "Back" to "Home")
        Tab.Settings -> listOf("←↑↓→" to "Move", "OK" to "Change", "Back" to "Home")
    }
    Row(
        Modifier.background(C.Bg.copy(alpha = 0.92f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k, color = C.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(C.Surface2, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(5.dp))
                Text(v, color = C.Muted, fontSize = 11.sp)
            }
        }
    }
}

/** "Updated to build N" for a few seconds after an update (and the first time this version opens). */
@Composable
private fun UpdateBanner(build: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var show by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(build) {
        val sp = ctx.getSharedPreferences("sideline", android.content.Context.MODE_PRIVATE)
        val last = sp.getInt("lastBuild", 0)
        if (build > 0 && build != last) {
            sp.edit().putInt("lastBuild", build).apply()
            show = if (last == 0) "Cooper TV build $build" else "Updated to build $build (was build $last)"
            kotlinx.coroutines.delay(8000)
            show = null
        }
    }
    val text = show ?: return
    Box(Modifier.fillMaxSize().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = C.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(C.Accent, RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp))
    }
}

/** Slim line under the tabs saying what's being updated in the background (lists stay usable). */
@Composable
private fun LoadingStrip() {
    val steps by SidelineApp.repo.steps.collectAsState()
    val guide by app.sideline.tv.data.FullGuide.status.collectAsState()
    val active = steps.firstOrNull { it.state == "working" }
    val doneCount = steps.count { it.done }
    val text = when {
        active != null -> "Updating · ${active.label} (${active.detail})  ·  $doneCount of ${steps.size} done"
        guide.startsWith("Downloading") || guide.startsWith("Reading") -> guide
        else -> null
    } ?: return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = C.Muted, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * Shown while there's nothing to browse yet (first start, or no saved copy): a checklist of what's
 * downloading. It closes by itself when done; Back hides it (e.g. to fix a login in Settings).
 */
@Composable
private fun LoadingDialog() {
    val loading by SidelineApp.repo.loading.collectAsState()
    val steps by SidelineApp.repo.steps.collectAsState()
    val channels by SidelineApp.repo.channels.collectAsState()
    val movies by SidelineApp.repo.movies.collectAsState()
    val series by SidelineApp.repo.series.collectAsState()
    var hidden by androidx.compose.runtime.remember { mutableStateOf(false) }
    val nothing = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    if (!loading || !nothing || hidden || steps.isEmpty()) return
    TvDialog({ hidden = true }) {
        Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(620.dp).background(C.Surface, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Getting Cooper TV ready", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text("Downloading your channels, movies and series. The first time can take a minute; after that the app opens instantly " +
                    "from a saved copy and updates in the background.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
                steps.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                        when (st.state) {
                            "done" -> Text("✓", color = C.Accent, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                            "failed" -> Text("✗", color = C.Live, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                            "working" -> Box(Modifier.width(28.dp)) { CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)) }
                            else -> Text("•", color = C.Faint, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                        }
                        Text(st.label, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(st.detail, color = if (st.state == "failed") C.Live else C.Muted, fontSize = 13.sp, maxLines = 1)
                    }
                }
                Text("Back hides this window.", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

/** Time in the top-right corner, as most TV interfaces show; updates once a minute. */
@Composable
private fun Clock() {
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000 - now % 60_000)
        }
    }
    Text(timeOf(now), color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
}
