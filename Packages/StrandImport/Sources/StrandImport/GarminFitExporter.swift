import Foundation

/// Build a full-fidelity FIT activity that Garmin Connect accepts as a device recording: one record per
/// second carrying the strap's measured heart rate, the GPS position (interpolated between real timed
/// fixes), altitude, cumulative distance and speed, plus the file_id / file_creator / device_info / timer
/// events / lap / session / activity messages Garmin Connect's importer expects. The byte-for-byte twin of
/// the Kotlin `GarminFitExport`; `GarminFitExporterTests` pins the bytes the Kotlin test asserts.
///
/// Fork feature (jonashoelzel/noop): the file is uploaded by the user through Garmin Connect's web
/// "Import Data" page, and Garmin Connect then forwards it to Strava. The layout reproduces the file that
/// was verified against Garmin Connect on 2026-11-19 (accepted, "Summary Data: Original", HR graph,
/// forwarded to Strava).
///
/// The `device` identity is written into file_id and device_info and is what Garmin Connect shows as the
/// recording device. It is a label the user picks, not a measurement, which is why ``DeviceIdentity/noop``
/// (no Garmin claim) exists.
public enum GarminFitExporter {

    /// One timed GPS fix. `t` is Unix seconds. `altM` is metres above sea level when the fix had one.
    public struct TrackPoint: Sendable, Equatable {
        public var t: Int
        public var lat: Double
        public var lon: Double
        public var altM: Double?
        public init(t: Int, lat: Double, lon: Double, altM: Double? = nil) {
            self.t = t; self.lat = lat; self.lon = lon; self.altM = altM
        }
    }

    /// One heart-rate sample: Unix seconds + bpm.
    public struct HrPoint: Sendable, Equatable {
        public var t: Int
        public var bpm: Int
        public init(t: Int, bpm: Int) { self.t = t; self.bpm = bpm }
    }

    /// The device the file claims to come from. Raw values are persisted as the user's setting on both
    /// platforms, so they must not change.
    public enum DeviceIdentity: String, CaseIterable, Sendable {
        /// Garmin Forerunner 945 (manufacturer garmin, product 3113). The identity verified to import as an
        /// original device recording.
        case forerunner945 = "forerunner945"
        /// Tacx Training App for Windows (manufacturer tacx, product 20533), the identity
        /// peloton-to-garmin uses to get Garmin's server-side Training Effect for indoor sessions.
        case tacxTrainingApp = "tacxTrainingApp"
        /// No Garmin claim: manufacturer "development".
        case noop = "noop"

        public static let `default`: DeviceIdentity = .forerunner945

        public var manufacturer: Int {
            switch self {
            case .forerunner945: return 1
            case .tacxTrainingApp: return 89
            case .noop: return 255
            }
        }
        public var product: Int {
            switch self {
            case .forerunner945: return 3113
            case .tacxTrainingApp: return 20533
            case .noop: return 0
            }
        }
        /// file_id / device_info serial number (uint32z; 0 means "not set").
        public var serial: UInt32 {
            switch self {
            case .forerunner945: return 3_989_412_345
            case .tacxTrainingApp: return 1
            case .noop: return 0
            }
        }
        /// Software version ×100 (FIT scale 100), e.g. 2000 = 20.00.
        public var softwareVersion: Int {
            switch self {
            case .forerunner945: return 2000
            case .tacxTrainingApp: return 130
            case .noop: return 100
            }
        }
    }

    /// Everything the file is built from.
    public struct Workout: Sendable, Equatable {
        public var startTs: Int
        public var endTs: Int
        /// Moving/timer time in seconds when the workout had pauses; nil means the whole window.
        public var timerS: Double?
        public var sport: String?
        public var energyKcal: Double?
        /// Timed GPS fixes in ascending time order. Empty for an indoor / non-GPS workout.
        public var track: [TrackPoint]
        /// HR samples. Duplicate timestamps: the last one wins.
        public var hr: [HrPoint]
        public init(startTs: Int, endTs: Int, timerS: Double? = nil, sport: String?, energyKcal: Double? = nil,
                    track: [TrackPoint], hr: [HrPoint]) {
            self.startTs = startTs; self.endTs = endTs; self.timerS = timerS; self.sport = sport
            self.energyKcal = energyKcal; self.track = track; self.hr = hr
        }
    }

    /// Longest window exported, one record per second (a day; matches the manual-workout span cap).
    public static let maxSeconds = 86_400

    /// Suggested file name, stable for a workout so a re-export overwrites rather than piles up.
    /// Kotlin twin: `GarminFitExport.fileName`.
    public static func fileName(startTs: Int) -> String { "noop-\(startTs)-garmin.fit" }

