import Foundation
import RoadScenarios

private func report(_ value: String) { FileHandle.standardOutput.write(Data((value + "\n").utf8)) }
@main struct Main {
    @MainActor static func main() async {
        do {
            let core = CoreScenarios(), races = RegistrationRaceScenarios()
            try core.testPCMEndianAndDurationBoundaries(); report("PASS WAV PCM/duration")
            try core.testMalformedRIFFFormatBoundsAndDuplicates(); report("PASS malformed WAV")
            try core.testSSEFramingIsIncrementalUTF8CRLFAndBounded(); report("PASS SSE framing/bounds")
            try core.testEndpointAndInvitationNeverAcceptForeignOriginOrURLBearer(); report("PASS endpoint/auth invitation")
            try core.testNaverEncodingAndRegionalBounds(); report("PASS NAVER encoding/bounds")
            try core.testRetryKeepsOriginalBytesAndIDAndExpiresAtThirtySeconds(); report("PASS explicit retry expiry")
            try core.testServerClockAddsRTTAndMonotonicTimeWithoutGoingBackwards(); report("PASS server clock")
            try core.testPushNeedsCurrentBindingActivationAndExcludesSelfAndDuplicate(); report("PASS activation/self/dedup")
            try core.testFreshnessLeaseRoomAndEpochRecheckedAfterDownload(); report("PASS post-download expiry/epoch")
            try core.testAlreadyActiveSessionAcceptsFurtherPushesAndOrdersPending(); report("PASS active session queue order")
            try core.testWrongRegistrationVersionChannelExpiredLeaseAndPendingCapFailClosed(); report("PASS push validation/lease/cap")
            try core.testSameChannelRotationPreservesActivationAndDiscardsOldPendingEpoch(); report("PASS token rotation active/epoch boundary")
            try core.testLateOldChannelInvalidationCannotClearNewRoomGate(); report("PASS late old-channel callbacks")
            try await races.testOldPostCannotRetireNewTokenRegistrationInSameChannel(); report("PASS stale registration reply")
            try await races.testOldLeaveGetCannotDeleteRegistrationFromNewJoinIntent(); report("PASS stale leave reply")
            try await races.testPendingPostBeforeSystemLeaveCannotRegisterAfterFence(); report("PASS pending POST/system leave fence")
            try await races.testCancelledAcceptedPostIsRetiredOnConfirmedSystemLeave(); report("PASS accepted POST/system leave retirement")
            try await races.testLateOldChannelCleanupCannotCancelNewChannelJoin(); report("PASS late cleanup/new channel join")
            try await races.testRetirementRetriesConcurrentCASWithoutRecreatingRegistration(); report("PASS cleanup concurrent CAS retry")
            try await races.testRetirementPermissionFailureSurfacesWithoutRetryLoop(); report("PASS cleanup permission failure bounded")
            let api = APIContractScenarios()
            try await api.testRoomsDestinationIsolationAndAuthenticatedVoiceBytes(); report("PASS real HTTP auth/destination/WAV/idempotency")
            try await api.testSSEAuthEventsAndImmediateCancellation(); report("PASS real SSE/cancellation")
            try await api.testDisabledPTTVariableTokenAndCASFence(); report("PASS real PTT disabled/CAS/variable token")
            try await api.testLeaveCloseRevokeVoiceAccess(); report("PASS real leave/close byte access")
            report("24/24 Foundation scenarios passed; no iOS platform or physical-device claim")
        } catch { FileHandle.standardError.write(Data("FAIL \(error)\n".utf8)); exit(1) }
    }
}
