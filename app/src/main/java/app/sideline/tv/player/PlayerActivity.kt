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
import androidx.media3.common.C as MC
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.sideline.tv.data.OpenSubtitles
import app.sideline.tv.ui.FocusCard
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.EpgItem
import app.sideline.tv.data.MovieInfo
import app.sideline.tv.data.ProgressEntry
import app.sideline.tv.data.MovieInfoService
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import app.sideline.tv.data.Net
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import app.sideline.tv.sports.Leagues
import app.sideline.tv.sports.Situation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.sideline.tv.sports.AlertKind
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.GameMatcher
import app.sideline.tv.sports.ScoreAlert
import app.sideline.tv.ui.Chip
import app.sideline.tv.ui.RatingBadge
import app.sideline.tv.ui.LiveDetailsFull
import app.sideline.tv.ui.LiveStrip
import app.sideline.tv.ui.hasLiveStrip
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
    private var vodItem: Play.Item.Vod? = null
    private var vodUrl: String? = null
    private var subsPanel by mutableStateOf(false)
    private var subsInitDone = false
    private var subsRecoveryTried = false
    private var externalSub: java.io.File? = null
    private var playerView: PlayerView? = null
    private var paused by mutableStateOf(false)
    private var gamesPanel by mutableStateOf(false)
    /** OK while watching live: channel list over the picture. */
    private var channelPanel by mutableStateOf(false)
    /** When live TV was paused (ms), or null if not paused. */
    private var pausedAt by mutableStateOf<Long?>(null)
    /** "Game starting" prompt: OK = watch it, Menu = pin the score. */
    private var prompt by mutableStateOf<ScoreAlert?>(null)
    private var promptJob: Job? = null
    /** TV-guide reminder that just came due: OK = switch to that channel. */
    private var reminder by mutableStateOf<app.sideline.tv.data.Reminder?>(null)
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

        // Subtitles: convert them while loading the file (Media3's default), and also let the text renderer
        // decode raw subtitle formats itself. Without the second part, some files/subtitle sources make the
        // player stop with ERROR_CODE_FAILED_RUNTIME_CHECK.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: android.content.Context, output: TextOutput, outputLooper: android.os.Looper,
                extensionRendererMode: Int, out: java.util.ArrayList<Renderer>,
            ) {
                out.add(TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
            }
        }
        // Big enough buffer to hold about 5 minutes of a paused live channel (capped at 160 MB of memory).
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 330_000, 2_500, 5_000)
            .setTargetBufferBytes(160 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                // DefaultDataSource: streams go through OkHttp, but local files (downloaded subtitles) can be read too.
                // Using OkHttp alone made downloaded subtitles fail with ERROR_CODE_FAILED_RUNTIME_CHECK.
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(Net.http)))
                    .experimentalParseSubtitlesDuringExtraction(true),
            )
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Movies/episodes: if subtitles are on, assume they caused it — turn them off and carry on.
                if (!live && !subsRecoveryTried && subtitlesActive()) {
                    subsRecoveryTried = true
                    recoverWithoutSubtitles()
                    return
                }
                val detail = (error.cause?.message ?: error.message ?: "").take(80)
                handleFailure(error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ') +
                    if (detail.isNotBlank()) ": $detail" else "")
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
                updateAwake()
            }
            override fun onTracksChanged(tracks: Tracks) {
                // First time a movie/episode's tracks are known: apply the subtitles-on/off preference.
                if (!live && !subsInitDone && !tracks.isEmpty) { subsInitDone = true; autoSubtitles() }
            }
            override fun onPlaybackStateChanged(state: Int) {
                updateAwake()
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
                vodItem = item
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

        lifecycleScope.launch {
            SidelineApp.reminders.due.collect { r ->
                reminder = r
                promptJob?.cancel()
                promptJob = lifecycleScope.launch { delay(60_000); reminder = null }
            }
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
                                playerView = this
                                // Readable TV subtitles: white text on a soft dark box, a little larger than default.
                                subtitleView?.setStyle(
                                    CaptionStyleCompat(android.graphics.Color.WHITE, 0x99000000.toInt(), android.graphics.Color.TRANSPARENT,
                                        CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.WHITE, null),
                                )
                                subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
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
                    ReminderPrompt()
                    ScorePopupHost(skipStarts = true)
                    if (gamesPanel) GamesPanel()
                    if (channelPanel) ChannelPanel()
                    app.sideline.tv.ui.ChannelMenuHost()
                    app.sideline.tv.ui.ConfirmHost()
                    if (subsPanel) SubtitlesPanel()
                }
            }
        }
    }

    // ---------- playback ----------

    private fun start(url: String, subtitle: java.io.File? = null, positionMs: Long = 0L) {
        val b = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (subtitle != null) {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(subtitle))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(prefs.subsLang.value)
                    .setLabel("OpenSubtitles")
                    .setSelectionFlags(MC.SELECTION_FLAG_DEFAULT)
                    .build(),
            ))
        }
        if (!live) vodUrl = url
        if (positionMs > 0) player.setMediaItem(b.build(), positionMs) else player.setMediaItem(b.build())
        player.prepare()
        player.playWhenReady = true
    }

    // ---------- subtitles ----------

    private data class TextTrack(val group: Tracks.Group, val label: String, val language: String?)

    private fun textTracks(): List<TextTrack> = player.currentTracks.groups
        .filter { it.type == MC.TRACK_TYPE_TEXT && it.length > 0 }
        .map { g ->
            val f = g.getTrackFormat(0)
            val lang = f.language
            val name = f.label ?: lang?.let { java.util.Locale(it).displayLanguage.ifBlank { it } } ?: "Unknown"
            TextTrack(g, name, lang)
        }

    private fun subtitlesActive() = !player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT) &&
        (externalSub != null || player.currentTracks.groups.any { it.type == MC.TRACK_TYPE_TEXT && it.isSelected } || prefs.subsOn.value)

    private fun recoverWithoutSubtitles() {
        val url = vodUrl ?: return
        val pos = player.currentPosition
        externalSub = null
        subtitlesOff()
        start(url, null, pos)
        flash("Subtitles caused a playback error on this file, so they're off. Press ▲ to try a different one.")
    }

    private fun subtitlesOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
    }

    private fun selectTrack(t: TextTrack) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, 0))
            .build()
    }

    private fun langMatches(trackLang: String?, wanted: String): Boolean {
        if (trackLang == null) return false
        val a = trackLang.lowercase().take(2)
        val b = wanted.lowercase().take(2)
        if (a == b) return true
        // Files often tag tracks with 3-letter codes ("eng", "fre"); compare those too.
        return try { java.util.Locale(wanted).isO3Language.equals(trackLang, ignoreCase = true) } catch (_: Exception) { false }
    }

    /** Subtitles on: prefer a built-in track in your language, else fetch one from OpenSubtitles. Off: hide them. */
    private fun autoSubtitles() {
        if (!prefs.subsOn.value) { subtitlesOff(); return }
        val lang = prefs.subsLang.value
        val tracks = textTracks()
        val match = tracks.firstOrNull { langMatches(it.language, lang) }
        when {
            match != null -> selectTrack(match)
            OpenSubtitles.configured && externalSub == null -> lifecycleScope.launch {
                try {
                    val results = searchSubs()
                    val best = results.firstOrNull { !it.hearingImpaired } ?: results.firstOrNull()
                    if (best == null) flash("No ${langName(lang)} subtitles found online") else attachOnline(best)
                } catch (e: Exception) { flash(e.message ?: "Couldn't get subtitles") }
            }
            tracks.isNotEmpty() -> { selectTrack(tracks.first()); flash("No ${langName(lang)} subtitles in this file. Showing ${tracks.first().label}") }
            else -> flash("No subtitles in this file. Add an OpenSubtitles key in Settings to fetch them online")
        }
    }

    private fun langName(code: String) = java.util.Locale(code).displayLanguage.ifBlank { code }

    private suspend fun searchSubs(): List<OpenSubtitles.Result> {
        val item = vodItem ?: return emptyList()
        val d = details
        val (clean, yearHint) = MovieInfoService.cleanTitle(item.title)
        val title = if (item.season != null) clean else (d?.title?.takeIf { it.isNotBlank() } ?: clean)
        val year = if (item.season != null) null else (d?.year?.takeIf { it.isNotBlank() } ?: yearHint)
        return OpenSubtitles.search(title, year, item.season, item.episode, prefs.subsLang.value)
    }

    private suspend fun attachOnline(r: OpenSubtitles.Result) {
        val url = vodUrl ?: return
        flash("Loading subtitles…")
        val file = OpenSubtitles.download(this, r.fileId)
        externalSub = file
        subsRecoveryTried = false
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(MC.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(prefs.subsLang.value)
            .build()
        start(url, file, pos)
        player.playWhenReady = wasPlaying
        flash("Subtitles on")
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
        reminder?.let { r ->
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    reminder = null
                    val c = repo.channel(r.channelKey)
                    if (c == null) flash("That channel isn't in your list any more") else {
                        if (!live) saveResume()
                        vodKey = null; details = null; vodItem = null
                        queue = listOf(c)
                        tune(c)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { reminder = null; return true }
            }
        }
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (liveMode) zap(-1) else if (!live) subsPanel = true
                return true
            }
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
                if (liveMode) channelPanel = true else togglePause()
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
        val liveMode = live && !catchUp
        if (!liveMode) {
            player.playWhenReady = !player.playWhenReady
            showInfoBriefly()
            return
        }
        // ---- Live TV ----
        if (player.playWhenReady) {
            player.playWhenReady = false
            pausedAt = System.currentTimeMillis()
            showInfo = true
            flash(if (current?.hasArchive == true) "Paused. Resume any time (this channel has catch-up)." else "Paused. Resume within about 5 minutes to carry on from here.")
            return
        }
        val c = current
        val since = pausedAt
        pausedAt = null
        val away = if (since == null) 0L else System.currentTimeMillis() - since
        when {
            // Catch-up channel paused for a while: continue from the exact moment via the provider's archive.
            c != null && since != null && c.hasArchive && away > 20_000 -> resumeFromArchive(c, since)
            away <= 5 * 60_000 -> { player.playWhenReady = true; showInfoBriefly() }
            c != null -> { tune(c); flash("Paused for over 5 minutes, so you're back to live.") }
        }
    }

    /** Plays a catch-up channel from [sinceMs] (when you paused), using the provider's archive. */
    private fun resumeFromArchive(c: Channel, sinceMs: Long) {
        val client = repo.client(c.accountId) ?: return
        // The archive wants the provider's local time; work out its offset from a guide entry.
        val sample = epg.firstOrNull { it.startRaw.length >= 16 && it.start > 0 }
        val offsetSec = sample?.let {
            try {
                val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                (f.parse(it.startRaw.substring(0, 16))!!.time / 1000) - it.start
            } catch (_: Exception) { null }
        } ?: (java.util.TimeZone.getDefault().getOffset(sinceMs) / 1000).toLong()
        val local = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(sinceMs + offsetSec * 1000))
        val url = client.timeshiftUrl(c, local, 240)
        if (url == null) { player.playWhenReady = true; return }
        catchUp = true
        val mins = ((System.currentTimeMillis() - sinceMs) / 60_000).coerceAtLeast(1)
        title = "${c.name} · $mins min behind live"
        start(url)
        flash("Carrying on from where you paused. Press Back to jump to live.")
    }

    private fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        showInfoBriefly()
    }

    private fun showInfoBriefly() {
        showInfo = true
        infoJob?.cancel()
        infoJob = lifecycleScope.launch { delay(5_000); if (player.playWhenReady && pausedAt == null) showInfo = false }
    }

    private fun flash(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = lifecycleScope.launch { delay(4_000); toast = null }
    }

    // ---------- keep the screensaver away while playing ----------

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Screen stays on while something is playing or loading (including brief buffering, so the
     * screensaver can't sneak in mid-stream). Paused or stopped: the normal screensaver rules apply.
     */
    private fun updateAwake() {
        val state = player.playbackState
        val awake = player.playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED
        if (awake) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ON_AFTER_RELEASE, "sideline:playback",
                ).apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
            }
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onPause() {
        super.onPause()
        wakeLock?.let { if (it.isHeld) it.release() }
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
            // "Continue watching" list.
            val v = vodItem
            if (v != null && dur > 0) {
                val m = v.movie
                val e = v.episodeObj
                val now = System.currentTimeMillis()
                when {
                    m != null -> SidelineApp.progress.update(ProgressEntry(
                        key = k, kind = "movie", accountId = m.accountId, title = details?.title?.takeIf { it.isNotBlank() } ?: m.name,
                        image = m.poster, position = pos, duration = dur, updated = now, streamId = m.streamId, ext = m.ext,
                    ))
                    e != null -> SidelineApp.progress.update(ProgressEntry(
                        key = k, kind = "episode", accountId = e.accountId, title = v.title, subtitle = v.subtitle,
                        image = v.seriesCover ?: e.image, position = pos, duration = dur, updated = now, ext = e.ext,
                        seriesId = v.seriesId, episodeId = e.id, season = e.season, episode = e.number, plot = e.plot,
                    ))
                }
            }
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
                        .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = if (prefs.pinnedGames.value.isEmpty()) 36.dp else (36 + 84 * ((prefs.pinnedGames.value.size + 2) / 3)).dp),
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
                                if (pausedAt != null) append("⏸ PAUSED · Play to resume     ")
                                append("OK channel list   ▲▼ channels   ◀ previous channel   ⏯ pause")
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
                        Text(if (player.playWhenReady) "OK pause   ◀ −10s   ▶ +30s   ▲ subtitles   ≡ games & scores" else "Paused · OK to resume",
                            color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }

    /** Pinned games along the bottom, always visible while watching. */
    @kotlin.OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PinnedTicker() {
        val pinned by prefs.pinnedGames.collectAsState()
        val board by SidelineApp.scores.board.collectAsState()
        val spoilerFree by prefs.spoilerFree.collectAsState()
        val games = board.values.flatten().distinctBy { it.id }.filter { it.id in pinned }
        if (games.isEmpty()) return
        Box(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 20.dp), contentAlignment = Alignment.BottomCenter) {
            // Wraps onto extra rows instead of squeezing the last scores off the edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                games.forEach { g -> TickerChip(g, spoilerFree) }
            }
        }
    }

    /** One pinned game: league tag, score, clock — plus down, distance and a mini field for live football. */
    @Composable
    private fun TickerChip(g: Game, spoilerFree: Boolean) {
        val league = Leagues.byId(g.leagueId)
        val sit = g.situation
        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE6131A22)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(league?.tag ?: "", color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x332EE59D)).padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
                if (g.isLive) { Box(Modifier.size(8.dp).clip(CircleShape).background(C.Live)); Spacer(Modifier.width(8.dp)) }
                if (g.competitors.size == 2) {
                    g.competitors.sortedBy { it.home }.forEachIndexed { i, c ->
                        if (i == 1) Text("–", color = C.Faint, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp))
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(c.abbr.ifBlank { c.shortName.take(10) }, color = C.Text, fontSize = 14.sp)
                        if (g.isLive && sit?.possessionTeamId != null && sit.possessionTeamId == c.id) Text(" 🏈", fontSize = 12.sp)
                        g.soccer?.let { sc -> val reds = if (c.home) sc.redHome else sc.redAway; if (reds > 0) Text(" " + "🟥".repeat(reds), fontSize = 11.sp) }
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
            // For 2 minutes after a score: who scored and assisted.
            val recent by SidelineApp.scores.recentScoring.collectAsState()
            val goal = recent[g.id]?.takeIf { System.currentTimeMillis() - it.second < 120_000 }?.first
            if (goal != null && !spoilerFree) {
                Text("🚨 $goal", color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).widthIn(max = 520.dp))
            }
            // Second line: football field / hockey shots & power play / soccer possession, plus win probability.
            if (hasLiveStrip(g) && !spoilerFree) {
                Box(Modifier.padding(top = 6.dp)) { LiveStrip(g) }
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

    @Composable
    private fun ReminderPrompt() {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopEnd) {
            AnimatedVisibility(visible = reminder != null, enter = fadeIn(), exit = fadeOut()) {
                val r = reminder ?: return@AnimatedVisibility
                Column(
                    Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0131A22))
                        .border(2.dp, C.Accent, RoundedCornerShape(16.dp)).padding(18.dp),
                ) {
                    Text("🔔 Starting now", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(r.title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(r.channelName, color = C.Muted, fontSize = 14.sp)
                    Text("OK  switch to it     Back  dismiss", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }

    /**
     * OK while watching live (like TiviMate): groups on the left, channels with what's on now and a progress
     * bar on the right. OK switches channel; Menu (≡) on a channel = favourite / rename / move / hide.
     */
    @Composable
    private fun ChannelPanel() {
        val favs by prefs.favChannels.collectAsState()
        val favOrder by prefs.favOrder.collectAsState()
        val hiddenCats by prefs.hiddenCats.collectAsState()
        val cats by repo.liveCategories.collectAsState()
        val channels by repo.channels.collectAsState()
        val recent by prefs.recentChannels.collectAsState()
        val cur = current
        val curCat = cur?.let { "${it.accountId}:${it.categoryId}" }
        var group by remember { mutableStateOf(if (queue.size > 1) "__queue" else (curCat ?: "__fav")) }
        val groups = remember(cats, hiddenCats) {
            listOf("__queue" to "▶ Now playing list", "__fav" to "★ Favourites", "__recent" to "⟲ Recent") +
                cats.filter { "live:${it.accountId}:${it.id}" !in hiddenCats }.map { "${it.accountId}:${it.id}" to it.name }
        }
        val list = remember(group, channels, favs, favOrder, recent) {
            when (group) {
                "__queue" -> queue.mapNotNull { repo.channel(it.key) ?: it }
                "__fav" -> prefs.orderedFavs().mapNotNull { repo.channel(it) }
                "__recent" -> recent.mapNotNull { repo.channel(it) }
                else -> channels.filter { "${it.accountId}:${it.categoryId}" == group }
            }
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val curFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            val i = list.indexOfFirst { it.key == cur?.key }
            if (i > 0) listState.scrollToItem((i - 2).coerceAtLeast(0))
            delay(200)
            try { curFocus.requestFocus() } catch (_: Exception) {}
        }
        TvDialog({ channelPanel = false }, dim = false) {
            androidx.compose.runtime.CompositionLocalProvider(app.sideline.tv.ui.LocalPanelAlpha provides panelAlpha()) {
            Row(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxHeight().width(820.dp).background(panelColor()).padding(start = 28.dp, top = 24.dp, end = 16.dp)) {
                    LazyColumn(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(groups, key = { it.first }) { (k, name) ->
                            app.sideline.tv.ui.CategoryItem(name, group == k) { group = k }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(list, key = { it.key }) { c ->
                            PanelChannelRow(c, c.key == cur?.key, c.key in favs,
                                modifier = if (c.key == cur?.key) Modifier.focusRequester(curFocus) else Modifier,
                                onClick = {
                                    channelPanel = false
                                    queue = list
                                    if (c.key != cur?.key) tune(c)
                                },
                                onMenu = { app.sideline.tv.ui.ChannelMenu.open(c, inFavourites = group == "__fav") })
                        }
                    }
                }
            }
            }
        }
    }

    /** Panel background with your chosen see-through level (Settings → Player panels). */
    @Composable
    private fun panelColor(): Color = C.Bg.copy(alpha = panelAlpha())

    @Composable
    private fun panelAlpha(): Float {
        val t by prefs.panelTransparency.collectAsState()
        return (100 - t) / 100f
    }

    @Composable
    private fun PanelChannelRow(c: Channel, playing: Boolean, fav: Boolean, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit) {
        var now by remember(c.key) { mutableStateOf<EpgItem?>(null) }
        LaunchedEffect(c.key) {
            delay(150) // let fast scrolling skip rows
            val t = System.currentTimeMillis() / 1000
            now = repo.epgCached(c).firstOrNull { t in it.start until it.end }
        }
        FocusCard(onClick = onClick, onMenu = onMenu, modifier = modifier.fillMaxWidth().height(62.dp), scaleOnFocus = 1.02f,
            shape = RoundedCornerShape(10.dp)) { _ ->
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(width = 56.dp, height = 32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (playing) { Text("▶ ", color = C.Accent, fontSize = 13.sp) }
                        Text(c.name, color = if (playing) C.Accent else C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (fav) Text("  ★", color = C.Gold, fontSize = 13.sp)
                    }
                    val p = now
                    if (p != null) {
                        Text(p.title, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val frac = ((System.currentTimeMillis() / 1000 - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(2.dp).background(C.Surface2)) {
                            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(C.Accent))
                        }
                    }
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

        TvDialog({ gamesPanel = false }, dim = false) {
            androidx.compose.runtime.CompositionLocalProvider(app.sideline.tv.ui.LocalPanelAlpha provides panelAlpha()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.fillMaxHeight().width(620.dp).background(panelColor()).padding(28.dp)) {
                    Text("Games", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Live now and starting in the next 3 hours, in your leagues.", color = C.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    if (games.isEmpty()) Text("No live or upcoming games right now.", color = C.Muted)
                    // Your teams & pinned games first, then everything else grouped by league.
                    val mine = games.filter { it.id in pinned || SidelineApp.scores.isWatched(it) }
                    val rest = games.filter { it !in mine }
                    val leagueOrder = (Leagues.all.map { it.id } + rest.map { it.leagueId }).distinct()
                    val sections = buildList {
                        if (mine.isNotEmpty()) add("★ Your teams & pinned" to mine)
                        rest.groupBy { it.leagueId }.entries.sortedBy { leagueOrder.indexOf(it.key) }
                            .forEach { (lid, list) -> add((Leagues.byId(lid)?.name ?: lid) to list) }
                    }
                    val firstId = sections.firstOrNull()?.second?.firstOrNull()?.id
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        sections.forEach { (heading, list) ->
                            item(key = "h-$heading") {
                                Text("$heading  (${list.size})", color = C.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp))
                            }
                            items(list, key = { "$heading-${it.id}" }) { g ->
                                GameRow(g, pinned, followed, if (g.id == firstId) Modifier.focusRequester(first) else Modifier)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    /** ▲ during a movie/episode: Off, built-in tracks, or search OpenSubtitles. */
    @Composable
    private fun SubtitlesPanel() {
        val lang by prefs.subsLang.collectAsState()
        var tracks by remember { mutableStateOf(textTracks()) }
        var results by remember { mutableStateOf<List<OpenSubtitles.Result>?>(null) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val first = remember { FocusRequester() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val textOn = !player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT)
        LaunchedEffect(Unit) { delay(250); try { first.requestFocus() } catch (_: Exception) {} }
        TvDialog({ subsPanel = false }, dim = false) {
            androidx.compose.runtime.CompositionLocalProvider(app.sideline.tv.ui.LocalPanelAlpha provides panelAlpha()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.fillMaxHeight().width(560.dp).background(panelColor()).padding(28.dp)) {
                    Text("Subtitles", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        item {
                            Chip(if (!textOn) "✓ Off" else "Off", !textOn, modifier = Modifier.focusRequester(first), onClick = {
                                subtitlesOff(); subsPanel = false
                            })
                        }
                        if (tracks.isNotEmpty()) item { Text("In this file", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                        items(tracks, key = { it.group.mediaTrackGroup.id + it.label }) { t ->
                            val on = textOn && t.group.isSelected
                            Chip(if (on) "✓ ${t.label}" else t.label, on, onClick = { selectTrack(t); subsPanel = false })
                        }
                        item {
                            Text("Online (OpenSubtitles, ${langName(lang)})", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                        if (!OpenSubtitles.configured) item {
                            Text("Add your free OpenSubtitles key in Settings → Subtitles to search online.", color = C.Faint, fontSize = 14.sp)
                        } else if (results == null) item {
                            Chip(if (busy) "Searching…" else "🔍 Find subtitles online", false, onClick = {
                                if (busy) return@Chip
                                busy = true; status = ""
                                scope.launch {
                                    try { results = searchSubs(); if (results!!.isEmpty()) status = "Nothing found for this title." }
                                    catch (e: Exception) { status = e.message ?: "Search failed" }
                                    busy = false
                                }
                            })
                        } else {
                            items(results!!.take(15), key = { it.fileId }) { r ->
                                FocusCard(onClick = {
                                    if (busy) return@FocusCard
                                    busy = true; status = "Downloading…"
                                    scope.launch {
                                        try { attachOnline(r); subsPanel = false }
                                        catch (e: Exception) { status = e.message ?: "Download failed" }
                                        busy = false
                                    }
                                }, modifier = Modifier.fillMaxWidth(), scaleOnFocus = 1.02f) { _ ->
                                    Column(Modifier.padding(12.dp)) {
                                        Text(r.release, color = C.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${r.downloads} downloads" + if (r.hearingImpaired) " · for hard of hearing" else "", color = C.Faint, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (status.isNotBlank()) item { Text(status, color = C.Gold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            }
        }
        // Tracks can change after an online subtitle is attached.
        LaunchedEffect(player.currentTracks) { tracks = textTracks() }
    }

    @Composable
    private fun GameRow(g: Game, pinned: Set<String>, followed: Set<String>, watchModifier: Modifier) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface.copy(alpha = app.sideline.tv.ui.LocalPanelAlpha.current)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (g.isLive) { Pill("LIVE", C.Live, Color.White); Spacer(Modifier.width(8.dp)) }
                Text(g.title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                if (g.state != "pre" && g.competitors.size == 2 && !prefs.spoilerFree.value)
                    Text(g.competitors.sortedBy { it.home }.joinToString(" – ") { it.score }, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (g.state == "pre" && g.startMillis > 0) "Starts ${timeOf(g.startMillis)}" else g.detail, color = C.Muted, fontSize = 13.sp)
            if (!prefs.spoilerFree.value) LiveDetailsFull(g)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Chip("▶ Watch", true, onClick = { watchGame(g) }, modifier = watchModifier)
                Chip(if (g.id in pinned) "📌 Unpin" else "📌 Pin", g.id in pinned, onClick = { prefs.togglePin(g.id) })
                Chip(if (g.id in followed) "🔔 On" else "🔕 Pop-ups", g.id in followed, onClick = { prefs.toggleFollow(g.id) })
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
                        if (d != null && (d.metaLine.isNotBlank() || d.certification.isNotBlank())) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                if (d.certification.isNotBlank()) { RatingBadge(d.certification); Spacer(Modifier.width(10.dp)) }
                                Text(d.metaLine, color = C.Accent, fontSize = 15.sp)
                            }
                        }
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
                        Text("OK or Play to resume   ◀ −10s   ▶ +30s   ▲ subtitles   ≡ games & scores   Back to exit", color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
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
