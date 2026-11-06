package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.DestinationConfirmation
import ai.pleos.destinations.core.DestinationKind
import ai.pleos.destinations.core.NavigationGateway
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.PlaceSource
import ai.pleos.destinations.core.VehicleCoordinator
import ai.pleos.destinations.core.VehicleState
import org.junit.Assert.*
import org.junit.Test

class VehiclePresentationTest {
    private val exact = PrototypePlaces.all.first()
    private val place = exact.copy(kind = DestinationKind.PLEOS_PLACE, source = PlaceSource.PLEOS_BOOKMARK)
    private val person = ProposalPerson("a".repeat(24), "차량 A")
    private fun accepted(target: Destination = place, revision: Long = 4) = DestinationProposal(
        "b".repeat(24), 2, "accepted", target, "잠시 쉬어요", person, 0, 120_000, revision - 1,
        listOf(ProposalVote(person, "accepted", 1)), revision, 2, null)
    private fun confirmed(target: Destination = place, actual: Destination = exact) = VehicleState(
        connection = NativeConnection.CONNECTED, roomConnected = true, shared = target, revision = 4,
        current = CurrentDestination.Present(actual, 30), applying = ApplyPhase.CONFIRMED, confirmedAt = 30,
        confirmation = DestinationConfirmation(target.kind, target, actual, 30))

    @Test fun acceptedRecordAndSameCurrentNameNeverImplyConfirmation() {
        val old = confirmed().copy(applying = ApplyPhase.IDLE, confirmedAt = null, confirmation = null)
        assertFalse(VehiclePresentation.confirmedCurrentTarget(old, accepted()))
        assertNotEquals(StatusTone.SUCCESS, VehiclePresentation.destinationStatus(old, accepted()).tone)
        assertFalse(VehiclePresentation.confirmedCurrentTarget(old.copy(applying = ApplyPhase.CONFIRMED, confirmedAt = 30), accepted()))
    }

    @Test fun actualConfirmationMustBelongToCurrentTargetAndAcceptedRevision() {
        val good = confirmed()
        assertTrue(VehiclePresentation.confirmedCurrentTarget(good, accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good, accepted(revision = 3)))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(shared = PrototypePlaces.all.last()), accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(confirmation = good.confirmation!!.copy(source = exact)), accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(confirmation = good.confirmation!!.copy(kind = DestinationKind.EXACT_POINT)), accepted()))
    }

    @Test fun disconnectOrLaterNativeValueRemovesSuccess() {
        val good = confirmed()
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(roomConnected = false), accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(connection = NativeConnection.DISCONNECTED), accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(current = CurrentDestination.Unknown), accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(good.copy(current = CurrentDestination.Present(PrototypePlaces.all.last(), 31)), accepted()))
    }

    @Test fun routeEventsAndRequestPhasesNeverBecomeSuccess() {
        listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING, ApplyPhase.FAILED, ApplyPhase.TIMED_OUT).forEach { phase ->
            val state = confirmed().copy(applying = phase, routingCompletedObserved = true)
            assertNotEquals(StatusTone.SUCCESS, VehiclePresentation.destinationStatus(state, accepted()).tone)
        }
    }

    @Test fun placeConfirmationAllowsDifferentEntranceCoordinatesButRequiresIdentity() {
        val otherEntrance = exact.copy(latitude = exact.latitude + 0.001, longitude = exact.longitude + 0.001)
        assertTrue(VehiclePresentation.confirmedCurrentTarget(confirmed(actual = otherEntrance), accepted()))
        val wrongPoi = exact.copy(pleos = exact.pleos!!.copy(poiId = "another-poi"))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(confirmed(actual = wrongPoi), accepted()))
        assertFalse(VehiclePresentation.confirmedCurrentTarget(confirmed(exact, otherEntrance), accepted(exact)))
        assertTrue(VehiclePresentation.confirmedCurrentTarget(confirmed(exact, exact), accepted(exact)))
    }

    @Test fun explicitStopIsNeutralAndDoesNotAskForFailureRetry() {
        val gateway = object : NavigationGateway {
            override fun connect(listener: NavigationGateway.Listener) = Unit
            override fun readDestination() = Unit
            override fun requestRoute(destination: Destination) = Unit
            override fun release() = Unit
        }
        val coordinator = VehicleCoordinator(gateway, { 0 }, {}, { _, _ -> })
        coordinator.attachRoom(true, 4, exact)
        assertTrue(coordinator.startAutomatic())
        coordinator.stopAutomatic()
        val status = VehiclePresentation.destinationStatus(coordinator.state, null)
        assertEquals(ApplyPhase.CANCELLED, coordinator.state.applying)
        assertEquals(StatusTone.NEUTRAL, status.tone)
        assertFalse(VehiclePresentation.confirmedCurrentTarget(coordinator.state, null))
    }

    @Test fun optionalReasonUsesFactualDefaultAndRejectsProtocolInvalidInput() {
        assertFalse(VehiclePresentation.reason("   ").isNullOrBlank())
        assertEquals("잠시 쉬어요", VehiclePresentation.reason(" 잠시 쉬어요 "))
        assertNull(VehiclePresentation.reason("새 목적지\n변경"))
        assertNull(VehiclePresentation.reason("가".repeat(201)))
    }
}
