import XCTest
import RoadScenarios

@MainActor final class RegistrationRaceScenariosTests: XCTestCase {
    func testOldPostCannotRetireNewTokenRegistrationInSameChannel() async throws { try await RegistrationRaceScenarios().testOldPostCannotRetireNewTokenRegistrationInSameChannel() }
    func testOldLeaveGetCannotDeleteRegistrationFromNewJoinIntent() async throws { try await RegistrationRaceScenarios().testOldLeaveGetCannotDeleteRegistrationFromNewJoinIntent() }
}
