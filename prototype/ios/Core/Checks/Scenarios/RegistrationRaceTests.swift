import Foundation
import RoadCore

private actor RacingRegistrationAPI: RegistrationAPI {
    var state = PTTState(configured: true, status: "unregistered", revision: 0, registration: nil, serverTime: 1000)
    var pauseFirstPost = false, pauseNextGet = false
    var postPaused = false, getPaused = false
    var postContinuation: CheckedContinuation<PTTState, Never>?, getContinuation: CheckedContinuation<PTTState, Never>?
    var oldPostReply: PTTState?
    func pausePost() { pauseFirstPost = true }
    func pauseGet() { pauseNextGet = true }
    func ptt(_ member: Session) async throws -> PTTState {
        if pauseNextGet { pauseNextGet = false; getPaused = true; return await withCheckedContinuation { getContinuation = $0 } }; return state
    }
    func register(_ member: Session, channel: UUID, token: Data, expectedRevision: Int64) async throws -> PTTState {
        guard expectedRevision == state.revision else { throw RoadError.http(409, "ptt_revision_conflict", "conflict") }
        let next = state.revision + 1
        state = PTTState(configured: true, status: "registered", revision: next, registration: PTTRegistration(id: String(format: "%024x", next), channelUUID: channel, expiresAt: 90000), serverTime: 1000)
        if pauseFirstPost { pauseFirstPost = false; postPaused = true; oldPostReply = state; return await withCheckedContinuation { postContinuation = $0 } }; return state
    }
    func unregister(_ member: Session, channel: UUID, expectedRevision: Int64) async throws -> PTTState {
        guard expectedRevision == state.revision else { throw RoadError.http(409, "ptt_revision_conflict", "conflict") }
        state = PTTState(configured: true, status: "unregistered", revision: state.revision+1, registration: nil, serverTime: 1000); return state
    }
    func releasePost() { postContinuation?.resume(returning: oldPostReply!); postContinuation = nil }
    func releaseGetWithLatest() { getContinuation?.resume(returning: state); getContinuation = nil }
}
@MainActor public struct RegistrationRaceScenarios { public init() {}
    private func member() throws -> Session { try Session(server: "http://localhost:4180", roomID: roomID, memberID: selfID, role: "member", token: String(repeating: "x", count: 43)) }
    private func wait(_ condition: @escaping () async -> Bool) async throws { for _ in 0..<100 { if await condition() { return }; try await Task.sleep(for: .milliseconds(5)) }; try failCheck("controlled request did not pause") }
    public func testOldPostCannotRetireNewTokenRegistrationInSameChannel() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), channel = UUID(), member = try member()
        await api.pausePost()
        let old = Task { try await coordinator.join(client: api, member: member, channel: channel, token: Data([1])) }
        try await wait { await api.postPaused }
        let new = try await coordinator.join(client: api, member: member, channel: channel, token: Data([2]))
        await api.releasePost(); let oldResult = try await old.value, latest = await api.state
        try expectNil(oldResult); try expectEqual(latest.registration?.id, new?.registration?.id); try expectEqual(latest.revision, 2)
    }
    public func testOldLeaveGetCannotDeleteRegistrationFromNewJoinIntent() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), channel = UUID(), member = try member()
        _ = try await coordinator.join(client: api, member: member, channel: channel, token: Data([1])); await api.pauseGet()
        let leave = Task { try await coordinator.leave(client: api, member: member, channel: channel) }
        try await wait { await api.getPaused }
        let new = try await coordinator.join(client: api, member: member, channel: channel, token: Data([2]))
        await api.releaseGetWithLatest(); try await leave.value; let latest = await api.state
        try expectEqual(latest.registration?.id, new?.registration?.id); try expectEqual(latest.revision, 2)
    }
}
