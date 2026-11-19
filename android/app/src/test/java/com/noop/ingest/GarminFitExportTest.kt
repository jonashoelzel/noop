package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Twin of the Swift `GarminFitExporterTests`. The expected hex is the stdout of the standalone Swift oracle
 * (`swiftc GarminFitExporter.swift RouteExporter.swift main.swift`), pasted verbatim; each file was checked
 * with Garmin's official FIT SDK decoder. The Swift suite asserts the same literals.
 */
class GarminFitExportTest {

    private val start = 1_794_996_318L

    private val gpsFixture = GarminFitExport.Workout(
        startTs = start, endTs = start + 20, timerS = 18.4, sport = "Running", energyKcal = 12.6,
        track = listOf(
            GarminFitExport.TrackPoint(start, 51.028130, 13.760030, 112.4),
            GarminFitExport.TrackPoint(start + 4, 51.028270, 13.760210, 112.9),
            GarminFitExport.TrackPoint(start + 8, 51.028310, 13.760370),
            GarminFitExport.TrackPoint(start + 13, 51.028380, 13.760620, 113.6),
            GarminFitExport.TrackPoint(start + 18, 51.028370, 13.760830, 113.1),
        ),
        hr = (0..20).mapNotNull { i ->
            if (i == 7 || i == 8) null
            else GarminFitExport.HrPoint(start + i, if (i == 12) 0 else 120 + i * 2)
        },
    )

    private val indoorFixture = GarminFitExport.Workout(
        startTs = start, endTs = start + 5, sport = "Strength", track = emptyList(),
        hr = listOf(GarminFitExport.HrPoint(start + 1, 101), GarminFitExport.HrPoint(start + 1, 99)),
    )

    private val gpsHex =
            "0e205c08770300002e4649544df940000000000500010001028402028403048c04048600040100290cf999c9ed5e2c60" +
            "4541000031000200028401010201d007ff420000170006fd048600010202028403048c040284050284025e2c60450001" +
            "00f999c9ed290cd007430000150004fd0486000100010100040102035e2c6045000000440000140007fd048600048501" +
            "0485020284030102050486060284045e2c604538634924e0efc809f60b7800000000fe11045f2c6045da644924f9f1c8" +
            "09f70b7af5010000f41004602c60457b66492412f4c809f70b7ce9030000361004612c60451d6849242bf6c809f80b7e" +
            "de050000a80f04622c6045bf69492444f8c809ffff80d2070000940f04632c6045366a492421fac809ffff82ff080000" +
            "840f04642c6045ad6a4924fefbc809ffff842c0a00000e0f04652c6045246b4924dbfdc809ffffff590b0000980e0466" +
            "2c60459c6b4924b9ffc809ffffff860c0000220e04672c6045436c49240d02c909ffff8a050e0000540d04682c6045ea" +
            "6c49246204c909ffff8c840f00004e0d04692c6045916d4924b606c909ffff8e02110000480d046a2c6045386e49240b" +
            "09c909ffffff81120000410d046b2c6045df6e49245f0bc909fc0b92001400003b0d046c2c6045c76e4924540dc909fc" +
            "0b9426150000bc0b046d2c6045af6e4924490fc909fb0b964d1600003d0a046e2c6045976e49243f11c909fb0b987417" +
            "0000b709046f2c60457f6e49243413c909fa0b9a9a1800000f0904702c6045686e49242915c909fa0b9cc11900003808" +
            "04712c6045ffffff7fffffff7fffff9ec1190000ac0704722c6045ffffff7fffffff7fffffa0c1190000e70603722c60" +
            "45000400450000130013fe0284fd04860001000101000204860304850404850504850604850704860804860904860b02" +
            "840d02840e02840f0102100102180100190100050000722c604509015e2c604538634924e0efc809686e49242915c909" +
            "204e0000e0470000c11900000d00e00cfe118ca00001460000120014fe0284fd04860001000101000204860304850404" +
            "850501000601000704860804860904860b02840e02840f02841001021101021902841a02841c0100060000722c604508" +
            "015e2c604538634924e0efc8090100204e0000e0470000c11900000d00e00cfe118ca00000010000470000220007fd04" +
            "8600048601028402010003010004010005048607722c6045e04700000100001a01823a6045eee7"

