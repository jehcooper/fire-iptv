package app.sideline.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks what affects IPTV smoothness, in plain terms: the home network, how quickly the internet and the
 * provider answer, how much download headroom there is, and how a live channel actually behaves.
 */
object SpeedTest {
    enum class Grade { GOOD, OK, POOR, INFO }
    data class Line(val title: String, val value: String, val grade: Grade, val note: String = "")
    data class Result(val lines: List<Line>, val advice: List<String>)

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress

    // Own client: short timeouts, no caching, nothing shared with playback.
    private val http by lazy {
        Net.http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    suspend fun run(ctx: Context, channel: Channel?): Result = withContext(Dispatchers.IO) {
        val lines = mutableListOf<Line>()
        val advice = mutableListOf<String>()
        val repo = app.sideline.tv.SidelineApp.repo
        val accounts = app.sideline.tv.SidelineApp.prefs.accounts.value

        // 1) Home network
        _progress.value = "Checking your home network…"
        val net = network(ctx)
        lines += net.first
        advice += net.second

        // 2) Internet vs provider response time
        _progress.value = "Measuring internet response time…"
        val google = latency("https://www.google.com/generate_204")
        lines += if (google == null) Line("Internet response", "no answer", Grade.POOR, "Couldn't reach the internet")
        else Line("Internet response", "${google.first} ms", grade(google.first, 60, 150), "jitter ±${google.second} ms")
        if (google != null && google.first > 150) advice += "Your internet itself is slow to respond (${google.first} ms). Restart the router and the Fire TV; if it stays high, check with your internet provider."

        var providerMs: Int? = null
        for (a in accounts) {
            val c = repo.client(a.id) ?: continue
            _progress.value = "Measuring ${a.name} server response…"
            val l = latency("${c.base}/player_api.php?username=${enc(a.username)}&password=${enc(a.password)}")
            if (l == null) {
                lines += Line("${a.name} server", "no answer", Grade.POOR, "The provider's server didn't respond")
                advice += "${a.name}'s server didn't answer. It may be down or blocking your connection. Check with the provider, or try their backup server address if they gave you one."
            } else {
                if (a.useLive) providerMs = l.first
                lines += Line("${a.name} server", "${l.first} ms", grade(l.first, 150, 400), "jitter ±${l.second} ms")
                if (l.first > 400 && (google?.first ?: 0) < 100) advice += "${a.name}'s server is slow to answer (${l.first} ms) while your internet is quick, so the delay is on the provider's side. Many providers have alternative server addresses; ask them for one closer to you."
                if (l.second > 150) advice += "${a.name}'s response time varies a lot (±${l.second} ms). That often shows up as channels taking a while to start. It's usually busy-time congestion at the provider."
            }
        }

        // 3) Download headroom: a movie file downloads as fast as the connection allows
        val movie = repo.movies.value.firstOrNull()
        var movieMbps: Double? = null
        if (movie != null) {
            _progress.value = "Measuring download speed from your provider (8 s)…"
            val url = repo.client(movie.accountId)?.movieUrl(movie)
            movieMbps = url?.let { throughput(it, 8_000).first }
            lines += if (movieMbps == null) Line("Download speed (movies)", "failed", Grade.POOR, "Couldn't download a test movie")
            else Line("Download speed (movies)", "%.1f Mbps".format(movieMbps), gradeHigh(movieMbps, 25.0, 12.0), "how fast your provider can send to this TV")
        }

        // 4) A live channel for 12 seconds: start time, bitrate, stutters
        val ch = channel ?: repo.channels.value.firstOrNull()
        var liveMbps: Double? = null
        if (ch != null) {
            _progress.value = "Testing a live channel: ${ch.name} (12 s)…"
            val url = repo.client(ch.accountId)?.liveUrl(ch)
            if (url != null) {
                val (mbps, ttfb, stalls) = throughput(url, 12_000)
                liveMbps = mbps
                lines += Line("Live channel start", if (ttfb == null) "failed" else "%.1f s".format(ttfb / 1000.0),
                    if (ttfb == null) Grade.POOR else gradeLow(ttfb, 1500, 4000), ch.name)
                if (mbps != null) {
                    lines += Line("Live channel bitrate", "%.1f Mbps".format(mbps), Grade.INFO, "the stream's own data rate")
                    lines += Line("Live stutters", "$stalls", if (stalls == 0) Grade.GOOD else if (stalls <= 2) Grade.OK else Grade.POOR, "pauses over 1.5 s in the data")
                }
                if (ttfb != null && ttfb > 4000) advice += "Channels take ${"%.1f".format(ttfb / 1000.0)} s to start. That's mostly the provider's servers; switching the live format (Settings → account → HLS / MPEG-TS) sometimes helps."
                if (stalls > 0) advice += "The live stream paused $stalls time(s) in 12 seconds. Try the other live format (MPEG-TS ↔ HLS) in your account settings, or a lower-quality copy of the channel (HD instead of FHD/4K). If it only happens in the evening, the provider is likely overloaded at peak times."
            }
        }

        // Headroom: movie speed vs. what a live stream needs
        if (movieMbps != null && liveMbps != null && liveMbps > 0.5) {
            val ratio = movieMbps / liveMbps
            lines += Line("Headroom", "%.1f×".format(ratio), gradeHigh(ratio, 2.5, 1.5), "download speed ÷ stream bitrate")
            if (ratio < 1.5) advice += "There's very little spare speed (only ${"%.1f".format(ratio)}× what the stream needs), so any dip causes buffering. Improve the Wi-Fi (see above), or use lower-quality versions of channels."
        } else if (movieMbps != null && movieMbps < 12) {
            advice += "Download speed from your provider is low (${"%.1f".format(movieMbps)} Mbps). HD needs about 8–10 Mbps steady, 4K about 25."
        }
        if (movieMbps != null && movieMbps >= 25 && (liveMbps == null || liveMbps < 0.1)) {
            advice += "Movies download quickly but the live channel didn't send data. The channel may be offline, or the account's one connection is in use on another device."
        }
        if (providerMs != null && google != null && movieMbps != null && movieMbps < 10 && google.first < 60) {
            advice += "Your internet responds quickly but the provider sends slowly. Some internet providers slow down streaming traffic at busy times; a VPN can sometimes avoid that (check your provider's terms)."
        }
        if (advice.isEmpty()) advice += "Everything looks healthy. If you still see buffering, note the time and channel. It's likely the provider's stream at that moment, not your setup."
        _progress.value = ""
        Result(lines, advice.distinct())
    }

    private fun grade(ms: Int, good: Int, ok: Int) = when { ms <= good -> Grade.GOOD; ms <= ok -> Grade.OK; else -> Grade.POOR }
    private fun gradeLow(v: Long, good: Long, ok: Long) = when { v <= good -> Grade.GOOD; v <= ok -> Grade.OK; else -> Grade.POOR }
    private fun gradeHigh(v: Double, good: Double, ok: Double) = when { v >= good -> Grade.GOOD; v >= ok -> Grade.OK; else -> Grade.POOR }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Median response time and spread over 5 tries, in ms; null if it never answered. */
    private fun latency(url: String): Pair<Int, Int>? {
        val times = mutableListOf<Long>()
        repeat(5) {
            val t0 = System.nanoTime()
            try {
                http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    r.body?.source()?.request(1)
                    times += (System.nanoTime() - t0) / 1_000_000
                }
            } catch (_: Exception) { }
        }
        if (times.isEmpty()) return null
        val sorted = times.sorted()
        return sorted[sorted.size / 2].toInt() to ((sorted.last() - sorted.first()) / 2).toInt()
    }

