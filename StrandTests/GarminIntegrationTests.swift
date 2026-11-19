import XCTest
import WhoopStore
import WhoopProtocol
import StrandImport
@testable import Strand

/// The app-side half of the Garmin Connect integration (fork feature): duplicate handling, the stored
/// shapes, the "Send to Garmin Connect" inputs, the sync pass and the Personal API client. The pure
/// parsing and the skip-rule table are pinned in `StrandImport`'s `GarminConnectTests`.
final class GarminIntegrationTests: XCTestCase {

    private func row(_ start: Int, _ end: Int, sport: String, source: String, avgHr: Int? = 140,
                     maxHr: Int? = 170, distanceM: Double? = 30_000, energyKcal: Double? = 800,
                     strain: Double? = nil) -> WorkoutRow {
        WorkoutRow(startTs: start, endTs: end, sport: sport, source: source,
                   durationS: Double(end - start), energyKcal: energyKcal, avgHr: avgHr, maxHr: maxHr,
                   strain: strain, distanceM: distanceM, zonesJSON: nil, notes: nil, steps: nil)
    }

    // MARK: - Classification and duplicates

    func testGarminSourceClassifiesAsGarminAndIsReadOnly() {
        XCTAssertEqual(WorkoutSource.classify("garmin-connect"), .garmin)
        XCTAssertEqual(WorkoutSource.sourceLabel(row(0, 10, sport: "Cycling", source: "garmin-connect")), "garmin")
        XCTAssertFalse(WorkoutMerge.isMergeable(row(0, 10, sport: "Cycling", source: "garmin-connect")))
        XCTAssertEqual(Repository.garminConnectSource, GarminConnect.sourceId)
    }

    func testGarminRideBeatsTheSameRideFromHealthConnectOnARichnessTie() {
        let garmin = row(1_000, 6_400, sport: "Cycling", source: "garmin-connect")
        let hc = row(1_020, 6_390, sport: "Cycling", source: "health-connect")
        XCTAssertEqual(WorkoutSource.richness(garmin), WorkoutSource.richness(hc))
        for input in [[garmin, hc], [hc, garmin]] {
            let kept = WorkoutSource.dedupCrossSource(input)
            XCTAssertEqual(kept.map(\.source), ["garmin-connect"])
        }
        // Apple Health copy too.
        let apple = row(1_000, 6_400, sport: "Cycling", source: "apple-health")
        XCTAssertEqual(WorkoutSource.dedupCrossSource([apple, garmin]).map(\.source), ["garmin-connect"])
        let (kept, trace) = WorkoutSource.dedupCrossSourceTrace([hc, garmin])
        XCTAssertEqual(kept.map(\.source), ["garmin-connect"])
        XCTAssertTrue(trace.first?.contains("kept=garmin") ?? false, "\(trace)")
    }

    func testStrapDetectedBoutBesideAGarminRideIsDropped() {
        let garmin = row(1_000, 6_400, sport: "Cycling", source: "garmin-connect")
        let detected = row(1_200, 6_000, sport: "detected", source: "my-whoop-noop", strain: 12)
        XCTAssertEqual(WorkoutSource.dedupCrossSource([detected, garmin]).map(\.source), ["garmin-connect"])
    }

    func testGarminRideAndAManualRunInTheSameWindowAreBothKept() {
        let garmin = row(1_000, 6_400, sport: "Cycling", source: "garmin-connect")
        let manual = row(1_000, 6_400, sport: "Running", source: "manual")
        XCTAssertEqual(WorkoutSource.dedupCrossSource([garmin, manual]).count, 2)
    }

    func testGarminRoadRideAndHealthImportShareTheSportKey() {
        // FIT sport 2 decodes as "Cycling"; Garmin's list calls a road ride "road_biking"; HealthKit and
        // Health Connect both store "Cycling". All three must fold to one key or the dedup never pairs them.
        let fromFit = ActivityFileImporter.workoutSport(from: "Cycling")
        let fromTypeKey = ActivityFileImporter.workoutSport(from: "road_biking")
        XCTAssertEqual(WorkoutSource.sportKey(fromFit), WorkoutSource.sportKey("Cycling"))
        XCTAssertEqual(WorkoutSource.sportKey(fromTypeKey), WorkoutSource.sportKey("Cycling"))
    }

    func testGarminNamespaceIsReadButNeverDeleted() {
        XCTAssertTrue(Repository.workoutNamespaces(rawIds: ["strap"]).contains("garmin-connect"))
        XCTAssertFalse(Repository.deletableWorkoutNamespaces(rawIds: ["strap"]).contains("garmin-connect"))
    }

