import Foundation
import RoadCore

final class TestTime: @unchecked Sendable { private let lock = NSLock(); private var value: TimeInterval = 0; func tick() -> TimeInterval { lock.lock(); defer { lock.unlock() }; return value }; func set(_ value: TimeInterval) { lock.lock(); self.value = value; lock.unlock() } }
func fictionalWAV(_ count: Int = 16000, frequency: Double = 220) throws -> Data { try WAV.encode((0..<count).map { Int16(sin(Double($0) * frequency * 2 * .pi / 16000) * 2000) }) }
let roomID = String(repeating: "a", count: 24), selfID = String(repeating: "b", count: 24), otherID = String(repeating: "c", count: 24)
func voice(_ id: Int = 1, sequence: Int64 = 1, sender: String = otherID, at: Int64 = 1000) throws -> Voice {
    let body: [String: Any] = ["id": String(format: "%024x", id), "sender": ["id": sender, "name": "가상 참여자"], "sequence": sequence, "acceptedAt": at, "autoPlayUntil": at + 30000, "durationMs": 1000, "byteLength": 32044, "sampleCount": 16000, "sampleRate": 16000, "channels": 1, "format": "pcm_s16le"]
    return try JSONDecoder().decode(Voice.self, from: JSONSerialization.data(withJSONObject: body))
}
func payload(_ voice: Voice, binding: PTTBinding, version: Int = 1, registrationID: String? = nil) throws -> PTTPayload {
    var message = try JSONSerialization.jsonObject(with: JSONEncoder().encode(voice)) as! [String: Any]
    message["durationMs"] = voice.durationMs
    return try JSONDecoder().decode(PTTPayload.self, from: JSONSerialization.data(withJSONObject: ["v": version, "roomId": binding.roomID, "channelUUID": binding.channelUUID.uuidString, "registrationId": registrationID ?? binding.registrationID, "message": message, "serverTime": 1000, "roomExpiresAt": binding.roomExpiresAt]))
}
func binding(epoch: UUID = UUID(), roomExpiresAt: Int64 = 100000, registrationExpiresAt: Int64 = 90000) -> PTTBinding { PTTBinding(roomID: roomID, memberID: selfID, channelUUID: UUID(), registrationID: String(repeating: "d", count: 24), registrationExpiresAt: registrationExpiresAt, roomExpiresAt: roomExpiresAt, epoch: epoch) }