    /// A legacy route with no per-point times: spread the points evenly across the window, exactly as
    /// ``RouteExporter`` does, so it still exports (with an evened-out pace curve).
    /// Kotlin twin: `GarminFitExport.untimedTrack`.
    public static func untimedTrack(_ points: [RoutePoint], startTs: Int, endTs: Int) -> [TrackPoint] {
        let times = RouteExporter.interpolatedTimes(points.count, startTs, endTs)
        return points.indices.map { TrackPoint(t: times[$0], lat: points[$0].lat, lon: points[$0].lon) }
    }

    // MARK: - Build

    /// The complete FIT file for `w`, claiming `device`; `utcOffsetS` sets the activity's local timestamp.
    /// Kotlin twin: `GarminFitExport.build`.
    public static func build(_ w: Workout, device: DeviceIdentity, utcOffsetS: Int) -> Data {
        let start = w.startTs
        let end = max(w.startTs, min(w.endTs, w.startTs + maxSeconds))
        let elapsed = end - start
        let canon = RouteExporter.canonicalSport(w.sport)

        var hrBySecond: [Int: Int] = [:]
        for p in w.hr where p.t >= start && p.t <= end { hrBySecond[p.t] = p.bpm }
        // Stable sort (index tiebreak) so equal timestamps keep input order, like Kotlin's sortedBy.
        let track = w.track.enumerated()
            .filter { $0.element.lat.isFinite && $0.element.lon.isFinite }
            .sorted { ($0.element.t, $0.offset) < ($1.element.t, $1.offset) }
            .map(\.element)

        // One sample per second.
        var recs: [Sample] = []
        recs.reserveCapacity(elapsed + 1)
        var j = 0
        var dist = 0.0
        var prev: (Double, Double)?
        for s in start...end {
            var pos: (Double, Double)?
            var alt: Double?
            if let first = track.first, let last = track.last, s >= first.t, s <= last.t {
                while j < track.count - 2 && track[j + 1].t <= s { j += 1 }
                let a = track[j], b = track[min(j + 1, track.count - 1)]
                let f = b.t == a.t ? 0.0 : Double(s - a.t) / Double(b.t - a.t)
                pos = (a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                if let a0 = a.altM, let b0 = b.altM { alt = a0 + (b0 - a0) * f }
            }
            if let pos {
                if let prev { dist += haversineMeters(prev.0, prev.1, pos.0, pos.1) }
                prev = pos
            }
            let bpm = hrBySecond[s].flatMap { (1...254).contains($0) ? $0 : nil }
            recs.append(Sample(t: s, pos: pos, altM: alt, bpm: bpm, distM: track.isEmpty ? nil : dist, speed: nil))
        }
        // Speed over a centred 10 s window of the distance series (GPS fix times are noisy).
        if !track.isEmpty {
            for i in recs.indices {
                let lo = max(0, i - 5), hi = min(recs.count - 1, i + 5)
                let dt = recs[hi].t - recs[lo].t
                recs[i].speed = dt > 0 ? (recs[hi].distM! - recs[lo].distM!) / Double(dt) : 0
            }
        }

        let bpms = recs.compactMap { $0.bpm }
        let avgHr: Int? = bpms.isEmpty ? nil : (2 * bpms.reduce(0, +) + bpms.count) / (2 * bpms.count)
        let maxHr: Int? = bpms.max()
        let totalDist: Double? = track.isEmpty ? nil : dist
        let avgSpeed: Double? = totalDist.map { elapsed > 0 ? $0 / Double(elapsed) : 0 }
        let maxSpeed: Double? = track.isEmpty ? nil : recs.compactMap { $0.speed }.max()
        let elapsedMs = UInt32(elapsed) &* 1000
        let timerMs: UInt32 = {
            if let t = w.timerS, t.isFinite, t > 0, t <= Double(elapsed) { return UInt32(roundHalfAway(t * 1000)) }
            return elapsedMs
        }()
        let startPos = recs.first(where: { $0.pos != nil })?.pos
        let endPos = recs.last(where: { $0.pos != nil })?.pos
        let fs = fitTime(start), fe = fitTime(end)

        var b: [UInt8] = []
        // file_id (0)
        defn(&b, 0, 0, [(0, 1, enumT), (1, 2, u16T), (2, 2, u16T), (3, 4, u32zT), (4, 4, u32T)])
        b.append(0); u8(&b, 4); u16(&b, device.manufacturer); u16(&b, device.product)
        u32(&b, device.serial); u32(&b, fs)
        // file_creator (49)
        defn(&b, 1, 49, [(0, 2, u16T), (1, 1, u8T)])
        b.append(1); u16(&b, device.softwareVersion); u8(&b, 0xFF)
        // device_info (23), the creator device
        defn(&b, 2, 23, [(253, 4, u32T), (0, 1, u8T), (2, 2, u16T), (3, 4, u32zT), (4, 2, u16T), (5, 2, u16T)])
        b.append(2); u32(&b, fs); u8(&b, 0); u16(&b, device.manufacturer); u32(&b, device.serial)
        u16(&b, device.product); u16(&b, device.softwareVersion)
        // event (21): timer start
        defn(&b, 3, 21, [(253, 4, u32T), (0, 1, enumT), (1, 1, enumT), (4, 1, u8T)])
        b.append(3); u32(&b, fs); u8(&b, 0); u8(&b, 0); u8(&b, 0)
        // record (20), one per second
        defn(&b, 4, 20, [(253, 4, u32T), (0, 4, s32T), (1, 4, s32T), (2, 2, u16T), (3, 1, u8T),
                         (5, 4, u32T), (6, 2, u16T)])
        for r in recs {
            b.append(4)
            u32(&b, fitTime(r.t))
            s32(&b, r.pos.map { semicircles($0.0) } ?? Int32.max)
            s32(&b, r.pos.map { semicircles($0.1) } ?? Int32.max)
            u16(&b, r.altM.map(altitude) ?? 0xFFFF)
            u8(&b, r.bpm ?? 0xFF)
            u32(&b, r.distM.map(centimetres) ?? 0xFFFF_FFFF)
            u16(&b, r.speed.map(mmPerSecond) ?? 0xFFFF)
        }
        // event (21): timer stop_all
        b.append(3); u32(&b, fe); u8(&b, 0); u8(&b, 4); u8(&b, 0)
        // lap (19)
        defn(&b, 5, 19, [(254, 2, u16T), (253, 4, u32T), (0, 1, enumT), (1, 1, enumT), (2, 4, u32T),
                         (3, 4, s32T), (4, 4, s32T), (5, 4, s32T), (6, 4, s32T), (7, 4, u32T), (8, 4, u32T),
                         (9, 4, u32T), (11, 2, u16T), (13, 2, u16T), (14, 2, u16T), (15, 1, u8T), (16, 1, u8T),
                         (24, 1, enumT), (25, 1, enumT)])
        b.append(5); u16(&b, 0); u32(&b, fe); u8(&b, 9); u8(&b, 1); u32(&b, fs)
        s32(&b, startPos.map { semicircles($0.0) } ?? Int32.max)
        s32(&b, startPos.map { semicircles($0.1) } ?? Int32.max)
        s32(&b, endPos.map { semicircles($0.0) } ?? Int32.max)
        s32(&b, endPos.map { semicircles($0.1) } ?? Int32.max)
        u32(&b, elapsedMs); u32(&b, timerMs)
        u32(&b, totalDist.map(centimetres) ?? 0xFFFF_FFFF)
        u16(&b, w.energyKcal.map(kcal) ?? 0xFFFF)
        u16(&b, avgSpeed.map(mmPerSecond) ?? 0xFFFF)
        u16(&b, maxSpeed.map(mmPerSecond) ?? 0xFFFF)
        u8(&b, avgHr ?? 0xFF); u8(&b, maxHr ?? 0xFF)
        u8(&b, 0); u8(&b, RouteExporter.fitSport(canon))
        // session (18)
        defn(&b, 6, 18, [(254, 2, u16T), (253, 4, u32T), (0, 1, enumT), (1, 1, enumT), (2, 4, u32T),
                         (3, 4, s32T), (4, 4, s32T), (5, 1, enumT), (6, 1, enumT), (7, 4, u32T), (8, 4, u32T),
                         (9, 4, u32T), (11, 2, u16T), (14, 2, u16T), (15, 2, u16T), (16, 1, u8T), (17, 1, u8T),
                         (25, 2, u16T), (26, 2, u16T), (28, 1, enumT)])
        b.append(6); u16(&b, 0); u32(&b, fe); u8(&b, 8); u8(&b, 1); u32(&b, fs)
        s32(&b, startPos.map { semicircles($0.0) } ?? Int32.max)
        s32(&b, startPos.map { semicircles($0.1) } ?? Int32.max)
        u8(&b, RouteExporter.fitSport(canon)); u8(&b, 0)
        u32(&b, elapsedMs); u32(&b, timerMs)
        u32(&b, totalDist.map(centimetres) ?? 0xFFFF_FFFF)
        u16(&b, w.energyKcal.map(kcal) ?? 0xFFFF)
        u16(&b, avgSpeed.map(mmPerSecond) ?? 0xFFFF)
        u16(&b, maxSpeed.map(mmPerSecond) ?? 0xFFFF)
        u8(&b, avgHr ?? 0xFF); u8(&b, maxHr ?? 0xFF)
        u16(&b, 0); u16(&b, 1); u8(&b, 0)
        // activity (34)
        defn(&b, 7, 34, [(253, 4, u32T), (0, 4, u32T), (1, 2, u16T), (2, 1, enumT), (3, 1, enumT),
                         (4, 1, enumT), (5, 4, u32T)])
        b.append(7); u32(&b, fe); u32(&b, timerMs); u16(&b, 1); u8(&b, 0); u8(&b, 26); u8(&b, 1)
        u32(&b, UInt32(clamping: Int64(fe) + Int64(utcOffsetS)))

        // 14-byte header (with its own CRC) + body + CRC over everything.
        var header: [UInt8] = []
        u8(&header, 14); u8(&header, 0x20); u16(&header, 2140); u32(&header, UInt32(b.count))
        header.append(contentsOf: Array(".FIT".utf8))
        u16(&header, RouteExporter.fitCrc(header))
        var out = header + b
        u16(&out, RouteExporter.fitCrc(out))
        return Data(out)
    }

    // MARK: - Internals

    private struct Sample {
        var t: Int
        var pos: (Double, Double)?
        var altM: Double?
        var bpm: Int?
        var distM: Double?
        var speed: Double?
    }

    private static let enumT = 0x00, u8T = 0x02, s32T = 0x85, u16T = 0x84, u32T = 0x86, u32zT = 0x8C

    /// Haversine metres, the same expression order as `RouteMath.haversineMeters` on both platforms.
    /// Kotlin twin: `GarminFitExport.haversineMeters`.
    static func haversineMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6_371_000.0
        let dLat = (lat2 - lat1) * Double.pi / 180.0
        let dLon = (lon2 - lon1) * Double.pi / 180.0
        let s = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * Double.pi / 180.0) * cos(lat2 * Double.pi / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(s.squareRoot(), (1 - s).squareRoot())
    }

