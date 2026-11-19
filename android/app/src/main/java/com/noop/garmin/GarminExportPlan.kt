package com.noop.garmin

import com.noop.analytics.RouteMath
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import com.noop.ingest.GarminFitExport
import com.noop.ingest.RouteExport
import com.noop.location.TimedFix
import java.time.Instant
import java.time.ZoneId

/**
 * Pure assembly of a stored workout into the [GarminFitExport.Workout] the FIT builder takes. Same rules
 * as the Swift side:
 *  - track: the stored timed fixes (t = tMs / 1000, altitude kept) when present, else the polyline spread
 *    across the window via [GarminFitExport.untimedTrack], else empty;
 *  - hr: the measured samples inside [startTs, endTs];
 *  - timer = row.durationS, energy = row.energyKcal, sport = row.sport.
 */
object GarminExportPlan {

    /** Whether the "Send to Garmin Connect" action applies: not a Garmin import, and HR or a route to send. */
    fun canSend(row: WorkoutRow): Boolean =
        row.source.lowercase() != GarminConnect.SOURCE_ID &&
            (row.avgHr != null || row.maxHr != null || !row.routePolyline.isNullOrEmpty())

    fun workout(row: WorkoutRow, timed: List<TimedFix>, hr: List<HrSample>): GarminFitExport.Workout {
        val track: List<GarminFitExport.TrackPoint> = when {
            timed.isNotEmpty() -> timed.map { GarminFitExport.TrackPoint(it.tMs / 1000, it.lat, it.lon, it.altM) }
            !row.routePolyline.isNullOrEmpty() -> {
                val pts = RouteMath.decode(row.routePolyline!!).map { RouteExport.Point(it.lat, it.lon) }
                GarminFitExport.untimedTrack(pts, row.startTs, row.endTs)
            }
            else -> emptyList()
        }
        return GarminFitExport.Workout(
            startTs = row.startTs,
            endTs = row.endTs,
            timerS = row.durationS,
            sport = row.sport,
            energyKcal = row.energyKcal,
            track = track,
            hr = hr.filter { it.ts in row.startTs..row.endTs }.map { GarminFitExport.HrPoint(it.ts, it.bpm) },
        )
    }

    /** The local zone's UTC offset at [endTs], seconds (the FIT activity's local_timestamp). */
    fun utcOffsetS(endTs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        zone.rules.getOffset(Instant.ofEpochSecond(endTs)).totalSeconds
}