    // MARK: - Stored shapes

    func testRoutePointWithoutAltitudeStillDecodesAndNilIsOmitted() throws {
        let legacy = #"[{"lat":48.1,"lon":11.5,"accuracyM":4,"tMs":1700000000000}]"#
        let points = try JSONDecoder().decode([WorkoutRoutePoint].self, from: Data(legacy.utf8))
        XCTAssertEqual(points.first?.altM, nil)
        XCTAssertEqual(points.first?.tMs, 1_700_000_000_000)
        let encoded = String(data: try JSONEncoder().encode(points), encoding: .utf8) ?? ""
        XCTAssertFalse(encoded.contains("altM"))
        let withAlt = [WorkoutRoutePoint(lat: 1, lon: 2, accuracyM: 3, tMs: 4, altM: 512.5)]
        let roundTrip = try JSONDecoder().decode([WorkoutRoutePoint].self, from: JSONEncoder().encode(withAlt))
        XCTAssertEqual(roundTrip, withAlt)
    }

    private func activity(start: Int = 1_763_000_000, seconds: Int = 30) -> ActivityFile {
        let t0 = Date(timeIntervalSince1970: TimeInterval(start))
        let timed = (0...seconds).map { TimedRoutePoint(ts: start + $0, lat: 48 + Double($0) * 1e-4, lon: 11, altM: 500) }
        return ActivityFile(kind: .fit, start: t0, end: t0.addingTimeInterval(TimeInterval(seconds)),
                            sport: "Cycling", distanceM: 330, energyKcal: 20, avgHr: 140, maxHr: 150,
                            gpsPointCount: timed.count, hrSampleCount: 0,
                            route: timed.map { RoutePoint(lat: $0.lat, lon: $0.lon) },
                            trainingEffectAerobic: 3.0, timedRoute: timed)
    }

    func testWriterRowAndGarminRoute() throws {
        let a = activity()
        let r = ActivityStoreWriter.row(for: a, target: .garminConnect)
        XCTAssertEqual(r.source, "garmin-connect")
        XCTAssertEqual(r.sport, "Cycling")
        XCTAssertNil(r.strain)
        XCTAssertEqual(r.notes, a.importNote())
        XCTAssertEqual(ActivityStoreWriter.row(for: a, target: .activityFile).source, "activity-file")
        XCTAssertTrue(ActivityStoreWriter.Target.garminConnect.persistsRoute)
        XCTAssertFalse(ActivityStoreWriter.Target.activityFile.persistsRoute)

        let route = try XCTUnwrap(ActivityStoreWriter.route(for: a))
        let pts = try XCTUnwrap(route.points)
        XCTAssertEqual(pts.map { $0.tMs / 1000 }, [0, 5, 10, 15, 20, 25, 30].map { Int64(1_763_000_000 + $0) })
        XCTAssertEqual(pts.first?.altM, 500)
        XCTAssertTrue(pts.allSatisfy { $0.accuracyM == -1 })
        XCTAssertFalse(route.hasExportableMeasurements, "imported fixes must stay out of the HealthKit route write")
        XCTAssertEqual(RouteMath.decode(route.polyline).count, pts.count)
    }

    // MARK: - Send to Garmin Connect

    func testExportOfferedOnlyForNonGarminRowsWithSomethingToSend() {
        XCTAssertTrue(GarminWorkoutExport.isOffered(source: "manual", hasHr: true, hasRoute: false))
        XCTAssertTrue(GarminWorkoutExport.isOffered(source: "apple-health", hasHr: false, hasRoute: true))
        XCTAssertFalse(GarminWorkoutExport.isOffered(source: "manual", hasHr: false, hasRoute: false))
        XCTAssertFalse(GarminWorkoutExport.isOffered(source: "garmin-connect", hasHr: true, hasRoute: true))
    }

