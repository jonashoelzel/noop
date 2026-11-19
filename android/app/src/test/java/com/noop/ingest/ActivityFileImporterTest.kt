package com.noop.ingest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream

/**
 * JVM mirror of the macOS ActivityFileImporterTests: GPX, TCX and FIT each parse into one normalized
 * Activity with the right counts + summaries, plus the malformed-input contract (no crash, reject
 * gracefully). The XML parser seam swaps in kXML2 (android.util.Xml is a throwing stub off-device).
 */
class ActivityFileImporterTest {

    @Test
    fun routeDistanceOrderedThreePointsUsesCanonicalBitPattern() {
        // Fork governance #99: exact twin parity matters even when the numerical delta is too small
        // to survive UI formatting. Keep point order and assert the binary64 result, not a tolerance.
        val route = listOf(
            ActivityFileImporter.RoutePoint(lat = 52.5, lon = 13.4),
            ActivityFileImporter.RoutePoint(lat = 52.5001, lon = 13.4001),
            ActivityFileImporter.RoutePoint(lat = 52.5002, lon = 13.4003),
        )

        assertEquals(0x403e89813f76c75cL, ActivityFileImporter.routeDistanceM(route).toRawBits())
    }

    @Test
    fun routeDistanceOneSegmentKeepsAdjacentControlBitPattern() {
        // Adjacent control: this first segment is already identical on both twins and must stay so.
        val route = listOf(
            ActivityFileImporter.RoutePoint(lat = 52.5, lon = 13.4),
            ActivityFileImporter.RoutePoint(lat = 52.5001, lon = 13.4001),
        )

        assertEquals(0x402a0921667f2bacL, ActivityFileImporter.routeDistanceM(route).toRawBits())
    }

    @Before
    fun installRealParser() {
        ActivityFileImporter.newPullParser = {
            Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
        }
    }

    @After
    fun restoreParser() {
        ActivityFileImporter.newPullParser = { android.util.Xml.newPullParser() }
    }

    // MARK: - GPX

