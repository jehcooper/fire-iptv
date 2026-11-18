package app.sideline.tv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Full-screen YouTube trailer inside Cooper TV (TMDB only stores YouTube links).
 * Remote: OK / ⏯ pause-play, ◀ ▶ back/forward 10 s, ▲▼ next/previous trailer, Back closes.
 */
class TrailerActivity : Activity() {
    companion object {
        fun start(ctx: Context, keys: List<String>, names: List<String>, title: String) {
            if (keys.isEmpty()) { Toast.makeText(ctx, "No trailer found for this title", Toast.LENGTH_SHORT).show(); return }
            app.sideline.tv.ui.PreviewControl.stop()
            ctx.startActivity(Intent(ctx, TrailerActivity::class.java)
                .putStringArrayListExtra("keys", ArrayList(keys)).putStringArrayListExtra("names", ArrayList(names)).putExtra("title", title))
        }
    }

    private lateinit var web: WebView
    private lateinit var label: TextView
    private var keys: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var index = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        keys = intent.getStringArrayListExtra("keys") ?: emptyList()
        names = intent.getStringArrayListExtra("names") ?: emptyList()
        if (keys.isEmpty()) { finish(); return }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            isFocusable = false
            isFocusableInTouchMode = false
            addJavascriptInterface(Bridge(), "Cooper")
        }
        label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setBackgroundColor(0x99000000.toInt()); setPadding(28, 14, 28, 14)
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(label, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = 48; topMargin = 36 })
        setContentView(root)
        play(0)
    }

    private fun play(i: Int) {
        index = (i + keys.size) % keys.size
        val title = intent.getStringExtra("title") ?: ""
        label.text = "$title · ${names.getOrNull(index) ?: "Trailer"}" + (if (keys.size > 1) "   (${index + 1}/${keys.size}, ▲▼ for more)" else "") +
            "\nOK pause · ◀ ▶ 10 s · Back to close"
        label.visibility = View.VISIBLE
        label.removeCallbacks(hideLabel)
        label.postDelayed(hideLabel, 5000)
        // YouTube needs to know which site embeds it (a "referrer"), so the page is given an https address.
        val html = """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style></head>
            <body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('p',{width:'100%',height:'100%',videoId:'${keys[index]}',
                playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,modestbranding:1,iv_load_policy:3,origin:'https://app.sideline.tv'},
                events:{onReady:function(e){e.target.playVideo()},
                        onStateChange:function(e){if(e.data==0)Cooper.ended()},
                        onError:function(e){Cooper.error(''+e.data)}}});
            }
            function toggle(){if(!player)return;var s=player.getPlayerState();if(s==1)player.pauseVideo();else player.playVideo()}
            function seek(d){if(player)player.seekTo(Math.max(0,player.getCurrentTime()+d),true)}
            </script></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://app.sideline.tv/", html, "text/html", "utf-8", null)
    }

    private val hideLabel = Runnable { label.visibility = View.GONE }

    private fun showLabel() {
        label.visibility = View.VISIBLE
        label.removeCallbacks(hideLabel)
        label.postDelayed(hideLabel, 4000)
    }

    inner class Bridge {
        @JavascriptInterface fun ended() = runOnUiThread { if (index < keys.size - 1) play(index + 1) else finish() }
        @JavascriptInterface fun error(code: String) = runOnUiThread {
            if (index < keys.size - 1) play(index + 1)
            else {
                Toast.makeText(this@TrailerActivity, "YouTube won't play this trailer inside the app (error $code).", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) finish(); true }
            else -> super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { web.evaluateJavascript("toggle()", null); showLabel() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { web.evaluateJavascript("seek(-10)", null); showLabel() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { web.evaluateJavascript("seek(10)", null); showLabel() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (keys.size > 1) play(index + 1) else showLabel()
            KeyEvent.KEYCODE_DPAD_UP -> if (keys.size > 1) play(index - 1) else showLabel()
            KeyEvent.KEYCODE_BACK -> {}
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onDestroy() {
        if (::web.isInitialized) { web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }
}
