package com.noop.garmin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One entry of the Garmin Connect activity list (only the fields the sync uses). */
data class GarminActivity(
    val activityId: Long,
    /** Start, unix seconds UTC (from `startTimeGMT`). */
    val startTs: Long,
    /** Duration in seconds. */
    val durationS: Double,
    val typeKey: String?,
    val name: String?,
    val manufacturer: String?,
) {
    /** End of the activity window, unix seconds. A missing or nonsensical duration (not finite, <= 0, a
     *  week or more) leaves a zero-length window, like the Swift `ActivitySummary.endTs`. */
    val endTs: Long
        get() = if (!durationS.isFinite() || durationS <= 0 || durationS >= 7 * 86_400.0) startTs
        else startTs + com.noop.ingest.ActivityFileImporter.roundHalfAwayExact(durationS)
}

/** The calls the sync needs; [GarminApiClient] is the network implementation, tests pass a fake. */
interface GarminApi {
    suspend fun listActivities(start: Int, limit: Int): List<GarminActivity>
    suspend fun downloadFit(activityId: Long): ByteArray
}

/**
 * A failed Garmin call, carrying the user-facing [message] (and the HTTP code when there was one).
 * [fatal] failures (rejected key, rate limit, server or network trouble) end the sync; a non-fatal one
 * concerns a single activity (no file to download, a 404) and the sync skips that activity.
 */
class GarminApiException(val httpCode: Int?, message: String, val fatal: Boolean = true) : Exception(message) {
    companion object {
        /** Whether an HTTP status should end the whole sync rather than skip one activity. */
        fun isFatal(code: Int): Boolean = code == 401 || code == 403 || code == 429 || code !in 400..499
    }
}

/**
 * Garmin Connect Personal API client.
 *
 * PRIVACY / NETWORK: this is the only code that talks to Garmin. It sends the user's Personal API key (to
 * exchange it for a short-lived bearer token) and that bearer token (to list and download the user's own
 * activities) to [baseUrl], and nothing else: no NOOP data, no device identifiers, no analytics. It is
 * off until the user saves a key in Data Sources, and the key never leaves this process except in the
 * `Authorization` header to Garmin. Neither the key nor the token is logged.
 */
