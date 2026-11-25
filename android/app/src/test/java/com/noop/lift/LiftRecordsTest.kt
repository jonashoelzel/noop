package com.noop.lift

import com.noop.lift.LiftRecords.Candidate
import com.noop.lift.LiftRecords.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftRecordsTest {
    private val prior = LiftRecords.Best()
        .adding(100.0, 5, isWarmup = false) // 1RM 116.67, volume 500
        .adding(80.0, 10, isWarmup = false) // 1RM 106.67, volume 800

    @Test
    fun bestsTakeEachMaximumFromWhicheverSetHoldsIt() {
        assertEquals(100.0, prior.weightKg!!, 1e-9)
        assertEquals(100.0 * (1 + 5 / 30.0), prior.oneRepMaxKg!!, 1e-9)
        assertEquals(800.0, prior.setVolumeKg!!, 1e-9)
    }

    @Test
    fun warmupsZeroRepsAndMissingWeightNeverCount() {
        val b = LiftRecords.Best()
            .adding(200.0, 5, isWarmup = true)
            .adding(200.0, 0, isWarmup = false)
            .adding(null, 10, isWarmup = false)
        assertNull(b.weightKg)
        assertNull(b.setVolumeKg)
    }

    @Test
    fun noPriorMeansNoRecordForAFirstSession() {
        assertTrue(LiftRecords.recordsIn(listOf(Candidate("a", 200.0, 5, false)), null).isEmpty())
    }

    @Test
    fun aTieIsNotARecord() {
        assertTrue(LiftRecords.recordsIn(listOf(Candidate("a", 100.0, 5, false)), prior).isEmpty())
    }

    @Test
    fun eachKindGoesToTheSessionsBestSetOnly() {
        val records = LiftRecords.recordsIn(
            listOf(
                Candidate("heavy", 105.0, 3, false), // weight record; 1RM 115.5 is below 116.67
                Candidate("heavier", 110.0, 2, false), // beats heavy on weight, 1RM 117.33 beats prior
                Candidate("volume", 70.0, 12, false), // 840 beats 800
                Candidate("warm", 150.0, 1, true), // warm-ups are never records
            ),
            prior,
        )
        assertEquals(setOf(Kind.HEAVIEST_WEIGHT, Kind.BEST_ONE_REP_MAX), records["heavier"])
        assertEquals(setOf(Kind.BEST_SET_VOLUME), records["volume"])
        assertNull(records["heavy"])
        assertNull(records["warm"])
    }

    @Test
    fun tiesInsideTheSessionGoToTheFirstSet() {
        val records = LiftRecords.recordsIn(
            listOf(Candidate("first", 120.0, 1, false), Candidate("second", 120.0, 1, false)),
            prior,
        )
        assertTrue(Kind.HEAVIEST_WEIGHT in records["first"].orEmpty())
        assertNull(records["second"])
    }

    @Test
    fun highRepSetsHoldNoOneRepMaxEstimate() {
        // The shared Epley estimate refuses sets above its rep ceiling, so a 20-rep set can set a volume record
        // but never an estimated-1RM one.
        val records = LiftRecords.recordsIn(listOf(Candidate("a", 60.0, 20, false)), prior)
        assertEquals(setOf(Kind.BEST_SET_VOLUME), records["a"])
    }
}
