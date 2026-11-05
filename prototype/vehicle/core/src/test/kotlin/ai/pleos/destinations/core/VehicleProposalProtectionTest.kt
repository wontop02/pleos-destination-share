package ai.pleos.destinations.core

import org.junit.Assert.*
import org.junit.Test

class VehicleProposalProtectionTest {
    private val first = Destination("42dot", 37.41289678, 127.09383464,
        PleosMetadata("36khp37hh8gqcm5nqhe5", null, "수정구 시흥동", "", null, null))
    private val second = Destination("소프트웨어드림센터", 37.41275833, 127.09481944,
        PleosMetadata("36khp2hdj2cfcm5nqhe3", null, "수정구 시흥동", "", null, null))
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        var reads = 0
        val latest get() = listeners.last()
        override fun connect(listener: NavigationGateway.Listener) { listeners.add(listener) }
        override fun readDestination() { reads++ }
        override fun requestRoute(destination: Destination) { routes.add(destination) }
        override fun release() {}
    }
    private inner class Fixture(host: Boolean = true) {
        var now = 1_000L
        val gateway = Gateway()
        val published = mutableListOf<Destination?>()
        val coordinator = VehicleCoordinator(gateway, { now }, {}, { _, value -> published.add(value) })
        init { coordinator.attachRoom(host, 1, first); coordinator.connect(); gateway.latest.connected(); gateway.latest.destinationInfo(first) }
        fun advance(ms: Long = 300) { now += ms; coordinator.tick() }
        fun accepted() { coordinator.protectProposal(2); coordinator.receive(coordinator.roomEpoch, 2, second); coordinator.applyShared() }
    }
    @Test fun pendingProtectionFencesAnAlreadyInFlightLeaderReadAndShareAcknowledgement() {
        val f = Fixture(); f.coordinator.startAutomatic(); f.advance()
        f.coordinator.protectProposal(); f.gateway.latest.destinationInfo(second)
        f.coordinator.shareFinished(f.coordinator.roomEpoch, 1, first, true)
        f.advance(15_000); f.gateway.latest.destinationInfo(second)
        assertTrue(f.published.isEmpty()); assertTrue(f.gateway.routes.isEmpty())
    }
    @Test fun acceptedInitialSnapshotProtectsBeforeLeaderStartsObservingOldNative() {
        val f = Fixture(); f.coordinator.protectProposal(2); f.coordinator.attachRoom(true, 2, second)
        f.coordinator.startAutomatic(); f.advance(); f.gateway.latest.destinationInfo(first)
        assertTrue(f.published.isEmpty()); assertTrue(f.gateway.routes.isEmpty())
    }
    @Test fun acceptedCommitIsNotBlockedByOldInFlightPublicationAndItsLateFailureCannotCancelApply() {
        val f = Fixture(); f.coordinator.startAutomatic(); f.advance(); f.gateway.latest.destinationInfo(second)
        assertTrue(f.coordinator.state.sharing); assertEquals(1, f.published.size)
        f.accepted(); assertFalse(f.coordinator.state.sharing); f.gateway.latest.connected()
        f.coordinator.shareFinished(f.coordinator.roomEpoch, 0, null, false)
        f.gateway.latest.routingCompleted(); f.gateway.latest.destinationInfo(second)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(listOf(second), f.gateway.routes)
        assertEquals(1, f.published.size)
    }
    @Test fun acceptedRevisionDoesNotEnterGenericFollowerPathAndExplicitApplyIsOneOperation() {
        val f = Fixture(false); f.coordinator.startAutomatic()
        f.coordinator.protectProposal(2); f.coordinator.receive(f.coordinator.roomEpoch, 2, second)
        f.advance(); assertTrue(f.gateway.routes.isEmpty())
        f.coordinator.applyShared(); f.coordinator.applyShared(); f.gateway.latest.connected(); f.advance()
        assertEquals(listOf(second), f.gateway.routes)
        f.gateway.latest.routingCompleted(); f.gateway.latest.destinationInfo(second)
        f.advance(); f.coordinator.applyShared()
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(listOf(second), f.gateway.routes)
    }
    @Test fun leaderFailureKeepsProtectionAndOldCallbacksCannotPublishOrRestoreSuccess() {
        val f = Fixture(); f.coordinator.startAutomatic(); f.advance(); f.gateway.latest.destinationInfo(first)
        val old = f.gateway.latest; f.accepted(); f.gateway.latest.connected(); f.gateway.latest.failed()
        old.destinationInfo(first); old.destinationChanged(); old.routingCompleted()
        f.advance(5_000); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
        f.advance(45_000); f.gateway.latest.destinationInfo(first)
        assertTrue(f.published.isEmpty()); assertEquals(listOf(second), f.gateway.routes)
        assertTrue(f.coordinator.proposalPublicationHeld); assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.coordinator.releaseProposalProtection(); f.advance(); f.gateway.latest.destinationInfo(first)
        assertEquals(listOf(first), f.published)
    }
    @Test fun acceptedAutoOffReconnectIsQueryOnlyAndPreservesOriginalDeadline() {
        val f = Fixture(); f.accepted(); f.gateway.latest.connected()
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline; val old = f.gateway.latest
        f.advance(20_000); f.coordinator.suspendRoom(true); f.coordinator.attachRoom(true, 2, second, true)
        f.gateway.latest.connected(); old.destinationInfo(second); assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.gateway.latest.destinationInfo(first)
        assertEquals(deadline, f.coordinator.confirmationSnapshot()!!.deadline)
        f.now = deadline; f.coordinator.tick(); f.gateway.latest.destinationInfo(second)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying); assertEquals(listOf(second), f.gateway.routes)
    }
    @Test fun lateLeaderDestinationChangeAfterMatchRechecksOnlyAndNeverPublishesOldTarget() {
        val f = Fixture(); f.coordinator.startAutomatic(); f.accepted(); f.gateway.latest.connected()
        f.gateway.latest.routingCompleted(); f.gateway.latest.destinationInfo(second)
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline
        f.gateway.latest.destinationChanged(); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(deadline, f.coordinator.confirmationSnapshot()!!.deadline)
        assertTrue(f.published.isEmpty()); assertEquals(listOf(second), f.gateway.routes)
    }
    @Test fun stopDuringAcceptedConnectFencesEveryLateSdkEvent() {
        val f = Fixture(); f.accepted(); val old = f.gateway.latest
        f.coordinator.stopAutomatic(); old.connected(); old.destinationChanged(); old.destinationInfo(second); old.routingCompleted()
        f.advance(60_000); assertTrue(f.gateway.routes.isEmpty()); assertTrue(f.published.isEmpty())
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }
    @Test fun pendingReleaseAndStaleSuccessCannotClearCurrentAcceptedProtection() {
        val f = Fixture(); f.coordinator.startAutomatic(); f.accepted(); f.gateway.latest.connected(); f.gateway.latest.failed()
        f.coordinator.protectProposal(); f.coordinator.releasePendingProposalProtection()
        f.coordinator.completeAcceptedProposalProtection(1)
        f.advance(5_000); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
        assertTrue(f.coordinator.proposalPublicationHeld); assertTrue(f.published.isEmpty())
        assertEquals(listOf(second), f.gateway.routes)
    }
    @Test fun successfulAcceptedCompletionPreservesANewerPendingHold() {
        val f = Fixture(); f.coordinator.startAutomatic(); f.accepted(); f.gateway.latest.connected()
        f.gateway.latest.routingCompleted(); f.gateway.latest.destinationInfo(second)
        f.coordinator.protectProposal(); f.coordinator.completeAcceptedProposalProtection(2)
        f.advance(15_000); f.gateway.latest.destinationInfo(first)
        assertTrue(f.coordinator.proposalPublicationHeld); assertTrue(f.published.isEmpty())
        f.coordinator.releasePendingProposalProtection(); f.advance(); f.gateway.latest.destinationInfo(first)
        assertEquals(listOf(first), f.published); assertEquals(listOf(second), f.gateway.routes)
    }

}
