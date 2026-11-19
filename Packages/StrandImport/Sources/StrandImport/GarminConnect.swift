import Foundation
import ZIPFoundation

// MARK: - Garmin Connect (fork feature): shared constants + the pure half of the Personal API import
//
// Fork feature (jonashoelzel/noop). The user's Garmin activities (for example Edge rides) are pulled into
// NOOP through Garmin's official Personal API, and NOOP workouts can be sent to Garmin Connect as FIT files.
// Everything here is pure (no networking, no store) so it is unit-tested with `swift test`; the URLSession
// client, Keychain and the store writes live in the app target (`Strand/Garmin/`).
//
// Every constant below has a byte-identical Kotlin twin in `com.noop.garmin` (settings keys, the source id,
// the skip rule and its test table), because both platforms read and write the same kinds of rows.

/// Shared constants for the Garmin Connect integration.
public enum GarminConnect {
    /// Workout `source` AND HR-stream `deviceId` for activities imported from Garmin Connect.
    public static let sourceId = "garmin-connect"
    /// Display name of the registered device.
    public static let displayName = "Garmin Connect"

    /// Personal API base URL.
    public static let apiBase = "https://personal.connectapi.garmin.com"
    /// Garmin Connect page where the user creates a Personal API key.
    public static let keyPageURL = "https://connect.garmin.com/app/settings/personalApiToken"
    /// Garmin Connect web "Import Data" page, where an exported FIT file is uploaded.
    public static let importPageURL = "https://connect.garmin.com/modern/import-data"

    /// Keychain service holding the Personal API key (Apple). Android: EncryptedSharedPreferences file
    /// `garmin_secure`, key `personalApiKey`.
    public static let keychainService = "noop.garmin.personalApiKey"

    /// Preference keys (UserDefaults / SharedPreferences), identical on both platforms.
    public enum Prefs {
        /// String: `GarminFitExporter.DeviceIdentity` raw value; default `forerunner945`.
        public static let exportDevice = "garmin.exportDevice"
        /// Bool, default true: sync on foreground once a key is saved.
        public static let autoSync = "garmin.autoSync"
        /// Int unix seconds: the last sync attempt, successful or not.
        public static let lastSyncAttemptTs = "garmin.lastSyncAttemptTs"
        /// Int unix seconds: the newest activity start seen by the last successful sync.
        public static let lastSyncCursorTs = "garmin.lastSyncCursorTs"
        /// String: the human status line of the last sync ("Imported 2 activities", or the error).
        public static let lastSyncSummary = "garmin.lastSyncSummary"
    }

    /// Activities per list page.
    public static let pageSize = 20
    /// Most pages one sync walks (200 activities).
    public static let maxPages = 10
    /// The first sync (no cursor) looks this far back.
    public static let firstSyncDays = 30
    /// Later syncs re-read this much before the cursor, so a late-uploaded activity is not missed.
    public static let overlapDays = 2
    /// Foreground auto-sync runs at most this often.
    public static let autoSyncIntervalS = 3_600
    /// Cap on one activity download (the ZIP).
    public static let maxDownloadBytes = 32 << 20
    /// A cached access token is refreshed this long before its `exp`.
    public static let tokenRefreshMarginS = 3_600
    /// Imported timed route points are kept at most one per this many seconds.
    public static let routeSpacingS = 5

    /// Start of the window a sync reads: the last `firstSyncDays` days on a first sync, else the cursor
    /// minus `overlapDays`. Kotlin twin: `GarminSyncEngine.windowStart`.
    public static func syncWindowStart(cursorTs: Int?, now: Int) -> Int {
        if let cursorTs, cursorTs > 0 { return cursorTs - overlapDays * 86_400 }
        return now - firstSyncDays * 86_400
    }

    /// Whether the foreground trigger should start a sync.
    public static func shouldAutoSync(hasKey: Bool, autoSync: Bool, lastAttemptTs: Int?, now: Int) -> Bool {
        guard hasKey, autoSync else { return false }
        guard let last = lastAttemptTs, last > 0 else { return true }
        return now - last >= autoSyncIntervalS
    }

    /// Keep at most one timed point per `spacingS` seconds: the first point, then each point at least
    /// `spacingS` after the last kept one. The final point is always kept so the route ends where the
    /// activity did.
    public static func downsample(_ points: [TimedRoutePoint], spacingS: Int = routeSpacingS) -> [TimedRoutePoint] {
        guard points.count > 2, spacingS > 0 else { return points }
        var out: [TimedRoutePoint] = [points[0]]
        for p in points.dropFirst().dropLast() where p.ts - out[out.count - 1].ts >= spacingS {
            out.append(p)
        }
        let last = points[points.count - 1]
        if last.ts - out[out.count - 1].ts >= spacingS || out.count == 1 {
            out.append(last)
        } else {
            out[out.count - 1] = last
        }
        return out
    }
}

