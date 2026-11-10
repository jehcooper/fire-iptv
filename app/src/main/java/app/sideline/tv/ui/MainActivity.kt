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
import app.sideline.tv.SidelineApp

enum class Tab(val label: String) { Home("Home"), Discover("Discover"), Sports("Sports"), Live("Live TV"), Movies("Movies"), Series("Series"), Settings("Settings") }

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
    val accounts by SidelineApp.prefs.accounts.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (accounts.isEmpty()) Tab.Settings else Tab.Home) }
    val loading by SidelineApp.repo.loading.collectAsState()

    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(app.sideline.tv.R.drawable.logo_wordmark),
                    contentDescription = "Cooper TV",
                    modifier = Modifier.height(30.dp),
                )
                Spacer(Modifier.width(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tab.entries.forEach { t -> Chip(t.label, selected = t == tab, onClick = { tab = t }, compact = true) }
                }
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Clock()
            }
            LoadingStrip()
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.Home -> HomeScreen(goTo = { tab = it })
                    Tab.Discover -> DiscoverScreen(goTo = { tab = it })
                    Tab.Sports -> SportsScreen(goTo = { tab = it })
                    Tab.Live -> LiveScreen()
                    Tab.Movies -> MoviesScreen()
                    Tab.Series -> SeriesScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
        ScorePopupHost()
        ReminderHost()
        ChannelMenuHost()
        LoadingDialog()
        ConfirmHost()
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
        Modifier.fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = 4.dp),
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
    Text(timeOf(now), color = C.Muted, fontSize = 18.sp)
}