    @Test
    fun gpxTrackWithHrExtension() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk>
                <type>running</type>
                <trkseg>
                  <trkpt lat="51.5000" lon="-0.1000">
                    <ele>10.0</ele><time>2026-06-01T10:00:00Z</time>
                    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                  </trkpt>
                  <trkpt lat="51.5010" lon="-0.1000">
                    <ele>20.0</ele><time>2026-06-01T10:01:00Z</time>
                    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>140</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                  </trkpt>
                  <trkpt lat="51.5020" lon="-0.1000">
                    <ele>25.0</ele><time>2026-06-01T10:02:00Z</time>
                    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>160</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val r = ActivityFileImporter.parse(gpx.toByteArray(Charsets.UTF_8), "run.gpx")
        val a = r.activity!!
        assertEquals(ActivityFileImporter.Kind.GPX, a.kind)
        assertEquals(3, a.gpsPointCount)
        assertEquals(3, a.hrSampleCount)
        assertEquals("running", a.sport)
        assertEquals(140, a.avgHr)
        assertEquals(160, a.maxHr)
        assertEquals(120.0, a.durationS!!, 1e-6)
        assertEquals(222.0, a.distanceM!!, 30.0)
        assertEquals(15.0, a.ascentM!!, 1e-3)
        // #137: the REAL per-sample HR series is now carried through (not just avg/max), so the app
        // layer can persist it under the activity-file source and light a strap-less day's Effort ring.
        // Values in order, timestamped at each point's own time (start + 0/60/120 s). Parity with Swift.
        assertEquals(listOf(120, 140, 160), a.hrSamples.map { it.bpm })
        val base = a.startTs
        assertEquals(listOf(base, base + 60, base + 120), a.hrSamples.map { it.ts })
    }

    @Test
    fun hrSamplesRequireBothTimestampAndHr() {
        // #137: a sample must carry BOTH a timestamp and an HR to be persisted — a point with HR but no
        // <time> can't key into the (deviceId, ts) HR store, and one with a time but no HR has nothing to
        // store. Point 1: time+HR (kept). Point 2: time, no HR (excluded). Point 3: HR, no time
        // (excluded). `hrSampleCount` still counts every HR-bearing point. Parity with the Swift twin.
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="51.5000" lon="-0.1000"><time>2026-06-01T10:00:00Z</time>
                <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
              <trkpt lat="51.5010" lon="-0.1000"><time>2026-06-01T10:01:00Z</time></trkpt>
              <trkpt lat="51.5020" lon="-0.1000">
                <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>160</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val r = ActivityFileImporter.parse(gpx.toByteArray(Charsets.UTF_8), "mixed.gpx")
        val a = r.activity!!
        assertEquals(2, a.hrSampleCount)                    // both HR-bearing points counted
        assertEquals(listOf(120), a.hrSamples.map { it.bpm }) // only the timestamped-with-HR one persisted
        assertEquals(a.startTs, a.hrSamples.first().ts)
    }

    @Test
    fun hrSampleTimestampFloorsFractionalSecondsForSwiftParity() {
        // #137 byte-parity anchor: `hrSample.ts` is the `(deviceId, ts)` store key, so Android and Apple
        // must derive the SAME whole second from a fractional timestamp. Kotlin floors via
        // OffsetDateTime.toEpochSecond(); Swift truncates its fractional Date to match. Parse the same
        // trackpoint time as a whole second and as `.500`, and assert both land on the SAME ts (floor).
        fun ts(time: String): Long {
            val gpx = """
                <gpx><trk><trkseg>
                  <trkpt lat="51.5000" lon="-0.1000"><time>$time</time>
                    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>130</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
                </trkseg></trk></gpx>
            """.trimIndent()
            return ActivityFileImporter.parse(gpx.toByteArray(Charsets.UTF_8), "frac.gpx").activity!!.hrSamples.first().ts
        }
        assertEquals("a .5-second fraction must FLOOR to the same whole second (Swift parity), not round up",
            ts("2026-06-01T10:00:00Z"), ts("2026-06-01T10:00:00.500Z"))
    }

    @Test
    fun gpxRejectsNullIsland() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="0.0" lon="0.0"><time>2026-06-01T10:00:00Z</time></trkpt>
              <trkpt lat="51.5" lon="-0.1"><time>2026-06-01T10:01:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val r = ActivityFileImporter.parse(gpx.toByteArray(Charsets.UTF_8), "x.gpx")
        assertEquals(1, r.activity!!.gpsPointCount)
    }

    @Test
    fun untimedGpxRequiresTwoPointsForDerivedDistance() {
        // bhelm/noop#100: without a summary distance, one coordinate has no measurable segment.
        // The untimed parser must preserve that absence as null, matching the timestamped branch.
        val onePoint = """
            <gpx><trk><trkseg>
              <trkpt lat="48.1" lon="11.5"></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val single = ActivityFileImporter.parse(onePoint.toByteArray(Charsets.UTF_8), "route.gpx").activity!!
        assertEquals(1, single.gpsPointCount)
        assertNull(single.distanceM)

        // Adjacent control: two untimed coordinates do define a segment and retain derived distance.
        val twoPoints = """
            <gpx><trk><trkseg>
              <trkpt lat="48.1" lon="11.5"></trkpt>
              <trkpt lat="48.1001" lon="11.5001"></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val pair = ActivityFileImporter.parse(twoPoints.toByteArray(Charsets.UTF_8), "route.gpx").activity!!
        assertEquals(2, pair.gpsPointCount)
        val derivedDistance = pair.distanceM
        assertNotNull(derivedDistance)
        assertTrue((derivedDistance ?: 0.0) > 0.0)
    }

    // MARK: - TCX

    @Test
    fun tcxActivityWithLapSummaries() {
        val tcx = """
            <?xml version="1.0"?>
            <TrainingCenterDatabase>
              <Activities>
                <Activity Sport="Biking">
                  <Lap>
                    <TotalTimeSeconds>60</TotalTimeSeconds>
                    <DistanceMeters>1500</DistanceMeters>
                    <Calories>45</Calories>
                    <MaximumHeartRateBpm><Value>175</Value></MaximumHeartRateBpm>
                    <Track>
                      <Trackpoint>
                        <Time>2026-06-01T08:00:00Z</Time>
                        <Position><LatitudeDegrees>40.0</LatitudeDegrees><LongitudeDegrees>-3.0</LongitudeDegrees></Position>
                        <AltitudeMeters>600</AltitudeMeters>
                        <HeartRateBpm><Value>150</Value></HeartRateBpm>
                      </Trackpoint>
                      <Trackpoint>
                        <Time>2026-06-01T08:01:00Z</Time>
                        <Position><LatitudeDegrees>40.01</LatitudeDegrees><LongitudeDegrees>-3.0</LongitudeDegrees></Position>
                        <AltitudeMeters>610</AltitudeMeters>
                        <HeartRateBpm><Value>170</Value></HeartRateBpm>
                      </Trackpoint>
                    </Track>
                  </Lap>
                </Activity>
              </Activities>
            </TrainingCenterDatabase>
        """.trimIndent()
        val r = ActivityFileImporter.parse(tcx.toByteArray(Charsets.UTF_8), "ride.tcx")
        val a = r.activity!!
        assertEquals(ActivityFileImporter.Kind.TCX, a.kind)
        assertEquals("Biking", a.sport)
        assertEquals(2, a.gpsPointCount)
        assertEquals(2, a.hrSampleCount)
        assertEquals(1500.0, a.distanceM!!, 1e-6)
        assertEquals(45.0, a.energyKcal!!, 1e-6)
        assertEquals(160, a.avgHr)
        assertEquals(175, a.maxHr)
        assertEquals(60.0, a.durationS!!, 1e-6)
        assertEquals("Cycling", ActivityFileImporter.workoutSport(a.sport))
    }

    // MARK: - FIT

    @Test
    fun fitRecordAndSessionMessages() {
        val fit = FitFixture()
        val sessionStart = 100_000L
        fit.definition(0, 18, listOf(
            Triple(2, 4, 0x86), Triple(5, 1, 0x00), Triple(9, 4, 0x86), Triple(10, 4, 0x86),
            Triple(11, 2, 0x84), Triple(16, 1, 0x02), Triple(17, 1, 0x02),
        ))
        fit.dataHeader(0)
        fit.u32(sessionStart)
        fit.u8(1)            // sport = running
        fit.u32(523)         // total_distance → 5.23 m
        fit.u32(1175)        // total_cycles = STRIDES for a foot sport; 1 stride = 2 steps → 2350 steps (#568)
        fit.u16(300)         // total_calories
        fit.u8(150)          // avg_hr
        fit.u8(182)          // max_hr

        fit.definition(1, 20, listOf(
            Triple(253, 4, 0x86), Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(3, 1, 0x02),
        ))
        val lat = 51.5; val lon = -0.1
        val latSemi = Math.round(lat / (180.0 / 2_147_483_648.0)).toInt()
        val lonSemi = Math.round(lon / (180.0 / 2_147_483_648.0)).toInt()
        for (k in 0 until 3) {
            fit.dataHeader(1)
            fit.u32(sessionStart + k * 10L)
            fit.i32(latSemi)
            fit.i32(lonSemi)
            fit.u8((120 + k * 20))
        }

        val r = ActivityFileImporter.parse(fit.finish(), "ride.fit")
        val a = r.activity!!
        assertEquals(ActivityFileImporter.Kind.FIT, a.kind)
        assertEquals("Running", a.sport)
        assertEquals(3, a.gpsPointCount)
        assertEquals(3, a.hrSampleCount)
        assertEquals(5.23, a.distanceM!!, 1e-3)
        assertEquals(300.0, a.energyKcal!!, 1e-6)
        assertEquals(2350, a.steps)   // FIT field 10 = 1175 strides → ×2 = 2350 steps (#568)
        assertEquals(150, a.avgHr)
        assertEquals(182, a.maxHr)
        assertEquals(lat, a.route.first().lat, 1e-4)
        assertEquals(lon, a.route.first().lon, 1e-4)
        assertEquals((631_065_600L + 100_000L).toDouble(), a.startTs.toDouble(), 1.0)
        // #137: FIT persists the same real per-record HR series as GPX/TCX (shared extractor). Records
        // are 0/10/20 s apart from the FIT-epoch start; HR 120/140/160. Parity with the Swift twin.
        val fitBase = 631_065_600L + 100_000L
        assertEquals(listOf(120, 140, 160), a.hrSamples.map { it.bpm })
        assertEquals(listOf(fitBase, fitBase + 10, fitBase + 20), a.hrSamples.map { it.ts })
    }

    @Test
    fun fitDetectionFromMagicBytes() {
        val fit = FitFixture()
        fit.definition(0, 20, listOf(Triple(253, 4, 0x86), Triple(3, 1, 0x02)))
        fit.dataHeader(0); fit.u32(50_000L); fit.u8(100)
        assertEquals(ActivityFileImporter.Format.FIT, ActivityFileImporter.detectFormat(fit.finish()))
    }

    // MARK: - Malformed input (must not crash, must reject gracefully)

    @Test
    fun malformedInputsRejectedNotCrash() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0x00, 0x01, 0x02),
            "not xml not fit".toByteArray(),
            "<gpx><trk><trkseg><trkpt lat=\"abc\" lon=\"xyz\"></trkpt>".toByteArray(),
            byteArrayOf(0x0C, 0x10, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F,
                '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte()),
        )
        for (d in cases) {
            val r = ActivityFileImporter.parse(d, null)
            assertNull("expected no activity from malformed input", r.activity)
        }
    }

    @Test
    fun gpxExternalEntityNotExpanded() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <gpx><trk><trkseg>
              <trkpt lat="51.5" lon="-0.1"><time>2026-06-01T10:00:00Z</time><name>&xxe;</name></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        // Must not crash or leak file contents; bounded result.
        val r = ActivityFileImporter.parse(xxe.toByteArray(Charsets.UTF_8), "x.gpx")
        assertTrue((r.activity?.gpsPointCount ?: 0) <= 1)
    }

    @Test
    fun workoutSportNormalization() {
        assertEquals("Running", ActivityFileImporter.workoutSport("running"))
        assertEquals("Cycling", ActivityFileImporter.workoutSport("road_biking"))
        assertEquals("Activity", ActivityFileImporter.workoutSport(null))
        assertEquals("Activity", ActivityFileImporter.workoutSport(""))
        assertEquals("Trail Run", ActivityFileImporter.workoutSport("Trail Run"))
        assertEquals("Kayaking", ActivityFileImporter.workoutSport("kayaking"))
    }

    @Test
    fun detectFormatTreatsTrailingDotAsEmptyExtensionWithSwiftParity() {
        val empty = byteArrayOf()
        val unknownXml = "<unknown/>".toByteArray(Charsets.UTF_8)
        val fitMagic = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 46, 70, 73, 84)
        val gpx = "<gpx></gpx>".toByteArray(Charsets.UTF_8)
        val tcx = "<TrainingCenterDatabase></TrainingCenterDatabase>".toByteArray(Charsets.UTF_8)

        assertEquals(
            ActivityFileImporter.Format.UNKNOWN,
            ActivityFileImporter.detectFormat("ride.fit.", empty),
        )
        assertEquals(
            ActivityFileImporter.Format.UNKNOWN,
            ActivityFileImporter.detectFormat("ride.fit..", empty),
        )
        assertEquals(
            ActivityFileImporter.Format.UNKNOWN,
            ActivityFileImporter.detectFormat("route.gpx.", unknownXml),
        )
        assertEquals(ActivityFileImporter.Format.UNKNOWN, ActivityFileImporter.detectFormat("fit", empty))

        assertEquals(ActivityFileImporter.Format.FIT, ActivityFileImporter.detectFormat("ride.fit", empty))
        assertEquals(ActivityFileImporter.Format.GPX, ActivityFileImporter.detectFormat("route.gpx", empty))
        assertEquals(ActivityFileImporter.Format.GPX, ActivityFileImporter.detectFormat("route.GPX", empty))
        assertEquals(ActivityFileImporter.Format.FIT, ActivityFileImporter.detectFormat("ride.fit.", fitMagic))
        assertEquals(ActivityFileImporter.Format.FIT, ActivityFileImporter.detectFormat("ride.bin", fitMagic))
        assertEquals(ActivityFileImporter.Format.GPX, ActivityFileImporter.detectFormat("route.gpx.", gpx))
        assertEquals(ActivityFileImporter.Format.GPX, ActivityFileImporter.detectFormat("route.bin", gpx))
        assertEquals(ActivityFileImporter.Format.TCX, ActivityFileImporter.detectFormat("workout.tcx.", tcx))
    }

    @Test
    fun summaryTextIncludesOnlyPositiveStepsWithSwiftParity() {
        fun activity(steps: Int?) = ActivityFileImporter.Activity(
            kind = ActivityFileImporter.Kind.FIT,
            startTs = 1_000,
            endTs = 1_060,
            sport = "walking",
            distanceM = 1_000.0,
            energyKcal = null,
            steps = steps,
            avgHr = null,
            maxHr = null,
            ascentM = null,
            gpsPointCount = 0,
            hrSampleCount = 0,
            route = emptyList(),
        )

        val base = "Imported a 1.00 km Walking activity"
        assertEquals(base, ActivityFileImporter.summaryText(activity(null)))
        assertEquals(base, ActivityFileImporter.summaryText(activity(0)))
        assertEquals(
            "$base · 2350 steps",
            ActivityFileImporter.summaryText(activity(2_350)),
        )
    }

    @Test
    fun distanceTextUsesExplicitHalfUpRoundingWithSwiftParity() {
        fun activity(distanceM: Double) = ActivityFileImporter.Activity(
            kind = ActivityFileImporter.Kind.GPX,
            startTs = 1_000,
            endTs = 1_060,
            sport = "running",
            distanceM = distanceM,
            energyKcal = null,
            steps = null,
            avgHr = null,
            maxHr = null,
            ascentM = null,
            gpsPointCount = 0,
            hrSampleCount = 0,
            route = emptyList(),
        )

        val cases = listOf(
            9_500.0 to "9.50 km",
            10_500.0 to "11 km",
            11_500.0 to "12 km",
        )
        for ((metres, distance) in cases) {
            val value = activity(metres)
            assertEquals("Imported GPX · $distance", value.importNote())
            assertEquals(
                "Imported a $distance Running activity",
                ActivityFileImporter.summaryText(value),
            )
        }
    }

    // MARK: - Garmin session summaries + enhanced altitude + timed route (fork Garmin integration)

    /** A cycling session carrying Training Effect, training load, power and cadence, plus two records. */
    private fun garminSessionFit(
        teAerobic: Int = 50, teAnaerobic: Int = 31, loadRaw: Int = 26_458_276, power: Int = 210, cadence: Int = 88,
    ): ByteArray {
        val fit = FitFixture()
        fit.definition(0, 18, listOf(
            Triple(2, 4, 0x86), Triple(5, 1, 0x00), Triple(7, 4, 0x86), Triple(9, 4, 0x86),
            Triple(18, 1, 0x02), Triple(20, 2, 0x84), Triple(24, 1, 0x02), Triple(137, 1, 0x02),
            Triple(168, 4, 0x85),
        ))
        fit.dataHeader(0)
        fit.u32(200_000L)     // start_time
        fit.u8(2)             // sport = cycling
        fit.u32(3_600_000L)   // total_elapsed_time 3600 s
        fit.u32(3_000_000L)   // 30 km
        fit.u8(cadence)       // avg_cadence
        fit.u16(power)        // avg_power
        fit.u8(teAerobic)     // total_training_effect x10
        fit.u8(teAnaerobic)   // total_anaerobic_training_effect x10
        fit.i32(loadRaw)      // training_load_peak x65536 (26458276 / 65536 = 403.72)
        // Records with enhanced_altitude (78) only, the way an Edge writes them.
        fit.definition(1, 20, listOf(
            Triple(253, 4, 0x86), Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(78, 4, 0x86),
        ))
        val semi = 180.0 / 2_147_483_648.0
        for (k in 0 until 2) {
            fit.dataHeader(1)
            fit.u32(200_000L + k * 5L)
            fit.i32(Math.round((51.0 + k * 0.001) / semi).toInt())
            fit.i32(Math.round(13.7 / semi).toInt())
            fit.u32(((112.4 + k) + 500.0).times(5.0).let { Math.round(it) })
        }
        return fit.finish()
    }

    @Test
    fun fitDecodesGarminSessionSummaries() {
        val a = ActivityFileImporter.parse(garminSessionFit(), "ride.fit").activity!!
        assertEquals("Cycling", a.sport)
        assertEquals(5.0, a.trainingEffectAerobic!!, 1e-9)
        assertEquals(3.1, a.trainingEffectAnaerobic!!, 1e-9)
        assertEquals(403.72, a.trainingLoad!!, 0.005)
        assertEquals(210, a.avgPowerW)
        assertEquals(88, a.avgCadence)
    }

    @Test
    fun fitInvalidSessionSummariesStayNull() {
        val a = ActivityFileImporter.parse(
            garminSessionFit(teAerobic = 0xFF, teAnaerobic = 0xFF, loadRaw = 0x7FFFFFFF, power = 0xFFFF, cadence = 0xFF),
            "ride.fit",
        ).activity!!
        assertNull(a.trainingEffectAerobic)
        assertNull(a.trainingEffectAnaerobic)
        assertNull(a.trainingLoad)
        assertNull(a.avgPowerW)
        assertNull(a.avgCadence)
        assertEquals("Imported FIT · 30 km · 2 GPS points", a.importNote())
    }

    @Test
    fun importNoteAppendsGarminSummariesWithSwiftParity() {
        // Both platforms must produce this exact string for the same file.
        val a = ActivityFileImporter.parse(garminSessionFit(), "ride.fit").activity!!
        assertEquals(
            "Imported FIT · 30 km · 2 GPS points · Training Effect 5.0 aerobic · 3.1 anaerobic · load 404 · avg 210 W",
            a.importNote(),
        )
        // Partial presence: each part only when present; anaerobic alone still carries the label.
        val base = a.copy(trainingEffectAerobic = null, trainingLoad = 402.5, avgPowerW = null)
        assertEquals(
            "Imported FIT · 30 km · 2 GPS points · Training Effect 3.1 anaerobic · load 403",
            base.importNote(),
        )
        assertEquals(
            "Imported FIT · 30 km · 2 GPS points · load -3",
            a.copy(trainingEffectAerobic = null, trainingEffectAnaerobic = null, trainingLoad = -2.5, avgPowerW = null)
                .importNote(),
        )
    }

    @Test
    fun fitEnhancedAltitudeFillsElevationAndTimedRoute() {
        val a = ActivityFileImporter.parse(garminSessionFit(), "ride.fit").activity!!
        val base = 631_065_600L + 200_000L
        assertEquals(listOf(base, base + 5), a.timedRoute.map { it.ts })
        assertEquals(112.4, a.timedRoute[0].altM!!, 1e-9)
        assertEquals(113.4, a.timedRoute[1].altM!!, 1e-9)
        assertEquals(51.0, a.timedRoute[0].lat, 1e-6)
        assertEquals(13.7, a.timedRoute[1].lon, 1e-6)
        assertEquals(2, a.route.size)          // the existing route is unchanged
    }

    @Test
    fun fitAltitudeFieldTwoWinsOverEnhanced() {
        val fit = FitFixture()
        fit.definition(0, 20, listOf(
            Triple(253, 4, 0x86), Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(78, 4, 0x86), Triple(2, 2, 0x84),
        ))
        val semi = 180.0 / 2_147_483_648.0
        fit.dataHeader(0)
        fit.u32(300_000L); fit.i32(Math.round(48.0 / semi).toInt()); fit.i32(Math.round(11.0 / semi).toInt())
        fit.u32(Math.round((900.0 + 500.0) * 5.0))   // enhanced 900 m
        fit.u16(((520.0 + 500.0) * 5.0).toInt())      // field 2: 520 m
        fit.dataHeader(0)
        fit.u32(300_001L); fit.i32(Math.round(48.0 / semi).toInt()); fit.i32(Math.round(11.0 / semi).toInt())
        fit.u32(Math.round((901.0 + 500.0) * 5.0))   // enhanced 901 m
        fit.u16(0xFFFF)                               // field 2 invalid -> enhanced is used
        val a = ActivityFileImporter.parse(fit.finish(), "x.fit").activity!!
        assertEquals(520.0, a.timedRoute[0].altM!!, 1e-9)
        assertEquals(901.0, a.timedRoute[1].altM!!, 1e-9)
    }

    @Test
    fun gpxLeavesTimedRouteEmptyWithSwiftParity() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="51.0" lon="13.0"><ele>100</ele><time>2024-01-01T10:00:00Z</time></trkpt>
              <trkpt lat="51.001" lon="13.0"><time>2024-01-01T10:00:05Z</time></trkpt>
              <trkpt lat="51.002" lon="13.0"></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val a = ActivityFileImporter.parse(gpx.toByteArray(), "x.gpx").activity!!
        assertTrue(a.timedRoute.isEmpty())   // FIT only, like the Swift twin
        assertEquals(3, a.route.size)
    }

    @Test
    fun oneDecimalRoundsHalfAwayFromTheTenthsLikeSwift() {
        assertEquals("5.0", ActivityFileImporter.oneDecimal(5.0))
        assertEquals("3.1", ActivityFileImporter.oneDecimal(31 / 10.0))
        assertEquals("1.5", ActivityFileImporter.oneDecimal(1.45))   // 1.45 * 10 == 14.5 in binary64
        assertEquals("0.3", ActivityFileImporter.oneDecimal(0.25))
        assertEquals("-0.3", ActivityFileImporter.oneDecimal(-0.25))
        assertEquals("0.0", ActivityFileImporter.oneDecimal(Double.NaN))
        assertEquals(0L, ActivityFileImporter.roundHalfAwayExact(0.49999999999999994))
        assertEquals(404L, ActivityFileImporter.roundHalfAwayExact(403.72088623046875))
        assertEquals(-3L, ActivityFileImporter.roundHalfAwayExact(-2.5))
    }
}

