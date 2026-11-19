import XCTest
import ZIPFoundation
@testable import StrandImport

/// Pins the pure half of the Garmin Connect import: the FIT reader's Garmin fields, the import note, the
/// skip rule (same table as the Kotlin `GarminImportRuleTest`), and the Personal API response handling.
final class GarminConnectTests: XCTestCase {

    // MARK: - FIT fields

    /// A ride: a session carrying Training Effect, load, power and cadence, and records that carry only
    /// `enhanced_altitude` (field 78), the way an Edge 840 writes them.
    private func garminRide(te: UInt8 = 50, anaerobic: UInt8 = 31, loadRaw: Int32 = Int32(403.72 * 65_536),
                            power: UInt16 = 210, cadence: UInt8 = 88) -> Data {
        var fit = FitFixture()
        let start: UInt32 = 1_100_000_000
        fit.definition(local: 0, global: 20, fields: [
            (253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85), (3, 1, 0x02), (78, 4, 0x86),
        ])
        let semi = 180.0 / 2_147_483_648.0
        for k in 0..<3 {
            fit.dataHeader(local: 0)
            fit.u32(start + UInt32(k))
            fit.i32(Int32((48.0 + Double(k) * 0.0001) / semi))
            fit.i32(Int32(11.0 / semi))
            fit.u8(UInt8(130 + k))
            fit.u32(UInt32((520.0 + Double(k) + 500.0) * 5.0))   // 520, 521, 522 m
        }
        fit.definition(local: 1, global: 18, fields: [
            (2, 4, 0x86), (5, 1, 0x00), (7, 4, 0x86), (24, 1, 0x02), (137, 1, 0x02),
            (168, 4, 0x85), (20, 2, 0x84), (18, 1, 0x02),
        ])
        fit.dataHeader(local: 1)
        fit.u32(start)
        fit.u8(2)                 // cycling
        fit.u32(2_000)            // 2 s elapsed
        fit.u8(te)
        fit.u8(anaerobic)
        fit.i32(loadRaw)
        fit.u16(power)
        fit.u8(cadence)
        return fit.finish()
    }

    func testFitReadsGarminSessionFieldsAndEnhancedAltitude() throws {
        let a = try XCTUnwrap(ActivityFileImporter.parse(data: garminRide(), filename: "ride.fit").activity)
        XCTAssertEqual(a.trainingEffectAerobic, 5.0)
        XCTAssertEqual(a.trainingEffectAnaerobic ?? 0, 3.1, accuracy: 1e-9)
        XCTAssertEqual(a.trainingLoad ?? 0, 403.72, accuracy: 0.0001)
        XCTAssertEqual(a.avgPowerW, 210)
        XCTAssertEqual(a.avgCadence, 88)
        XCTAssertEqual(ActivityFileImporter.workoutSport(from: a.sport), "Cycling")
        // The timed track: every record had a time and a coordinate, altitude from field 78.
        let fitBase = 631_065_600 + 1_100_000_000
        XCTAssertEqual(a.timedRoute.map(\.ts), [fitBase, fitBase + 1, fitBase + 2])
        XCTAssertEqual(a.timedRoute.map { $0.altM ?? -1 }, [520, 521, 522])
        XCTAssertEqual(a.timedRoute.first?.lat ?? 0, 48.0, accuracy: 1e-6)
        XCTAssertEqual(a.route.count, 3)   // the untimed route is unchanged
        // Ascent comes from the field-78 elevations now that they are decoded (520→521→522: each step is
        // under the 1 m hysteresis floor, so 0).
        XCTAssertEqual(a.ascentM, 0)
    }

    func testFitGarminFieldsInvalidSentinelsReadAsAbsent() throws {
        let a = try XCTUnwrap(ActivityFileImporter.parse(
            data: garminRide(te: 0xFF, anaerobic: 0xFF, loadRaw: 0x7FFF_FFFF, power: 0xFFFF, cadence: 0xFF),
            filename: "ride.fit").activity)
        XCTAssertNil(a.trainingEffectAerobic)
        XCTAssertNil(a.trainingEffectAnaerobic)
        XCTAssertNil(a.trainingLoad)
        XCTAssertNil(a.avgPowerW)
        XCTAssertNil(a.avgCadence)
        XCTAssertEqual(a.importNote(), "Imported FIT · 0.02 km · 3 GPS points · 3 HR samples")
    }

