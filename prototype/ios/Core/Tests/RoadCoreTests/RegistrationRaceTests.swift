import XCTest
import RoadScenarios

@MainActor final class RegistrationRaceScenariosTests: XCTestCase {
    func testOldPostCannotRetireNewTokenRegistrationInSameChannel() async throws { try await RegistrationRaceScenarios().testOldPostCannotRetireNewTokenRegistrationInSameChannel() }
    func testOldLeaveGetCannotDeleteRegistrationFromNewJoinIntent() async throws { try await RegistrationRaceScenarios().testOldLeaveGetCannotDeleteRegistrationFromNewJoinIntent() }
    func testPendingPostBeforeSystemLeaveCannotRegisterAfterFence() async throws { try await RegistrationRaceScenarios().testPendingPostBeforeSystemLeaveCannotRegisterAfterFence() }
    func testCancelledAcceptedPostIsRetiredOnConfirmedSystemLeave() async throws { try await RegistrationRaceScenarios().testCancelledAcceptedPostIsRetiredOnConfirmedSystemLeave() }
    func testLateOldChannelCleanupCannotCancelNewChannelJoin() async throws { try await RegistrationRaceScenarios().testLateOldChannelCleanupCannotCancelNewChannelJoin() }
    func testRetirementRetriesConcurrentCASWithoutRecreatingRegistration() async throws { try await RegistrationRaceScenarios().testRetirementRetriesConcurrentCASWithoutRecreatingRegistration() }
    func testRetirementPermissionFailureSurfacesWithoutRetryLoop() async throws { try await RegistrationRaceScenarios().testRetirementPermissionFailureSurfacesWithoutRetryLoop() }
}
