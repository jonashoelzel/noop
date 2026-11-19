package com.noop.garmin

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The pure parts of the Garmin Personal API client (JWT expiry, list JSON, ZIP entry choice, error text)
 * plus the request flow through an OkHttp interceptor that answers locally. Never touches the network.
 */
class GarminApiClientTest {

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"RS256\"}".toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + ".c2ln"
    }

    @Test
    fun jwtExpiryReadsExpClaim() {
        assertEquals(1_800_000_000L, GarminApiClient.jwtExpiry(jwt("{\"sub\":\"x\",\"exp\":1800000000}")))
        assertNull(GarminApiClient.jwtExpiry(jwt("{\"sub\":\"x\"}")))
        assertNull(GarminApiClient.jwtExpiry("not-a-jwt"))
        assertNull(GarminApiClient.jwtExpiry("a.%%%.c"))
    }

    @Test
    fun parsesActivityList() {
        val json = """
            [
              {"activityId": 25077379197, "activityName": "Dresden Road Cycling",
               "startTimeGMT": "2026-11-15 09:12:03", "duration": 10950.123,
               "activityType": {"typeKey": "road_biking", "typeId": 10}, "manufacturer": "GARMIN"},
              {"activityId": 2, "startTimeGMT": "bogus", "duration": 10.0},
              {"activityName": "no id", "startTimeGMT": "2026-11-15 09:12:03"},
              {"activityId": 3, "startTimeGMT": "2026-11-16 00:00:00", "activityType": null}
            ]
        """.trimIndent()
        val list = GarminApiClient.parseActivityList(json)
        assertEquals(listOf(25_077_379_197L, 3L), list.map { it.activityId })
        val a = list[0]
        assertEquals(1_794_733_923L, a.startTs) // 2026-11-15T09:12:03Z
        assertEquals(10_950.123, a.durationS, 1e-9)
        assertEquals(a.startTs + 10_950L, a.endTs)
        assertEquals("road_biking", a.typeKey)
        assertEquals("Dresden Road Cycling", a.name)
        assertEquals("GARMIN", a.manufacturer)
        assertNull(list[1].typeKey)
        assertEquals(0.0, list[1].durationS, 0.0)
    }

    @Test
    fun tokenBodyAndStartTimeAndEndTolerances() {
        assertEquals("a.b.c", GarminApiClient.parseToken("  \"a.b.c\"\n"))
        assertNull(GarminApiClient.parseToken("a.b"))
        assertNull(GarminApiClient.parseToken("a.b c.d"))
        assertEquals(1_794_733_923L, GarminApiClient.parseGmt("2026-11-15T09:12:03.0"))
        assertNull(GarminApiClient.parseGmt("2026-11-15"))
        val a = GarminActivity(1, 1_000, 10.5, null, null, null)
        assertEquals(1_011L, a.endTs)                                   // rounded half away from zero
        assertEquals(1_000L, a.copy(durationS = Double.NaN).endTs)
        assertEquals(1_000L, a.copy(durationS = -5.0).endTs)
        assertEquals(1_000L, a.copy(durationS = 7 * 86_400.0).endTs)
    }

    private fun fitBytes(tag: Byte): ByteArray =
        byteArrayOf(14, 0x20, 0, 0, 0, 0, 0, 0, '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), tag)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun picksFirstFitEntryCaseInsensitive() {
        val body = zip("readme.txt" to "hi".toByteArray(), "123_ACTIVITY.FIT" to fitBytes(1), "b.fit" to fitBytes(2))
        assertArrayEquals(fitBytes(1), GarminApiClient.fitFromDownload(body))
        assertNull(GarminApiClient.fitFromDownload(zip("a.gpx" to "x".toByteArray())))
        assertNull(GarminApiClient.fitFromDownload("garbage".toByteArray()))
        // A body that already is a FIT file passes through.
        assertArrayEquals(fitBytes(3), GarminApiClient.fitFromDownload(fitBytes(3)))
    }

    @Test
    fun errorTextAndFatality() {
        assertEquals("Garmin rejected the API key. Create a new one in Garmin Connect.", GarminApiClient.errorMessage(401))
        assertEquals("Garmin rejected the API key. Create a new one in Garmin Connect.", GarminApiClient.errorMessage(403))
        assertEquals("Garmin's rate limit was hit. Try again later.", GarminApiClient.errorMessage(429))
        assertTrue(GarminApiClient.errorMessage(500).contains("500"))
        assertTrue(GarminApiException.isFatal(401))
        assertTrue(GarminApiException.isFatal(429))
        assertTrue(GarminApiException.isFatal(503))
        assertFalse(GarminApiException.isFatal(404))
    }

    /** An OkHttp client whose every call is answered by [handler], with the requests recorded. */
    private class FakeServer(val handler: (Request) -> Pair<Int, ByteArray>) {
        val requests = ArrayList<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            requests += req
            val (code, body) = handler(req)
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }).build()
    }

    @Test
    fun tokenIsCachedUntilAnHourBeforeExpiry() = runBlocking {
        var now = 1_000_000L
        val token = jwt("{\"exp\":${1_000_000L + 7200}}")
        val server = FakeServer { req ->
            when {
                req.url.encodedPath.endsWith("/personal-api-key/token") -> 200 to "  $token\n".toByteArray()
                req.url.encodedPath.endsWith("/activities/search/activities") -> 200 to "[]".toByteArray()
                else -> 404 to ByteArray(0)
            }
        }
        val c = GarminApiClient("KEY123", "https://example.test", server.client) { now }
        c.listActivities(0, 20)
        c.listActivities(20, 20)
        val tokenCalls = server.requests.count { it.url.encodedPath.endsWith("/token") }
        assertEquals(1, tokenCalls)
        val tokenReq = server.requests.first()
        assertEquals("POST", tokenReq.method)
        assertEquals("ApiKey KEY123", tokenReq.header("Authorization"))
        assertEquals("*/*", tokenReq.header("Accept"))
        val list = server.requests.last()
        assertEquals("Bearer $token", list.header("Authorization"))
        assertEquals("application/json", list.header("Accept"))
        assertEquals("20", list.url.queryParameter("start"))
        assertEquals("20", list.url.queryParameter("limit"))
        // Past exp - 3600 s the token is fetched again.
        now += 3601
        c.listActivities(0, 20)
        assertEquals(2, server.requests.count { it.url.encodedPath.endsWith("/token") })
    }

    @Test
    fun downloadUnzipsAndMapsErrors() = runBlocking {
        val server = FakeServer { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/token") -> 200 to jwt("{\"exp\":9999999999}").toByteArray()
                path.endsWith("/activity/7") -> 200 to zip("7_ACTIVITY.fit" to fitBytes(7))
                path.endsWith("/activity/8") -> 404 to ByteArray(0)
                path.endsWith("/activity/9") -> 401 to ByteArray(0)
                else -> 500 to ByteArray(0)
            }
        }
        val c = GarminApiClient("K", "https://example.test", server.client)
        assertArrayEquals(fitBytes(7), c.downloadFit(7))
        val dl = server.requests.last()
        assertEquals("/download-service/files/activity/7", dl.url.encodedPath)
        assertEquals("application/zip, application/x-zip-compressed, application/octet-stream", dl.header("Accept"))
        try {
            c.downloadFit(8); fail()
        } catch (e: GarminApiException) {
            assertEquals(404, e.httpCode); assertFalse(e.fatal)
        }
        try {
            c.downloadFit(9); fail()
        } catch (e: GarminApiException) {
            assertTrue(e.fatal)
            assertEquals("Garmin rejected the API key. Create a new one in Garmin Connect.", e.message)
        }
    }
}