    func testExportTrackPrefersTimedPointsThenPolylineThenNothing() {
        let a = RouteMath.LatLng(48.0, 11.0), b = RouteMath.LatLng(48.001, 11.0)
        let timed = WorkoutRoute(polyline: RouteMath.encode([a, b]), distanceM: 111, points: [
            WorkoutRoutePoint(lat: a.lat, lon: a.lon, accuracyM: 4, tMs: 1_000_500, altM: 300),
            WorkoutRoutePoint(lat: b.lat, lon: b.lon, accuracyM: 4, tMs: 1_010_900, altM: nil),
        ])
        let t1 = GarminWorkoutExport.track(route: timed, startTs: 990, endTs: 1_020)
        XCTAssertEqual(t1.map(\.t), [1_000, 1_010])
        XCTAssertEqual(t1.map(\.altM), [300, nil])

        let untimed = WorkoutRoute(polyline: RouteMath.encode([a, b]), distanceM: 111)
        let t2 = GarminWorkoutExport.track(route: untimed, startTs: 1_000, endTs: 1_100)
        XCTAssertEqual(t2.count, 2)
        XCTAssertEqual(t2.first?.t, 1_000)
        XCTAssertEqual(t2.last?.t, 1_100)
        XCTAssertNil(t2.first?.altM)

        XCTAssertTrue(GarminWorkoutExport.track(route: nil, startTs: 0, endTs: 10).isEmpty)
    }

    func testExportWorkoutCarriesRowFieldsAndWindowedHr() {
        let r = row(1_000, 2_000, sport: "Running", source: "manual", energyKcal: 321)
        let hr = [HRSample(ts: 999, bpm: 90), HRSample(ts: 1_000, bpm: 100), HRSample(ts: 2_000, bpm: 150),
                  HRSample(ts: 2_001, bpm: 99)]
        let w = GarminWorkoutExport.workout(row: r, route: nil, hr: hr)
        XCTAssertEqual(w.startTs, 1_000)
        XCTAssertEqual(w.endTs, 2_000)
        XCTAssertEqual(w.timerS, 1_000)
        XCTAssertEqual(w.sport, "Running")
        XCTAssertEqual(w.energyKcal, 321)
        XCTAssertEqual(w.hr.map(\.t), [1_000, 2_000])
        XCTAssertTrue(w.track.isEmpty)
        // A valid FIT comes out even without a track.
        let data = GarminFitExporter.build(w, device: .default, utcOffsetS: 3_600)
        XCTAssertEqual(ActivityFileImporter.detectFormat(data: data), .fit)
    }

    func testExportDevicePreferenceDefaultsToForerunner() throws {
        let d = try XCTUnwrap(UserDefaults(suiteName: "garmin-test-\(UUID().uuidString)"))
        XCTAssertEqual(GarminSync.exportDevice(d), .forerunner945)
        d.set("tacxTrainingApp", forKey: "garmin.exportDevice")
        XCTAssertEqual(GarminSync.exportDevice(d), .tacxTrainingApp)
        d.set("bogus", forKey: "garmin.exportDevice")
        XCTAssertEqual(GarminSync.exportDevice(d), .forerunner945)
    }

    // MARK: - Sync pass

    private func fitFile(start: Int, seconds: Int = 600) -> Data {
        let w = GarminFitExporter.Workout(startTs: start, endTs: start + seconds, sport: "Cycling",
                                          track: [], hr: (0...seconds).map { .init(t: start + $0, bpm: 120) })
        return GarminFitExporter.build(w, device: .default, utcOffsetS: 0)
    }

    private final class Fake {
        var pages: [[GarminApi.ActivitySummary]] = []
        var listCalls: [Int] = []
        var downloads: [Int64] = []
        var written: [Int] = []
        var existing: [GarminImportRule.Existing] = []
        var downloadError: [Int64: Error] = [:]
        var fits: [Int64: Data] = [:]

        func engine() -> GarminSyncEngine {
            GarminSyncEngine(
                listPage: { start in
                    self.listCalls.append(start)
                    let i = start / GarminConnect.pageSize
                    return i < self.pages.count ? self.pages[i] : []
                },
                download: { id in
                    self.downloads.append(id)
                    if let e = self.downloadError[id] { throw e }
                    return self.fits[id] ?? Data()
                },
                existing: { _, _ in self.existing },
                write: { a in self.written.append(Int(a.start.timeIntervalSince1970)) }
            )
        }
    }

