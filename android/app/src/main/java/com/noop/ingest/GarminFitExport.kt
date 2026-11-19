package com.noop.ingest

import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Build a full-fidelity FIT activity that Garmin Connect accepts as a device recording: one record per
 * second carrying the strap's measured heart rate, the GPS position (interpolated between real timed fixes),
 * altitude, cumulative distance and speed, plus the file_id / file_creator / device_info / timer events /
 * lap / session / activity messages Garmin Connect's importer expects.
 *
 * Byte-for-byte twin of the Swift `GarminFitExporter` (Packages/StrandImport). `GarminFitExportTest`
 * asserts the hex the Swift oracle printed, and the Swift test asserts the same literals.
 *
 * Fork feature (jonashoelzel/noop): the user uploads the file through Garmin Connect's web "Import Data"
 * page; Garmin Connect then forwards it to Strava. The layout reproduces the file verified against Garmin
 * Connect on 2026-11-19 (accepted, "Summary Data: Original", HR graph, badges, forwarded to Strava).
 *
 * [DeviceIdentity] is written into file_id and device_info. Garmin Connect only credits badges for files
 * that name a Garmin product, so the user picks which identity the file claims; [DeviceIdentity.NOOP]
 * makes no Garmin claim.
 */
object GarminFitExport {

    /** One timed GPS fix. [t] is Unix seconds; [altM] metres above sea level when the fix had one. */
    data class TrackPoint(val t: Long, val lat: Double, val lon: Double, val altM: Double? = null)

    /** One heart-rate sample: Unix seconds + bpm. */
    data class HrPoint(val t: Long, val bpm: Int)