class GarminApiClient(
    private val apiKey: String,
    private val baseUrl: String = GarminConnect.API_BASE,
    private val client: OkHttpClient = defaultClient(),
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
) : GarminApi {

    private var cachedToken: String? = null
    private var cachedUntilS: Long = 0L

    /** A bearer token, reused from memory until its `exp` minus an hour. */
    suspend fun token(): String {
        cachedToken?.let { if (nowS() < cachedUntilS) return it }
        val request = Request.Builder()
            .url("$baseUrl/personalapitoken-service/personal-api/personal-api-key/token")
            .header("Accept", "*/*")
            .header("Authorization", "ApiKey $apiKey")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val body = execute(request, MAX_TEXT_BYTES)
        val jwt = parseToken(String(body, Charsets.UTF_8))
            ?: throw GarminApiException(null, UNEXPECTED_RESPONSE)
        val exp = jwtExpiry(jwt)
        cachedToken = jwt
        cachedUntilS = if (exp != null) exp - TOKEN_EARLY_REFRESH_S else 0L
        return jwt
    }

    override suspend fun listActivities(start: Int, limit: Int): List<GarminActivity> {
        val request = Request.Builder()
            .url("$baseUrl/activitylist-service/activities/search/activities?start=$start&limit=$limit")
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = execute(request, MAX_TEXT_BYTES)
        return try {
            parseActivityList(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            throw GarminApiException(null, UNEXPECTED_RESPONSE)
        }
    }

    override suspend fun downloadFit(activityId: Long): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/download-service/files/activity/$activityId")
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/zip, application/x-zip-compressed, application/octet-stream")
            .get()
            .build()
        val body = execute(request, MAX_DOWNLOAD_BYTES)
        return fitFromDownload(body)
            ?: throw GarminApiException(null, UNEXPECTED_RESPONSE, fatal = false)
    }

    private suspend fun execute(request: Request, cap: Int): ByteArray {
        val (code, bytes) = try {
            client.newCall(request).await().use { response ->
                val stream = response.body?.byteStream()
                val data = stream?.use { readCapped(it, cap) } ?: ByteArray(0)
                response.code to data
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: GarminApiException) {
            throw e
        } catch (e: IOException) {
            throw GarminApiException(null, "Couldn't reach Garmin Connect. Check your connection.")
        }
        if (code !in 200..299) throw GarminApiException(code, errorMessage(code), GarminApiException.isFatal(code))
        return bytes
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { response.close() } else response.close()
            }
        })
    }

    companion object {
        /** Activity download ceiling. */
        const val MAX_DOWNLOAD_BYTES = 32 shl 20

        /** Token / list response ceiling. */
        const val MAX_TEXT_BYTES = 4 shl 20

        /** Status text for a body Garmin sent that NOOP could not use (same text as the Swift side). */
        const val UNEXPECTED_RESPONSE = "Garmin Connect sent an unexpected response."

        /** Refresh the bearer token this long before its `exp`. */
        const val TOKEN_EARLY_REFRESH_S = 3600L

        private val GMT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        /** User-facing text for an HTTP failure. */
        fun errorMessage(code: Int): String = when (code) {
            401, 403 -> "Garmin rejected the API key. Create a new one in Garmin Connect."
            429 -> "Garmin's rate limit was hit. Try again later."
            else -> "Garmin Connect returned an error (HTTP $code)."
        }

        /** The `exp` claim (unix seconds) of a JWT, or null when it can't be read. Signature is not checked. */
        fun jwtExpiry(jwt: String): Long? = runCatching {
            val parts = jwt.trim().split('.')
            if (parts.size < 2) return null
            val payload = String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
            val obj = JSONObject(payload)
            // Same sanity range as the Swift twin.
            if (!obj.has("exp")) null else obj.getDouble("exp")
                .takeIf { it.isFinite() && it > 0 && it < 4_102_444_800.0 }?.toLong()
        }.getOrNull()

        /** Parse the activity-list JSON array. Entries without an id or a readable start are dropped. */
        fun parseActivityList(json: String): List<GarminActivity> {
            val arr = JSONArray(json)
            val out = ArrayList<GarminActivity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("activityId") || o.isNull("activityId")) continue
                // A number, or (tolerated, like Swift) a numeric string.
                val id = o.optLong("activityId", -1L).takeIf { it > 0 } ?: continue
                val start = parseGmt(o.optString("startTimeGMT", "")) ?: continue
                val duration = o.optDouble("duration", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
                val typeKey = o.optJSONObject("activityType")?.let { t -> t.optStringOrNull("typeKey") }
                out.add(
                    GarminActivity(
                        activityId = id,
                        startTs = start,
                        durationS = duration,
                        typeKey = typeKey,
                        name = o.optStringOrNull("activityName"),
                        manufacturer = o.optStringOrNull("manufacturer"),
                    ),
                )
            }
            return out
        }

        /**
         * "yyyy-MM-dd HH:mm:ss" (UTC) → unix seconds, or null. A `T` separator or a fractional part is
         * tolerated (only the first 19 characters are read), like the Swift `parseStartTimeGMT`.
         */
        fun parseGmt(raw: String): Long? = runCatching {
            val s = raw.trim()
            if (s.length < 19) return null
            val head = s.substring(0, 19).let { if (it[10] == 'T') it.replaceRange(10, 11, " ") else it }
            LocalDateTime.parse(head, GMT_FORMAT).toEpochSecond(ZoneOffset.UTC)
        }.getOrNull()

        /**
         * The bearer token from the token endpoint's body: trimmed, surrounding quotes dropped, and only
         * accepted when it has the three dot-separated JWT parts and no spaces. Null otherwise.
         */
        fun parseToken(body: String): String? {
            var s = body.trim()
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            if (s.split('.').size != 3 || s.contains(' ')) return null
            return s
        }

        /**
         * The FIT bytes inside a download: the first ZIP entry whose name ends with `.fit` (any case),
         * read up to [MAX_DOWNLOAD_BYTES]. A body that already is a FIT file is returned as is. Null when
         * neither.
         */
        fun fitFromDownload(body: ByteArray): ByteArray? {
            if (isFit(body)) return body
            return runCatching {
                ZipInputStream(ByteArrayInputStream(body)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".fit")) {
                            return@runCatching readCapped(zip, MAX_DOWNLOAD_BYTES)
                        }
                    }
                    null
                }
            }.getOrNull()
        }

        private fun isFit(b: ByteArray): Boolean =
            b.size >= 12 && b[8] == '.'.code.toByte() && b[9] == 'F'.code.toByte() &&
                b[10] == 'I'.code.toByte() && b[11] == 'T'.code.toByte()

        /** Read at most [cap] bytes; more than that fails the call rather than truncating silently. */
        internal fun readCapped(input: InputStream, cap: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) throw GarminApiException(null, UNEXPECTED_RESPONSE)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
