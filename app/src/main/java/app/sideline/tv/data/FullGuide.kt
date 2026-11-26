package app.sideline.tv.data

import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The full TV guide (XMLTV): several days for every channel, instead of the provider's short "now & next" list.
 * Source: your provider's own xmltv.php (default) or a custom XMLTV address.
 * Downloaded at most every 12 hours and read in the background. To keep memory sensible on the stick it keeps
 * 12 hours back (for catch-up) to 2 days ahead, only for channels you haven't hidden.
 */
object FullGuide {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_AGE = 12 * 3600_000L

    /** epg channel id (lower-case) → programmes, sorted by start. */
    @Volatile private var programmes: Map<String, List<EpgItem>> = emptyMap()

    private val _status = MutableStateFlow("Not loaded")
    val status: StateFlow<String> = _status
    private val _version = MutableStateFlow(0)
    /** Bumps when new guide data is ready, so screens can refresh. */
    val version: StateFlow<Int> = _version
    @Volatile private var loading = false
    @Volatile private var loadedAt = 0L

    val loaded: Boolean get() = programmes.isNotEmpty()

    fun forChannel(epgId: String?): List<EpgItem>? = epgId?.lowercase()?.let { programmes[it] }

    /** Every channel in the guide file: id (lower-case) → its name, and what's on each right now. */
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var nowOn: Map<String, String> = emptyMap()

    fun displayName(epgId: String?): String? = epgId?.lowercase()?.let { names[it] }
    fun nowOn(epgId: String?): String? = epgId?.lowercase()?.let { id ->
        val t = System.currentTimeMillis() / 1000
        programmes[id]?.firstOrNull { t >= it.start && t < it.end }?.title ?: nowOn[id]
    }
    val hasNames: Boolean get() = names.isNotEmpty()

    /** Re-reads the downloaded guide (no new download), e.g. after you pick a different guide for a channel. */
    fun reload() { loadedAt = 0L; load(false) }

