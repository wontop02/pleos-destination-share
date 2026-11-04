import XCTest
import RoadScenarios

@MainActor final class APIContractScenariosTests: XCTestCase {
    func testRoomsDestinationIsolationAndAuthenticatedVoiceBytes() async throws { try await APIContractScenarios().testRoomsDestinationIsolationAndAuthenticatedVoiceBytes() }
    func testSSEAuthEventsAndImmediateCancellation() async throws { try await APIContractScenarios().testSSEAuthEventsAndImmediateCancellation() }
    func testDisabledPTTVariableTokenAndCASFence() async throws { try await APIContractScenarios().testDisabledPTTVariableTokenAndCASFence() }
    func testLeaveCloseRevokeVoiceAccess() async throws { try await APIContractScenarios().testLeaveCloseRevokeVoiceAccess() }
}
