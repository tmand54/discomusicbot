package app.snapstack.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Shared HTTP client: keeps connections warm (big win for scan latency), applies polite
 * per-host rate limits required by the public card APIs and retries transient failures.
 */
class Http(cacheDir: File?, userAgent: String) {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "http"), 32L * 1024 * 1024)) }
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json;q=0.9,*/*;q=0.8")
                    .build()
            )
        }
        .build()

    private val minIntervalMs = mapOf(
        "api.scryfall.com" to 100L,
        "db.ygoprodeck.com" to 60L,
        "api.lorcast.com" to 100L,
        "api.tcgdex.net" to 20L,
        "tcgcsv.com" to 50L,
    )
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val lastCall = ConcurrentHashMap<String, Long>()

    private suspend fun throttle(host: String) {
        val interval = minIntervalMs[host] ?: return
        hostLocks.getOrPut(host) { Mutex() }.withLock {
            val wait = (lastCall[host] ?: 0L) + interval - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastCall[host] = System.currentTimeMillis()
        }
    }

    /** Returns the body, or null on 404. Throws [IOException] on other failures after retries. */
    suspend fun get(url: String): String? = execute(Request.Builder().url(url).get().build())

    suspend fun postJson(url: String, body: JSONObject): String? = execute(
        Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
    )

    suspend fun getJson(url: String): JSONObject? = get(url)?.let { JSONObject(it) }
    suspend fun getJsonArray(url: String): JSONArray? = get(url)?.let { JSONArray(it) }

    private suspend fun execute(request: Request): String? = withContext(Dispatchers.IO) {
        val host = request.url.host
        var attempt = 0
        var lastError: IOException? = null
        while (attempt < 3) {
            throttle(host)
            try {
                client.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> return@withContext resp.body?.string()
                        resp.code == 404 -> return@withContext null
                        // YGOPRODeck answers "no card found" with 400.
                        resp.code == 400 && host == "db.ygoprodeck.com" -> return@withContext null
                        resp.code == 429 || resp.code >= 500 ->
                            lastError = HttpException(resp.code, "HTTP ${resp.code} from $host")
                        else -> throw HttpException(resp.code, "HTTP ${resp.code} from $host")
                    }
                }
            } catch (e: HttpException) {
                if (e.code in 400..499 && e.code != 429) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            attempt++
            delay(250L * attempt * attempt)
        }
        throw lastError ?: IOException("Request to $host failed")
    }
}
