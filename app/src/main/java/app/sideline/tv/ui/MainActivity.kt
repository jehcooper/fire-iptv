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

enum class Tab(val label: String) { Home("Home"), Sports("Sports"), Live("Live TV"), Movies("Movies"), Series("Series"), Settings("Settings") }

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
                    modifier = Modifier.height(36.dp),
                )
                Spacer(Modifier.width(36.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tab.entries.forEach { t -> Chip(t.label, selected = t == tab, onClick = { tab = t }) }
                }
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Clock()
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.Home -> HomeScreen(goTo = { tab = it })
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
        ConfirmHost()
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
