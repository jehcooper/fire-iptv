package app.sideline.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Net {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; AFTKA) Sideline/1.0"

    val http: OkHttpClient = OkHttpClient.Builder()
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
