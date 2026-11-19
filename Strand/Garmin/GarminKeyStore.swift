import Foundation
import Security
import StrandImport

/// Keychain storage for the Garmin Connect Personal API key (fork feature). One generic-password item
/// under `GarminConnect.keychainService`, so the key never lands in UserDefaults, a plist or a log.
/// Same shape as `OuraTokenStore` / `AIKeyStore`: delete-then-add, this-device-only accessibility.
enum GarminKeyStore {
    private static let account = "personalApiKey"

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: GarminConnect.keychainService,
            kSecAttrAccount as String: account,
        ]
    }

    /// Store (or replace) the key. Returns false when the Keychain write fails, so a caller never assumes
    /// a key is stored when it is not. An empty key is not stored.
    @discardableResult
    static func save(_ key: String) -> Bool {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else { return false }
        SecItemDelete(baseQuery as CFDictionary)
        var attrs = baseQuery
        attrs[kSecValueData as String] = data
        attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attrs as CFDictionary, nil) == errSecSuccess
    }

    /// The stored key, or nil.
    static func load() -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let key = String(data: data, encoding: .utf8), !key.isEmpty else { return nil }
        return key
    }

    /// Remove the stored key.
    static func clear() { SecItemDelete(baseQuery as CFDictionary) }

    static var hasKey: Bool { load() != nil }
}
