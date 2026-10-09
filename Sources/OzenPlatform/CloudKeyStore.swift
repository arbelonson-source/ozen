import Foundation
import OzenKit
import Security

public enum CloudKeyStore {
    private static let account = "api-key"

    public static func read(for provider: CloudProvider = .openRouter) -> String? {
        var query = baseQuery(provider)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        let key = String(decoding: data, as: UTF8.self)
        return key.isEmpty ? nil : key
    }

    public static func hasKey(for provider: CloudProvider = .openRouter) -> Bool { read(for: provider) != nil }

    @discardableResult
    public static func save(_ key: String, for provider: CloudProvider = .openRouter) -> Bool {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            SecItemDelete(baseQuery(provider) as CFDictionary)
            return true
        }
        // Update in place rather than delete-then-add: a read() racing this
        // call (the cloud engine checking for a key on its own actor while
        // this runs from the Settings sheet) would otherwise land in the
        // brief window where the item is genuinely gone and wrongly see no
        // key at all, right after one was just saved.
        let data = Data(trimmed.utf8)
        let updateStatus = SecItemUpdate(baseQuery(provider) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else { return false }
        var item = baseQuery(provider)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    public static func remove(for provider: CloudProvider = .openRouter) {
        SecItemDelete(baseQuery(provider) as CFDictionary)
    }

    private static func baseQuery(_ provider: CloudProvider) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: provider.keychainService,
            kSecAttrAccount as String: account,
        ]
    }
}
