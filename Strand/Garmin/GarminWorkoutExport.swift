import Foundation
import StrandImport
import WhoopStore
import WhoopProtocol

/// Builds the "Send to Garmin Connect" FIT file for one stored workout (fork feature). The bytes come from
/// `GarminFitExporter`; this type only gathers the inputs, with the same rules as the Android twin:
///
/// - track: the recorded timed points when the route has them (`t = tMs / 1000`, altitude), else the stored
///   polyline spread evenly over the window (`GarminFitExporter.untimedTrack`), else none;
/// - hr: the measured samples of the workout window (`Repository.workoutExportHrSamples`);
/// - timer = the row's duration, energy and sport from the row, the UTC offset of the local zone at the
///   workout's end, and the device identity the user picked (`garmin.exportDevice`).
///
/// Nothing is sent anywhere: the file is written to the scratch directory and handed to the share sheet
/// (or a Save panel on macOS); the user uploads it on Garmin Connect's import page.
enum GarminWorkoutExport {

    /// Whether the detail screen offers the action: never for a row that came FROM Garmin Connect, and only
    /// when there is something to send (heart rate or a route).
    static func isOffered(source: String, hasHr: Bool, hasRoute: Bool) -> Bool {
        WorkoutSource.classify(source) != .garmin && (hasHr || hasRoute)
    }

    /// The exporter's track for a stored route.
    static func track(route: WorkoutRoute?, startTs: Int, endTs: Int) -> [GarminFitExporter.TrackPoint] {
        guard let route else { return [] }
        if let points = route.points, points.count >= 2 {
            let timed = points.compactMap { p -> GarminFitExporter.TrackPoint? in
                guard p.lat.isFinite, p.lon.isFinite, p.tMs > 0 else { return nil }
                return GarminFitExporter.TrackPoint(t: Int(p.tMs / 1000), lat: p.lat, lon: p.lon, altM: p.altM)
            }
            if timed.count >= 2 { return timed.sorted { $0.t < $1.t } }
        }
        let poly = RouteMath.decode(route.polyline)
        guard poly.count >= 2 else { return [] }
        return GarminFitExporter.untimedTrack(poly.map { RoutePoint(lat: $0.lat, lon: $0.lon) },
                                              startTs: startTs, endTs: endTs)
    }

    /// The exporter input for a row, from its stored route and HR samples. Pure.
    static func workout(row: WorkoutRow, route: WorkoutRoute?, hr: [HRSample]) -> GarminFitExporter.Workout {
        GarminFitExporter.Workout(
            startTs: row.startTs,
            endTs: row.endTs,
            timerS: row.durationS,
            sport: row.sport,
            energyKcal: row.energyKcal,
            track: track(route: route, startTs: row.startTs, endTs: row.endTs),
            hr: hr.filter { $0.ts >= row.startTs && $0.ts <= row.endTs }
                .map { GarminFitExporter.HrPoint(t: $0.ts, bpm: $0.bpm) }
        )
    }

    /// Local UTC offset (seconds) at `ts`.
    static func utcOffset(at ts: Int, zone: TimeZone = .current) -> Int {
        zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(ts)))
    }

    /// Gather the inputs, build the file off the main actor and write it to the scratch directory.
    /// Returns the file URL, or nil when the write failed.
    @MainActor
    static func buildFile(for row: WorkoutRow, repo: Repository) async -> URL? {
        let route = RouteStore.loadWithPoints(startTs: row.startTs, sport: row.sport)
        let hr = await repo.workoutExportHrSamples(from: row.startTs, to: row.endTs, source: row.source)
        let device = GarminSync.exportDevice()
        let name = GarminFitExporter.fileName(startTs: row.startTs)
        let offset = utcOffset(at: row.endTs)
        return await Task.detached(priority: .userInitiated) { () -> URL? in
            let data = GarminFitExporter.build(workout(row: row, route: route, hr: hr), device: device, utcOffsetS: offset)
            let url = NoopScratch.file(name)
            do { try data.write(to: url, options: .atomic) } catch { return nil }
            return url
        }.value
    }
}
