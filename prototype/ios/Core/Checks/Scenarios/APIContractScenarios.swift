import Foundation
import RoadCore

private final class NodeFixture {
    let process = Process(), client: APIClient
    init() throws {
        var source = URL(fileURLWithPath: #filePath); for _ in 0..<5 { source.deleteLastPathComponent() }; source.appendPathComponent("server.mjs")
        let encoded = String(data: try JSONEncoder().encode(source.absoluteString), encoding: .utf8)!
        let code = "import {createPrototypeServer} from \(encoded);const app=createPrototypeServer({pttProvider:null});app.server.listen(0,'127.0.0.1',()=>console.log(app.server.address().port));"
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env"); process.arguments = ["node", "--input-type=module", "-e", code]
        let output = Pipe(); process.standardOutput = output; process.standardError = Pipe(); try process.run()
        var bytes = Data()
        while bytes.count < 64 { let byte = output.fileHandleForReading.readData(ofLength: 1); if byte.isEmpty || byte == Data([10]) { break }; bytes.append(byte) }
        guard let port = Int(String(decoding: bytes, as: UTF8.self)), port > 0 else { process.terminate(); throw CheckFailure("temporary Node fixture did not publish a port") }
        client = APIClient(endpoint: try Endpoint("http://127.0.0.1:\(port)", debugLocalHTTP: true))
    }
    func stop() async throws {
        if process.isRunning { process.terminate() }
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        // waitUntilExit can strand a cooperative executor in its run-loop wait.
        while process.isRunning {
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw CheckFailure("temporary Node fixture did not terminate within two seconds") }
            try await Task.sleep(for: .milliseconds(5))
        }
    }
    deinit { if process.isRunning { process.terminate() } }
    func host() async throws -> Session { try await client.create(name: "가상 대표").value.session(endpoint: client.endpoint) }
    func member(_ host: Session) async throws -> Session { try await client.join(room: host.roomID, invite: host.inviteSecret!, name: "가상 참여자").value.session(endpoint: client.endpoint) }
}
private func withNodeFixture(_ body: (NodeFixture) async throws -> Void) async throws {
    let fixture = try NodeFixture()
    do { try await body(fixture) }
    catch { try? await fixture.stop(); throw error }
    try await fixture.stop()
}
private actor EventProbe { var events: [SSEEvent] = []; func add(_ event: SSEEvent) { events.append(event) }; func has(_ type: String) -> Bool { events.contains { $0.type == type } } }
public struct APIContractScenarios: Sendable {
    public init() {}
    public func testRoomsDestinationIsolationAndAuthenticatedVoiceBytes() async throws {
        try await withNodeFixture { f in
            let host = try await f.host(), member = try await f.member(host), other = try await f.host()
            try expectFalse(host.token == member.token); try expectFalse(host.token == host.inviteSecret)
            _ = try await f.client.destination(host, name: "가상 공원 & 쉼터", latitude: 37.52, longitude: 127.12)
            let state = try await f.client.snapshot(member).value; try expectEqual(state.destination?.name, "가상 공원 & 쉼터")
            do { _ = try await f.client.destination(member, name: "권한 없음", latitude: 37.52, longitude: 127.12); try failCheck("member changed destination") } catch RoadError.http(let status, _, _) { try expectEqual(status, 403) }
            let outsider = try Session(server: host.server, roomID: host.roomID, memberID: other.memberID, role: "member", token: other.token)
            do { _ = try await f.client.snapshot(outsider); try failCheck("other room token accepted") } catch RoadError.http(let status, _, _) { try expectEqual(status, 403) }
            let clip = RetryClip(bytes: try fictionalWAV()); let result = try await f.client.upload(host, clip: clip).value
            let data = try await f.client.download(member, voice: result.message); try expectEqual(data, clip.bytes)
            let retry = try await f.client.upload(host, clip: clip).value; try expectTrue(retry.duplicate); try expectEqual(retry.message, result.message)
            do { _ = try await f.client.upload(host, clip: RetryClip(bytes: fictionalWAV(frequency: 440), clientMessageID: clip.clientMessageID)); try failCheck("changed duplicate accepted") } catch RoadError.http(let status, _, _) { try expectEqual(status, 409) }
        }
    }
    public func testSSEAuthEventsAndImmediateCancellation() async throws {
        try await withNodeFixture { f in
            let host = try await f.host(), member = try await f.member(host), probe = EventProbe()
            let client = f.client
            let task = Task { for try await event in client.events(member) { await probe.add(event) } }
            for _ in 0..<200 { if await probe.has("snapshot") { break }; try await Task.sleep(for: .milliseconds(5)) }
            let receivedSnapshot = await probe.has("snapshot"); try expectTrue(receivedSnapshot)
            _ = try await f.client.upload(host, clip: RetryClip(bytes: fictionalWAV()))
            for _ in 0..<200 { if await probe.has("voice") { break }; try await Task.sleep(for: .milliseconds(5)) }
            let receivedVoice = await probe.has("voice"); try expectTrue(receivedVoice)
            let began = ProcessInfo.processInfo.systemUptime; task.cancel(); _ = try? await task.value
            try expectTrue(ProcessInfo.processInfo.systemUptime - began < 1, "SSE cancellation waited for heartbeat")
        }
    }
    public func testDisabledPTTVariableTokenAndCASFence() async throws {
        try await withNodeFixture { f in
            let member = try await f.host(), channel = UUID()
            let initial = try await f.client.ptt(member); try expectFalse(initial.configured); try expectEqual(initial.revision, 0)
            let posted = try await f.client.register(member, channel: channel, token: Data([1]), expectedRevision: 0)
            try expectEqual(posted.status, "not-configured"); try expectNil(posted.registration); try expectEqual(posted.revision, 1)
            let deleted = try await f.client.unregister(member, channel: channel, expectedRevision: 1); try expectEqual(deleted.revision, 2)
            do { _ = try await f.client.register(member, channel: channel, token: Data(repeating: 2, count: 512), expectedRevision: 0); try failCheck("stale revision accepted") } catch RoadError.http(let status, let code, _) { try expectEqual(status, 409); try expectEqual(code, "ptt_revision_conflict") }
            let next = try await f.client.register(member, channel: channel, token: Data(repeating: 2, count: 512), expectedRevision: 2); try expectEqual(next.revision, 3); try expectFalse(next.configured)
            do { _ = try await f.client.register(member, channel: channel, token: Data(repeating: 2, count: 513), expectedRevision: 3); try failCheck("oversized token accepted") } catch RoadError.invalid { }
        }
    }
    public func testLeaveCloseRevokeVoiceAccess() async throws {
        try await withNodeFixture { f in
            let host = try await f.host(), member = try await f.member(host)
            let sent = try await f.client.upload(member, clip: RetryClip(bytes: fictionalWAV())).value
            try await f.client.leave(member)
            do { _ = try await f.client.snapshot(member); try failCheck("left token accepted") } catch RoadError.http(let status, _, _) { try expectEqual(status, 403) }
            _ = try await f.client.download(host, voice: sent.message); try await f.client.leave(host)
            do { _ = try await f.client.download(host, voice: sent.message); try failCheck("closed audio accepted") } catch RoadError.http(let status, _, _) { try expectEqual(status, 404) }
        }
    }
}
