import Foundation
import StrandImport

// PRIVACY (network path, fork feature): this client talks to Garmin's official Personal API at
// `GarminConnect.apiBase` and nowhere else. What it sends: the user's Personal API key (only to the token
// endpoint, as `Authorization: ApiKey …`) and the short-lived bearer token Garmin returns (to the list and
// download endpoints). Nothing else leaves the device: no health data, no identifiers, no NOOP data. It is
// off until the user saves a key in Data Sources → Garmin Connect, and every request is user-initiated or
// the foreground auto-sync the user can switch off. The key and the token are never logged.

/// Errors the client surfaces; `GarminSync` maps each to user text.
enum GarminClientError: Error, Equatable {
    /// No API key is saved.
    case noKey
    /// Garmin answered, but not with success.
    case api(GarminApi.Failure)
    /// The request never got an answer (offline, DNS, timeout, TLS).
    case network
}

/// The Garmin Connect Personal API client: token exchange (cached until `exp` minus an hour), the
/// activity list, and the activity download. The transport is injected so tests run without a network.
actor GarminAPIClient {

    /// Sends one request and returns the body (at most `maxBytes`) and the HTTP response.
    typealias Transport = @Sendable (_ request: URLRequest, _ maxBytes: Int) async throws -> (Data, HTTPURLResponse)

    private let base: URL
    private let apiKey: @Sendable () -> String?
    private let transport: Transport
    private let now: @Sendable () -> Date
    private var cached: (token: String, refreshAt: Int)?

    init(base: URL = URL(string: GarminConnect.apiBase)!,
         apiKey: @escaping @Sendable () -> String? = { GarminKeyStore.load() },
         transport: @escaping Transport = GarminAPIClient.urlSessionTransport(),
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.base = base
        self.apiKey = apiKey
        self.transport = transport
        self.now = now
    }

    /// Forget the cached token (after the key changes or is removed).
    func resetToken() { cached = nil }

    /// A bearer token, from the cache while it has more than `tokenRefreshMarginS` left, else fresh.
    func accessToken() async throws -> String {
        let nowS = Int(now().timeIntervalSince1970)
        if let cached, nowS < cached.refreshAt { return cached.token }
        guard let key = apiKey(), !key.isEmpty else { throw GarminClientError.noKey }
        var req = URLRequest(url: base.appendingPathComponent(GarminApi.tokenPath))
        req.httpMethod = "POST"
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("ApiKey \(key)", forHTTPHeaderField: "Authorization")
        let body = try await send(req, maxBytes: 64 * 1024)
        guard let token = GarminApi.parseToken(body) else { throw GarminClientError.api(.badResponse) }
        // A token without a readable `exp` is used for this sync only: refresh on the next call after now.
        let refreshAt = GarminApi.jwtExpiry(token).map { $0 - GarminConnect.tokenRefreshMarginS } ?? nowS
        cached = (token, refreshAt)
        return token
    }

    /// One page of the activity list, newest first.
    func listActivities(start: Int, limit: Int = GarminConnect.pageSize) async throws -> [GarminApi.ActivitySummary] {
        var comps = URLComponents(url: base.appendingPathComponent(GarminApi.activityListPath),
                                  resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "start", value: String(start)),
                            URLQueryItem(name: "limit", value: String(limit))]
        let body = try await authorized(comps.url!, accept: "application/json", maxBytes: 8 << 20)
        guard let list = GarminApi.parseActivityList(body) else { throw GarminClientError.api(.badResponse) }
        return list
    }

    /// The original FIT file of one activity (unzipped from the download).
    func downloadFit(activityId: Int64) async throws -> Data {
        let url = base.appendingPathComponent(GarminApi.downloadPath(activityId: activityId))
        let body = try await authorized(url, accept: "application/zip, application/x-zip-compressed, application/octet-stream",
                                        maxBytes: GarminConnect.maxDownloadBytes)
        guard let fit = GarminApi.fitData(fromDownload: body) else { throw GarminClientError.api(.badDownload) }
        return fit
    }

    /// GET with the bearer token. A 401/403 is retried once with a fresh token, in case the cached one
    /// was revoked early; a second rejection means the key itself is no longer accepted.
    private func authorized(_ url: URL, accept: String, maxBytes: Int) async throws -> Data {
        for attempt in 0..<2 {
            let token = try await accessToken()
            var req = URLRequest(url: url)
            req.setValue(accept, forHTTPHeaderField: "Accept")
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            do {
                return try await send(req, maxBytes: maxBytes)
            } catch GarminClientError.api(.rejectedKey) where attempt == 0 {
                cached = nil
            }
        }
        throw GarminClientError.api(.rejectedKey)
    }

    private func send(_ req: URLRequest, maxBytes: Int) async throws -> Data {
        var req = req
        req.timeoutInterval = 30
        let data: Data, response: HTTPURLResponse
        do {
            (data, response) = try await transport(req, maxBytes)
        } catch let e as GarminClientError {
            throw e
        } catch {
            throw GarminClientError.network
        }
        if let failure = GarminApi.failure(forStatus: response.statusCode) { throw GarminClientError.api(failure) }
        return data
    }

    /// The production transport: URLSession, reading at most `maxBytes` of the body.
    static func urlSessionTransport(_ session: URLSession = .shared) -> Transport {
        { request, maxBytes in
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw GarminClientError.api(.badResponse) }
            if http.expectedContentLength > Int64(maxBytes) { throw GarminClientError.api(.badDownload) }
            var data = Data()
            if http.expectedContentLength > 0 { data.reserveCapacity(Int(http.expectedContentLength)) }
            for try await byte in bytes {
                data.append(byte)
                if data.count > maxBytes { throw GarminClientError.api(.badDownload) }
            }
            return (data, http)
        }
    }
}
