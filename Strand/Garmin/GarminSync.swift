import Foundation
import StrandImport
import WhoopStore

/// One Garmin Connect sync pass, with every side effect injected so it runs in tests without a network or
/// a store. `GarminSync` wires it to the real client, repository and `ActivityStoreWriter`.
///
/// Window: the last `GarminConnect.firstSyncDays` days on a first sync, else the cursor minus
/// `GarminConnect.overlapDays`. Pages are read newest first until a page is empty, short, or reaches
/// before the window (at most `GarminConnect.maxPages`). Activities in the window are then handled
/// oldest first through `GarminImportRule`.
struct GarminSyncEngine {
    /// One list page starting at the given offset.
    var listPage: (_ start: Int) async throws -> [GarminApi.ActivitySummary]
    /// The original FIT bytes of one activity.
    var download: (_ activityId: Int64) async throws -> Data
    /// Stored workout rows overlapping `[from, to]`, for the skip rule.
    var existing: (_ from: Int, _ to: Int) async -> [GarminImportRule.Existing]
    /// Persist one parsed activity.
    var write: (_ activity: ActivityFile) async throws -> Void

    struct Outcome: Equatable {
        var imported = 0
        var alreadyImported = 0
        var sentFromNoop = 0
        /// Listed in the window but without a usable FIT file (a manual entry, a 404, an empty archive).
        var unreadable = 0
        /// Newest start over every listed activity; the next cursor. nil when the list was empty.
        var newestStart: Int?
    }

    func run(cursorTs: Int?, now: Int) async throws -> Outcome {
        let windowStart = GarminConnect.syncWindowStart(cursorTs: cursorTs, now: now)
        var listed: [GarminApi.ActivitySummary] = []
        for page in 0..<GarminConnect.maxPages {
            let items = try await listPage(page * GarminConnect.pageSize)
            listed += items
            if items.count < GarminConnect.pageSize || GarminApi.pageEndsWindow(items, windowStart: windowStart) { break }
        }
        var outcome = Outcome(newestStart: listed.map(\.startTs).max())

        var seen = Set<Int64>()
        let candidates = listed
            .filter { $0.startTs >= windowStart && seen.insert($0.activityId).inserted }
            .sorted { $0.startTs < $1.startTs }
        guard !candidates.isEmpty else { return outcome }

        var rows = await existing(windowStart - 86_400, now + 86_400)
        for item in candidates {
            switch GarminImportRule.decide(activityStart: item.startTs, activityEnd: item.endTs, existing: rows) {
            case .alreadyImported:
                outcome.alreadyImported += 1
            case .sentFromNoop:
                outcome.sentFromNoop += 1
            case .importActivity:
                let fit: Data
                do {
                    fit = try await download(item.activityId)
                } catch GarminClientError.api(let failure) where Self.isPerActivity(failure) {
                    outcome.unreadable += 1
                    continue
                }
                guard let activity = ActivityFileImporter.parse(data: fit, filename: "activity.fit").activity,
                      let duration = activity.durationS, duration > 0 else {
                    outcome.unreadable += 1
                    continue
                }
                try await write(activity)
                outcome.imported += 1
                // So a second listing of the same activity in this pass is recognised.
                rows.append(GarminImportRule.Existing(startTs: Int(activity.start.timeIntervalSince1970),
                                                      endTs: Int(activity.end.timeIntervalSince1970),
                                                      source: GarminConnect.sourceId))
                rows.append(GarminImportRule.Existing(startTs: item.startTs, endTs: item.endTs,
                                                      source: GarminConnect.sourceId))
            }
        }
        return outcome
    }

    /// Failures that belong to one activity rather than to the whole sync: skip it and carry on. A
    /// rejected key, a rate limit or a network error stop the sync instead (the cursor is not advanced).
    static func isPerActivity(_ failure: GarminApi.Failure) -> Bool {
        switch failure {
        case .badDownload, .badResponse: return true
        case .http(let code): return (400..<500).contains(code)
        case .rejectedKey, .rateLimited: return false
        }
    }
}

/// The Garmin Connect integration's state and its single sync entry point (fork feature). Opt-in: nothing
/// talks to Garmin until the user saves a Personal API key. Never runs two syncs at once; no background
/// scheduling (foreground auto-sync at most hourly, plus "Sync now").
@MainActor
final class GarminSync: ObservableObject {

    static let shared = GarminSync()

    @Published private(set) var isSyncing = false
    @Published private(set) var hasKey: Bool
    @Published private(set) var lastSummary: String?
    @Published private(set) var lastAttemptTs: Int?

    private let client: GarminAPIClient
    private let defaults: UserDefaults

    init(client: GarminAPIClient = GarminAPIClient(), defaults: UserDefaults = .standard) {
        self.client = client
        self.defaults = defaults
        self.hasKey = GarminKeyStore.hasKey
        self.lastSummary = defaults.string(forKey: GarminConnect.Prefs.lastSyncSummary)
        let attempt = defaults.integer(forKey: GarminConnect.Prefs.lastSyncAttemptTs)
        self.lastAttemptTs = attempt > 0 ? attempt : nil
    }

    // MARK: Settings

    /// `garmin.autoSync`, default true.
    var autoSync: Bool {
        get { defaults.object(forKey: GarminConnect.Prefs.autoSync) as? Bool ?? true }
        set { defaults.set(newValue, forKey: GarminConnect.Prefs.autoSync); objectWillChange.send() }
    }

    /// The device identity exported FIT files claim (`garmin.exportDevice`).
    nonisolated static func exportDevice(_ defaults: UserDefaults = .standard) -> GarminFitExporter.DeviceIdentity {
        defaults.string(forKey: GarminConnect.Prefs.exportDevice)
            .flatMap(GarminFitExporter.DeviceIdentity.init(rawValue:)) ?? .default
    }

