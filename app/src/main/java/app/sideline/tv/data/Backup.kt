package app.sideline.tv.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Backup & restore of everything you've set up — accounts, favourites, hidden and renamed channels,
 * teams, reminders, progress, keys — as one file.
 *
 * While the Backup page is open in Settings, the stick serves a small web page on your home Wi-Fi.
 * Open the address it shows on a phone or computer to download the backup, or upload one to restore.
 * The address includes a random 4-digit code and the page closes when you leave that screen.
 */
object Backup {
    /** SharedPreferences files that make up a backup ("omdb" is just a cache, so it's left out). */
    private val FILES = listOf("sideline", "progress", "resume", "reminders")

    // ---------------- Export / import ----------------

    fun export(ctx: Context): String {
        val root = buildJsonObject {
            put("app", "Cooper TV")
            put("format", 1)
            put("created", System.currentTimeMillis())
            putJsonObject("prefs") {
                for (name in FILES) {
                    val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                    putJsonObject(name) {
                        for ((k, v) in sp.all) {
                            putJsonObject(k) {
                                when (v) {
                                    is String -> { put("t", "s"); put("v", v) }
                                    is Boolean -> { put("t", "b"); put("v", v) }
                                    is Int -> { put("t", "i"); put("v", v) }
                                    is Long -> { put("t", "l"); put("v", v) }
                                    is Float -> { put("t", "f"); put("v", v) }
                                    is Set<*> -> { put("t", "ss"); putJsonArray("v") { v.forEach { add(JsonPrimitive(it.toString())) } } }
                                }
                            }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    /** Replaces the current setup with the backup. Throws with a readable message if the file isn't a Cooper TV backup. */
    fun import(ctx: Context, text: String) {
        val root = try { Net.json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw IllegalArgumentException("That isn't a Cooper TV backup file") }
        if (root["prefs"] !is JsonObject) throw IllegalArgumentException("That isn't a Cooper TV backup file")
        val prefs = root["prefs"]!!.jsonObject
        for (name in FILES) {
            val data = prefs[name] as? JsonObject ?: continue
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((k, el) in data) {
                val o = el as? JsonObject ?: continue
                val t = o["t"]?.jsonPrimitive?.contentOrNull
                val v = o["v"] ?: continue
                when (t) {
                    "s" -> e.putString(k, v.jsonPrimitive.contentOrNull)
                    "b" -> e.putBoolean(k, v.jsonPrimitive.booleanOrNull ?: false)
                    "i" -> e.putInt(k, v.jsonPrimitive.contentOrNull?.toIntOrNull() ?: 0)
                    "l" -> e.putLong(k, v.jsonPrimitive.contentOrNull?.toLongOrNull() ?: 0L)
                    "f" -> e.putFloat(k, v.jsonPrimitive.contentOrNull?.toFloatOrNull() ?: 0f)
                    "ss" -> e.putStringSet(k, v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet())
                }
            }
            e.commit()
        }
    }

    /** Restart the app so everything reloads from the restored settings. */
    fun restartApp(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        val pi = PendingIntent.getActivity(ctx, 7, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------------- Little web page on the home network ----------------

    private var server: ServerSocket? = null
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    var address: String? = null; private set

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    fun start(ctx: Context): String? {
        stop()
        val ip = localIp() ?: run { _status.value = "The stick doesn't seem to be on a network."; return null }
        val pin = (1000..9999).random().toString()
        val ss = try { ServerSocket(8642) } catch (e: Exception) { ServerSocket(0) }
        server = ss
        val app = ctx.applicationContext
        address = "http://$ip:${ss.localPort}/$pin/"
        _status.value = "Waiting for your phone or computer…"
        thread(name = "backup-server", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { handle(app, s, pin) } catch (_: Exception) { } finally { try { s.close() } catch (_: Exception) { } } }
            }
        }
        return address
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
        address = null
    }

    private fun handle(ctx: Context, s: Socket, pin: String) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: "/"
        val out = s.getOutputStream()
        if (!path.startsWith("/$pin/")) { respond(out, 404, "text/plain", "Not found".toByteArray()); return }
        val sub = path.removePrefix("/$pin/")
        when {
            method == "GET" && (sub == "" || sub == "index.html") -> respond(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
            method == "GET" && sub.startsWith("cooper-tv-backup") -> {
                _status.value = "Backup downloaded."
                val name = "cooper-tv-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
                respond(out, 200, "application/json", export(ctx).toByteArray(), "attachment; filename=\"$name\"")
            }
            method == "POST" && sub == "restore" -> {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0 || len > 20 * 1024 * 1024) { respond(out, 400, "text/plain", "No file received".toByteArray()); return }
                val body = ByteArray(len); var read = 0
                while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
                try {
                    import(ctx, String(body, 0, read, Charsets.UTF_8))
                    respond(out, 200, "text/plain", "Restored. Cooper TV is restarting on the TV.".toByteArray())
                    _status.value = "Restored. Restarting…"
                    Thread.sleep(1200)
                    restartApp(ctx)
                } catch (e: Exception) {
                    respond(out, 400, "text/plain", (e.message ?: "Restore failed").toByteArray())
                }
            }
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, disposition: String? = null) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val h = StringBuilder("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
        if (disposition != null) h.append("Content-Disposition: $disposition\r\n")
        h.append("\r\n")
        out.write(h.toString().toByteArray()); out.write(body); out.flush()
    }

    private const val PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cooper TV backup</title>
<style>
body{margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0b0f14;color:#e8eef3;display:flex;justify-content:center}
main{max-width:520px;padding:32px 20px}
h1{font-size:26px;margin:0 0 4px}h1 span{color:#22d3ee;font-weight:300}
p{color:#8fa3b5;line-height:1.5}
.card{background:#131a22;border-radius:16px;padding:20px;margin:18px 0}
a.btn,button{display:inline-block;background:#2ee59d;color:#0b0f14;font-weight:700;border:0;border-radius:999px;padding:12px 22px;font-size:16px;text-decoration:none;cursor:pointer}
button.warn{background:#ff4d5e;color:#fff}
#msg{margin-top:12px;color:#ffc94d}
input{margin:10px 0;color:#e8eef3}
</style></head><body><main>
<h1>COOPER <span>TV</span></h1>
<p>Backup & restore for the Cooper TV app on your Fire TV.</p>
<div class="card"><h3>Download a backup</h3>
<p>Saves your accounts, favourites, hidden and renamed channels, teams, reminders, progress and keys. It includes your logins, so keep the file private.</p>
<a class="btn" href="cooper-tv-backup.json" download>Download backup</a></div>
<div class="card"><h3>Restore from a backup</h3>
<p>This replaces everything currently set up on the TV, then restarts the app.</p>
<input type="file" id="f" accept=".json,application/json"><br>
<button class="warn" onclick="up()">Restore this backup</button><div id="msg"></div></div>
<script>
async function up(){const f=document.getElementById('f').files[0];const m=document.getElementById('msg');
if(!f){m.textContent='Choose a backup file first.';return}
if(!confirm('Replace everything on the TV with this backup?'))return;
m.textContent='Sending…';
try{const r=await fetch('restore',{method:'POST',headers:{'Content-Type':'application/json'},body:await f.text()});m.textContent=await r.text()}
catch(e){m.textContent='Sent. If the TV restarted, the restore worked.'}}
</script></main></body></html>"""
}