    func testSyncImportsOldestFirstAndSkipsKnownActivities() async throws {
        let now = 1_800_000_000
        let f = Fake()
        let a1 = GarminApi.ActivitySummary(activityId: 1, startTs: now - 3 * 86_400, durationS: 600)
        let a2 = GarminApi.ActivitySummary(activityId: 2, startTs: now - 2 * 86_400, durationS: 600)
        let a3 = GarminApi.ActivitySummary(activityId: 3, startTs: now - 86_400, durationS: 600)
        let a4 = GarminApi.ActivitySummary(activityId: 4, startTs: now - 3_600, durationS: 600)
        let old = GarminApi.ActivitySummary(activityId: 9, startTs: now - 40 * 86_400, durationS: 600)
        f.pages = [[a4, a3, a2, a1, old]]   // newest first, short page: one request
        f.fits = [1: fitFile(start: a1.startTs), 3: fitFile(start: a3.startTs), 4: fitFile(start: a4.startTs)]
        f.existing = [
            .init(startTs: a2.startTs + 1, endTs: a2.endTs, source: "garmin-connect"),   // already imported
            .init(startTs: a3.startTs - 60, endTs: a3.endTs, source: "manual"),          // sent from NOOP
        ]
        f.downloadError = [4: GarminClientError.api(.http(404))]                          // a manual entry

        let out = try await f.engine().run(cursorTs: nil, now: now)
        XCTAssertEqual(f.listCalls, [0])
        XCTAssertEqual(out.imported, 1)
        XCTAssertEqual(out.alreadyImported, 1)
        XCTAssertEqual(out.sentFromNoop, 1)
        XCTAssertEqual(out.unreadable, 1)
        XCTAssertEqual(out.newestStart, a4.startTs)
        XCTAssertEqual(f.downloads, [1, 4], "only the activities the rule imports are downloaded, oldest first")
        XCTAssertEqual(f.written, [a1.startTs])
    }

    func testSyncPagesUntilTheWindowAndStopsOnHardErrors() async throws {
        let now = 1_800_000_000
        let f = Fake()
        let full = (0..<GarminConnect.pageSize).map {
            GarminApi.ActivitySummary(activityId: Int64(100 + $0), startTs: now - 3_600 * ($0 + 1), durationS: 60)
        }
        let second = [GarminApi.ActivitySummary(activityId: 500, startTs: now - 10 * 86_400, durationS: 60)]
        f.pages = [full, second, full]
        // Cursor 5 days ago: window starts 7 days ago, so page 2 (10 days ago) ends paging.
        f.existing = full.map { .init(startTs: $0.startTs, endTs: $0.endTs, source: "garmin-connect") }
        let out = try await f.engine().run(cursorTs: now - 5 * 86_400, now: now)
        XCTAssertEqual(f.listCalls, [0, 20])
        XCTAssertEqual(out.alreadyImported, GarminConnect.pageSize)
        XCTAssertEqual(out.imported, 0)
        XCTAssertTrue(f.downloads.isEmpty)

        let g = Fake()
        g.pages = [[GarminApi.ActivitySummary(activityId: 1, startTs: now - 60, durationS: 30)]]
        g.downloadError = [1: GarminClientError.api(.rateLimited)]
        do {
            _ = try await g.engine().run(cursorTs: nil, now: now)
            XCTFail("a rate limit must stop the sync")
        } catch {
            XCTAssertEqual(error as? GarminClientError, .api(.rateLimited))
        }
    }

    func testUserFacingMessages() {
        XCTAssertEqual(GarminSync.message(for: GarminClientError.api(.rejectedKey)),
                       "Garmin rejected the API key. Create a new one in Garmin Connect.")
        XCTAssertEqual(GarminSync.message(for: GarminClientError.api(.rateLimited)),
                       "Garmin's rate limit was hit. Try again later.")
        XCTAssertTrue(GarminSync.message(for: GarminClientError.api(.http(502))).contains("502"))
        XCTAssertEqual(GarminSync.summary(.init()), "Up to date")
        XCTAssertEqual(GarminSync.summary(.init(imported: 2)), "Imported 2 activities")
    }

    // MARK: - Personal API client

    private actor Recorder {
        var requests: [URLRequest] = []
        func add(_ r: URLRequest) { requests.append(r) }
    }