// MARK: - FIT byte fixture builder (little-endian, test-only). Mirrors the Swift FitFixture.

private class FitFixture {
    private val records = ByteArrayOutputStream()

    fun definition(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>) {
        records.write(0x40 or (local and 0x0F))   // definition header, no dev fields
        records.write(0)                            // reserved
        records.write(0)                            // architecture: little-endian
        records.write(global and 0xFF)
        records.write((global shr 8) and 0xFF)
        records.write(fields.size)
        for (f in fields) {
            records.write(f.first and 0xFF)         // field def num
            records.write(f.second and 0xFF)        // size
            records.write(f.third and 0xFF)         // base type
        }
    }

    fun dataHeader(local: Int) { records.write(local and 0x0F) }

    fun u8(v: Int) { records.write(v and 0xFF) }
    fun u16(v: Int) { records.write(v and 0xFF); records.write((v shr 8) and 0xFF) }
    fun u32(v: Long) {
        records.write((v and 0xFF).toInt()); records.write(((v shr 8) and 0xFF).toInt())
        records.write(((v shr 16) and 0xFF).toInt()); records.write(((v shr 24) and 0xFF).toInt())
    }
    fun i32(v: Int) { u32(v.toLong() and 0xFFFFFFFFL) }

    fun finish(): ByteArray {
        val body = records.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(12); out.write(0x10); out.write(0x00); out.write(0x00) // headerSize, protocol, profile
        val size = body.size.toLong()
        out.write((size and 0xFF).toInt()); out.write(((size shr 8) and 0xFF).toInt())
        out.write(((size shr 16) and 0xFF).toInt()); out.write(((size shr 24) and 0xFF).toInt())
        out.write('.'.code); out.write('F'.code); out.write('I'.code); out.write('T'.code)
        out.write(body)
        out.write(0); out.write(0)   // trailing CRC (unchecked by the decoder)
        return out.toByteArray()
    }
}
