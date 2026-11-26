package app.sideline.tv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.sideline.tv.data.Net

/**
 * Plays one short video file (a goal clip) full screen in our own player: no web page, no ads.
 * OK pauses/plays, ◀ ▶ skip 5 seconds, Back closes. Closes by itself at the end.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipPlayerActivity : Activity() {
    companion object {
        fun start(ctx: Context, url: String, title: String, hls: Boolean) {
            ctx.startActivity(Intent(ctx, ClipPlayerActivity::class.java)
                .putExtra("url", url).putExtra("title", title).putExtra("hls", hls))
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""

        view = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 2500
            setShowNextButton(false); setShowPreviousButton(false)
            setBackgroundColor(Color.BLACK)
        }
        label = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE); textSize = 20f
            setPadding(56, 36, 56, 12)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> label.visibility = v })

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.http)))
            .build()
        val item = MediaItem.Builder().setUri(url).apply {
            if (intent.getBooleanExtra("hls", false)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@ClipPlayerActivity, "This clip won't play here (${error.errorCodeName}).", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        p.prepare()
        p.playWhenReady = true
        view.player = p
        player = p
        label.postDelayed({ if (!view.isControllerFullyVisible) label.visibility = View.GONE }, 4000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (p.isPlaying) p.pause() else p.play(); view.showController(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo((p.currentPosition - 5000).coerceAtLeast(0)); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekTo(p.currentPosition + 5000); view.showController(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