    private func jwt(exp: Int) -> String {
        let payload = Data(#"{"exp":\#(exp)}"#.utf8).base64EncodedString()
            .replacingOccurrences(of: "=", with: "").replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        return "eyJhbGciOiJSUzI1NiJ9.\(payload).c2ln"
    }

    private func response(_ req: URLRequest, _ status: Int) -> HTTPURLResponse {
        HTTPURLResponse(url: req.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
    }

    func testClientSendsKeyThenBearerAndCachesTheToken() async throws {
        let rec = Recorder()
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let token = jwt(exp: 1_800_000_000 + 7_200)
        let client = GarminAPIClient(
            base: URL(string: "https://example.invalid")!, apiKey: { "secret-key" },
            transport: { req, _ in
                await rec.add(req)
                if req.url!.path.hasSuffix("/token") { return (Data((token + "\n").utf8), self.response(req, 200)) }
                return (Data(#"[{"activityId":1,"startTimeGMT":"2027-01-15 08:00:00","duration":60}]"#.utf8),
                        self.response(req, 200))
            },
            now: { now })
        let first = try await client.listActivities(start: 0)
        _ = try await client.listActivities(start: 20)
        XCTAssertEqual(first.map(\.activityId), [1])
        let reqs = await rec.requests
        XCTAssertEqual(reqs.count, 3, "one token exchange, then two list calls on the cached token")
        XCTAssertEqual(reqs[0].httpMethod, "POST")
        XCTAssertEqual(reqs[0].url?.path, "/personalapitoken-service/personal-api/personal-api-key/token")
        XCTAssertEqual(reqs[0].value(forHTTPHeaderField: "Authorization"), "ApiKey secret-key")
        XCTAssertEqual(reqs[0].value(forHTTPHeaderField: "Accept"), "*/*")
        XCTAssertEqual(reqs[1].value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
        XCTAssertEqual(reqs[1].value(forHTTPHeaderField: "Accept"), "application/json")
        XCTAssertEqual(reqs[1].url?.path, "/activitylist-service/activities/search/activities")
        XCTAssertEqual(reqs[1].url?.query, "start=0&limit=20")
        XCTAssertEqual(reqs[2].url?.query, "start=20&limit=20")
    }

    func testClientRefreshesATokenWithinAnHourOfExpiry() async throws {
        let rec = Recorder()
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let client = GarminAPIClient(
            base: URL(string: "https://example.invalid")!, apiKey: { "k" },
            transport: { req, _ in
                await rec.add(req)
                return (Data(self.jwt(exp: 1_800_000_000 + 3_000).utf8), self.response(req, 200))
            },
            now: { now })
        _ = try await client.accessToken()
        _ = try await client.accessToken()
        let count = await rec.requests.count
        XCTAssertEqual(count, 2, "a token with less than an hour left is not reused")
    }

    func testClientMapsStatusesAndRetriesARejectedBearerOnce() async throws {
        let rec = Recorder()
        let client = GarminAPIClient(
            base: URL(string: "https://example.invalid")!, apiKey: { "k" },
            transport: { req, _ in
                await rec.add(req)
                if req.url!.path.hasSuffix("/token") {
                    return (Data(self.jwt(exp: 4_000_000_000).utf8), self.response(req, 200))
                }
                return (Data(), self.response(req, 401))
            })
        do {
            _ = try await client.listActivities(start: 0)
            XCTFail("expected a rejected key")
        } catch {
            XCTAssertEqual(error as? GarminClientError, .api(.rejectedKey))
        }
        let paths = await rec.requests.map { $0.url!.path.hasSuffix("/token") ? "token" : "list" }
        XCTAssertEqual(paths, ["token", "list", "token", "list"])

        let limited = GarminAPIClient(
            base: URL(string: "https://example.invalid")!, apiKey: { "k" },
            transport: { req, _ in (Data(), self.response(req, 429)) })
        do { _ = try await limited.accessToken(); XCTFail() } catch {
            XCTAssertEqual(error as? GarminClientError, .api(.rateLimited))
        }
        let offline = GarminAPIClient(
            base: URL(string: "https://example.invalid")!, apiKey: { "k" },
            transport: { _, _ in throw URLError(.notConnectedToInternet) })
        do { _ = try await offline.accessToken(); XCTFail() } catch {
            XCTAssertEqual(error as? GarminClientError, .network)
        }
        let keyless = GarminAPIClient(base: URL(string: "https://example.invalid")!, apiKey: { nil },
                                      transport: { req, _ in (Data(), self.response(req, 200)) })
        do { _ = try await keyless.accessToken(); XCTFail() } catch {
            XCTAssertEqual(error as? GarminClientError, .noKey)
        }
    }

    func testClientDownloadUnzipsTheFitEntry() async throws {
        let fit = fitFile(start: 1_800_000_000, seconds: 5)
        let client = GarminAPIClient(
            base: URL(string: "https://example.invalid")!, apiKey: { "k" },
            transport: { req, maxBytes in
                if req.url!.path.hasSuffix("/token") {
                    return (Data(self.jwt(exp: 4_000_000_000).utf8), self.response(req, 200))
                }
                XCTAssertEqual(req.url?.path, "/download-service/files/activity/77")
                XCTAssertEqual(req.value(forHTTPHeaderField: "Accept"),
                               "application/zip, application/x-zip-compressed, application/octet-stream")
                XCTAssertEqual(maxBytes, 32 << 20)
                return (fit, self.response(req, 200))   // a bare FIT body is accepted as is
            })
        let data = try await client.downloadFit(activityId: 77)
        XCTAssertEqual(data, fit)
    }
}