    private val indoorHex =
            "0e205c082d0200002e464954d87a40000000000500010001028402028403048c040486000459003550010000005e2c60" +
            "45410000310002000284010102018200ff420000170006fd048600010202028403048c040284050284025e2c60450059" +
            "000100000035508200430000150004fd0486000100010100040102035e2c6045000000440000140007fd048600048501" +
            "0485020284030102050486060284045e2c6045ffffff7fffffff7fffffffffffffffffff045f2c6045ffffff7fffffff" +
            "7fffff63ffffffffffff04602c6045ffffff7fffffff7fffffffffffffffffff04612c6045ffffff7fffffff7fffffff" +
            "ffffffffffff04622c6045ffffff7fffffff7fffffffffffffffffff04632c6045ffffff7fffffff7fffffffffffffff" +
            "ffff03632c6045000400450000130013fe0284fd04860001000101000204860304850404850504850604850704860804" +
            "860904860b02840d02840e02840f0102100102180100190100050000632c604509015e2c6045ffffff7fffffff7fffff" +
            "ff7fffffff7f8813000088130000ffffffffffffffffffff63630000460000120014fe0284fd04860001000101000204" +
            "860304850404850501000601000704860804860904860b02840e02840f02841001021101021902841a02841c01000600" +
            "00632c604508015e2c6045ffffff7fffffff7f00008813000088130000ffffffffffffffffffff636300000100004700" +
            "00220007fd048600048601028402010003010004010005048607632c6045881300000100001a0113e65f456eb6"

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test fun gpsWorkoutBytesArePinned() {
        val data = GarminFitExport.build(gpsFixture, GarminFitExport.DeviceIdentity.FORERUNNER_945, 3600)
        assertEquals(gpsHex, hex(data))
    }

    @Test fun indoorWorkoutBytesArePinned() {
        val data = GarminFitExport.build(indoorFixture, GarminFitExport.DeviceIdentity.TACX_TRAINING_APP, -18000)
        assertEquals(indoorHex, hex(data))
    }

    @Test fun roundTripsThroughTheImporter() {
        val data = GarminFitExport.build(gpsFixture, GarminFitExport.DeviceIdentity.FORERUNNER_945, 3600)
        val a = ActivityFileImporter.parse(data, "run.fit").activity
        assertNotNull(a)
        assertEquals(start, a!!.startTs)
        assertEquals(start + 20, a.endTs)
        assertEquals(140, a.avgHr)
        assertEquals(160, a.maxHr)
        // 21 seconds minus the 2-second gap and the invalid 0: the 0xFF "no HR" sentinel must not read as 255.
        assertEquals(18, a.hrSamples.size)
        assertFalse(a.hrSamples.any { it.bpm == 255 })
        assertEquals(19, a.route.size)
        assertEquals(65.93, a.distanceM!!, 0.001)
    }

    @Test fun deviceIdentityKeysAreStable() {
        assertEquals(listOf("forerunner945", "tacxTrainingApp", "noop"),
            GarminFitExport.DeviceIdentity.entries.map { it.key })
        assertEquals(GarminFitExport.DeviceIdentity.FORERUNNER_945, GarminFitExport.DeviceIdentity.DEFAULT)
        assertEquals(GarminFitExport.DeviceIdentity.DEFAULT, GarminFitExport.DeviceIdentity.fromKey("bogus"))
    }

    @Test fun untimedTrackSpreadsPointsAcrossTheWindow() {
        val pts = listOf(RouteExport.Point(1.0, 2.0), RouteExport.Point(3.0, 4.0), RouteExport.Point(5.0, 6.0))
        assertEquals(listOf(100L, 105L, 110L), GarminFitExport.untimedTrack(pts, 100, 110).map { it.t })
    }
}