// MARK: - Skip rule

/// Decides whether a listed Garmin activity is imported. Pure; the Kotlin twin runs the same test table.
public enum GarminImportRule {

    public enum Decision: String, Sendable, Equatable {
        /// Not in NOOP yet: download and import it.
        case importActivity = "import"
        /// A `garmin-connect` row already starts within 2 s of it.
        case alreadyImported
        /// It overlaps a workout NOOP recorded (source `manual`): the user uploaded that run to Garmin and
        /// it came back through the list. Importing it would duplicate the run.
        case sentFromNoop
    }

    /// One stored workout row, reduced to what the rule reads.
    public struct Existing: Sendable, Equatable {
        public var startTs: Int
        public var endTs: Int
        public var source: String
        public init(startTs: Int, endTs: Int, source: String) {
            self.startTs = startTs
            self.endTs = endTs
            self.source = source
        }
    }

    /// Tolerance for "same start" between a Garmin listing and a row imported from it.
    public static let sameStartToleranceS = 2

    /// Sources compare case-insensitively, as `WorkoutSource.classify` reads them.
    /// Kotlin twin: `GarminImportRule.decide`.
    public static func decide(activityStart: Int, activityEnd: Int, existing: [Existing]) -> Decision {
        if existing.contains(where: {
            $0.source.lowercased() == GarminConnect.sourceId && abs($0.startTs - activityStart) <= sameStartToleranceS
        }) {
            return .alreadyImported
        }
        if existing.contains(where: {
            $0.source.lowercased() == "manual" && overlapsMoreThanHalf(activityStart, activityEnd, $0.startTs, $0.endTs)
        }) {
            return .sentFromNoop
        }
        return .importActivity
    }

    /// The `WorkoutSource.sameActivity` time test: the windows overlap by more than half of the shorter.
    /// Kotlin twin: `GarminImportRule.overlapsMajority`.
    static func overlapsMoreThanHalf(_ aStart: Int, _ aEnd: Int, _ bStart: Int, _ bEnd: Int) -> Bool {
        let overlap = min(aEnd, bEnd) - max(aStart, bStart)
        guard overlap > 0 else { return false }
        let shorter = max(1, min(aEnd - aStart, bEnd - bStart))
        return Double(overlap) > 0.5 * Double(shorter)
    }
}

// MARK: - Personal API: request shapes and response parsing

/// The pure half of the Personal API client: paths, response parsing, error mapping. The app's
/// `GarminAPIClient` sends the requests.
public enum GarminApi {

    public static let tokenPath = "/personalapitoken-service/personal-api/personal-api-key/token"
    public static let activityListPath = "/activitylist-service/activities/search/activities"
    public static func downloadPath(activityId: Int64) -> String { "/download-service/files/activity/\(activityId)" }

    /// One activity from the list endpoint.
    public struct ActivitySummary: Sendable, Equatable {
        public var activityId: Int64
        /// Start, unix seconds (from `startTimeGMT`).
        public var startTs: Int
        /// Duration in seconds.
        public var durationS: Double
        public var typeKey: String?
        public var name: String?
        public var manufacturer: String?
        public init(activityId: Int64, startTs: Int, durationS: Double, typeKey: String? = nil,
                    name: String? = nil, manufacturer: String? = nil) {
            self.activityId = activityId
            self.startTs = startTs
            self.durationS = durationS
            self.typeKey = typeKey
            self.name = name
            self.manufacturer = manufacturer
        }
        /// End, unix seconds. A missing or nonsensical duration leaves a zero-length window.
        public var endTs: Int {
            guard durationS.isFinite, durationS > 0, durationS < 86_400 * 7 else { return startTs }
            return startTs + Int(durationS.rounded())
        }
    }

    /// Why a request failed. The app maps each case to user text.
    public enum Failure: Error, Sendable, Equatable {
        /// 401 / 403: the key was rejected.
        case rejectedKey
        /// 429.
        case rateLimited
        /// Any other non-2xx status.
        case http(Int)
        /// The response body was not what the endpoint returns.
        case badResponse
        /// The download exceeded `GarminConnect.maxDownloadBytes`, or held no FIT entry.
        case badDownload
    }

    /// Map an HTTP status to a failure, or nil for 2xx.
    public static func failure(forStatus status: Int) -> Failure? {
        switch status {
        case 200..<300: return nil
        case 401, 403: return .rejectedKey
        case 429: return .rateLimited
        default: return .http(status)
        }
    }

