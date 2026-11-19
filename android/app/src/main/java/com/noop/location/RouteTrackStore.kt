package com.noop.location

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sidecar store for a recorded workout's TIMED GPS track: one JSON file per workout under
 * `filesDir/route-tracks/<startTs>_<sport>.json`, holding
 * `[{"lat":..,"lon":..,"altM":..|null,"tMs":..}]`.
 *
 * The Room `workout` row keeps only the bare polyline ([com.noop.data.WorkoutRow.routePolyline]); the
 * per-point times and altitudes live here so a Garmin FIT export can carry the real timing, without a
 * schema migration. Mirrors the iOS RouteStore, which also keeps timed points outside the database.
 *
 * Lifecycle: written when a GPS workout is saved (AppViewModel end-workout path) and deleted when that
 * workout is deleted through [com.noop.ui.AppViewModel.deleteWorkout]. Other removal paths (merge, bulk
 * delete, restore of a backup) can leave an orphaned file; that is harmless (a few KB, never read for a
 * workout that no longer exists) and accepted. The file is not part of the `.noopbak` backup, so a
 * restored workout falls back to the evenly-spread polyline on export.
 */
object RouteTrackStore {

    private const val DIR = "route-tracks"

    /** File-name key for a workout, `<startTs>_<sport>` with anything outside [A-Za-z0-9-] folded to `-`. */
    internal fun fileName(startTs: Long, sport: String): String =
        "${startTs}_${sport.replace(Regex("[^A-Za-z0-9-]"), "-")}.json"

    private fun file(context: Context, startTs: Long, sport: String): File =
        File(File(context.filesDir, DIR), fileName(startTs, sport))

    /** Persist [track] for the workout keyed by ([startTs], [sport]). An empty track writes nothing. */
    fun save(context: Context, startTs: Long, sport: String, track: List<TimedFix>) {
        if (track.isEmpty()) return
        runCatching {
            val f = file(context, startTs, sport)
            f.parentFile?.mkdirs()
            f.writeText(encode(track))
        }
    }

    /** The stored timed track, or empty when none was recorded (or the file is unreadable). */
    fun load(context: Context, startTs: Long, sport: String): List<TimedFix> =
        runCatching {
            val f = file(context, startTs, sport)
            if (f.isFile) decode(f.readText()) else emptyList()
        }.getOrDefault(emptyList())

    /** Remove the stored track for a deleted workout. No-op when none exists. */
    fun delete(context: Context, startTs: Long, sport: String) {
        runCatching { file(context, startTs, sport).delete() }
    }

    internal fun encode(track: List<TimedFix>): String {
        val arr = JSONArray()
        for (p in track) {
            arr.put(
                JSONObject()
                    .put("lat", p.lat)
                    .put("lon", p.lon)
                    .put("altM", p.altM ?: JSONObject.NULL)
                    .put("tMs", p.tMs),
            )
        }
        return arr.toString()
    }

    internal fun decode(json: String): List<TimedFix> {
        val arr = JSONArray(json)
        val out = ArrayList<TimedFix>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || !o.has("tMs")) continue
            val alt = if (o.isNull("altM")) null else o.optDouble("altM", Double.NaN).takeIf { it.isFinite() }
            out.add(TimedFix(lat, lon, alt, o.getLong("tMs")))
        }
        return out
    }
}
