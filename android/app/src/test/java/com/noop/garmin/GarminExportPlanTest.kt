package com.noop.garmin

import com.noop.analytics.RouteMath
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import com.noop.location.RouteTrackStore
import com.noop.location.TimedFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Assembly of a stored workout into the FIT builder's input, and the timed-track sidecar's JSON. */
class GarminExportPlanTest {

    private val poly = RouteMath.encode(listOf(RouteMath.LatLng(51.0, 13.0), RouteMath.LatLng(51.01, 13.0)))
    private val row = WorkoutRow(
        deviceId = "my-whoop", startTs = 1_000, endTs = 1_100, sport = "Running", source = "manual",
        durationS = 90.0, energyKcal = 50.0, avgHr = 130, routePolyline = poly,
    )

    @Test
    fun timedTrackWinsOverPolyline() {
        val timed = listOf(TimedFix(51.0, 13.0, 112.4, 1_000_900L), TimedFix(51.01, 13.0, null, 1_050_000L))
        val hr = listOf(HrSample("my-whoop", 999, 100), HrSample("my-whoop", 1_000, 120), HrSample("my-whoop", 1_100, 140),
            HrSample("my-whoop", 1_101, 150))
        val w = GarminExportPlan.workout(row, timed, hr)
        assertEquals(listOf(1_000L, 1_050L), w.track.map { it.t })
        assertEquals(112.4, w.track[0].altM!!, 0.0)
        assertNull(w.track[1].altM)
        assertEquals(listOf(1_000L, 1_100L), w.hr.map { it.t })
        assertEquals(90.0, w.timerS!!, 0.0)
        assertEquals(50.0, w.energyKcal!!, 0.0)
        assertEquals("Running", w.sport)
    }

    @Test
    fun polylineIsSpreadAcrossWindowWithoutTimedTrack() {
        val w = GarminExportPlan.workout(row, emptyList(), emptyList())
        assertEquals(listOf(1_000L, 1_100L), w.track.map { it.t })
        val none = GarminExportPlan.workout(row.copy(routePolyline = null), emptyList(), emptyList())
        assertTrue(none.track.isEmpty())
    }

    @Test
    fun sendIsOfferedForNonGarminRowsWithHrOrRoute() {
        assertTrue(GarminExportPlan.canSend(row))
        assertTrue(GarminExportPlan.canSend(row.copy(avgHr = null)))          // route only
        assertTrue(GarminExportPlan.canSend(row.copy(routePolyline = null)))  // HR only
        assertFalse(GarminExportPlan.canSend(row.copy(avgHr = null, routePolyline = null)))
        assertFalse(GarminExportPlan.canSend(row.copy(source = "garmin-connect")))
    }

    @Test
    fun utcOffsetAtEnd() {
        // 2026-07-01 is CEST (+2 h) in Berlin; 2026-01-01 is CET (+1 h).
        assertEquals(7200, GarminExportPlan.utcOffsetS(1_782_864_000L, ZoneId.of("Europe/Berlin")))
        assertEquals(3600, GarminExportPlan.utcOffsetS(1_767_225_600L, ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun routeTrackJsonRoundTrips() {
        val track = listOf(TimedFix(51.0, 13.0, 112.4, 1_000_900L), TimedFix(51.01, 13.5, null, 1_050_000L))
        val json = RouteTrackStore.encode(track)
        assertTrue(json.contains("\"altM\":null"))
        assertEquals(track, RouteTrackStore.decode(json))
        assertEquals("1000_Road-Cycling.json", RouteTrackStore.fileName(1000, "Road Cycling"))
    }
}
