import Foundation
import StrandImport
import WhoopStore
import WhoopProtocol   // Streams / HRSample, to persist an imported activity's per-sample HR

/// Writes one parsed `ActivityFile` into the local store: the workout row, its per-sample HR stream, the
/// day's step total, the source's device registration and (for sources that keep one) the timed route.
///
/// Shared by the manual workout-file import (source `activity-file`) and the Garmin Connect import (source
/// `garmin-connect`), so the two persist identically except for the source id. Formerly inlined in
/// `DataSourcesView.importActivityFile`; the behaviour for `activity-file` is unchanged.
///
/// #137: the imported ride's REAL per-sample HR is persisted as an HR stream under the source's deviceId,
/// and the source is registered as an `.activityFile` device. Together that lets a strap-less day's ride
/// light the day Effort ring: the per-day owner resolver (`IntelligenceEngine.resolveDayOwner`) treats an
/// `.activityFile` device as the LOWEST-ranked candidate (priority 3, below whole-day imports at 2) and,
/// being the only source with HR that day, picks it as the day owner. On a day the user ALSO wore the
/// strap, the strap (priority 0/1) wins ownership and the imported HR is ignored for Effort. The workout
/// row itself still stores `strain = nil` (a per-workout strain is never fabricated).
enum ActivityStoreWriter {

    /// Where an activity is written and how its source is registered.
    struct Target: Equatable {
        /// Workout `source` and the deviceId of the HR stream / day metrics.
        let sourceId: String
        /// Brand shown for the registered device.
        let deviceBrand: String
        /// Whether to keep the route in `RouteStore` (timed points, downsampled). The file import never
        /// did on Apple; the Garmin import does, so its rides draw a map.
        let persistsRoute: Bool

        static let activityFile = Target(sourceId: ActivityFileImporter.sourceId,
                                         deviceBrand: "Workout files", persistsRoute: false)
        static let garminConnect = Target(sourceId: GarminConnect.sourceId,
                                          deviceBrand: GarminConnect.displayName, persistsRoute: true)
    }

    /// The workout row an activity is stored as. Pure, so the mapping is unit-testable.
    static func row(for activity: ActivityFile, target: Target) -> WorkoutRow {
        WorkoutRow(
            startTs: Int(activity.start.timeIntervalSince1970),
            endTs: Int(activity.end.timeIntervalSince1970),
            sport: ActivityFileImporter.workoutSport(from: activity.sport),
            source: target.sourceId,
            durationS: activity.durationS,
            energyKcal: activity.energyKcal,
            avgHr: activity.avgHr,
            maxHr: activity.maxHr,
            strain: nil,                         // never a fabricated cardiovascular strain
            distanceM: activity.distanceM,
            zonesJSON: nil,
            notes: activity.importNote(),
            steps: activity.steps                 // #1058: per-session steps, summed into the day below
        )
    }

    /// The route kept for a Garmin import: the timed track downsampled to at most one point per
    /// `GarminConnect.routeSpacingS` seconds, as a polyline plus its points. Accuracy is unknown for an
    /// imported fix, so it is stored as -1 (CoreLocation's "invalid" convention), which also keeps these
    /// points out of the HealthKit route write-back, whose gate requires a real accuracy. nil when fewer
    /// than two points survive.
    static func route(for activity: ActivityFile) -> WorkoutRoute? {
        let timed = GarminConnect.downsample(activity.timedRoute)
        if timed.count >= 2 {
            let latLngs = timed.map { RouteMath.LatLng($0.lat, $0.lon) }
            let points = timed.map {
                WorkoutRoutePoint(lat: $0.lat, lon: $0.lon, accuracyM: -1, tMs: Int64($0.ts) * 1000, altM: $0.altM)
            }
            return WorkoutRoute(polyline: RouteMath.encode(latLngs), distanceM: RouteMath.totalMeters(latLngs),
                                points: points)
        }
        let plain = activity.route.map { RouteMath.LatLng($0.lat, $0.lon) }
        guard plain.count >= 2 else { return nil }
        return WorkoutRoute(polyline: RouteMath.encode(plain), distanceM: RouteMath.totalMeters(plain))
    }

    /// Persist `activity` under `target`. `register` adds the source to the device registry
    /// (`AppModel.registerDevice(_:makeActive: false)` in the app). Throws only when the workout row
    /// itself cannot be written; the HR stream and day steps are best-effort, as they always were.
    @MainActor @discardableResult
    static func write(_ activity: ActivityFile, target: Target, store: WhoopStore,
                      register: (PairedDevice) -> Void) async throws -> WorkoutRow {
        let row = row(for: activity, target: target)
        try await store.upsertWorkouts([row], deviceId: target.sourceId)

        // #137 (A): persist the real per-sample HR under the source. The insert is keyed on (deviceId, ts),
        // so re-importing the same activity is idempotent (an identical ts overwrites, never duplicates).
        // Skipped when the file carried no timestamped HR (a pure GPS track).
        if !activity.hrSamples.isEmpty {
            let hr = activity.hrSamples.map { HRSample(ts: $0.ts, bpm: $0.bpm) }
            _ = try? await store.insert(Streams(hr: hr), deviceId: target.sourceId)
        }

        // #1058: recompute the day's step total for this source as the SUM over ALL that day's sessions, so
        // a second activity on the same day ADDS to the first instead of clobbering it. Idempotent on
        // re-import (the row, keyed on startTs+sport, is replaced, not duplicated). Only when THIS activity
        // contributed steps (a foot sport); a ride leaves the day's step total untouched.
        if (activity.steps ?? 0) > 0 {
            let dayStart = Calendar.current.startOfDay(for: activity.start)
            let dayEnd = Calendar.current.date(byAdding: .day, value: 1, to: dayStart)
                ?? dayStart.addingTimeInterval(86_400)
            let daySteps = (try? await store.sumWorkoutSteps(
                deviceId: target.sourceId,
                from: Int(dayStart.timeIntervalSince1970),
                to: Int(dayEnd.timeIntervalSince1970))) ?? 0
            if daySteps > 0 {
                let metric = DailyMetric(
                    day: Repository.localDayKey(activity.start),
                    totalSleepMin: nil,
                    efficiency: nil,
                    deepMin: nil,
                    remMin: nil,
                    lightMin: nil,
                    disturbances: nil,
                    restingHr: nil,
                    avgHrv: nil,
                    recovery: nil,
                    strain: nil,
                    exerciseCount: nil,
                    steps: daySteps
                )
                try? await store.upsertDailyMetrics([metric], deviceId: target.sourceId)
            }
        }

        if target.persistsRoute, let route = route(for: activity) {
            RouteStore.store(route, startTs: row.startTs, sport: row.sport)
        }

        // #137 (B1): register the source as an `.activityFile` device so the per-day owner resolver can pick
        // it on a strap-less day (it iterates the registry's paired devices; an unregistered source is
        // invisible to it). Priority 3, below whole-day imports (2). status `.paired`, NEVER `.active`, so
        // it can never displace the live strap; capability `.hr` marks what the source CAN provide.
        let now = Int(Date().timeIntervalSince1970)
        register(PairedDevice(
            id: target.sourceId,
            brand: target.deviceBrand,
            model: "",
            sourceKind: .activityFile,
            capabilities: [.hr],
            status: .paired,
            addedAt: now,
            lastSeenAt: now
        ))
        return row
    }
}
