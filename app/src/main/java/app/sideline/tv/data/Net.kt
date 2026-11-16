package app.sideline.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

object Net {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; AFTKA) Sideline/1.0"

    /**
     * TMDB is blocked by DNS on some internet providers (e.g. some in India). For TMDB's hosts only, look the
     * address up through Cloudflare's or Google's secure DNS first, then fall back to the normal lookup.
     * Everything else (your IPTV streams) uses the normal lookup.
     */
    private object SmartDns : okhttp3.Dns {
        private val bootstrap = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        private val resolvers: List<okhttp3.Dns> by lazy {
            listOf(
                okhttp3.dnsoverhttps.DnsOverHttps.Builder().client(bootstrap)
                    .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(java.net.InetAddress.getByName("1.1.1.1"), java.net.InetAddress.getByName("1.0.0.1")).build(),
                okhttp3.dnsoverhttps.DnsOverHttps.Builder().client(bootstrap)
                    .url("https://dns.google/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(java.net.InetAddress.getByName("8.8.8.8"), java.net.InetAddress.getByName("8.8.4.4")).build(),
            )
        }
        private fun special(host: String) = host.endsWith("tmdb.org") || host.endsWith("themoviedb.org")
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (special(hostname)) {
                for (r in resolvers) {
                    try { val a = r.lookup(hostname); if (a.isNotEmpty()) return a } catch (_: Exception) { }
                }
            }
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(SmartDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where big provider lists are kept between launches (set by the app at start-up). */
    @Volatile var cacheDir: java.io.File? = null

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Server answered ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    suspend fun getJson(url: String): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Server answered ${resp.code}")
            val body = resp.body?.string().orEmpty()
            json.parseToJsonElement(body.ifBlank { "null" })
        }
    }
}