    /// Round half away from zero, the twin of Kotlin `GarminFitExport.roundHalfAway`.
    static func roundHalfAway(_ v: Double) -> Int64 {
        if v >= 0 { return Int64((v + 0.5).rounded(.down)) }
        return Int64((v - 0.5).rounded(.up))
    }

    /// Unix seconds to FIT seconds, clamped to the valid uint32 range. Kotlin twin: `GarminFitExport.fitTime`.
    private static func fitTime(_ unix: Int) -> UInt32 {
        UInt32(min(max(0, unix - RouteExporter.fitEpoch), 0xFFFF_FFFE))
    }
    /// Degrees to FIT semicircles. Kotlin twin: `GarminFitExport.semicircles`.
    private static func semicircles(_ deg: Double) -> Int32 {
        Int32(clamping: roundHalfAway(deg * RouteExporter.semiPerDeg))
    }
    /// Metres to FIT distance (scale 100). Kotlin twin: `GarminFitExport.centimetres`.
    private static func centimetres(_ m: Double) -> UInt32 { UInt32(clamping: max(0, roundHalfAway(m * 100))) }
    /// m/s to FIT speed (scale 1000). Kotlin twin: `GarminFitExport.mmPerSecond`.
    private static func mmPerSecond(_ mps: Double) -> Int { Int(min(max(0, roundHalfAway(mps * 1000)), 0xFFFE)) }
    /// Metres to FIT altitude (scale 5, offset 500). Kotlin twin: `GarminFitExport.altitude`.
    private static func altitude(_ m: Double) -> Int { Int(min(max(0, roundHalfAway((m + 500) * 5)), 0xFFFE)) }
    /// Kilocalories to the FIT uint16 field. Kotlin twin: `GarminFitExport.kcal`.
    private static func kcal(_ k: Double) -> Int { Int(min(max(0, roundHalfAway(k)), 0xFFFE)) }

    private static func defn(_ b: inout [UInt8], _ local: Int, _ global: Int, _ fields: [(Int, Int, Int)]) {
        u8(&b, 0x40 | (local & 0x0F)); u8(&b, 0); u8(&b, 0); u16(&b, global); u8(&b, fields.count)
        for f in fields { u8(&b, f.0); u8(&b, f.1); u8(&b, f.2) }
    }
    private static func u8(_ b: inout [UInt8], _ v: Int) { b.append(UInt8(v & 0xFF)) }
    private static func u16(_ b: inout [UInt8], _ v: Int) { b.append(UInt8(v & 0xFF)); b.append(UInt8((v >> 8) & 0xFF)) }
    private static func u32(_ b: inout [UInt8], _ v: UInt32) {
        b.append(UInt8(v & 0xFF)); b.append(UInt8((v >> 8) & 0xFF))
        b.append(UInt8((v >> 16) & 0xFF)); b.append(UInt8((v >> 24) & 0xFF))
    }
    private static func s32(_ b: inout [UInt8], _ v: Int32) { u32(&b, UInt32(bitPattern: v)) }
}