    /// The `exp` claim of a JWT (unix seconds), or nil when the token is not a decodable JWT. The signature
    /// is not checked: the token only ever goes back to the server that issued it.
    /// Kotlin twin: `GarminApiClient.jwtExpiry`.
    public static func jwtExpiry(_ token: String) -> Int? {
        let parts = token.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2 else { return nil }
        var b64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64.append("=") }
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let exp = obj["exp"] as? NSNumber else { return nil }
        let v = exp.doubleValue
        guard v.isFinite, v > 0, v < 4_102_444_800 else { return nil }
        return Int(v)
    }

    /// Parse the token endpoint's body: the trimmed text, when it looks like a JWT (three dot-separated
    /// parts). Some gateways wrap it in quotes; those are stripped.
    /// Kotlin twin: `GarminApiClient.parseToken`.
    public static func parseToken(_ body: Data) -> String? {
        guard var s = String(data: body, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        if s.hasPrefix("\""), s.hasSuffix("\""), s.count >= 2 { s = String(s.dropFirst().dropLast()) }
        guard s.split(separator: ".", omittingEmptySubsequences: false).count == 3, !s.contains(" ") else { return nil }
        return s
    }

    /// Parse the activity list (a JSON array). nil when the body is not an array; entries without an id or
    /// a parseable start are skipped. Kotlin twin: `GarminApiClient.parseActivityList`.
    public static func parseActivityList(_ data: Data) -> [ActivitySummary]? {
        guard let arr = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return nil }
        return arr.compactMap { item -> ActivitySummary? in
            guard let o = item as? [String: Any] else { return nil }
            let id: Int64?
            if let n = o["activityId"] as? NSNumber { id = n.int64Value }
            else if let s = o["activityId"] as? String { id = Int64(s) }
            else { id = nil }
            guard let activityId = id, activityId > 0,
                  let startText = o["startTimeGMT"] as? String,
                  let start = parseStartTimeGMT(startText) else { return nil }
            let duration = (o["duration"] as? NSNumber)?.doubleValue ?? 0
            let typeKey = (o["activityType"] as? [String: Any])?["typeKey"] as? String
            return ActivitySummary(activityId: activityId, startTs: start, durationS: duration,
                                   typeKey: typeKey, name: o["activityName"] as? String,
                                   manufacturer: o["manufacturer"] as? String)
        }
    }

    /// `"yyyy-MM-dd HH:mm:ss"` in UTC (an optional fractional part or a `T` separator is tolerated) to
    /// unix seconds. Parsed by hand so it cannot depend on a device locale or calendar.
    /// Kotlin twin: `GarminApiClient.parseGmt`.
    public static func parseStartTimeGMT(_ s: String) -> Int? {
        let chars = Array(s.trimmingCharacters(in: .whitespaces))
        guard chars.count >= 19, chars[4] == "-", chars[7] == "-", chars[10] == " " || chars[10] == "T",
              chars[13] == ":", chars[16] == ":" else { return nil }
        func num(_ a: Int, _ b: Int) -> Int? { Int(String(chars[a..<b])) }
        guard let y = num(0, 4), let mo = num(5, 7), let d = num(8, 10),
              let h = num(11, 13), let mi = num(14, 16), let sec = num(17, 19),
              (1970...2100).contains(y), (1...12).contains(mo), (1...31).contains(d),
              (0...23).contains(h), (0...59).contains(mi), (0...60).contains(sec) else { return nil }
        return daysFromCivil(y, mo, d) * 86_400 + h * 3_600 + mi * 60 + sec
    }

    /// Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
    static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int {
        let y = m <= 2 ? y0 - 1 : y0
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// True when paging can stop after this page: it is empty, or its oldest activity starts before the
    /// window.
    public static func pageEndsWindow(_ page: [ActivitySummary], windowStart: Int) -> Bool {
        guard let oldest = page.map(\.startTs).min() else { return true }
        return oldest < windowStart
    }

    /// The first `.fit` entry (by name, case-insensitive) of a downloaded ZIP, or nil. Extraction stops
    /// once the expanded entry passes `maxBytes`, so a zip bomb cannot inflate past the cap. A body that
    /// is already a bare FIT file (".FIT" at offset 8) is returned as is.
    /// Kotlin twin: `GarminApiClient.fitFromDownload`.
    public static func fitData(fromDownload data: Data, maxBytes: Int = GarminConnect.maxDownloadBytes) -> Data? {
        guard data.count <= maxBytes else { return nil }
        if ActivityFileImporter.detectFormat(data: data) == .fit { return data }
        guard let archive = try? Archive(data: data, accessMode: .read) else { return nil }
        guard let entry = archive.first(where: { $0.type == .file && $0.path.lowercased().hasSuffix(".fit") }) else {
            return nil
        }
        var out = Data()
        do {
            _ = try archive.extract(entry, bufferSize: 64 * 1024, skipCRC32: false) { chunk in
                guard out.count + chunk.count <= maxBytes else { throw Failure.badDownload }
                out.append(chunk)
            }
        } catch {
            return nil
        }
        return out.isEmpty ? nil : out
    }
}