    /// Save the key to the Keychain. Returns false when the Keychain refused it.
    @discardableResult
    func saveKey(_ key: String) -> Bool {
        let ok = GarminKeyStore.save(key)
        hasKey = GarminKeyStore.hasKey
        Task { await client.resetToken() }
        return ok
    }

    /// Remove the key. Imported activities stay; only future syncs stop. The sync history is forgotten too
    /// (as on Android), so a key for another account starts a fresh window.
    func removeKey() {
        GarminKeyStore.clear()
        hasKey = false
        for key in [GarminConnect.Prefs.lastSyncAttemptTs, GarminConnect.Prefs.lastSyncCursorTs,
                    GarminConnect.Prefs.lastSyncSummary] {
            defaults.removeObject(forKey: key)
        }
        lastAttemptTs = nil
        lastSummary = nil
        Task { await client.resetToken() }
    }

    // MARK: Triggers

    /// Foreground trigger: sync when a key is saved, auto-sync is on and the last attempt is at least
    /// `GarminConnect.autoSyncIntervalS` old.
    func syncIfDue(model: AppModel) {
        let now = Int(Date().timeIntervalSince1970)
        guard !isSyncing,
              GarminConnect.shouldAutoSync(hasKey: hasKey, autoSync: autoSync, lastAttemptTs: lastAttemptTs, now: now)
        else { return }
        Task { await syncNow(model: model) }
    }

    /// Run one sync now. A no-op while one is already running or without a key.
    func syncNow(model: AppModel) async {
        guard !isSyncing, hasKey else { return }
        isSyncing = true
        defer { isSyncing = false }

        let now = Int(Date().timeIntervalSince1970)
        lastAttemptTs = now
        defaults.set(now, forKey: GarminConnect.Prefs.lastSyncAttemptTs)
        let cursorRaw = defaults.integer(forKey: GarminConnect.Prefs.lastSyncCursorTs)
        let cursor = cursorRaw > 0 ? cursorRaw : nil

        guard let store = await model.repo.storeHandle() else {
            finish(summary: String(localized: "Couldn't open the local store."), model: model, detail: "store unavailable")
            return
        }
        let client = self.client
        let repo = model.repo
        let engine = GarminSyncEngine(
            listPage: { try await client.listActivities(start: $0) },
            download: { try await client.downloadFit(activityId: $0) },
            existing: { from, to in
                await repo.rawWorkoutRows(from: from, to: to).map {
                    GarminImportRule.Existing(startTs: $0.startTs, endTs: $0.endTs, source: $0.source)
                }
            },
            write: { activity in
                try await ActivityStoreWriter.write(activity, target: .garminConnect, store: store) {
                    model.registerDevice($0, makeActive: false)
                }
            }
        )

        do {
            let outcome = try await engine.run(cursorTs: cursor, now: now)
            if let newest = outcome.newestStart, newest > (cursor ?? 0) {
                defaults.set(newest, forKey: GarminConnect.Prefs.lastSyncCursorTs)
            }
            if outcome.imported > 0 { await model.repo.refresh() }
            finish(summary: Self.summary(outcome), model: model,
                   detail: "\(outcome.imported) imported, \(outcome.alreadyImported) already in NOOP, "
                       + "\(outcome.sentFromNoop) sent from NOOP, \(outcome.unreadable) without a file")
        } catch {
            let text = Self.message(for: error)
            finish(summary: text, model: model, detail: "failed (\(Self.logToken(for: error)))")
        }
    }

    private func finish(summary: String, model: AppModel, detail: String) {
        lastSummary = summary
        defaults.set(summary, forKey: GarminConnect.Prefs.lastSyncSummary)
        // Same exported strap log the file imports write to: counts and an error class only, never a key,
        // a token, an activity name or any health value.
        model.live.append(log: "[\(AppModel.logTimeFormatter.string(from: Date()))] Import Garmin Connect: \(detail)")
    }

    // MARK: Text

    nonisolated static func summary(_ o: GarminSyncEngine.Outcome) -> String {
        switch o.imported {
        case 0: return String(localized: "Up to date")
        case 1: return String(localized: "Imported 1 activity")
        default: return String(localized: "Imported \(o.imported) activities")
        }
    }

    nonisolated static func message(for error: Error) -> String {
        switch error as? GarminClientError {
        case .noKey?:
            return String(localized: "Save a Personal API key first.")
        case .api(.rejectedKey)?:
            return String(localized: "Garmin rejected the API key. Create a new one in Garmin Connect.")
        case .api(.rateLimited)?:
            return String(localized: "Garmin's rate limit was hit. Try again later.")
        case .api(.http(let code))?:
            return String(localized: "Garmin Connect returned an error (HTTP \(code)).")
        case .api(.badResponse)?, .api(.badDownload)?:
            return String(localized: "Garmin Connect sent an unexpected response.")
        case .network?:
            return String(localized: "Couldn't reach Garmin Connect. Check your connection.")
        case nil:
            return String(localized: "Import failed: \(error.localizedDescription)")
        }
    }

    /// A short, value-free class of the error for the log line.
    nonisolated static func logToken(for error: Error) -> String {
        switch error as? GarminClientError {
        case .noKey?: return "no key"
        case .api(.rejectedKey)?: return "key rejected"
        case .api(.rateLimited)?: return "rate limited"
        case .api(.http(let code))?: return "HTTP \(code)"
        case .api(.badResponse)?: return "bad response"
        case .api(.badDownload)?: return "bad download"
        case .network?: return "network"
        case nil: return "store write"
        }
    }
}
