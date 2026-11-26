package app.sideline.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.sideline.tv.SidelineApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Finds the actual video file behind an NHL goal clip, so it plays in Empire TV's own player (no web page, no ads).
 *
 * NHL.com plays its clips with a Brightcove player. The first time, the clip's NHL.com page is opened invisibly and
 * the player's own video request is watched for: that gives the video file, and the player key, which is remembered
 * so later clips are asked for directly. This isn't an official NHL route, so it can stop working if NHL.com changes.
 */
object ClipVideo {
    data class Src(val url: String, val hls: Boolean)

    private val RX_PLAYBACK = Regex("edge\\.api\\.brightcove\\.com/playback/v1/accounts/(\\d+)/videos/([^/?#]+)")
    private val RX_PK = Regex("pk=([A-Za-z0-9_\\-.]+)")
    private val AD_WORDS = listOf("doubleclick", "googlesyndication", "imasdk", "2mdn", "googleads", "adsafeprotected", "moatads",
        "innovid", "springserve", "fwmrm", "freewheel", "adservice", "/ads/", "pubads", "amazon-adsystem", "teads")

    /** NHL clip: [videoId] is the clip's id from the NHL feed, [page] its NHL.com address. */
    suspend fun nhl(ctx: Context, videoId: String?, page: String?): Src? {
        val prefs = SidelineApp.prefs
        val known = prefs.bcovKey.split('|').takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        if (known != null && videoId != null) playback(known[0], known[1], videoId)?.let { return it }
        val url = page ?: videoId?.let { "https://www.nhl.com/video/$it" } ?: return null
        val cap = capture(ctx, url) ?: return null
        if (cap.account != null && cap.policy != null) {
            prefs.bcovKey = "${cap.account}|${cap.policy}"
            val vid = videoId ?: cap.video
            if (vid != null) playback(cap.account, cap.policy, vid)?.let { return it }
            if (cap.video != null && cap.video != vid) playback(cap.account, cap.policy, cap.video)?.let { return it }
        }
        return cap.media?.let { Src(it, it.contains(".m3u8")) }
    }

    /** Brightcove's playback feed for one video → best source (HLS first, else the sharpest MP4). */
    private suspend fun playback(account: String, policy: String, videoId: String): Src? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://edge.api.brightcove.com/playback/v1/accounts/$account/videos/$videoId")
                .header("Accept", "application/json;pk=$policy")
                .header("Origin", "https://www.nhl.com").header("Referer", "https://www.nhl.com/")
                .build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = Net.json.parseToJsonElement(r.body?.string().orEmpty()).obj() ?: return@withContext null
                val sources = o["sources"].arr()?.mapNotNull { it.obj() }.orEmpty().filter { (it.s("src") ?: "").startsWith("https") }
                val hls = sources.firstOrNull { (it.s("type") ?: "").contains("mpegurl", true) || (it.s("src") ?: "").contains(".m3u8") }
                if (hls != null) return@withContext Src(hls.s("src")!!, true)
                val mp4 = sources.filter { (it.s("container") ?: it.s("type") ?: "").contains("mp4", true) || (it.s("src") ?: "").contains(".mp4") }
                    .maxByOrNull { it.i("height") ?: it.i("avg_bitrate") ?: 0 }
                mp4?.let { Src(it.s("src")!!, false) }
            }
        } catch (e: Exception) { null }
    }

    private class Capture(val account: String?, val policy: String?, val video: String?, val media: String?)

    /** Opens the page invisibly and watches its requests for the video player's feed or a video file (20 s at most). */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun capture(ctx: Context, url: String): Capture? {
        val done = CompletableDeferred<Capture>()
        var web: WebView? = null
        withContext(Dispatchers.Main) {
            val w = WebView(ctx)
            web = w
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.mediaPlaybackRequiresUserGesture = false
            w.settings.blockNetworkImage = true
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    val low = u.lowercase()
                    if (AD_WORDS.any { low.contains(it) }) return null
                    RX_PLAYBACK.find(u)?.let { m ->
                        val headers = request.requestHeaders.orEmpty()
                        val pk = headers.entries.firstOrNull { it.key.equals("BCOV-Policy", true) }?.value
                            ?: headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value?.let { RX_PK.find(it)?.groupValues?.get(1) }
                        done.complete(Capture(m.groupValues[1], pk, m.groupValues[2], null))
                        return null
                    }
                    if (low.contains(".m3u8") || low.substringBefore('?').endsWith(".mp4")) done.complete(Capture(null, null, null, u))
                    return null
                }
            }
            w.loadUrl(url)
        }
        val r = withTimeoutOrNull(20_000) { done.await() }
        withContext(Dispatchers.Main) { try { web?.stopLoading(); web?.destroy() } catch (_: Exception) {} }
        return r
    }

    /** A plain video file from an ESPN highlight link (MP4 or HLS). */
    fun espn(url: String) = Src(url, url.contains(".m3u8"))
}
