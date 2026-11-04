import Foundation

extension NSLock { fileprivate func guarded<T>(_ body: () throws -> T) rethrows -> T { lock(); defer { unlock() }; return try body() } }
public final class ServerClock: @unchecked Sendable {
    private let lock = NSLock(); private var anchor: Int64?, anchoredAt: TimeInterval = 0
    private let uptime: @Sendable () -> TimeInterval
    public init(uptime: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) { self.uptime = uptime }
    private func current() -> Int64 { anchor.map { $0 + Int64(max(0, uptime() - anchoredAt) * 1000) } ?? Int64(Date().timeIntervalSince1970 * 1000) }
    public func now() -> Int64 { lock.guarded { current() } }
    public func update(_ serverTime: Int64, roundTripMS: Int64 = 0) { guard (0...32_503_680_000_000).contains(serverTime) else { return }; lock.guarded { anchor = max(anchor == nil ? Int64.min : current(), serverTime + min(20_000, max(0, roundTripMS))); anchoredAt = uptime() } }
}
public enum PushDecision: Sendable { case accept(Voice, UUID), leave(String) }
/// Delegate-safe synchronous validation. No URLSession, Keychain or main-queue work under this lock.
public final class ReceiveGate: @unchecked Sendable {
    private let lock = NSLock(); private let clock: ServerClock
    private var binding: PTTBinding?, joined = false, active = false, seen = Set<String>(), pending: [Voice] = [], lastStarted: Int64 = -1
    public init(clock: ServerClock) { self.clock = clock }
    public func configure(_ value: PTTBinding?, joined: Bool, seenIDs: [String] = [], lastSequence: Int64 = -1) {
        lock.guarded { let remainsActive = active && binding?.channelUUID == value?.channelUUID; binding = value; self.joined = joined; active = joined && remainsActive; seen = Set(seenIDs.suffix(100)); pending = []; lastStarted = lastSequence }
    }
    public func setAudioActive(_ value: Bool) { lock.guarded { active = value } }
    public var audioIsActive: Bool { lock.guarded { active } }
    public func cancelPending() { lock.guarded { pending = [] } }
    public func snapshot() -> (binding: PTTBinding?, seen: [String], lastSequence: Int64) { lock.guarded { (binding, Array(seen).sorted(), lastStarted) } }
    public func invalidate(channelUUID: UUID? = nil) { lock.guarded { if let channelUUID, let binding, binding.channelUUID != channelUUID { return }; binding = nil; joined = false; active = false; pending = [] } }
    public func receive(_ payload: PTTPayload, channelUUID: UUID) -> PushDecision {
        lock.guarded {
            let now = clock.now()
            guard let b = binding, joined, b.channelUUID == channelUUID, payload.channelUUID == channelUUID,
                  payload.v == 1, payload.roomId == b.roomID, payload.registrationId == b.registrationID,
                  b.registrationExpiresAt > now, b.roomExpiresAt > now, payload.roomExpiresAt == b.roomExpiresAt,
                  payload.serverTime <= now + 5000, payload.serverTime >= payload.message.acceptedAt,
                  payload.message.sender.id != b.memberID, payload.message.acceptedAt <= now,
                  payload.message.autoPlayUntil > now, payload.message.sequence > lastStarted,
                  !seen.contains(payload.message.id), seen.count < 100, pending.count < 16,
                  (try? payload.message.validate()) != nil else { return .leave("수신 정보가 오래됐거나 현재 공유방과 일치하지 않아요.") }
            seen.insert(payload.message.id); pending.append(payload.message); pending.sort { $0.sequence < $1.sequence }
            return .accept(payload.message, b.epoch)
        }
    }
    public func next() -> (Voice, PTTBinding)? {
        lock.guarded {
            guard let b = binding, joined, active else { return nil }
            while !pending.isEmpty {
                let voice = pending.removeFirst(), now = clock.now()
                if voice.sequence > lastStarted, voice.acceptedAt <= now, voice.autoPlayUntil > now, b.roomExpiresAt > now, b.registrationExpiresAt > now { return (voice, b) }
            }; return nil
        }
    }
    public func mayStart(_ voice: Voice, binding expected: PTTBinding) -> Bool {
        lock.guarded {
            let now = clock.now()
            guard let binding, binding == expected, joined, active, voice.sequence > lastStarted,
                  voice.acceptedAt <= now, voice.autoPlayUntil > now, binding.registrationExpiresAt > now, binding.roomExpiresAt > now else { return false }
            lastStarted = voice.sequence; return true
        }
    }
}
public struct SSEEvent: Sendable { public let type: String, data: Data }
public struct SSEParser: Sendable {
    private var line: [UInt8] = [], data: [UInt8] = [], event = "message", carriageReturn = false, total = 0
    public init() {}
    public mutating func feed(_ bytes: Data) throws -> [SSEEvent] {
        var events: [SSEEvent] = []
        for byte in bytes {
            if byte == 10 && carriageReturn { carriageReturn = false; continue }
            if byte == 10 || byte == 13 {
                if line.isEmpty {
                    if !data.isEmpty { data.removeLast(); events.append(SSEEvent(type: event, data: Data(data))) }
                    data = []; total = 0; event = "message"
                } else {
                    total += line.count; guard total <= 262144, let text = String(bytes: line, encoding: .utf8) else { throw RoadError.invalid("이벤트 수신 형식이 올바르지 않아요.") }
                    if !text.hasPrefix(":") {
                        let fields = text.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
                        let raw = fields.count > 1 ? String(fields[1]) : ""
                        let value = raw.hasPrefix(" ") ? String(raw.dropFirst()) : raw
                        if fields[0] == "event" { event = value }; if fields[0] == "data" { data += value.utf8; data.append(10) }
                    }
                }
                line = []; carriageReturn = byte == 13
            } else { carriageReturn = false; line.append(byte); guard line.count + total <= 262144 else { throw RoadError.invalid("이벤트 크기가 상한을 넘었어요.") } }
        }; return events
    }
}
