package com.noop.ui

import com.noop.data.WorkoutRow
import com.noop.ingest.ActivityFileImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Read-time dedup of Garmin Connect imports (fork Garmin integration). Twin of the Swift
 * WorkoutSource Garmin cases: classification, trace label, and the three collapse outcomes.
 */
class GarminDedupTest {

    private fun row(
        source: String, sport: String, start: Long = 1_000, end: Long = 4_600,
        deviceId: String = source, avgHr: Int? = 140, maxHr: Int? = 170,
        distanceM: Double? = 30_000.0, energyKcal: Double? = 800.0,
    ) = WorkoutRow(
        deviceId = deviceId, startTs = start, endTs = end, sport = sport, source = source,
        durationS = (end - start).toDouble(), energyKcal = energyKcal, avgHr = avgHr, maxHr = maxHr,
        distanceM = distanceM,
    )

    @Test
    fun classifiesGarminConnect() {
        assertEquals(WorkoutSource.GARMIN, WorkoutEditing.classify("garmin-connect"))
        assertEquals(WorkoutSource.GARMIN, WorkoutEditing.classify("Garmin-Connect"))
        assertEquals("garmin", WorkoutEditing.sourceLabel(row("garmin-connect", "Cycling")))
        assertFalse(WorkoutMerge.isMergeable(row("garmin-connect", "Cycling")))
    }

    @Test
    fun garminRideKeptOverHealthConnectCopyOnRichnessTie() {
        val garmin = row("garmin-connect", ActivityFileImporter.workoutSport("Cycling"))
        val hc = row("health-connect", "Cycling", start = 1_002, end = 4_590)
        assertEquals(WorkoutEditing.richness(garmin), WorkoutEditing.richness(hc))
        assertEquals(listOf(garmin), WorkoutEditing.dedupCrossSource(listOf(hc, garmin)))
        assertEquals(listOf(garmin), WorkoutEditing.dedupCrossSource(listOf(garmin, hc)))
        // Apple Health (Android import) the same.
        val apple = row("apple-health", "Cycling")
        assertEquals(listOf(garmin), WorkoutEditing.dedupCrossSource(listOf(apple, garmin)))
    }

    @Test
    fun detectedBoutUnderGarminRideIsDropped() {
        val garmin = row("garmin-connect", "Cycling")
        val detected = row("my-whoop-noop", "detected", start = 1_200, end = 4_000, deviceId = "my-whoop-noop")
        assertEquals(listOf(garmin), WorkoutEditing.dedupCrossSource(listOf(detected, garmin)))
    }

    @Test
    fun garminRideAndManualRunOfSameWindowBothKept() {
        val garmin = row("garmin-connect", "Cycling")
        val manual = row("manual", "Running", deviceId = "my-whoop")
        assertEquals(listOf(garmin, manual), WorkoutEditing.dedupCrossSource(listOf(garmin, manual)))
    }

    @Test
    fun roadRideFromFitAndHealthImportsShareSportKey() {
        // FIT sport 2 decodes to "Cycling"; Garmin's road_biking / cycling labels map to "Cycling" too.
        val fitSport = ActivityFileImporter.workoutSport("Cycling")
        val key = WorkoutEditing.sportKey(fitSport)
        assertEquals(key, WorkoutEditing.sportKey(ActivityFileImporter.workoutSport("road_biking")))
        assertEquals(key, WorkoutEditing.sportKey(ActivityFileImporter.workoutSport("cycling")))
        // Health Connect EXERCISE_TYPE_BIKING and Apple Health HKWorkoutActivityTypeCycling store "Cycling".
        assertEquals(key, WorkoutEditing.sportKey("Cycling"))
        assertEquals("cycling", key)
    }
}
