import Foundation
import Security
import RoadCore

struct CachedChannel: Codable {
    var id: UUID, name: String, receivingSelected: Bool
    var binding: PTTBinding?, seenIDs: [String], lastSequence: Int64
    var serverEstimate: Int64, savedUptime: TimeInterval
}
struct DeviceCache: Codable { var session: Session?; var channel: CachedChannel? }
enum KeychainStore {
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "kr.together.road.ios.session", kSecAttrAccount as String: "current", kSecAttrSynchronizable as String: false] }
    static func load() -> DeviceCache? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let bytes = item as? Data, bytes.count <= 32768 else { return nil }
        return try? JSONDecoder().decode(DeviceCache.self, from: bytes)
    }
    static func save(_ cache: DeviceCache) throws {
        let bytes = try JSONEncoder().encode(cache)
        guard bytes.count <= 32768 else { throw RoadError.invalid("공유방 보관 크기가 상한을 넘었어요.") }
        let attributes: [String: Any] = [kSecValueData as String: bytes, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw RoadError.invalid("공유방 인증을 안전하게 보관하지 못했어요.") }
    }
    static func clear() { SecItemDelete(query as CFDictionary) }
}
