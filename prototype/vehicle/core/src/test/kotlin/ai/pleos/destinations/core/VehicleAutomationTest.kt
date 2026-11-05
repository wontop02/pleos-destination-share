package ai.pleos.destinations.core

import org.junit.Assert.*
import org.junit.Test

class VehicleAutomationTest {
    private val first = Destination("42dot", 37.48544722, 127.03636666,
        PleosMetadata("36khp37hh8gqcm5nqhe5", null, "서울시 강남구 남부순환로 2621", "", null, null))
    private val second = Destination("소프트웨어드림센터", 37.41275833, 127.09481944,
        PleosMetadata("36khp2hdj2cfcm5nqhe3", null, "경기도 성남시 수정구 창업로40번길 6", "", null, null))
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        var reads = 0
        var releases = 0
        val latest get() = listeners.last()
        override fun connect(listener: NavigationGateway.Listener) { listeners.add(listener) }
        override fun readDestination() { reads++ }
        override fun requestRoute(destination: Destination) { routes.add(destination) }
        override fun release() { releases++ }
    }
    private inner class Fixture(host: Boolean = false, shared: Destination? = first) {
        var now = 1_000L
        val gateway = Gateway()
        val published = mutableListOf<Pair<Long, Destination?>>()
        val coordinator = VehicleCoordinator(gateway, { now }, {}, { epoch, value -> published.add(epoch to value) })
        init { coordinator.connect(); gateway.latest.connected(); gateway.latest.destinationInfo(first); coordinator.attachRoom(host, 1, shared) }
        fun advance(ms: Long = 300) { now += ms; coordinator.tick() }
        fun start() { assertTrue(coordinator.startAutomatic()); advance() }
        fun acceptRequest() { gateway.latest.connected() }
        fun match(value: Destination = first) { gateway.latest.routingCompleted(); gateway.latest.destinationInfo(value) }
    }
    @Test fun defaultOffNeverSharesOrAppliesOnNativeAndRoomEvents() {
        val f = Fixture(); f.gateway.latest.destinationChanged(); f.gateway.latest.destinationInfo(second)
        f.coordinator.receive(f.coordinator.roomEpoch, 2, second); f.advance(30_000)
        assertEquals(AutomaticMode.OFF, f.coordinator.state.automatic)
        assertTrue(f.gateway.routes.isEmpty()); assertTrue(f.published.isEmpty())
    }
    @Test fun explicitLeaderStartReadsAndPreservesActualNullMetadata() {
        val f = Fixture(true, null); f.start(); f.gateway.latest.destinationInfo(first)
        assertEquals(listOf(f.coordinator.roomEpoch to first), f.published)
        assertNull(f.published.single().second!!.pleos!!.poiSubId)
        assertFalse(f.coordinator.startAutomatic())
    }
    @Test fun leaderEventBurstCoalescesAndSameValueDoesNotRepublish() {
        val f = Fixture(true); f.start(); f.gateway.latest.destinationInfo(first)
        val before = f.gateway.reads
        repeat(100) { f.gateway.latest.destinationChanged() }
        f.advance(299); assertEquals(before, f.gateway.reads)
        f.advance(1); assertEquals(before + 1, f.gateway.reads)
        f.gateway.latest.destinationInfo(first)
        assertTrue(f.published.isEmpty())
    }
    @Test fun leaderPublishesLatestObservationAfterEarlierPublishCompletes() {
        val f = Fixture(true); f.start(); f.gateway.latest.destinationInfo(second)
        val epoch = f.coordinator.roomEpoch
        f.gateway.latest.destinationChanged(); f.advance(); f.gateway.latest.destinationInfo(first)
        assertEquals(1, f.published.size)
        f.coordinator.shareFinished(epoch, 2, second, true)
        assertEquals(listOf(epoch to second, epoch to first), f.published)
    }
    @Test fun onlyActualCompleteAbsentReadCanAutoClearSharedValue() {
        val f = Fixture(true); f.start(); f.gateway.latest.routeCancelled()
        val fresh = f.gateway.latest; fresh.connected()
        assertTrue(f.published.isEmpty()); fresh.destinationAbsent()
        assertEquals(listOf(f.coordinator.roomEpoch to null), f.published)
    }
    @Test fun invalidOrTimedOutReadNeverPublishesNull() {
        val f = Fixture(true); f.start(); val old = f.gateway.latest; old.invalidDestination()
        old.destinationAbsent(); f.advance(5_000); f.gateway.latest.connected(); f.advance(8_000)
        assertTrue(f.published.isEmpty()); assertTrue(f.coordinator.state.current is CurrentDestination.Failed)
    }
    @Test fun leaderLongRunningBoundedPollDoesNotAmplifyIdenticalNativeValues() {
        val f = Fixture(true); f.start(); f.gateway.latest.destinationInfo(first)
        repeat(60) { f.advance(15_000); f.gateway.latest.destinationInfo(first) }
        assertEquals(62, f.gateway.reads); assertTrue(f.published.isEmpty()); assertTrue(f.gateway.routes.isEmpty())
    }
    @Test fun followerOptInAppliesLatestOnceAndOnlyActualStrictReadConfirms() {
        val f = Fixture(); f.start(); f.acceptRequest()
        assertEquals(listOf(first), f.gateway.routes)
        f.gateway.latest.routeStarted(); assertEquals(ApplyPhase.ROUTING, f.coordinator.state.applying)
        f.gateway.latest.routingCompleted(); assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        f.gateway.latest.destinationInfo(first.copy(pleos = first.pleos!!.copy(poiSubId = "0")))
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.advance(2_000); f.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertTrue(f.published.isEmpty())
        f.advance(30_000); assertEquals(1, f.gateway.routes.size)
    }
    @Test fun rapidRevisionsBeforeCoalescingRequestOnlyLatestTarget() {
        val f = Fixture(); assertTrue(f.coordinator.startAutomatic())
        val epoch = f.coordinator.roomEpoch
        f.coordinator.receive(epoch, 2, second); f.coordinator.receive(epoch, 3, first); f.coordinator.receive(epoch, 4, second)
        f.advance(299); assertTrue(f.gateway.routes.isEmpty())
        f.advance(1); f.acceptRequest(); assertEquals(listOf(second), f.gateway.routes)
    }
    @Test fun newerTargetRetiresEarlierPendingListenerAndOldRoomRevision() {
        val f = Fixture(); f.start(); f.acceptRequest(); val old = f.gateway.latest
        val epoch = f.coordinator.roomEpoch
        f.coordinator.receive(epoch, 2, second); f.coordinator.receive(epoch, 1, first)
        f.advance(); f.acceptRequest()
        old.routingCompleted(); old.destinationInfo(first); old.routeCancelled(); old.error("STALE")
        assertEquals(ApplyPhase.REQUESTED, f.coordinator.state.applying)
        f.match(second); assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(listOf(first, second), f.gateway.routes)
    }
    @Test fun identicalHigherRevisionAdoptsPendingOperationWithoutRerequest() {
        val f = Fixture(); f.start(); f.acceptRequest()
        f.coordinator.receive(f.coordinator.roomEpoch, 2, first); f.advance(); f.match()
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(1, f.gateway.routes.size)
        f.coordinator.receive(f.coordinator.roomEpoch, 3, first); f.advance(10_000)
        assertEquals(1, f.gateway.routes.size); assertEquals(3, f.coordinator.state.revision)
    }
    @Test fun sharedNullWaitsWithoutCancellingNativeGuidanceOrIssuingRoute() {
        val f = Fixture(); f.start(); f.acceptRequest(); val old = f.gateway.latest
        f.coordinator.receive(f.coordinator.roomEpoch, 2, null); f.advance(60_000)
        old.routingCompleted(); old.destinationInfo(first)
        assertEquals(1, f.gateway.routes.size); assertNull(f.coordinator.state.shared)
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertTrue(f.coordinator.state.message.contains("순정 안내 유지"))
    }
    @Test fun stopFencesConnectedCallbacksDestinationEventsAndTimers() {
        val f = Fixture(); f.start(); val old = f.gateway.latest
        f.coordinator.stopAutomatic(); old.connected(); old.destinationChanged(); old.destinationInfo(second)
        f.coordinator.receive(f.coordinator.roomEpoch, 2, second); f.advance(60_000)
        assertEquals(AutomaticMode.OFF, f.coordinator.state.automatic); assertTrue(f.gateway.routes.isEmpty()); assertTrue(f.published.isEmpty())
    }
    @Test fun stopThenExplicitStartCreatesFreshConsentAndFencesOldSession() {
        val f = Fixture(); f.start(); val old = f.gateway.latest
        f.coordinator.stopAutomatic(); f.coordinator.startAutomatic(); f.advance(); f.acceptRequest()
        old.connected(); old.routingCompleted(); old.destinationInfo(second)
        assertEquals(listOf(first), f.gateway.routes); assertEquals(ApplyPhase.REQUESTED, f.coordinator.state.applying)
    }
    @Test fun cancellationDuringReplacementResumesQueryOnlyWithOriginalDeadline() {
        val f = Fixture(); f.start(); f.acceptRequest(); val old = f.gateway.latest
        f.advance(20_000); old.routeCancelled(); val queryOnly = f.gateway.latest; queryOnly.connected()
        assertEquals(1, f.gateway.routes.size)
        old.destinationInfo(first); assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        queryOnly.destinationInfo(second); f.advance(25_000)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying); assertEquals(1, f.gateway.routes.size)
    }
    @Test fun cancellationQueryOnlyCanConfirmWithoutAppReturnOrAnotherRoute() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.gateway.latest.routeCancelled()
        f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(1, f.gateway.routes.size)
    }
    @Test fun sameRoomReconnectResumesExistingRequestByFreshQueryNotRoute() {
        val f = Fixture(); f.start(); f.acceptRequest(); val old = f.gateway.latest
        f.coordinator.suspendRoom(); f.coordinator.attachRoom(false, 1, first, true)
        f.gateway.latest.connected(); old.routingCompleted(); old.destinationInfo(first)
        f.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(1, f.gateway.routes.size)
    }
    @Test fun differentRoomAttachRevokesConsentAndStaleEpochCannotStartRequest() {
        val f = Fixture(); f.start(); val old = f.gateway.latest; val epoch = f.coordinator.roomEpoch
        f.coordinator.attachRoom(false, 1, second); f.coordinator.receive(epoch, 100, first); f.advance()
        old.connected(); assertEquals(AutomaticMode.OFF, f.coordinator.state.automatic); assertTrue(f.gateway.routes.isEmpty())
    }
    @Test fun failedAutomaticRevisionDoesNotLoopRequestsWithoutANewIntent() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.gateway.latest.failed()
        f.advance(5_000); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(second)
        f.advance(60_000); assertEquals(1, f.gateway.routes.size)
        f.coordinator.receive(f.coordinator.roomEpoch, 2, second); f.advance(); f.acceptRequest()
        assertEquals(2, f.gateway.routes.size)
    }

    @Test fun recentConfirmationThenLateCancelRequiresFreshMatchWithoutAnotherRoute() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val original = checkNotNull(f.coordinator.confirmationSnapshot())
        val old = f.gateway.latest
        f.advance(1_000); old.routeCancelled()
        assertEquals(ApplyPhase.CONNECTING, f.coordinator.state.applying); assertNull(f.coordinator.state.confirmedAt)
        old.destinationInfo(first); old.routingCompleted()
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.gateway.latest.connected()
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        assertEquals(original.deadline, f.coordinator.confirmationSnapshot()!!.deadline)
        f.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertNotNull(f.coordinator.state.confirmedAt); assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun recentConfirmationThenCancelEmptyFreshReadAndLateTargetNeverConfirms() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline
        f.gateway.latest.routeCancelled(); f.gateway.latest.connected(); val fresh = f.gateway.latest
        fresh.destinationAbsent()
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertNull(f.coordinator.state.confirmedAt)
        assertTrue(f.coordinator.state.current is CurrentDestination.Absent)
        f.now = deadline; fresh.destinationInfo(first)
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.coordinator.tick(); assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying)
        assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun recentConfirmationThenCancelWrongFreshReadDoesNotRestoreOldSuccess() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline
        f.gateway.latest.routeCancelled(); f.gateway.latest.connected(); val fresh = f.gateway.latest
        fresh.destinationInfo(second)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying); assertNull(f.coordinator.state.confirmedAt)
        f.now = deadline; f.coordinator.tick(); fresh.destinationInfo(first)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying); assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun recentConfirmationThenCancelUnansweredFreshReadIsFailureNotSuccessOrAbsence() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        f.gateway.latest.routeCancelled(); f.gateway.latest.connected(); val fresh = f.gateway.latest
        f.advance(8_000); fresh.destinationInfo(first)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying)
        assertTrue(f.coordinator.state.current is CurrentDestination.Failed)
        assertNull(f.coordinator.state.confirmedAt); assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun recentConfirmationSnapshotAdoptsIdenticalNewRevisionBeforeLateCancel() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline
        f.coordinator.receive(f.coordinator.roomEpoch, 2, first)
        assertEquals(2L, f.coordinator.confirmationSnapshot()!!.revision)
        f.gateway.latest.routeCancelled(); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(2L, f.coordinator.state.revision); assertEquals(deadline, f.coordinator.confirmationSnapshot()!!.deadline)
        assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun newerTargetAfterConfirmationFencesItsLateCancellationAndOldFreshRead() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        f.gateway.latest.routeCancelled(); val cancelled = f.gateway.latest
        f.coordinator.receive(f.coordinator.roomEpoch, 2, second); f.advance(); f.acceptRequest()
        cancelled.connected(); cancelled.destinationInfo(first); cancelled.routeCancelled()
        assertEquals(ApplyPhase.REQUESTED, f.coordinator.state.applying)
        f.match(second); assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(listOf(first, second), f.gateway.routes)
    }
    @Test fun stopOrLeaveDuringRecentConfirmationRecheckFencesAllLateCallbacks() {
        for (leave in listOf(false, true)) {
            val f = Fixture(); f.start(); f.acceptRequest(); f.match()
            f.gateway.latest.routeCancelled(); val fresh = f.gateway.latest
            if (leave) f.coordinator.detachRoom() else f.coordinator.stopAutomatic()
            fresh.connected(); fresh.routingCompleted(); fresh.destinationInfo(first); fresh.routeCancelled(); f.advance(60_000)
            assertEquals(AutomaticMode.OFF, f.coordinator.state.automatic)
            assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertNull(f.coordinator.state.confirmedAt)
            assertEquals(listOf(first), f.gateway.routes)
        }
    }
    @Test fun expiredRecentConfirmationCannotBeRevivedByCancelAndMatchingObservation() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline
        f.now = deadline; f.gateway.latest.routeCancelled(); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertNull(f.coordinator.confirmationSnapshot()); assertNull(f.coordinator.state.confirmedAt)
        assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun repeatedPostConfirmationCancelsNeverExtendOriginalDeadline() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val deadline = f.coordinator.confirmationSnapshot()!!.deadline
        repeat(3) {
            f.advance(1_000); f.gateway.latest.routeCancelled(); f.gateway.latest.connected(); f.gateway.latest.destinationInfo(first)
            assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
            assertEquals(deadline, f.coordinator.confirmationSnapshot()!!.deadline)
        }
        assertEquals(listOf(first), f.gateway.routes)
    }
    @Test fun lateDestinationChangeAfterConfirmationAlsoNeedsFreshQueryOnlyMatch() {
        val f = Fixture(); f.start(); f.acceptRequest(); f.match()
        val old = f.gateway.latest; old.destinationChanged()
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertNull(f.coordinator.state.confirmedAt)
        old.destinationInfo(first); f.gateway.latest.connected()
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying); assertEquals(listOf(first), f.gateway.routes)
    }
}