    /** The device the file claims to come from. [key] is persisted as the user's setting; never change it. */
    enum class DeviceIdentity(
        val key: String,
        val manufacturer: Int,
        val product: Int,
        val serial: Long,
        /** Software version x100 (FIT scale 100). */
        val softwareVersion: Int,
    ) {
        FORERUNNER_945("forerunner945", 1, 3113, 3_989_412_345L, 2000),
        TACX_TRAINING_APP("tacxTrainingApp", 89, 20533, 1L, 130),
        NOOP("noop", 255, 0, 0L, 100);

        companion object {
            val DEFAULT = FORERUNNER_945
            fun fromKey(key: String?): DeviceIdentity = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    /** Everything the file is built from. */
    data class Workout(
        val startTs: Long,
        val endTs: Long,
        /** Moving/timer seconds when the workout had pauses; null means the whole window. */
        val timerS: Double? = null,
        val sport: String?,
        val energyKcal: Double? = null,
        /** Timed GPS fixes. Empty for an indoor / non-GPS workout. */
        val track: List<TrackPoint>,
        /** HR samples. Duplicate timestamps: the last one wins. */
        val hr: List<HrPoint>,
    )

    /** Longest window exported, one record per second (a day). */
    const val MAX_SECONDS = 86_400L

    /** Suggested file name, stable per workout. */
    fun fileName(startTs: Long): String = "noop-$startTs-garmin.fit"

    /** A legacy route with no per-point times: spread evenly across the window, as [RouteExport] does. */
    fun untimedTrack(points: List<RouteExport.Point>, startTs: Long, endTs: Long): List<TrackPoint> {
        val times = RouteExport.interpolatedTimes(points.size, startTs, endTs)
        return points.indices.map { TrackPoint(times[it], points[it].lat, points[it].lon) }
    }

    private class Sample(
        val t: Long,
        val lat: Double?,
        val lon: Double?,
        val altM: Double?,
        val bpm: Int?,
        val distM: Double?,
        var speed: Double? = null,
    )

    fun build(w: Workout, device: DeviceIdentity, utcOffsetS: Int): ByteArray {
        val start = w.startTs
        val end = maxOf(w.startTs, minOf(w.endTs, w.startTs + MAX_SECONDS))
        val elapsed = end - start
        val canon = RouteExport.canonicalSport(w.sport)

        val hrBySecond = HashMap<Long, Int>()
        for (p in w.hr) if (p.t in start..end) hrBySecond[p.t] = p.bpm
        val track = w.track.filter { it.lat.isFinite() && it.lon.isFinite() }.sortedBy { it.t }

        val recs = ArrayList<Sample>((elapsed + 1).toInt())
        var j = 0
        var dist = 0.0
        var prevLat: Double? = null
        var prevLon = 0.0
        for (s in start..end) {
            var lat: Double? = null
            var lon: Double? = null
            var alt: Double? = null
            if (track.isNotEmpty() && s >= track.first().t && s <= track.last().t) {
                while (j < track.size - 2 && track[j + 1].t <= s) j++
                val a = track[j]
                val b = track[minOf(j + 1, track.size - 1)]
                val f = if (b.t == a.t) 0.0 else (s - a.t).toDouble() / (b.t - a.t).toDouble()
                lat = a.lat + (b.lat - a.lat) * f
                lon = a.lon + (b.lon - a.lon) * f
                if (a.altM != null && b.altM != null) alt = a.altM + (b.altM - a.altM) * f
            }
            if (lat != null && lon != null) {
                if (prevLat != null) dist += haversineMeters(prevLat, prevLon, lat, lon)
                prevLat = lat; prevLon = lon
            }
            val bpm = hrBySecond[s]?.takeIf { it in 1..254 }
            recs.add(Sample(s, lat, lon, alt, bpm, if (track.isEmpty()) null else dist))
        }
        if (track.isNotEmpty()) {
            for (i in recs.indices) {
                val lo = maxOf(0, i - 5)
                val hi = minOf(recs.size - 1, i + 5)
                val dt = recs[hi].t - recs[lo].t
                recs[i].speed = if (dt > 0) (recs[hi].distM!! - recs[lo].distM!!) / dt.toDouble() else 0.0
            }
        }

        val bpms = recs.mapNotNull { it.bpm }
        val avgHr: Int? = if (bpms.isEmpty()) null else (2 * bpms.sum() + bpms.size) / (2 * bpms.size)
        val maxHr: Int? = bpms.maxOrNull()
        val totalDist: Double? = if (track.isEmpty()) null else dist
        val avgSpeed: Double? = totalDist?.let { if (elapsed > 0) it / elapsed.toDouble() else 0.0 }
        val maxSpeed: Double? = if (track.isEmpty()) null else recs.mapNotNull { it.speed }.maxOrNull()
        val elapsedMs = elapsed * 1000L
        val timerMs: Long = w.timerS?.takeIf { it.isFinite() && it > 0 && it <= elapsed.toDouble() }
            ?.let { roundHalfAway(it * 1000) } ?: elapsedMs
        val startRec = recs.firstOrNull { it.lat != null }
        val endRec = recs.lastOrNull { it.lat != null }
        val fs = fitTime(start)
        val fe = fitTime(end)

        val b = ByteArrayOutputStream(1024 + recs.size * 23)
        // file_id (0)
        b.defn(0, 0, listOf(F(0, 1, ENUM), F(1, 2, U16), F(2, 2, U16), F(3, 4, U32Z), F(4, 4, U32)))
        b.write(0); b.u8(4); b.u16(device.manufacturer); b.u16(device.product); b.u32(device.serial); b.u32(fs)
        // file_creator (49)
        b.defn(1, 49, listOf(F(0, 2, U16), F(1, 1, U8)))
        b.write(1); b.u16(device.softwareVersion); b.u8(0xFF)
        // device_info (23), the creator device
        b.defn(2, 23, listOf(F(253, 4, U32), F(0, 1, U8), F(2, 2, U16), F(3, 4, U32Z), F(4, 2, U16), F(5, 2, U16)))
        b.write(2); b.u32(fs); b.u8(0); b.u16(device.manufacturer); b.u32(device.serial)
        b.u16(device.product); b.u16(device.softwareVersion)
        // event (21): timer start
        b.defn(3, 21, listOf(F(253, 4, U32), F(0, 1, ENUM), F(1, 1, ENUM), F(4, 1, U8)))
        b.write(3); b.u32(fs); b.u8(0); b.u8(0); b.u8(0)
        // record (20), one per second
        b.defn(4, 20, listOf(F(253, 4, U32), F(0, 4, S32), F(1, 4, S32), F(2, 2, U16), F(3, 1, U8),
            F(5, 4, U32), F(6, 2, U16)))
        for (r in recs) {
            b.write(4)
            b.u32(fitTime(r.t))
            b.s32(r.lat?.let { semicircles(it) } ?: Int.MAX_VALUE)
            b.s32(r.lon?.let { semicircles(it) } ?: Int.MAX_VALUE)
            b.u16(r.altM?.let { altitude(it) } ?: 0xFFFF)
            b.u8(r.bpm ?: 0xFF)
            b.u32(r.distM?.let { centimetres(it) } ?: 0xFFFFFFFFL)
            b.u16(r.speed?.let { mmPerSecond(it) } ?: 0xFFFF)
        }
        // event (21): timer stop_all
        b.write(3); b.u32(fe); b.u8(0); b.u8(4); b.u8(0)
        // lap (19)
        b.defn(5, 19, listOf(F(254, 2, U16), F(253, 4, U32), F(0, 1, ENUM), F(1, 1, ENUM), F(2, 4, U32),
            F(3, 4, S32), F(4, 4, S32), F(5, 4, S32), F(6, 4, S32), F(7, 4, U32), F(8, 4, U32),
            F(9, 4, U32), F(11, 2, U16), F(13, 2, U16), F(14, 2, U16), F(15, 1, U8), F(16, 1, U8),
            F(24, 1, ENUM), F(25, 1, ENUM)))
        b.write(5); b.u16(0); b.u32(fe); b.u8(9); b.u8(1); b.u32(fs)
        b.s32(startRec?.lat?.let { semicircles(it) } ?: Int.MAX_VALUE)
        b.s32(startRec?.lon?.let { semicircles(it) } ?: Int.MAX_VALUE)
        b.s32(endRec?.lat?.let { semicircles(it) } ?: Int.MAX_VALUE)
        b.s32(endRec?.lon?.let { semicircles(it) } ?: Int.MAX_VALUE)
        b.u32(elapsedMs); b.u32(timerMs)
        b.u32(totalDist?.let { centimetres(it) } ?: 0xFFFFFFFFL)
        b.u16(w.energyKcal?.let { kcal(it) } ?: 0xFFFF)
        b.u16(avgSpeed?.let { mmPerSecond(it) } ?: 0xFFFF)
        b.u16(maxSpeed?.let { mmPerSecond(it) } ?: 0xFFFF)
        b.u8(avgHr ?: 0xFF); b.u8(maxHr ?: 0xFF)
        b.u8(0); b.u8(RouteExport.fitSport(canon))
        // session (18)
        b.defn(6, 18, listOf(F(254, 2, U16), F(253, 4, U32), F(0, 1, ENUM), F(1, 1, ENUM), F(2, 4, U32),
            F(3, 4, S32), F(4, 4, S32), F(5, 1, ENUM), F(6, 1, ENUM), F(7, 4, U32), F(8, 4, U32),
            F(9, 4, U32), F(11, 2, U16), F(14, 2, U16), F(15, 2, U16), F(16, 1, U8), F(17, 1, U8),
            F(25, 2, U16), F(26, 2, U16), F(28, 1, ENUM)))
        b.write(6); b.u16(0); b.u32(fe); b.u8(8); b.u8(1); b.u32(fs)
        b.s32(startRec?.lat?.let { semicircles(it) } ?: Int.MAX_VALUE)
        b.s32(startRec?.lon?.let { semicircles(it) } ?: Int.MAX_VALUE)
        b.u8(RouteExport.fitSport(canon)); b.u8(0)
        b.u32(elapsedMs); b.u32(timerMs)
        b.u32(totalDist?.let { centimetres(it) } ?: 0xFFFFFFFFL)
        b.u16(w.energyKcal?.let { kcal(it) } ?: 0xFFFF)
        b.u16(avgSpeed?.let { mmPerSecond(it) } ?: 0xFFFF)
        b.u16(maxSpeed?.let { mmPerSecond(it) } ?: 0xFFFF)
        b.u8(avgHr ?: 0xFF); b.u8(maxHr ?: 0xFF)
        b.u16(0); b.u16(1); b.u8(0)
        // activity (34)
        b.defn(7, 34, listOf(F(253, 4, U32), F(0, 4, U32), F(1, 2, U16), F(2, 1, ENUM), F(3, 1, ENUM),
            F(4, 1, ENUM), F(5, 4, U32)))
        b.write(7); b.u32(fe); b.u32(timerMs); b.u16(1); b.u8(0); b.u8(26); b.u8(1)
        b.u32((fe + utcOffsetS).coerceIn(0L, 0xFFFFFFFFL))

        val body = b.toByteArray()
        val header = ByteArrayOutputStream(14)
        header.u8(14); header.u8(0x20); header.u16(2140); header.u32(body.size.toLong())
        header.write(byteArrayOf('.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte()))
        header.u16(RouteExport.fitCrc(header.toByteArray()))
        val out = ByteArrayOutputStream(16 + body.size)
        out.write(header.toByteArray()); out.write(body)
        out.u16(RouteExport.fitCrc(out.toByteArray()))
        return out.toByteArray()
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────────

    private const val ENUM = 0x00
    private const val U8 = 0x02
    private const val S32 = 0x85
    private const val U16 = 0x84
    private const val U32 = 0x86
    private const val U32Z = 0x8C
    private const val FIT_EPOCH = 631_065_600L
    private const val SEMI_PER_DEG = 2_147_483_648.0 / 180.0

    /** Haversine metres, the same expression order as `RouteMath.haversineMeters` and the Swift twin. */
    internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * Math.PI / 180.0
        val dLon = (lon2 - lon1) * Math.PI / 180.0
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * Math.PI / 180.0) * cos(lat2 * Math.PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(s), sqrt(1 - s))
    }

    /** Round half away from zero, the twin of Swift `roundHalfAway`. */
    internal fun roundHalfAway(v: Double): Long =
        if (v >= 0.0) floor(v + 0.5).toLong() else ceil(v - 0.5).toLong()

    private fun fitTime(unix: Long): Long = (unix - FIT_EPOCH).coerceIn(0L, 0xFFFFFFFEL)
    private fun semicircles(deg: Double): Int =
        roundHalfAway(deg * SEMI_PER_DEG).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    private fun centimetres(m: Double): Long = roundHalfAway(m * 100).coerceIn(0L, 0xFFFFFFFFL)
    private fun mmPerSecond(mps: Double): Int = roundHalfAway(mps * 1000).coerceIn(0L, 0xFFFEL).toInt()
    private fun altitude(m: Double): Int = roundHalfAway((m + 500) * 5).coerceIn(0L, 0xFFFEL).toInt()
    private fun kcal(k: Double): Int = roundHalfAway(k).coerceIn(0L, 0xFFFEL).toInt()

    private data class F(val num: Int, val size: Int, val baseType: Int)

    /** A little-endian FIT definition message. Swift twin: `GarminFitExporter.defn`. */
    private fun ByteArrayOutputStream.defn(local: Int, global: Int, fields: List<F>) {
        u8(0x40 or (local and 0x0F)); u8(0); u8(0); u16(global); u8(fields.size)
        for (f in fields) { u8(f.num); u8(f.size); u8(f.baseType) }
    }
    /** One byte. Swift twin: `GarminFitExporter.u8`. */
    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    /** Little-endian uint16. Swift twin: `GarminFitExporter.u16`. */
    private fun ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
    /** Little-endian uint32. Swift twin: `GarminFitExporter.u32`. */
    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt()); write(((v ushr 8) and 0xFF).toInt())
        write(((v ushr 16) and 0xFF).toInt()); write(((v ushr 24) and 0xFF).toInt())
    }
    /** Little-endian sint32 (two's complement). Swift twin: `GarminFitExporter.s32`. */
    private fun ByteArrayOutputStream.s32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
}
