import XCTest
import RoadScenarios

@MainActor final class CoreScenariosTests: XCTestCase {
    func testPCMEndianAndDurationBoundaries() throws { try CoreScenarios().testPCMEndianAndDurationBoundaries() }
    func testMalformedRIFFFormatBoundsAndDuplicates() throws { try CoreScenarios().testMalformedRIFFFormatBoundsAndDuplicates() }
    func testSSEFramingIsIncrementalUTF8CRLFAndBounded() throws { try CoreScenarios().testSSEFramingIsIncrementalUTF8CRLFAndBounded() }
    func testEndpointAndInvitationNeverAcceptForeignOriginOrURLBearer() throws { try CoreScenarios().testEndpointAndInvitationNeverAcceptForeignOriginOrURLBearer() }
    func testNaverEncodingAndRegionalBounds() throws { try CoreScenarios().testNaverEncodingAndRegionalBounds() }
    func testRetryKeepsOriginalBytesAndIDAndExpiresAtThirtySeconds() throws { try CoreScenarios().testRetryKeepsOriginalBytesAndIDAndExpiresAtThirtySeconds() }
    func testServerClockAddsRTTAndMonotonicTimeWithoutGoingBackwards() throws { try CoreScenarios().testServerClockAddsRTTAndMonotonicTimeWithoutGoingBackwards() }
    func testPushNeedsCurrentBindingActivationAndExcludesSelfAndDuplicate() throws { try CoreScenarios().testPushNeedsCurrentBindingActivationAndExcludesSelfAndDuplicate() }
    func testFreshnessLeaseRoomAndEpochRecheckedAfterDownload() throws { try CoreScenarios().testFreshnessLeaseRoomAndEpochRecheckedAfterDownload() }
    func testAlreadyActiveSessionAcceptsFurtherPushesAndOrdersPending() throws { try CoreScenarios().testAlreadyActiveSessionAcceptsFurtherPushesAndOrdersPending() }
    func testWrongRegistrationVersionChannelExpiredLeaseAndPendingCapFailClosed() throws { try CoreScenarios().testWrongRegistrationVersionChannelExpiredLeaseAndPendingCapFailClosed() }
    func testSameChannelRotationPreservesActivationAndDiscardsOldPendingEpoch() throws { try CoreScenarios().testSameChannelRotationPreservesActivationAndDiscardsOldPendingEpoch() }
    func testLateOldChannelInvalidationCannotClearNewRoomGate() throws { try CoreScenarios().testLateOldChannelInvalidationCannotClearNewRoomGate() }
}
