import Foundation

public enum RoadError: Error, LocalizedError, Sendable {
    case invalid(String), http(Int, String, String), cancelled
    public var errorDescription: String? { switch self { case .invalid(let text): return text; case .http(_, _, let text): return text; case .cancelled: return "작업을 취소했어요." } }
}
public func checkedID(_ text: String) throws -> String {
    guard text.utf8.count == 24, text.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { throw RoadError.invalid("공유방·원음 ID가 올바르지 않아요.") }; return text
}
public struct Endpoint: Sendable, Equatable {
    public let origin: URL
    public let debugLocalHTTP: Bool
    public init(_ text: String, debugLocalHTTP: Bool) throws {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = parts.host?.lowercased(), parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              ["", "/"].contains(parts.path), parts.port == nil || (1...65535).contains(parts.port!),
              parts.scheme == "https" || (debugLocalHTTP && parts.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(host)) else { throw RoadError.invalid("HTTPS 서버 주소가 필요해요. 로컬 시연만 localhost HTTP를 허용해요.") }
        var clean = parts; clean.path = ""; self.origin = clean.url!; self.debugLocalHTTP = debugLocalHTTP
    }
    public func api(_ path: String) throws -> URL { guard path.hasPrefix("/api/rooms"), !path.contains("?"), !path.contains("#"), let url = URL(string: origin.absoluteString + path) else { throw RoadError.invalid("API 경로가 올바르지 않아요.") }; return url }
    public func invitation(_ text: String) throws -> (room: String, secret: String) {
        guard let parts = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), let host = parts.host?.lowercased() else { throw RoadError.invalid("초대 링크를 확인해 주세요.") }
        let localAliases = debugLocalHTTP && origin.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(host) && ["localhost", "127.0.0.1", "::1"].contains(origin.host ?? "")
        guard parts.scheme == origin.scheme, (parts.port ?? (parts.scheme == "https" ? 443 : 80)) == (origin.port ?? (origin.scheme == "https" ? 443 : 80)), host == origin.host || localAliases,
              parts.user == nil, parts.password == nil, parts.query == nil, ["", "/"].contains(parts.path), let fragment = parts.fragment, fragment.hasPrefix("join=") else { throw RoadError.invalid("선택한 서버에서 만든 초대 링크가 필요해요.") }
        let fields = fragment.dropFirst(5).split(separator: ".", omittingEmptySubsequences: false)
        guard fields.count == 2 else { throw RoadError.invalid("초대 링크 형식이 올바르지 않아요.") }
        let room = try checkedID(String(fields[0])); let secret = String(fields[1]); try Session.checkSecret(secret); return (room, secret)
    }
}
public struct Session: Codable, Sendable, Equatable, CustomStringConvertible {
    public let server: String, roomID: String, memberID: String, role: String, token: String
    public let inviteSecret: String?
    public var description: String { "Session(credentials redacted)" }
    public init(server: String, roomID: String, memberID: String, role: String, token: String, inviteSecret: String? = nil) throws {
        _ = try checkedID(roomID); _ = try checkedID(memberID); try Self.checkSecret(token); if let inviteSecret { try Self.checkSecret(inviteSecret) }
        guard ["host", "member"].contains(role) else { throw RoadError.invalid("참여 권한이 올바르지 않아요.") }
        self.server = server; self.roomID = roomID; self.memberID = memberID; self.role = role; self.token = token; self.inviteSecret = inviteSecret
    }
    public static func checkSecret(_ value: String) throws { guard value.utf8.count == 43, value.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 95 || $0 == 45 }) else { throw RoadError.invalid("공유방 인증 형식이 올바르지 않아요.") } }
    public func validated(debugLocalHTTP: Bool) throws -> Session { _ = try Endpoint(server, debugLocalHTTP: debugLocalHTTP); return try Session(server: server, roomID: roomID, memberID: memberID, role: role, token: token, inviteSecret: inviteSecret) }
    public var invitation: String? { inviteSecret.map { "\(server)/#join=\(roomID).\($0)" } }
}
public struct Person: Codable, Sendable, Equatable { public let id: String, name: String; public let role: String? }
public struct RoomInfo: Codable, Sendable { public let id: String; public let createdAt, expiresAt: Int64 }
public struct Destination: Codable, Sendable, Equatable {
    public let name: String; public let latitude, longitude: Double; public let changedAt: Int64
    public init(name: String, latitude: Double, longitude: Double, changedAt: Int64) { self.name = name; self.latitude = latitude; self.longitude = longitude; self.changedAt = changedAt }
}
public struct Voice: Codable, Sendable, Equatable, Identifiable {
    public let id: String, sender: Person, sequence: Int64, acceptedAt: Int64, autoPlayUntil: Int64
    public let durationMs: Double, byteLength: Int, sampleCount: Int, sampleRate: Int, channels: Int, format: String
    public func validate() throws {
        _ = try checkedID(id); _ = try checkedID(sender.id)
        guard sequence >= 0, acceptedAt >= 0, acceptedAt <= 32_503_680_000_000, autoPlayUntil == acceptedAt + 30_000,
              sampleCount >= 16_000, sampleCount <= 240_000, byteLength >= sampleCount * 2 + 44, byteLength <= WAV.maximumBytes,
              sampleRate == 16_000, channels == 1, format == "pcm_s16le", durationMs == Double(sampleCount) / 16 else { throw RoadError.invalid("원음 수신 정보가 올바르지 않아요.") }
    }
}
public struct Snapshot: Codable, Sendable {
    public let room: RoomInfo, me: Person, members: [Person], destination: Destination?, revision: Int64, sequence: Int64, voiceMessages: [Voice], serverTime: Int64
    enum CodingKeys: String, CodingKey { case room, me = "self", members, destination, revision, sequence, voiceMessages, serverTime }
    public func validate() throws { _ = try checkedID(room.id); _ = try checkedID(me.id); guard members.count <= 20, voiceMessages.count <= 100, room.createdAt >= 0, room.expiresAt <= 32_503_680_000_000, room.expiresAt > room.createdAt else { throw RoadError.invalid("공유방 수신 정보가 올바르지 않아요.") }; try voiceMessages.forEach { try $0.validate() } }
}
public struct CreatedRoom: Codable, Sendable {
    public let roomId: String, memberId: String, role: String, token: String, inviteSecret: String?, state: Snapshot
    public func session(endpoint: Endpoint) throws -> Session { try state.validate(); return try Session(server: endpoint.origin.absoluteString, roomID: roomId, memberID: memberId, role: role, token: token, inviteSecret: inviteSecret) }
}
public struct DestinationUpdate: Codable, Sendable { public let destination: Destination, revision: Int64, sequence: Int64, serverTime: Int64 }
public struct UploadedVoice: Codable, Sendable { public let message: Voice, duplicate: Bool, serverTime: Int64 }
public struct PTTRegistration: Codable, Sendable, Equatable { public let id: String, channelUUID: UUID, expiresAt: Int64; public init(id: String, channelUUID: UUID, expiresAt: Int64) { self.id = id; self.channelUUID = channelUUID; self.expiresAt = expiresAt } }
public struct PTTState: Codable, Sendable { public let configured: Bool, status: String, revision: Int64, registration: PTTRegistration?, serverTime: Int64; public init(configured: Bool, status: String, revision: Int64, registration: PTTRegistration?, serverTime: Int64) { self.configured = configured; self.status = status; self.revision = revision; self.registration = registration; self.serverTime = serverTime } }
public struct PTTPayload: Codable, Sendable {
    public let v: Int, roomId: String, channelUUID: UUID, registrationId: String, message: Voice, serverTime: Int64, roomExpiresAt: Int64
}
public struct PTTBinding: Codable, Sendable, Equatable {
    public let roomID: String, memberID: String, channelUUID: UUID, registrationID: String, registrationExpiresAt: Int64, roomExpiresAt: Int64, epoch: UUID
    public init(roomID: String, memberID: String, channelUUID: UUID, registrationID: String, registrationExpiresAt: Int64, roomExpiresAt: Int64, epoch: UUID) { self.roomID = roomID; self.memberID = memberID; self.channelUUID = channelUUID; self.registrationID = registrationID; self.registrationExpiresAt = registrationExpiresAt; self.roomExpiresAt = roomExpiresAt; self.epoch = epoch }
}
public struct RetryClip: Sendable {
    public let bytes: Data, clientMessageID: String, capturedUptime: TimeInterval
    public init(bytes: Data, clientMessageID: String = UUID().uuidString, capturedUptime: TimeInterval = ProcessInfo.processInfo.systemUptime) { self.bytes = bytes; self.clientMessageID = clientMessageID; self.capturedUptime = capturedUptime }
    public func fresh(uptime: TimeInterval) -> Bool { uptime >= capturedUptime && uptime - capturedUptime < 30 }
}
public enum NaverNavigation {
    public static func url(_ destination: Destination, bundleID: String) -> URL? {
        guard destination.latitude.isFinite, destination.longitude.isFinite, (31.43...44.35).contains(destination.latitude), (122.37...132).contains(destination.longitude) else { return nil }
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let query = [("dlat", String(destination.latitude)), ("dlng", String(destination.longitude)), ("dname", destination.name), ("appname", bundleID)].map { "\($0.0)=\($0.1.addingPercentEncoding(withAllowedCharacters: allowed)!)" }.joined(separator: "&")
        return URL(string: "nmap://navigation?" + query)
    }
}