public struct CoreScenarios: Sendable { public init() {}
    public func testPCMEndianAndDurationBoundaries() throws {
        var samples = [Int16](repeating: 0, count: 16000); samples[0] = .min; samples[1] = .max; samples[2] = -17
        let encoded = try WAV.encode(samples), decoded = try WAV.decode(encoded)
        try expectEqual(decoded.sampleCount, 16000); try expectEqual(Array(decoded.bytes.prefix(6)), [0,128,255,127,239,255]); try expectEqual(encoded.count, 32044)
        try expectEqual(try WAV.decode(fictionalWAV(240000)).sampleCount, 240000)
        try expectThrows(try WAV.encode([Int16](repeating: 0, count: 15999))); try expectThrows(try WAV.encode([Int16](repeating: 0, count: 240001)))
    }
    public func testMalformedRIFFFormatBoundsAndDuplicates() throws {
        let valid = try fictionalWAV()
        for (offset, byte) in [(0,UInt8(0xD2)),(4,0),(20,3),(22,2),(24,0),(28,1),(32,4),(34,8),(40,255)] {
            var bad = valid; bad[offset] = byte; try expectThrows(try WAV.decode(bad), "offset \(offset)")
        }
        var trailing = valid; trailing.append(contentsOf: [1,2,3]); try expectThrows(try WAV.decode(trailing))
        try expectThrows(try WAV.decode(Data(repeating: 0, count: WAV.maximumBytes+1)))
        var duplicate = valid; duplicate.append(contentsOf: Array("data".utf8) + [2,0,0,0,0,0]); let size = UInt32(duplicate.count-8); for i in 0..<4 { duplicate[4+i] = UInt8(truncatingIfNeeded: size >> (i*8)) }; try expectThrows(try WAV.decode(duplicate))
    }
    public func testSSEFramingIsIncrementalUTF8CRLFAndBounded() throws {
        var parser = SSEParser(), events: [SSEEvent] = []
        for byte in ": comment\r\nevent: voice\r\ndata: {\r\ndata: \"name\":\"가상 동행\"}\r\n\r\n".utf8 { events += try parser.feed(Data([byte])) }
        try expectEqual(events.count, 1); try expectEqual(events[0].type, "voice"); try expectEqual((try JSONSerialization.jsonObject(with: events[0].data) as! [String: String])["name"], "가상 동행")
        try expectTrue(try parser.feed(Data("data: incomplete".utf8)).isEmpty)
        try expectThrows(try parser.feed(Data(repeating: 120, count: 262145)))
    }
    public func testEndpointAndInvitationNeverAcceptForeignOriginOrURLBearer() throws {
        let endpoint = try Endpoint("http://localhost:4180", debugLocalHTTP: true), token = String(repeating: "x", count: 43)
        try expectEqual(try endpoint.invitation("http://127.0.0.1:4180/#join=\(roomID).\(token)").room, roomID)
        for text in ["https://attacker.invalid/#join=\(roomID).\(token)", "http://localhost:4181/#join=\(roomID).\(token)", "http://localhost:4180/?token=\(token)#join=\(roomID).\(token)"] { try expectThrows(try endpoint.invitation(text)) }
        try expectThrows(try Endpoint("http://localhost:4180", debugLocalHTTP: false)); try expectThrows(try Endpoint("http://192.168.0.10:4180", debugLocalHTTP: true)); try expectThrows(try Endpoint("https://secret@example.com", debugLocalHTTP: false))
        let session = try Session(server: endpoint.origin.absoluteString, roomID: roomID, memberID: selfID, role: "host", token: token); try expectFalse(session.description.contains(token))
    }
    public func testNaverEncodingAndRegionalBounds() throws {
        let name = "가상 공원 & #1 + 100%?", dest = Destination(name: name, latitude: 37.52, longitude: 127.12, changedAt: 1000)
        let parts = URLComponents(url: try unwrap(NaverNavigation.url(dest, bundleID: "kr.together.road.ios")), resolvingAgainstBaseURL: false)!
        try expectEqual(parts.scheme, "nmap"); try expectEqual(parts.queryItems?.first { $0.name == "dname" }?.value, name); try expectEqual(parts.queryItems?.first { $0.name == "appname" }?.value, "kr.together.road.ios"); try expectNil(parts.fragment)
        try expectNil(NaverNavigation.url(Destination(name: name, latitude: 0, longitude: 0, changedAt: 0), bundleID: "kr.together.road.ios"))
    }
    public func testRetryKeepsOriginalBytesAndIDAndExpiresAtThirtySeconds() throws {
        let clip = RetryClip(bytes: Data([1,2,3]), capturedUptime: 100)
        try expectTrue(clip.fresh(uptime: 129.999)); try expectFalse(clip.fresh(uptime: 130)); try expectFalse(clip.fresh(uptime: 99)); try expectEqual(clip.clientMessageID, clip.clientMessageID); try expectEqual(clip.bytes, Data([1,2,3]))
    }
    public func testServerClockAddsRTTAndMonotonicTimeWithoutGoingBackwards() throws {
        let time = TestTime(), clock = ServerClock(uptime: time.tick); clock.update(1000, roundTripMS: 200); try expectEqual(clock.now(), 1200)
        time.set(0.1); try expectEqual(clock.now(), 1300); clock.update(1050); try expectEqual(clock.now(), 1300); time.set(0.2); try expectEqual(clock.now(), 1400)
    }
    public func testPushNeedsCurrentBindingActivationAndExcludesSelfAndDuplicate() throws {
        let clock = ServerClock(); clock.update(1000); let gate = ReceiveGate(clock: clock), b = binding(); gate.configure(b, joined: true)
        if case .accept = gate.receive(try payload(voice(), binding: b), channelUUID: b.channelUUID) {} else { try failCheck("valid push rejected") }
        try expectNil(gate.next()); gate.setAudioActive(true); let next = try unwrap(gate.next()); try expectEqual(next.0.id, try voice().id); try expectTrue(gate.mayStart(next.0, binding: b))
        if case .leave = gate.receive(try payload(voice(), binding: b), channelUUID: b.channelUUID) {} else { try failCheck("duplicate accepted") }
        if case .leave = gate.receive(try payload(voice(2, sequence: 2, sender: selfID), binding: b), channelUUID: b.channelUUID) {} else { try failCheck("self accepted") }
    }
    public func testFreshnessLeaseRoomAndEpochRecheckedAfterDownload() throws {
        let time = TestTime(), clock = ServerClock(uptime: time.tick); clock.update(1000)
        let gate = ReceiveGate(clock: clock), b = binding(); gate.configure(b, joined: true); gate.setAudioActive(true)
        _ = gate.receive(try payload(voice(), binding: b), channelUUID: b.channelUUID); let next = try unwrap(gate.next()); time.set(30); try expectFalse(gate.mayStart(next.0, binding: b))
        let changed = binding(); gate.configure(changed, joined: true); gate.setAudioActive(true); try expectFalse(gate.mayStart(next.0, binding: b))
        gate.invalidate(); try expectNil(gate.next())
    }
    public func testAlreadyActiveSessionAcceptsFurtherPushesAndOrdersPending() throws {
        let clock = ServerClock(); clock.update(1000); let gate = ReceiveGate(clock: clock), b = binding(); gate.configure(b, joined: true); gate.setAudioActive(true)
        _ = gate.receive(try payload(voice(3, sequence: 3), binding: b), channelUUID: b.channelUUID); _ = gate.receive(try payload(voice(2, sequence: 2), binding: b), channelUUID: b.channelUUID)
        try expectEqual(gate.next()?.0.sequence, 2); try expectEqual(gate.next()?.0.sequence, 3)
    }
    public func testWrongRegistrationVersionChannelExpiredLeaseAndPendingCapFailClosed() throws {
        let clock = ServerClock(); clock.update(1000); let gate = ReceiveGate(clock: clock), b = binding(); gate.configure(b, joined: true)
        for p in [try payload(voice(), binding: b, version: 2), try payload(voice(), binding: b, registrationID: "foreign")] { if case .leave = gate.receive(p, channelUUID: b.channelUUID) {} else { try failCheck("foreign payload accepted") } }
        let expired = binding(registrationExpiresAt: 999); gate.configure(expired, joined: true)
        if case .leave = gate.receive(try payload(voice(), binding: expired), channelUUID: expired.channelUUID) {} else { try failCheck("expired registration accepted") }
        gate.configure(b, joined: true)
        for index in 1...16 { _ = gate.receive(try payload(voice(index, sequence: Int64(index)), binding: b), channelUUID: b.channelUUID) }
        if case .leave = gate.receive(try payload(voice(17, sequence: 17), binding: b), channelUUID: b.channelUUID) {} else { try failCheck("unbounded queue") }
    }
    public func testSameChannelRotationPreservesActivationAndDiscardsOldPendingEpoch() throws {
        let clock = ServerClock(); clock.update(1000); let gate = ReceiveGate(clock: clock), old = binding(); gate.configure(old, joined: true); gate.setAudioActive(true)
        let first = try voice(); _ = gate.receive(try payload(first, binding: old), channelUUID: old.channelUUID)
        let updated = PTTBinding(roomID: old.roomID, memberID: old.memberID, channelUUID: old.channelUUID, registrationID: String(repeating: "e", count: 24), registrationExpiresAt: old.registrationExpiresAt, roomExpiresAt: old.roomExpiresAt, epoch: UUID())
        gate.configure(updated, joined: true, seenIDs: [first.id]); try expectTrue(gate.audioIsActive); try expectNil(gate.next())
        try expectFalse(gate.mayStart(first, binding: old))
        let fresh = try voice(2, sequence: 2); _ = gate.receive(try payload(fresh, binding: updated), channelUUID: updated.channelUUID)
        let next = try unwrap(gate.next()); try expectEqual(next.0.id, fresh.id); try expectTrue(gate.mayStart(fresh, binding: updated))
    }
    public func testLateOldChannelInvalidationCannotClearNewRoomGate() throws {
        let clock = ServerClock(); clock.update(1000); let gate = ReceiveGate(clock: clock), old = binding(), current = binding()
        gate.configure(old, joined: true); gate.configure(current, joined: true); gate.setAudioActive(true); gate.invalidate(channelUUID: old.channelUUID)
        _ = gate.receive(try payload(voice(), binding: current), channelUUID: current.channelUUID)
        try expectTrue(gate.audioIsActive); try expectEqual(gate.next()?.1, current)
    }
}