    func testFieldTwoAltitudeWinsOverEnhancedAltitude() throws {
        var fit = FitFixture()
        fit.definition(local: 0, global: 20, fields: [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85), (78, 4, 0x86), (2, 2, 0x84)])
        let semi = 180.0 / 2_147_483_648.0
        fit.dataHeader(local: 0)
        fit.u32(1_000_000); fit.i32(Int32(48.0 / semi)); fit.i32(Int32(11.0 / semi))
        fit.u32(UInt32((900.0 + 500) * 5)); fit.u16(UInt16((100.0 + 500) * 5))
        fit.dataHeader(local: 0)
        fit.u32(1_000_001); fit.i32(Int32(48.0 / semi)); fit.i32(Int32(11.0 / semi))
        fit.u32(UInt32((901.0 + 500) * 5)); fit.u16(0xFFFF)   // field 2 invalid → field 78 used
        let a = try XCTUnwrap(ActivityFileImporter.parse(data: fit.finish(), filename: "x.fit").activity)
        XCTAssertEqual(a.timedRoute.map { $0.altM ?? -1 }, [100, 901])
    }

    func testImportNoteAppendsGarminFiguresInOrder() throws {
        let a = try XCTUnwrap(ActivityFileImporter.parse(data: garminRide(), filename: "ride.fit").activity)
        XCTAssertEqual(a.importNote(),
                       "Imported FIT · 0.02 km · 3 GPS points · 3 HR samples · Training Effect 5.0 aerobic · 3.1 anaerobic · load 404 · avg 210 W")
    }

    func testImportNoteFormattingEdges() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        var a = ActivityFile(kind: .fit, start: t0, end: t0.addingTimeInterval(60))
        a.trainingEffectAnaerobic = 2.0
        XCTAssertEqual(a.importNote(), "Imported FIT · Training Effect 2.0 anaerobic")
        a.trainingEffectAnaerobic = nil
        a.trainingLoad = 12.5            // half away from zero
        XCTAssertEqual(a.importNote(), "Imported FIT · load 13")
        a.trainingLoad = 12.4999
        XCTAssertEqual(a.importNote(), "Imported FIT · load 12")
        a.trainingLoad = nil
        a.trainingEffectAerobic = 0.0
        a.avgPowerW = 0
        XCTAssertEqual(a.importNote(), "Imported FIT · Training Effect 0.0 aerobic · avg 0 W")
        XCTAssertEqual(ActivityFile.oneDecimal(3.05), "3.1")
        XCTAssertEqual(ActivityFile.oneDecimal(4.94), "4.9")
        // Same cases as the Kotlin `oneDecimalRoundsHalfAwayFromTheTenthsLikeSwift`.
        XCTAssertEqual(ActivityFile.oneDecimal(5.0), "5.0")
        XCTAssertEqual(ActivityFile.oneDecimal(31 / 10.0), "3.1")
        XCTAssertEqual(ActivityFile.oneDecimal(1.45), "1.5")
        XCTAssertEqual(ActivityFile.oneDecimal(0.25), "0.3")
        XCTAssertEqual(ActivityFile.oneDecimal(-0.25), "-0.3")
        XCTAssertEqual(ActivityFile.oneDecimal(.nan), "0.0")
        a.trainingEffectAerobic = nil
        a.avgPowerW = nil
        a.trainingLoad = -2.5
        XCTAssertEqual(a.importNote(), "Imported FIT · load -3")
    }

    /// A real Edge 840 file, read only when `GARMIN_REAL_FIT` points at one. Not committed: it is personal
    /// data. Run with `GARMIN_REAL_FIT=/path/to/file.fit swift test --filter GarminConnectTests`.
    func testRealEdgeFileWhenProvided() throws {
        guard let path = ProcessInfo.processInfo.environment["GARMIN_REAL_FIT"],
              let data = FileManager.default.contents(atPath: path) else {
            throw XCTSkip("set GARMIN_REAL_FIT to a Garmin FIT file to run this")
        }
        let r = ActivityFileImporter.parse(data: data, filename: "activity.fit")
        let a = try XCTUnwrap(r.activity)
        print("real FIT: sport=\(a.sport ?? "nil") start=\(a.start) end=\(a.end) gps=\(a.gpsPointCount) "
              + "hr=\(a.hrSampleCount) timed=\(a.timedRoute.count) alt=\(a.timedRoute.compactMap(\.altM).count) "
              + "ascent=\(a.ascentM ?? -1) te=\(String(describing: a.trainingEffectAerobic)) "
              + "an=\(String(describing: a.trainingEffectAnaerobic)) load=\(String(describing: a.trainingLoad)) "
              + "power=\(String(describing: a.avgPowerW)) cad=\(String(describing: a.avgCadence))")
        print("real FIT note: \(a.importNote())")
        XCTAssertEqual(a.trainingEffectAerobic, 5.0)
        XCTAssertEqual(a.trainingEffectAnaerobic ?? 0, 3.1, accuracy: 1e-9)
        XCTAssertEqual(a.trainingLoad ?? 0, 403.72, accuracy: 0.01)
        XCTAssertNil(a.avgPowerW)
        XCTAssertEqual(ActivityFileImporter.workoutSport(from: a.sport), "Cycling")
        XCTAssertFalse(a.timedRoute.isEmpty)
        XCTAssertTrue(a.timedRoute.contains { $0.altM != nil }, "enhanced_altitude must be decoded")
    }

    // MARK: - Skip rule (identical table on Android)

    func testImportRuleTable() {
        typealias E = GarminImportRule.Existing
        let cases: [([E], GarminImportRule.Decision)] = [
            ([], .importActivity),
            ([E(startTs: 1001, endTs: 1990, source: "garmin-connect")], .alreadyImported),
            ([E(startTs: 1003, endTs: 2000, source: "garmin-connect")], .importActivity),
            ([E(startTs: 1100, endTs: 1900, source: "manual")], .sentFromNoop),
            ([E(startTs: 1600, endTs: 2600, source: "manual")], .importActivity),
            ([E(startTs: 1400, endTs: 2500, source: "manual")], .sentFromNoop),
            ([E(startTs: 1000, endTs: 2000, source: "health-connect")], .importActivity),
            ([E(startTs: 1000, endTs: 2000, source: "activity-file")], .importActivity),
        ]
        for (existing, expected) in cases {
            XCTAssertEqual(GarminImportRule.decide(activityStart: 1000, activityEnd: 2000, existing: existing),
                           expected, "existing=\(existing)")
        }
        XCTAssertEqual(GarminImportRule.Decision.importActivity.rawValue, "import")
    }

    // MARK: - Personal API

    private func jwt(payload: String) -> String {
        func b64url(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return b64url(#"{"alg":"RS256","typ":"JWT"}"#) + "." + b64url(payload) + ".c2ln"
    }

    func testJwtExpiry() {
        XCTAssertEqual(GarminApi.jwtExpiry(jwt(payload: #"{"sub":"x","exp":1763560000}"#)), 1_763_560_000)
        // A payload whose base64url length needs padding.
        XCTAssertEqual(GarminApi.jwtExpiry(jwt(payload: #"{"exp":1763560001,"a":"bb"}"#)), 1_763_560_001)
        XCTAssertNil(GarminApi.jwtExpiry(jwt(payload: #"{"sub":"x"}"#)))
        XCTAssertNil(GarminApi.jwtExpiry("not-a-jwt"))
        XCTAssertNil(GarminApi.jwtExpiry("a.%%%.c"))
    }

    func testParseToken() {
        let t = jwt(payload: #"{"exp":1}"#)
        XCTAssertEqual(GarminApi.parseToken(Data("  \(t)\n".utf8)), t)
        XCTAssertEqual(GarminApi.parseToken(Data("\"\(t)\"".utf8)), t)
        XCTAssertNil(GarminApi.parseToken(Data("<html>error</html>".utf8)))
        XCTAssertNil(GarminApi.parseToken(Data()))
    }

    func testParseActivityList() throws {
        let json = """
        [
          {"activityId": 25077379197, "activityName": "Morning Ride", "startTimeGMT": "2026-11-15 08:01:02",
           "duration": 5452.9, "activityType": {"typeKey": "road_biking", "typeId": 10}, "manufacturer": "GARMIN"},
          {"activityId": "42", "startTimeGMT": "2026-11-14T07:00:00", "duration": 60},
          {"activityName": "no id", "startTimeGMT": "2026-11-14 07:00:00"},
          {"activityId": 7, "startTimeGMT": "garbage"}
        ]
        """
        let list = try XCTUnwrap(GarminApi.parseActivityList(Data(json.utf8)))
        XCTAssertEqual(list.count, 2)
        XCTAssertEqual(list[0].activityId, 25_077_379_197)
        XCTAssertEqual(list[0].startTs, 1_794_729_662)   // 2026-11-15T08:01:02Z
        XCTAssertEqual(list[0].endTs, 1_794_729_662 + 5_453)
        XCTAssertEqual(list[0].typeKey, "road_biking")
        XCTAssertEqual(list[0].name, "Morning Ride")
        XCTAssertEqual(list[0].manufacturer, "GARMIN")
        XCTAssertEqual(list[1].activityId, 42)
        XCTAssertNil(GarminApi.parseActivityList(Data(#"{"error":"x"}"#.utf8)))
        XCTAssertEqual(GarminApi.parseActivityList(Data("[]".utf8)), [])
    }

    func testParseStartTimeAgainstFoundation() {
        let f = ISO8601DateFormatter()
        for s in ["1970-01-01 00:00:00", "2000-02-29 23:59:59", "2026-11-19 12:34:56", "2099-12-31 00:00:01"] {
            let iso = s.replacingOccurrences(of: " ", with: "T") + "Z"
            XCTAssertEqual(GarminApi.parseStartTimeGMT(s), Int(f.date(from: iso)!.timeIntervalSince1970), s)
        }
        XCTAssertEqual(GarminApi.parseStartTimeGMT("2026-11-19 12:34:56.0"), GarminApi.parseStartTimeGMT("2026-11-19 12:34:56"))
        XCTAssertNil(GarminApi.parseStartTimeGMT("2026-13-01 00:00:00"))
        XCTAssertNil(GarminApi.parseStartTimeGMT(""))
    }

    func testFailureMapping() {
        XCTAssertNil(GarminApi.failure(forStatus: 200))
        XCTAssertEqual(GarminApi.failure(forStatus: 401), .rejectedKey)
        XCTAssertEqual(GarminApi.failure(forStatus: 403), .rejectedKey)
        XCTAssertEqual(GarminApi.failure(forStatus: 429), .rateLimited)
        XCTAssertEqual(GarminApi.failure(forStatus: 500), .http(500))
    }

    private func zip(_ entries: [(String, Data)]) throws -> Data {
        let archive = try XCTUnwrap(try Archive(accessMode: .create))
        for (path, data) in entries {
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count),
                                 compressionMethod: .deflate) { position, size in
                let start = data.startIndex + Int(position)
                return data.subdata(in: start ..< start + size)
            }
        }
        return try XCTUnwrap(archive.data)
    }

    func testFitExtractedFromDownloadZip() throws {
        let ride = garminRide()
        let body = try zip([("readme.txt", Data("x".utf8)), ("25077379197_ACTIVITY.FIT", ride)])
        XCTAssertEqual(GarminApi.fitData(fromDownload: body), ride)
        // A bare FIT body passes straight through.
        XCTAssertEqual(GarminApi.fitData(fromDownload: ride), ride)
        // No FIT entry, garbage, and an entry over the cap all yield nil.
        XCTAssertNil(GarminApi.fitData(fromDownload: try zip([("a.txt", Data("x".utf8))])))
        XCTAssertNil(GarminApi.fitData(fromDownload: Data("PK garbage".utf8)))
        let big = try zip([("big.fit", Data(count: 200_000))])
        XCTAssertNil(GarminApi.fitData(fromDownload: big, maxBytes: 100_000))
    }

    // MARK: - Sync window, trigger and paging

    func testSyncWindowAndAutoSyncGate() {
        let now = 2_000_000_000
        XCTAssertEqual(GarminConnect.syncWindowStart(cursorTs: nil, now: now), now - 30 * 86_400)
        XCTAssertEqual(GarminConnect.syncWindowStart(cursorTs: 1_900_000_000, now: now), 1_900_000_000 - 2 * 86_400)
        XCTAssertTrue(GarminConnect.shouldAutoSync(hasKey: true, autoSync: true, lastAttemptTs: nil, now: now))
        XCTAssertTrue(GarminConnect.shouldAutoSync(hasKey: true, autoSync: true, lastAttemptTs: now - 3_600, now: now))
        XCTAssertFalse(GarminConnect.shouldAutoSync(hasKey: true, autoSync: true, lastAttemptTs: now - 3_599, now: now))
        XCTAssertFalse(GarminConnect.shouldAutoSync(hasKey: false, autoSync: true, lastAttemptTs: nil, now: now))
        XCTAssertFalse(GarminConnect.shouldAutoSync(hasKey: true, autoSync: false, lastAttemptTs: nil, now: now))
    }

    func testPageEndsWindow() {
        let a = GarminApi.ActivitySummary(activityId: 1, startTs: 500, durationS: 10)
        let b = GarminApi.ActivitySummary(activityId: 2, startTs: 1_500, durationS: 10)
        XCTAssertTrue(GarminApi.pageEndsWindow([], windowStart: 1_000))
        XCTAssertTrue(GarminApi.pageEndsWindow([b, a], windowStart: 1_000))
        XCTAssertFalse(GarminApi.pageEndsWindow([b], windowStart: 1_000))
    }

    func testDownsampleKeepsOnePointPerFiveSeconds() {
        let pts = (0...12).map { TimedRoutePoint(ts: 100 + $0, lat: 48, lon: 11) }
        XCTAssertEqual(GarminConnect.downsample(pts).map(\.ts), [100, 105, 112])
        let sparse = [0, 7, 20].map { TimedRoutePoint(ts: $0, lat: 1, lon: 1) }
        XCTAssertEqual(GarminConnect.downsample(sparse).map(\.ts), [0, 7, 20])
        XCTAssertEqual(GarminConnect.downsample([]).count, 0)
    }
}
