package com.cubicl.cubinavconnect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object Net {
    var githubToken: () -> String = {
        try {
            NavController.prefs.settings.value.githubToken
        } catch (_: Throwable) {
            ""
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

    fun request(url: String, userAgent: String): Request {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        val token = githubToken().trim()
        if (token.isNotEmpty()) {
            val host = try { url.toHttpUrl().host.lowercase() } catch (_: Throwable) { "" }
            if (host.contains("github.com") || host.contains("githubusercontent.com")) {
                builder.header("Authorization", "Bearer $token")
            }
        }
        return builder.build()
    }

    suspend fun get(url: String, userAgent: String): String = withContext(Dispatchers.IO) {
        val req = request(url, userAgent)
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, body)
            body
        }
    }
}

suspend fun Net.getBytes(
    url: String,
    userAgent: String,
    maxBytes: Long,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): ByteArray = withContext(Dispatchers.IO) {
    val req = Net.request(url, userAgent)
    client.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw Net.HttpException(r.code, "")
        val body = r.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        if (total > maxBytes) throw IOException("File is larger than the allowed maximum ($maxBytes B)")
        val out = java.io.ByteArrayOutputStream(if (total in 1..maxBytes) total.toInt() else 64 * 1024)
        val buf = ByteArray(16 * 1024)
        var n = 0L
        var lastReport = 0L
        body.byteStream().use { ins ->
            while (true) {
                val k = ins.read(buf)
                if (k < 0) break
                n += k
                if (n > maxBytes) throw IOException("File is larger than the allowed maximum ($maxBytes B)")
                out.write(buf, 0, k)
                if (n - lastReport >= 32 * 1024) { lastReport = n; onProgress(n, total) }
            }
        }
        onProgress(n, total)
        out.toByteArray()
    }
}

class Place(val name: String, val type: String, val lat: Double, val lon: Double)

class RateLimitedException : Exception("Nominatim rate limit (HTTP 429)")

/** README 3.2. Search on demand only, max 1 request/s, cached, real User-Agent. */
object Geocoder {
    private val cache = HashMap<String, List<Place>>()
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(baseUrl: String, userAgent: String, query: String): List<Place> {
        val q = query.trim()
        cache[q.lowercase()]?.let { return it }
        return mutex.withLock {
            val wait = lastRequestAt + 1100 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
            val url = baseUrl.trimEnd('/').plus("/search").toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "5")
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("accept-language", Locale.getDefault().language)
                .build().toString()
            val body = try {
                Net.get(url, userAgent)
            } catch (e: Net.HttpException) {
                when (e.code) {
                    429 -> throw RateLimitedException()
                    403 -> throw IllegalStateException(
                        "Nominatim rejected the request (403). Set a real contact in Settings > User-Agent.")
                    else -> throw e
                }
            }
            val arr = JSONArray(body)
            val out = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.optString("display_name"), o.optString("type"), o.getDouble("lat"), o.getDouble("lon"))
            }
            cache[q.lowercase()] = out
            out
        }
    }
}

data class Preset(val id: String, val label: String, val url: String)

/** README 5.2: what the OSRM server can actually do (capabilities.json next to OSRM). */
class Capabilities(
    val classes: Set<String>,
    val exclude: List<Set<String>>,
    val presets: List<Preset>,
) {
    fun supports(ex: Set<String>) = ex.isEmpty() || exclude.any { it == ex }
}

object Router {
    suspend fun capabilities(url: String, userAgent: String): Capabilities? = try {
        val o = JSONObject(Net.get(url, userAgent))
        val classes = o.optJSONArray("classes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val ex = o.optJSONArray("exclude")?.let { a ->
            (0 until a.length()).map { i ->
                val inner = a.getJSONArray(i)
                (0 until inner.length()).map { inner.getString(it) }.toSet()
            }
        } ?: emptyList()
        val presets = o.optJSONArray("presets")?.let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Preset(p.getString("id"), p.optString("label", p.getString("id")), p.getString("url"))
            }
        } ?: emptyList()
        Capabilities(classes, ex, presets)
    } catch (e: Exception) {
        null
    }

    private fun f(v: Double) = String.format(Locale.US, "%.6f", v)

    /** README 5.1. Coordinates are lon,lat. [bearing] is only sent on reroutes. */
    suspend fun route(
        base: String,
        profile: String,
        userAgent: String,
        from: LatLon,
        to: LatLon,
        exclude: Set<String>,
        bearing: Int?,
        via: List<LatLon> = emptyList(),
        withNodes: Boolean = false,
    ): Route {
        val coords = (listOf(from) + via + to).joinToString(";") { f(it.lon) + "," + f(it.lat) }
        val sb = StringBuilder(base.trimEnd('/'))
            .append("/route/v1/").append(profile).append('/').append(coords)
            .append("?overview=full&geometries=polyline6&steps=true&annotations=").append(if (withNodes) "nodes" else "false")
        if (exclude.isNotEmpty()) sb.append("&exclude=").append(exclude.sorted().joinToString(","))
        if (bearing != null) sb.append("&bearings=").append(((bearing % 360) + 360) % 360).append(",45").append(";".repeat(via.size + 1))
        sb.append("&radiuses=100").append(";500".repeat(via.size)).append(";1000")
        val body = try {
            Net.get(sb.toString(), userAgent)
        } catch (e: Net.HttpException) {
            // OSRM answers NoRoute / NoSegment with HTTP 400 and a JSON body.
            if (e.code in 400..499 && e.body.trimStart().startsWith("{")) e.body else throw e
        }
        return RouteParser.parse(body, if (withNodes) SpeedDb::lookup else null)
    }
}