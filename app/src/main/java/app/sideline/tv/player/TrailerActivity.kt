package app.sideline.tv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Full-screen YouTube player inside Empire TV (trailers, game recaps, goal videos), or a web video page
 * (the NHL's own goal clips). Remote: OK / ⏯ pause-play, ◀ ▶ 10 s, ▲▼ previous/next video, Back closes.
 * A control bar (title, elapsed / total, quality, progress) shows on any key press and while paused.
 */
class TrailerActivity : Activity() {
    companion object {
        fun start(ctx: Context, keys: List<String>, names: List<String>, title: String, lengths: List<String> = emptyList()) {
            if (keys.isEmpty()) { Toast.makeText(ctx, "No video found", Toast.LENGTH_SHORT).show(); return }
            app.sideline.tv.ui.PreviewControl.stop()
            ctx.startActivity(Intent(ctx, TrailerActivity::class.java)
                .putStringArrayListExtra("keys", ArrayList(keys)).putStringArrayListExtra("names", ArrayList(names))
                .putStringArrayListExtra("lengths", ArrayList(lengths)).putExtra("title", title))
        }

        /** Plays a video web page (e.g. an NHL goal clip) full screen. */
        fun startPage(ctx: Context, url: String, title: String) {
            app.sideline.tv.ui.PreviewControl.stop()
            ctx.startActivity(Intent(ctx, TrailerActivity::class.java).putExtra("page", url).putExtra("title", title))
        }
    }

    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var progress: ProgressBar
    private var keys: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var lengths: List<String> = emptyList()
    private var index = 0
    private var page: String? = null
    private var paused = false
    private val ticker = object : Runnable {
        override fun run() {
            if (page == null) web.evaluateJavascript("status()") { v -> updateStatus(v) }
            web.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        keys = intent.getStringArrayListExtra("keys") ?: emptyList()
        names = intent.getStringArrayListExtra("names") ?: emptyList()
        lengths = intent.getStringArrayListExtra("lengths") ?: emptyList()
        page = intent.getStringExtra("page")
        if (keys.isEmpty() && page == null) { finish(); return }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = page != null
            isFocusableInTouchMode = page != null
            addJavascriptInterface(Bridge(), "Cooper")
        }
        titleView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; maxLines = 1 }
        timeView = TextView(this).apply { setTextColor(0xFFCCCCCC.toInt()); textSize = 15f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressDrawable.setTint(0xFFD4AF37.toInt())
        }
        val hint = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
            text = if (page != null) "Back to close" else "OK pause / play · ◀ ▶ 10 s · ▲▼ other videos · Back to close"
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 28, 56, 32)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            addView(titleView)
            addView(progress, LinearLayout.LayoutParams(-1, 18).apply { topMargin = 14; bottomMargin = 10 })
            val row = LinearLayout(this@TrailerActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(timeView, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(hint)
            addView(row)
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        if (page != null) {
            titleView.text = intent.getStringExtra("title") ?: ""
            timeView.text = "NHL video"
            progress.visibility = View.GONE
            web.loadUrl(page!!)
            showBar()
        } else play(0)
        web.post(ticker)
    }

    private fun play(i: Int) {
        index = (i + keys.size) % keys.size
        val title = intent.getStringExtra("title") ?: ""
        titleView.text = (names.getOrNull(index) ?: title) + if (keys.size > 1) "   (${index + 1}/${keys.size})" else ""
        timeView.text = "0:00 / " + (lengths.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "…")
        progress.progress = 0
        paused = false
        showBar()
        // YouTube needs to know which site embeds it (a "referrer"), so the page is given an https address.
        // vq / setPlaybackQuality ask for HD; YouTube still adapts to the connection.
        val html = """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style></head>
            <body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('p',{width:'100%',height:'100%',videoId:'${keys[index]}',
                playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,modestbranding:1,iv_load_policy:3,vq:'hd1080',origin:'https://app.sideline.tv'},
                events:{onReady:function(e){try{e.target.setPlaybackQuality('hd1080')}catch(x){} e.target.playVideo()},
                        onStateChange:function(e){if(e.data==0)Cooper.ended()},
                        onError:function(e){Cooper.error(''+e.data)}}});
            }
            function toggle(){if(!player)return;var s=player.getPlayerState();if(s==1)player.pauseVideo();else player.playVideo()}
            function seek(d){if(player)player.seekTo(Math.max(0,player.getCurrentTime()+d),true)}
            function status(){try{return JSON.stringify({t:player.getCurrentTime(),d:player.getDuration(),s:player.getPlayerState(),q:player.getPlaybackQuality()})}catch(x){return '{}'}}
            </script></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://app.sideline.tv/", html, "text/html", "utf-8", null)
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateStatus(raw: String?) {
        try {
            // evaluateJavascript returns the JSON text as a quoted string.
            val inner = org.json.JSONTokener(raw ?: return).nextValue() as? String ?: return
            val json = org.json.JSONObject(inner)
            val t = json.optDouble("t", 0.0); val d = json.optDouble("d", 0.0); val st = json.optInt("s", -1)
            val quality = when (json.optString("q", "")) {
                "hd2160" -> " · 4K"; "hd1440" -> " · 1440p"; "hd1080" -> " · 1080p"; "hd720" -> " · 720p"; "large" -> " · 480p"; "medium" -> " · 360p"; else -> ""
            }
            if (d > 0) {
                timeView.text = "${fmt(t)} / ${fmt(d)}" + quality + if (st == 2) "  ·  Paused" else ""
                progress.progress = ((t / d) * 1000).toInt()
            }
            val nowPaused = st == 2
            if (nowPaused != paused) { paused = nowPaused; showBar(sticky = paused) }
        } catch (_: Exception) {}
    }

    private val hideBar = Runnable { if (!paused) bar.visibility = View.GONE }

    private fun showBar(sticky: Boolean = false) {
        bar.visibility = View.VISIBLE
        bar.removeCallbacks(hideBar)
        if (!sticky) bar.postDelayed(hideBar, 4500)
    }

    inner class Bridge {
        @JavascriptInterface fun ended() = runOnUiThread { if (index < keys.size - 1) play(index + 1) else finish() }
        @JavascriptInterface fun error(code: String) = runOnUiThread {
            if (index < keys.size - 1) play(index + 1)
            else {
                Toast.makeText(this@TrailerActivity, "YouTube won't play this video inside the app (error $code).", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) finish(); return true }
        if (page != null) { if (event.action == KeyEvent.ACTION_DOWN) showBar(); return super.dispatchKeyEvent(event) }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { web.evaluateJavascript("toggle()", null); showBar() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { web.evaluateJavascript("seek(-10)", null); showBar() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { web.evaluateJavascript("seek(10)", null); showBar() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (keys.size > 1) play(index + 1) else showBar()
            KeyEvent.KEYCODE_DPAD_UP -> if (keys.size > 1) play(index - 1) else showBar()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onDestroy() {
        if (::web.isInitialized) { web.removeCallbacks(ticker); web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }
}