    private val RX_TAG = Regex("^[a-z]{2,3}\\s*[:|\\-]\\s*")
    private val RX_BRACKETS = Regex("[\\[(].*?[\\])]")
    private val RX_SPLIT = Regex("[^a-z0-9]+")
    private val STOP = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "tv", "channel", "the", "raw", "backup", "vip", "live", "fps", "50fps", "60fps")

    private fun tokens(name: String): List<String> {
        var t = name.lowercase()
        t = RX_BRACKETS.replace(t, " ")
        t = RX_TAG.replace(t.trim(), "")
        return t.split(RX_SPLIT).filter { it.isNotBlank() && it !in STOP }
    }

    /** 0…1: how much two channel names have in common (words, word starts, or initials like "LFC"). */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        var hit = 0
        for (x in ta) if (tb.any { y -> y == x || (x.length >= 3 && y.startsWith(x)) || (y.length >= 3 && x.startsWith(y)) }) hit++
        val initialsA = ta.joinToString("") { it.take(1) }; val initialsB = tb.joinToString("") { it.take(1) }
        val initials = (ta.size == 1 && ta[0].length in 2..5 && tb.size > 1 && initialsB.startsWith(ta[0])) ||
            (tb.size == 1 && tb[0].length in 2..5 && ta.size > 1 && initialsA.startsWith(tb[0]))
        return maxOf(hit.toDouble() / ta.size, if (initials) 0.9 else 0.0)
    }

    /** True when this channel's guide clearly belongs to a different channel (nothing in common between the names). */
    fun mismatch(c: Channel): Boolean {
        val id = c.epgId?.lowercase() ?: return false
        if (c.epgId == NO_GUIDE) return false
        val g = names[id] ?: return false
        return similarity(c.baseName, g) == 0.0 && similarity(c.baseName, id.replace('.', ' ').replace('_', ' ')) == 0.0
    }

    data class Candidate(val id: String, val name: String, val now: String?, val score: Double)

    /** Guide channels whose names look like this channel's, best first. */
    fun candidates(c: Channel, limit: Int = 30): List<Candidate> {
        val n = c.baseName
        return names.entries.asSequence()
            .map { (id, name) -> Candidate(id, name, nowOn(id), maxOf(similarity(n, name), similarity(name, n) * 0.95)) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(limit).toList()
    }

    fun clear() { programmes = emptyMap(); _status.value = "Using the provider's basic guide"; _version.value++ }

    fun load(force: Boolean = false) {
        if (loading) return
        val prefs = app.sideline.tv.SidelineApp.prefs
        val repo = app.sideline.tv.SidelineApp.repo
        val source = prefs.guideSource.value
        if (source == "basic") { clear(); return }
        // Already have a fresh copy in memory: nothing to do.
        if (!force && programmes.isNotEmpty() && System.currentTimeMillis() - loadedAt < MAX_AGE) return
        loading = true
        scope.launch {
            try {
                val dir = (Net.cacheDir ?: File("/tmp")).resolve("guide").apply { mkdirs() }
                val sources: List<Pair<String, String>> = if (source == "custom") {
                    listOfNotNull(prefs.guideUrl.value.trim().takeIf { it.startsWith("http") }?.let { "custom" to it })
                } else {
                    prefs.accounts.value.filter { it.useLive }.mapNotNull { a ->
                        repo.client(a.id)?.let { c -> a.id to "${c.base}/xmltv.php?username=${enc(a.username)}&password=${enc(a.password)}" }
                    }
                }
                if (sources.isEmpty()) { _status.value = if (source == "custom") "Add a guide address first" else "No live account"; return@launch }

                // Only keep guide data for channels you can see.
                val wanted = repo.channels.value.mapNotNull { it.epgId?.lowercase() }.toHashSet()
                val from = System.currentTimeMillis() / 1000 - 12 * 3600
                val to = System.currentTimeMillis() / 1000 + 48 * 3600
                val merged = HashMap<String, MutableList<EpgItem>>()
                val allNames = HashMap<String, String>()
                val allNow = HashMap<String, String>()
                for ((id, url) in sources) {
                    val f = File(dir, "${id.hashCode()}.xml")
                    if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE) {
                        _status.value = "Downloading the TV guide…"
                        download(url, f)
                    }
                    _status.value = "Reading the TV guide…"
                    parse(f, wanted, from, to, merged, allNames, allNow)
                }
                merged.values.forEach { it.sortBy { p -> p.start } }
                programmes = merged
                names = allNames
                nowOn = allNow
                loadedAt = System.currentTimeMillis()
                val n = merged.values.sumOf { it.size }
                _status.value = if (n == 0) "The guide downloaded but had nothing for your channels" else
                    "Full guide: ${merged.size} channels, ${n} programmes (updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())})"
                _version.value++
            } catch (e: Exception) {
                _status.value = "Couldn't load the full guide (${e.message}). Using the basic guide."
            } finally { loading = false }
        }
    }

    /** Programmes whose title matches, now and upcoming first, then earlier ones (for catch-up). */
    fun search(query: String, limit: Int = 200): List<Pair<String, EpgItem>> {
        if (query.length < 2) return emptyList()
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Pair<String, EpgItem>>()
        for ((id, list) in programmes) for (p in list) if (p.title.contains(query, ignoreCase = true)) out += id to p
        return out.sortedWith(compareBy({ if (it.second.end <= now) 1 else 0 }, { if (it.second.end <= now) -it.second.start else it.second.start }))
            .take(limit)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun download(url: String, f: File) {
        val tmp = File(f.path + ".part")
        Net.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("server answered ${r.code}")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
        if (tmp.length() < 100) { tmp.delete(); throw RuntimeException("the guide was empty") }
        tmp.renameTo(f)
    }

    private fun open(f: File): InputStream {
        val b = BufferedInputStream(f.inputStream(), 64 * 1024)
        b.mark(2)
        val gz = b.read() == 0x1f && b.read() == 0x8b
        b.reset()
        return if (gz) BufferedInputStream(GZIPInputStream(b, 64 * 1024), 64 * 1024) else b
    }

    /** XMLTV time "20261109140000 -0500" → epoch seconds, plus the local "yyyy-MM-dd HH:mm:ss" as written (used for catch-up). */
    private fun time(s: String?): Pair<Long, String>? {
        if (s == null || s.length < 14) return null
        val y = s.substring(0, 4).toIntOrNull() ?: return null
        val mo = s.substring(4, 6).toIntOrNull() ?: return null
        val d = s.substring(6, 8).toIntOrNull() ?: return null
        val h = s.substring(8, 10).toIntOrNull() ?: return null
        val mi = s.substring(10, 12).toIntOrNull() ?: return null
        val se = s.substring(12, 14).toIntOrNull() ?: 0
        val tz = s.substring(14).trim()
        val offsetSec = if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val sign = if (tz[0] == '-') -1 else 1
            sign * ((tz.substring(1, 3).toIntOrNull() ?: 0) * 3600 + (tz.substring(3, 5).toIntOrNull() ?: 0) * 60)
        } else 0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear(); cal.set(y, mo - 1, d, h, mi, se)
        val epoch = cal.timeInMillis / 1000 - offsetSec
        return epoch to "%04d-%02d-%02d %02d:%02d:%02d".format(y, mo, d, h, mi, se)
    }

    private fun parse(f: File, wanted: Set<String>, from: Long, to: Long, into: HashMap<String, MutableList<EpgItem>>,
                      names: HashMap<String, String>, nowOn: HashMap<String, String>) {
        val nowS = System.currentTimeMillis() / 1000
        var chId: String? = null
        var isNow = false
        open(f).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var ev = p.eventType
            var ch: String? = null; var start: Pair<Long, String>? = null; var stop: Long = 0
            var title = ""; var desc = ""; var inProg = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> chId = p.getAttributeValue(null, "id")?.lowercase()
                        "display-name" -> chId?.let { id -> if (id !in names) names[id] = p.nextText().trim() }
                        "programme" -> {
                            chId = null
                            ch = p.getAttributeValue(null, "channel")?.lowercase()
                            inProg = ch != null && ch in wanted
                            isNow = false
                            if (inProg) {
                                start = time(p.getAttributeValue(null, "start"))
                                stop = time(p.getAttributeValue(null, "stop"))?.first ?: 0
                                if (start == null || stop < from || start.first > to) inProg = false
                            } else if (ch != null && ch !in nowOn) {
                                // Other channels: only what's on right now (so you can pick the right guide for a channel).
                                val st = time(p.getAttributeValue(null, "start"))?.first ?: 0
                                val en = time(p.getAttributeValue(null, "stop"))?.first ?: 0
                                isNow = nowS in st until en
                            }
                            title = ""; desc = ""
                        }
                        "title" -> if ((inProg || isNow) && title.isEmpty()) title = p.nextText().trim()
                        "desc" -> if (inProg && desc.isEmpty()) desc = p.nextText().trim().take(240)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "channel") {
                    chId = null
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    if (isNow && ch != null && title.isNotEmpty()) nowOn[ch] = title
                    isNow = false
                    val s = start
                    if (inProg && ch != null && s != null && title.isNotEmpty()) {
                        val list = into.getOrPut(ch) { ArrayList() }
                        if (list.size < 80) list += EpgItem(title.intern(), desc, s.second, s.first, stop)
                    }
                    inProg = false
                }
                ev = p.next()
            }
        }
    }
}
