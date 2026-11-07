package app.sideline.tv.player

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.EpgItem
import app.sideline.tv.data.MovieInfo
import app.sideline.tv.data.MovieInfoService
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import app.sideline.tv.data.Net
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.sideline.tv.sports.AlertKind
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.GameMatcher
import app.sideline.tv.sports.ScoreAlert
import app.sideline.tv.ui.Chip
import app.sideline.tv.ui.TvDialog
import app.sideline.tv.ui.C
import app.sideline.tv.ui.Pill
import app.sideline.tv.ui.ScorePopupHost
import app.sideline.tv.ui.SidelineTheme
import app.sideline.tv.ui.timeOf
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live keys: ▲▼ change channel · ◀ previous channel · OK shows info · ⏪ watch from start (catch-up) · ≡ games & pinned scores.
 * Movies/episodes: OK or Play pauses · ◀▶ skip 10s/30s · rewind/fast-forward keys work too.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val repo get() = SidelineApp.repo
    private val prefs get() = SidelineApp.prefs

    private var queue: List<Channel> = emptyList()
    private var current by mutableStateOf<Channel?>(null)
    private var previous: Channel? = null
    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var showInfo by mutableStateOf(true)
    private var toast by mutableStateOf<String?>(null)
    private var epg by mutableStateOf<List<EpgItem>>(emptyList())
    private var live by mutableStateOf(true)
    private var catchUp by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var vodKey: String? = null
    private var paused by mutableStateOf(false)
    private var gamesPanel by mutableStateOf(false)
    /** "Game starting" prompt: OK = watch it, Menu = pin the score. */
    private var prompt by mutableStateOf<ScoreAlert?>(null)
    private var promptJob: Job? = null
    private var details by mutableStateOf<MovieInfo?>(null)
    private val tried = mutableSetOf<String>()
    private var infoJob: Job? = null
    private var toastJob: Job? = null
    private var stallJob: Job? = null
    private var epgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = Play.pending
        if (item == null) { finish(); return }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.http)))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleFailure("error ${error.errorCodeName}")
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                stallJob?.cancel()
                if (state == Player.STATE_BUFFERING && live && !catchUp) {
                    // A live stream stuck buffering this long is treated as dead → try a backup copy.
                    stallJob = lifecycleScope.launch { delay(15_000); handleFailure("stalled") }
                }
                if (state == Player.STATE_ENDED && live && !catchUp) handleFailure("ended")
            }
        })

        when (item) {
            is Play.Item.Live -> { queue = item.queue; tune(item.channel) }
            is Play.Item.Vod -> {
                live = false
                vodKey = item.key
                title = item.title
                subtitle = item.subtitle
                start(item.url)
                val resume = if (item.fromStart) 0L else getSharedPreferences("resume", MODE_PRIVATE).getLong(item.key, 0L)
                if (resume > 60_000) { player.seekTo(resume); flash("Resuming where you left off") }
                // Details for the pause screen.
                val mv = item.movie
                if (mv != null) lifecycleScope.launch { details = MovieInfoService.get(mv) }
                else if (!item.plot.isNullOrBlank()) details = MovieInfo(title = item.subtitle, plot = item.plot, poster = item.image)
                showInfoBriefly()
            }
            is Play.Item.CatchUp -> { current = item.channel; catchUp = true; title = item.title; start(item.url) }
        }

        // Games starting: offer to watch or pin, instead of a passive pop-up.
        lifecycleScope.launch {
            SidelineApp.scores.alerts.collect { a ->
                if (a.kind == AlertKind.STARTED || a.kind == AlertKind.STARTING_SOON) {
                    prompt = a
                    promptJob?.cancel()
                    promptJob = lifecycleScope.launch { delay(15_000); prompt = null }
                }
            }
        }

        setContent {
            SidelineTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                keepScreenOn = true
                                player = this@PlayerActivity.player
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    PositionTicker()
                    InfoOverlay()
                    PauseOverlay()
                    ToastOverlay()
                    PinnedTicker()
                    StartPrompt()
                    ScorePopupHost(skipStarts = true)
                    if (gamesPanel) GamesPanel()
                }
            }
        }
    }

    // ---------- playback ----------

    private fun start(url: String) {
        val b = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(b.build())
        player.prepare()
        player.playWhenReady = true
    }

    private fun tune(c: Channel, failover: Boolean = false) {
        val client = repo.client(c.accountId) ?: return
        if (!failover) {
            tried.clear()
            if (current != null && current?.key != c.key) previous = current
        }
        catchUp = false
        live = true
        current = c
        title = c.name
        subtitle = repo.categoryName(c)
        tried += c.key
        prefs.pushRecent(c.key)
        start(client.liveUrl(c))
        showInfoBriefly()
        epgJob?.cancel()
        epg = emptyList()
        epgJob = lifecycleScope.launch { epg = repo.epg(c, 3) }
    }

    private fun handleFailure(why: String) {
        stallJob?.cancel()
        val c = current
        if (live && !catchUp && c != null) {
            val backup = repo.backupsFor(c).firstOrNull { it.key !in tried }
            if (backup != null) {
                flash("Stream dropped. Switching to backup: ${backup.name}")
                tune(backup, failover = true)
                return
            }
            flash("This channel isn't working right now ($why). Try another.")
        } else {
            flash("Couldn't play this ($why).")
        }
    }

    private fun zap(dir: Int) {
        val c = current ?: return
        if (queue.size < 2) return
        val i = queue.indexOfFirst { it.key == c.key }.coerceAtLeast(0)
        tune(queue[(i + dir + queue.size) % queue.size])
    }

    private fun watchFromStart() {
        val c = current ?: return
        if (!c.hasArchive) { flash("This channel doesn't offer catch-up"); return }
        val now = epg.firstOrNull { it.isNow }
        if (now == null) { flash("No guide info, so can't find the start"); return }
        val minutes = ((now.end - now.start) / 60).toInt().coerceAtLeast(30)
        val url = repo.client(c.accountId)?.timeshiftUrl(c, now.startRaw, minutes)
        if (url == null) { flash("Catch-up isn't available for this programme"); return }
        catchUp = true
        title = "From the start: ${now.title}"
        start(url)
        flash("Watching from the start. Press Back to return to live.")
    }

    /** Jump to the channel showing a game: best match from "Take me to the game". */
    private fun watchGame(g: Game) {
        lifecycleScope.launch {
            val matches = GameMatcher.cached(g.id) ?: GameMatcher.quick(g, repo)
            val top = matches.firstOrNull { it.strong } ?: matches.firstOrNull()
            if (top == null) {
                flash("Couldn't find a channel for ${g.title} yet")
                GameMatcher.prefetch(listOf(g), repo)
                return@launch
            }
            if (!live) saveResume()
            vodKey = null
            details = null
            queue = matches.map { it.channel }
            gamesPanel = false
            tune(top.channel)
            flash("Switched to ${top.channel.name}")
        }
    }

    // ---------- remote ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val liveMode = live && !catchUp
        prompt?.let { p ->
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { prompt = null; watchGame(p.game); return true }
                KeyEvent.KEYCODE_MENU -> {
                    prompt = null
                    if (p.game.id !in prefs.pinnedGames.value) prefs.togglePin(p.game.id)
                    flash("Pinned ${p.game.title}")
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { prompt = null; return true }
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (liveMode) { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (liveMode) { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (liveMode) {
                    val p = previous
                    if (p != null) tune(p) else flash("No previous channel yet")
                } else seekBy(-10_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (liveMode) showInfoBriefly() else seekBy(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (liveMode) watchFromStart() else seekBy(-10_000); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!liveMode) seekBy(30_000); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (liveMode) { showInfo = !showInfo; if (showInfo) showInfoBriefly() } else togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MENU -> { gamesPanel = true; return true }
            KeyEvent.KEYCODE_BACK -> {
                if (catchUp && current != null && Play.pending is Play.Item.Live) { tune(current!!); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePause() {
        player.playWhenReady = !player.playWhenReady
        showInfoBriefly()
    }

    private fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        showInfoBriefly()
    }

    private fun showInfoBriefly() {
        showInfo = true
        infoJob?.cancel()
        infoJob = lifecycleScope.launch { delay(5_000); if (player.playWhenReady) showInfo = false }
    }

    private fun flash(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = lifecycleScope.launch { delay(4_000); toast = null }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        saveResume()
        player.pause()
    }

    private fun saveResume() {
        vodKey?.let { k ->
            val pos = player.currentPosition
            val dur = player.duration
            val nearEnd = dur > 0 && pos > dur - 5 * 60_000
            getSharedPreferences("resume", MODE_PRIVATE).edit().putLong(k, if (nearEnd) 0L else pos).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) player.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    // ---------- overlays ----------

    @Composable
    private fun PositionTicker() {
        LaunchedEffect(Unit) {
            while (true) {
                position = player.currentPosition
                duration = player.duration.coerceAtLeast(0)
                delay(500)
            }
        }
    }

    @Composable
    private fun InfoOverlay() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(visible = showInfo && !(paused && !live), enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
                        .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = if (prefs.pinnedGames.value.isEmpty()) 36.dp else 96.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        current?.logo?.let { if (live) AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(width = 96.dp, height = 54.dp)) }
                        if (live && current?.logo != null) Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (live && !catchUp) { Pill("LIVE", C.Live, Color.White); Spacer(Modifier.width(10.dp)) }
                                if (catchUp) { Pill("CATCH-UP", C.Gold); Spacer(Modifier.width(10.dp)) }
                                Text(title, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            if (subtitle.isNotBlank()) Text(subtitle, color = C.Muted, fontSize = 15.sp)
                        }
                        Text(timeOf(System.currentTimeMillis()), color = C.Muted, fontSize = 18.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (live && !catchUp) {
                        val now = epg.firstOrNull { it.isNow }
                        val next = now?.let { epg.getOrNull(epg.indexOf(it) + 1) }
                        if (now != null) {
                            Text("${timeOf(now.start * 1000)} – ${timeOf(now.end * 1000)}   ${now.title}", color = C.Text, fontSize = 17.sp, maxLines = 1)
                            val frac = ((System.currentTimeMillis() / 1000 - now.start).toFloat() / (now.end - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            ProgressBar(frac)
                        }
                        if (next != null) Text("Next  ${timeOf(next.start * 1000)}  ${next.title}", color = C.Muted, fontSize = 15.sp, maxLines = 1)
                        Text(
                            buildString {
                                append("▲▼ channels   ◀ previous channel")
                                if (current?.hasArchive == true) append("   ⏪ watch from start")
                                append("   ≡ games & scores")
                            },
                            color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                        )
                    } else {
                        if (duration > 0) {
                            ProgressBar(position.toFloat() / duration)
                            Text("${fmt(position)} / ${fmt(duration)}", color = C.Muted, fontSize = 15.sp)
                        }
                        Text(if (player.playWhenReady) "OK pause   ◀ −10s   ▶ +30s   ≡ games & scores" else "Paused · OK to resume",
                            color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }

    /** Pinned games along the bottom, always visible while watching. */
    @Composable
    private fun PinnedTicker() {
        val pinned by prefs.pinnedGames.collectAsState()
        val board by SidelineApp.scores.board.collectAsState()
        val spoilerFree by prefs.spoilerFree.collectAsState()
        val games = board.values.flatten().distinctBy { it.id }.filter { it.id in pinned }
        if (games.isEmpty()) return
        Box(Modifier.fillMaxSize().padding(bottom = 20.dp), contentAlignment = Alignment.BottomCenter) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                games.forEach { g ->
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE6131A22)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (g.isLive) { Box(Modifier.size(8.dp).clip(CircleShape).background(C.Live)); Spacer(Modifier.width(8.dp)) }
                        if (g.competitors.size == 2) {
                            g.competitors.sortedBy { it.home }.forEachIndexed { i, c ->
                                if (i == 1) Text("–", color = C.Faint, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp))
                                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(c.abbr.ifBlank { c.shortName.take(10) }, color = C.Text, fontSize = 14.sp)
                                if (g.state != "pre") {
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (spoilerFree) "•" else c.score, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Text(g.title, color = C.Text, fontSize = 14.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(if (g.state == "pre" && g.startMillis > 0) timeOf(g.startMillis) else g.detail, color = C.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    /** "Game starting" card: OK to switch to it, Menu to pin the score. */
    @Composable
    private fun StartPrompt() {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopEnd) {
            AnimatedVisibility(visible = prompt != null, enter = fadeIn(), exit = fadeOut()) {
                val p = prompt ?: return@AnimatedVisibility
                Column(
                    Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0131A22))
                        .border(2.dp, C.Accent, RoundedCornerShape(16.dp)).padding(18.dp),
                ) {
                    Text(p.headline, color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        p.game.competitors.sortedBy { it.home }.forEach { c ->
                            AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(p.game.title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text("OK  watch it now     ≡  pin the score     Back  dismiss", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }

    /** Menu (≡): live and upcoming games — pin scores, turn on pop-ups, or jump to the game's channel. */
    @Composable
    private fun GamesPanel() {
        val board by SidelineApp.scores.board.collectAsState()
        val pinned by prefs.pinnedGames.collectAsState()
        val followed by prefs.followedGames.collectAsState()
        val first = remember { FocusRequester() }
        val soon = System.currentTimeMillis() + 3 * 3600_000L
        val games = board.values.flatten().distinctBy { it.id }
            .filter { it.isLive || (it.state == "pre" && it.startMillis in 1..soon) || it.id in pinned }
            .sortedWith(compareBy({ it.id !in pinned }, { !SidelineApp.scores.isWatched(it) }, { !it.isLive }, { it.startMillis }))
        LaunchedEffect(Unit) { SidelineApp.scores.refreshNow(); delay(250); try { first.requestFocus() } catch (_: Exception) {} }

        TvDialog({ gamesPanel = false }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.fillMaxHeight().width(620.dp).background(Color(0xF20B0F14)).padding(28.dp)) {
                    Text("Games", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Live now and starting in the next 3 hours, in your leagues.", color = C.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    if (games.isEmpty()) Text("No live or upcoming games right now.", color = C.Muted)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        itemsIndexed(games, key = { _, g -> g.id }) { i, g ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (g.isLive) { Pill("LIVE", C.Live, Color.White); Spacer(Modifier.width(8.dp)) }
                                    Text(g.title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                                    if (g.state != "pre" && g.competitors.size == 2 && !prefs.spoilerFree.value)
                                        Text(g.competitors.sortedBy { it.home }.joinToString(" – ") { it.score }, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(if (g.state == "pre" && g.startMillis > 0) "Starts ${timeOf(g.startMillis)}" else g.detail, color = C.Muted, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Chip("▶ Watch", true, onClick = { watchGame(g) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                                    Chip(if (g.id in pinned) "📌 Unpin" else "📌 Pin", g.id in pinned, onClick = { prefs.togglePin(g.id) })
                                    Chip(if (g.id in followed) "🔔 On" else "🔕 Pop-ups", g.id in followed, onClick = { prefs.toggleFollow(g.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Movies & episodes: pausing shows what you're watching — description, cast, where you are. */
    @Composable
    private fun PauseOverlay() {
        AnimatedVisibility(visible = paused && !live, enter = fadeIn(), exit = fadeOut()) {
            val d = details
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20B0F14), Color(0xD90B0F14), Color(0x800B0F14))))) {
                Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 56.dp, bottom = 40.dp)) {
                    if (d?.poster != null) {
                        AsyncImage(model = d.poster, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 200.dp, height = 300.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(36.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("PAUSED", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(if (subtitle.isNotBlank() && d?.title == subtitle) title else (d?.title ?: title),
                            color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        if (subtitle.isNotBlank()) Text(subtitle, color = C.Muted, fontSize = 18.sp)
                        if (d != null && d.metaLine.isNotBlank()) Text(d.metaLine, color = C.Accent, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            when {
                                d == null -> "Looking up details…"
                                d.plot.isBlank() -> "No description found for this title."
                                else -> d.plot
                            },
                            color = C.Text, fontSize = 17.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp),
                        )
                        if (d != null && d.director.isNotBlank()) Text("Director: ${d.director}", color = C.Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                        if (d != null && d.cast.isNotBlank()) Text("Cast: ${d.cast}", color = C.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d != null && d.plotSource.isNotBlank()) Text(d.plotSource, color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.weight(1f))
                        if (duration > 0) {
                            ProgressBar(position.toFloat() / duration)
                            Text("${fmt(position)} / ${fmt(duration)}   ·   ${fmt((duration - position).coerceAtLeast(0))} left", color = C.Muted, fontSize = 15.sp)
                        }
                        Text("OK or Play to resume   ◀ −10s   ▶ +30s   ≡ games & scores   Back to exit", color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressBar(frac: Float) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Surface2)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).background(C.Accent))
        }
    }

    @Composable
    private fun ToastOverlay() {
        val msg = toast
        Box(Modifier.fillMaxSize().padding(top = 36.dp), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(visible = msg != null, enter = fadeIn(), exit = fadeOut()) {
                val shown = remember(msg) { msg ?: "" }
                Text(shown, color = C.Text, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE6131A22)).padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
