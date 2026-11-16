package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.Net
import kotlinx.coroutines.delay

/**
 * Only one preview may hold the stream connection, and it must let go before full-screen playback starts
 * (your live subscription allows one connection at a time).
 */
object PreviewControl {
    @Volatile var player: ExoPlayer? = null
    fun stop() { try { player?.stop(); player?.clearMediaItems() } catch (_: Exception) { } }
}

/**
 * Muted live preview of the channel you're on in Live TV. Starts after you rest on a channel for about a second,
 * stops when you leave the screen or open a channel full screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LivePreview(channel: Channel?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.http)))
            .build().apply { volume = 0f; playWhenReady = true }
    }
    var problem by remember { mutableStateOf(false) }
    var startedTick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    DisposableEffect(player) {
        PreviewControl.player = player
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { problem = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) problem = false }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (PreviewControl.player === player) PreviewControl.player = null
            player.release()
        }
    }
    // Stop when another screen (the player) comes to the front; pick up again when we're back.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) PreviewControl.stop()
            if (e == Lifecycle.Event.ON_START) startedTick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(channel?.key, startedTick) {
        player.stop()
        problem = false
        val c = channel ?: return@LaunchedEffect
        delay(1_000) // only when you pause on a channel, so fast scrolling doesn't open streams
        val url = SidelineApp.repo.client(c.accountId)?.liveUrl(c) ?: return@LaunchedEffect
        val item = MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        player.setMediaItem(item)
        player.prepare()
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
        if (channel == null) Text("Preview", color = C.Faint, fontSize = 12.sp)
        if (problem) Text("No preview", color = C.Faint, fontSize = 12.sp)
    }
}
