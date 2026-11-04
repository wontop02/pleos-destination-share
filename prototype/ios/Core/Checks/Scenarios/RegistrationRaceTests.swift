import Foundation
import RoadCore

private actor RacingRegistrationAPI: RegistrationAPI {
    var state = PTTState(configured: true, status: "unregistered", revision: 0, registration: nil, serverTime: 1000)
    var pauseFirstPost = false, pauseNextGet = false
    var postPaused = false, getPaused = false
    var postContinuation: CheckedContinuation<PTTState, Never>?, getContinuation: CheckedContinuation<PTTState, Never>?
    var oldPostReply: PTTState?
    var pauseBeforePost = false, beforePostPaused = false, conflictNextDelete = false
    var beforePostContinuation: CheckedContinuation<Void, Never>?
    var failNextGetStatus: Int?, getCount = 0, deleteCount = 0
    func pausePost() { pauseFirstPost = true }
    func pauseGet() { pauseNextGet = true }
    func pausePostBeforeCommit() { pauseBeforePost = true }
    func conflictDeleteOnce() { conflictNextDelete = true }
    func failGet(_ status: Int) { failNextGetStatus = status }
    func ptt(_ member: Session) async throws -> PTTState {
        getCount += 1
        if let status = failNextGetStatus { failNextGetStatus = nil; throw RoadError.http(status, "cleanup_failed", "cleanup failed") }
        if pauseNextGet { pauseNextGet = false; getPaused = true; return await withCheckedContinuation { getContinuation = $0 } }; return state
    }
    func register(_ member: Session, channel: UUID, token: Data, expectedRevision: Int64) async throws -> PTTState {
        if pauseBeforePost { pauseBeforePost = false; beforePostPaused = true; await withCheckedContinuation { beforePostContinuation = $0 } }
        guard expectedRevision == state.revision else { throw RoadError.http(409, "ptt_revision_conflict", "conflict") }
        let next = state.revision + 1
        state = PTTState(configured: true, status: "registered", revision: next, registration: PTTRegistration(id: String(format: "%024x", next), channelUUID: channel, expiresAt: 90000), serverTime: 1000)
        if pauseFirstPost { pauseFirstPost = false; postPaused = true; oldPostReply = state; return await withCheckedContinuation { postContinuation = $0 } }; return state
    }
    func unregister(_ member: Session, channel: UUID, expectedRevision: Int64) async throws -> PTTState {
        deleteCount += 1
        if conflictNextDelete { conflictNextDelete = false; state = PTTState(configured: true, status: state.status, revision: state.revision+1, registration: state.registration, serverTime: 1000); throw RoadError.http(409, "ptt_revision_conflict", "concurrent POST") }
        guard expectedRevision == state.revision else { throw RoadError.http(409, "ptt_revision_conflict", "conflict") }
        state = PTTState(configured: true, status: "unregistered", revision: state.revision+1, registration: nil, serverTime: 1000); return state
    }
    func releasePost() { postContinuation?.resume(returning: oldPostReply!); postContinuation = nil }
    func releaseGetWithLatest() { getContinuation?.resume(returning: state); getContinuation = nil }
    func releasePostBeforeCommit() { beforePostContinuation?.resume(); beforePostContinuation = nil }
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
    public func testPendingPostBeforeSystemLeaveCannotRegisterAfterFence() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), channel = UUID(), member = try member()
        await api.pausePostBeforeCommit()
        let joining = Task { try await coordinator.join(client: api, member: member, channel: channel, token: Data([1])) }
        try await wait { await api.beforePostPaused }
        try await coordinator.leave(client: api, member: member, channel: channel)
        await api.releasePostBeforeCommit(); let reply = try await joining.value, latest = await api.state
        try expectNil(reply); try expectNil(latest.registration); try expectEqual(latest.revision, 1)
    }
    public func testCancelledAcceptedPostIsRetiredOnConfirmedSystemLeave() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), channel = UUID(), member = try member()
        await api.pausePost()
        let joining = Task { try await coordinator.join(client: api, member: member, channel: channel, token: Data([1])) }
        try await wait { await api.postPaused }; joining.cancel()
        try await coordinator.leave(client: api, member: member, channel: channel)
        await api.releasePost(); let reply = try await joining.value, latest = await api.state
        try expectNil(reply); try expectNil(latest.registration); try expectEqual(latest.revision, 2)
    }
    public func testLateOldChannelCleanupCannotCancelNewChannelJoin() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), oldChannel = UUID(), newChannel = UUID(), member = try member()
        _ = try await coordinator.join(client: api, member: member, channel: oldChannel, token: Data([1]))
        await api.pausePost()
        let joining = Task { try await coordinator.join(client: api, member: member, channel: newChannel, token: Data([2])) }
        try await wait { await api.postPaused }
        try await coordinator.leave(client: api, member: member, channel: oldChannel)
        await api.releasePost(); let reply = try await joining.value, latest = await api.state
        try expectEqual(reply?.registration?.channelUUID, newChannel); try expectEqual(latest.registration?.channelUUID, newChannel); try expectEqual(latest.revision, 2)
    }
    public func testRetirementRetriesConcurrentCASWithoutRecreatingRegistration() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), channel = UUID(), member = try member()
        _ = try await coordinator.join(client: api, member: member, channel: channel, token: Data([1])); await api.conflictDeleteOnce()
        try await coordinator.leave(client: api, member: member, channel: channel)
        let latest = await api.state, calls = await api.deleteCount
        try expectNil(latest.registration); try expectEqual(calls, 2)
    }
    public func testRetirementPermissionFailureSurfacesWithoutRetryLoop() async throws {
        let api = RacingRegistrationAPI(), coordinator = RegistrationCoordinator(), channel = UUID(), member = try member()
        await api.failGet(403)
        do { try await coordinator.leave(client: api, member: member, channel: channel); try failCheck("cleanup permission failure was hidden") }
        catch RoadError.http(let status, _, _) { try expectEqual(status, 403) }
        let gets = await api.getCount, deletes = await api.deleteCount; try expectEqual(gets, 1); try expectEqual(deletes, 0)
    }
}