    /** Reads a stream for [ms]: average Mbps (after the first second), time to first byte, and stalls > 1.5 s. */
    private fun throughput(url: String, ms: Long): Triple<Double?, Long?, Int> {
        val t0 = System.currentTimeMillis()
        var ttfb: Long? = null
        var stalls = 0
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return Triple(null, null, 0)
                val input = r.body?.byteStream() ?: return Triple(null, null, 0)
                val buf = ByteArray(64 * 1024)
                var counted = 0L
                var countFrom = 0L
                var last = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < ms) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val now = System.currentTimeMillis()
                    if (ttfb == null) { ttfb = now - t0; countFrom = now + 1000 }
                    if (now - last > 1500) stalls++
                    last = now
                    if (now >= countFrom) counted += n
                }
                val secs = (System.currentTimeMillis() - countFrom) / 1000.0
                val mbps = if (secs > 1 && counted > 0) counted * 8 / secs / 1_000_000 else null
                return Triple(mbps, ttfb, stalls)
            }
        } catch (e: Exception) {
            return Triple(null, ttfb, stalls)
        }
    }

    @Suppress("DEPRECATION")
    private fun network(ctx: Context): Pair<List<Line>, List<String>> {
        val lines = mutableListOf<Line>()
        val advice = mutableListOf<String>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> { lines += Line("Connection", "offline", Grade.POOR); advice += "The Fire TV isn't connected to a network." }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> lines += Line("Connection", "Ethernet (wired)", Grade.GOOD, "the most reliable option")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = try { wm.connectionInfo } catch (e: Exception) { null }
                val rssi = info?.rssi ?: -127
                val band = info?.frequency?.let { if (it >= 4900) "5 GHz" else "2.4 GHz" } ?: "?"
                val link = info?.linkSpeed ?: -1
                lines += Line("Connection", "Wi-Fi · $band", if (band == "5 GHz") Grade.GOOD else Grade.OK)
                lines += Line("Wi-Fi signal", "$rssi dBm", when { rssi >= -60 -> Grade.GOOD; rssi >= -70 -> Grade.OK; else -> Grade.POOR },
                    when { rssi >= -60 -> "strong"; rssi >= -70 -> "fair"; else -> "weak" })
                if (link > 0) lines += Line("Wi-Fi link speed", "$link Mbps", if (link >= 150) Grade.GOOD else if (link >= 65) Grade.OK else Grade.POOR)
                if (rssi < -70) advice += "The Wi-Fi signal is weak ($rssi dBm). Move the router closer or out of a cabinet, use the HDMI extender that came with the stick (it gets the stick out from behind the TV), or use Amazon's Ethernet adapter for the Fire TV Stick."
                if (band == "2.4 GHz") advice += "The stick is on 2.4 GHz Wi-Fi, which is slower and more crowded. If your router has a 5 GHz network, connect the stick to that (Fire TV Settings → Network)."
            }
            else -> lines += Line("Connection", "connected", Grade.INFO)
        }
        return lines to advice
    }
}
