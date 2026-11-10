package ai.pleos.destinations.core

import org.junit.Assert.*
import org.junit.Test

class VehicleCoordinatorTest {
    private val first = Destination("공개 예제 42dot", 37.48544722, 127.03636666,
        PleosMetadata("36khp37hh8gqcm5nqhe5", "0", "서울시 강남구 남부순환로 2621", "", null, null))
    private val second = first.copy(name = "합성 목적지 B", latitude = 37.5, pleos = first.pleos!!.copy(poiId = "synthetic-b"))
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        var reads = 0; var releases = 0
        val latest get() = listeners.last()
        override fun connect(listener: NavigationGateway.Listener) { listeners.add(listener) }
        override fun readDestination() { reads++ }
        override fun requestRoute(destination: Destination) { routes.add(destination) }
        override fun release() { releases++ }
    }
    private inner class Fixture {
        val gateway = Gateway()
        var now = 1_000L
        val published = mutableListOf<Pair<Long, Destination?>>()
        val traces = mutableListOf<Pair<String, Boolean?>>()
        val coordinator = VehicleCoordinator(gateway, { now }, {}, { epoch, value -> published.add(epoch to value) },
            { event, _, _, matched -> traces.add(event to matched) })
        fun ready() {
            coordinator.connect(); gateway.latest.connected(); gateway.latest.destinationInfo(first)
        }
        fun room(host: Boolean = false, revision: Long = 1, value: Destination? = first): Long =
            coordinator.attachRoom(host, revision, value)
        fun apply(): NavigationGateway.Listener {
            coordinator.applyShared(); gateway.latest.connected(); return gateway.latest
        }
    }

    @Test fun sdkAbsenceIsFailureNotEmptyDestinationOrSimulatedConnection() {
        val f = Fixture(); f.coordinator.connect(); f.gateway.latest.failed()
        assertEquals(NativeConnection.FAILED, f.coordinator.state.connection)
        assertTrue(f.coordinator.state.current is CurrentDestination.Failed)
        assertTrue(f.gateway.routes.isEmpty()); assertTrue(f.published.isEmpty())
    }

    @Test fun connectionTimeoutFencesLateConnectedCallback() {
        val f = Fixture(); f.coordinator.connect(); val old = f.gateway.latest
        f.now += 15_000; f.coordinator.tick(); old.connected()
        assertEquals(NativeConnection.FAILED, f.coordinator.state.connection)
        assertEquals(0, f.gateway.reads)
    }
    @Test fun duplicateConnectionCallbackCannotIssueAnotherRoute() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        repeat(3) { listener.connected() }
        assertEquals(listOf(first), f.gateway.routes)
        listener.routingCompleted(); listener.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        listener.connected()
        assertEquals(1, f.gateway.routes.size)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun nativeEventsNeverShareWithoutHostExplicitAction() {
        val f = Fixture(); f.ready(); f.room(true)
        f.gateway.latest.destinationChanged(); f.gateway.latest.destinationInfo(second)
        assertTrue(f.published.isEmpty())
        f.coordinator.shareCurrent(); assertTrue(f.published.isEmpty())
        f.gateway.latest.destinationInfo(second)
        assertEquals(listOf(f.coordinator.roomEpoch to second), f.published)
    }

    @Test fun memberCannotPublishOrClearAndSharedReceiptDoesNotRequestRoute() {
        val f = Fixture(); f.ready(); val epoch = f.room(false)
        f.coordinator.shareCurrent(); f.coordinator.clearShared(); f.coordinator.receive(epoch, 2, second)
        assertTrue(f.published.isEmpty()); assertTrue(f.gateway.routes.isEmpty())
        assertEquals(second, f.coordinator.state.shared)
    }

    @Test fun aChangeDuringReadDiscardsCrossedResponseAndReadsAgain() {
        val f = Fixture(); f.coordinator.connect(); f.gateway.latest.connected()
        f.gateway.latest.destinationChanged(); f.gateway.latest.destinationInfo(first)
        assertEquals(2, f.gateway.reads)
        assertEquals(CurrentDestination.Loading, f.coordinator.state.current)
        f.gateway.latest.destinationInfo(second)
        assertEquals(second, (f.coordinator.state.current as CurrentDestination.Present).value)
        assertTrue(f.traces.any { it.first == "discardCrossedRead" })
    }

    @Test fun reversedAndRepeatedRoomRevisionsCannotReplaceNewerValue() {
        val f = Fixture(); f.ready(); val epoch = f.room()
        f.coordinator.receive(epoch, 3, second)
        f.coordinator.receive(epoch, 2, first); f.coordinator.receive(epoch, 3, null)
        assertEquals(second, f.coordinator.state.shared); assertEquals(3, f.coordinator.state.revision)
        assertTrue(f.gateway.routes.isEmpty())
    }

    @Test fun legacyDestinationAndMissingPoiIdentityAreNeverFilledWithInventedPoi() {
        val f = Fixture(); f.ready(); f.room(value = first.copy(pleos = null)); f.coordinator.applyShared()
        f.room(value = first.copy(pleos = first.pleos!!.copy(poiId = ""))); f.coordinator.applyShared()
        assertTrue(f.gateway.routes.isEmpty())
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun nullableSourceSubIdStaysNullInModelAndRequiresStrictNullableResultMatch() {
        val value = first.copy(pleos = first.pleos!!.copy(poiSubId = null))
        val f = Fixture(); f.ready(); f.room(value = value); val listener = f.apply()
        assertNull(f.gateway.routes.single().pleos!!.poiSubId)
        listener.routingCompleted(); listener.destinationInfo(first) // "0" is not equal to the source's null.
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        listener.destinationChanged(); listener.destinationInfo(value)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertNull(f.coordinator.state.shared!!.pleos!!.poiSubId)
    }

    @Test fun routeCallbackAloneAndWrongDestinationNeverConfirmApplication() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        assertEquals(listOf(first), f.gateway.routes)
        assertEquals(ApplyPhase.REQUESTED, f.coordinator.state.applying)
        listener.destinationInfo(first) // unsolicited, no post-route query yet
        assertEquals(ApplyPhase.REQUESTED, f.coordinator.state.applying)
        listener.routeStarted(); listener.routingCompleted()
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        listener.destinationInfo(second)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        listener.destinationChanged(); listener.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertNotNull(f.coordinator.state.confirmedAt)
        assertTrue(f.traces.contains("destinationMatch" to false)); assertTrue(f.traces.contains("destinationMatch" to true))
    }

    @Test fun moreRecentRoomDestinationCancelsAttemptAndOldListenerCannotCompleteNewOne() {
        val f = Fixture(); f.ready(); val epoch = f.room(); val old = f.apply()
        old.routingCompleted()
        f.coordinator.receive(epoch, 2, second)
        assertEquals(ApplyPhase.CANCELLED, f.coordinator.state.applying)
        val current = f.apply()
        old.destinationInfo(first); old.routingCompleted(); old.error("CAUSE_SEARCH_ROUTE"); old.routeCancelled()
        assertEquals(ApplyPhase.REQUESTED, f.coordinator.state.applying)
        current.routingCompleted(); current.destinationInfo(second)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun timeoutRetiresSessionAndLateSuccessfulCallbacksCannotReviveIt() {
        val f = Fixture(); f.ready(); f.room(); val old = f.apply()
        f.now += 45_000; f.coordinator.tick()
        old.routingCompleted(); old.destinationInfo(first)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying)
        assertEquals(NativeConnection.FAILED, f.coordinator.state.connection)
        assertNull(f.coordinator.state.confirmedAt)
    }

    @Test fun unansweredLookupIsFailureAndNotNoDestination() {
        val f = Fixture(); f.coordinator.connect(); f.gateway.latest.connected(); val old = f.gateway.latest
        f.now += 8_000; f.coordinator.tick(); old.destinationInfo(first)
        assertTrue(f.coordinator.state.current is CurrentDestination.Failed)
        assertFalse(f.coordinator.state.current is CurrentDestination.Absent)
    }

    @Test fun cancellationRetiresPreviousLookupAndRequiresAuthoritativeQueryForAbsence() {
        val f = Fixture(); f.ready(); f.room(); val old = f.apply(); old.routingCompleted(); old.routeCancelled()
        old.destinationInfo(first)
        assertFalse(f.coordinator.state.current is CurrentDestination.Absent)
        f.gateway.latest.connected(); f.gateway.latest.destinationAbsent()
        assertTrue(f.coordinator.state.current is CurrentDestination.Absent)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        assertEquals(1, f.gateway.routes.size)
        assertTrue(f.published.isEmpty())
    }

    @Test fun leaveInvalidatesAllPendingNativeAndHttpCallbacks() {
        val f = Fixture(); f.ready(); val epoch = f.room(); val old = f.apply()
        f.coordinator.detachRoom(epoch); old.routingCompleted(); old.destinationInfo(first)
        f.coordinator.receive(epoch, 99, second); f.coordinator.shareFinished(epoch, 99, second, true)
        assertFalse(f.coordinator.state.roomConnected); assertNull(f.coordinator.state.shared)
        assertEquals(ApplyPhase.CANCELLED, f.coordinator.state.applying)
    }

    @Test fun confirmedRouteCannotLoopThroughNativeEventsOrDuplicateApply() {
        val f = Fixture(); f.ready(); f.room(true); val listener = f.apply()
        listener.routingCompleted(); listener.destinationInfo(first)
        f.coordinator.applyShared()
        listener.destinationChanged(); listener.destinationInfo(first)
        assertEquals(1, f.gateway.routes.size); assertTrue(f.published.isEmpty())
    }

    @Test fun externalNativeChangeAllowsExplicitReapplicationOfTheSameSharedRevision() {
        val f = Fixture(); f.ready(); f.room(); val firstListener = f.apply()
        firstListener.routingCompleted(); firstListener.destinationInfo(first)
        firstListener.destinationChanged(); firstListener.destinationInfo(second)
        assertEquals(ApplyPhase.IDLE, f.coordinator.state.applying)
        assertNull(f.coordinator.state.confirmedAt)
        assertTrue(f.published.isEmpty())
        val secondListener = f.apply()
        assertEquals(2, f.gateway.routes.size)
        secondListener.routingCompleted(); secondListener.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun freshLookupWithoutChangeEventAlsoInvalidatesObsoleteConfirmation() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        listener.routingCompleted(); listener.destinationInfo(first)
        f.coordinator.refresh(); listener.destinationInfo(second)
        assertEquals(ApplyPhase.IDLE, f.coordinator.state.applying)
        assertNull(f.coordinator.state.confirmedAt)
        f.apply()
        assertEquals(2, f.gateway.routes.size)
    }

    @Test fun matchingRequiresPoiIdentityCoordinatesAndName() {
        assertFalse(first.matchesNative(first.copy(pleos = null)))
        assertFalse(first.matchesNative(first.copy(name = "다른 장소")))
        assertFalse(first.matchesNative(first.copy(latitude = first.latitude + 0.001)))
        assertFalse(first.matchesNative(first.copy(pleos = first.pleos!!.copy(poiId = "wrong"))))
        assertTrue(first.matchesNative(first.copy(latitude = first.latitude + 0.000001)))
    }

    @Test fun stopRemovesAllAbilityToConsumeCallbacksOrRequestMoreRoutes() {
        val f = Fixture(); f.ready(); f.room(); val old = f.apply(); f.coordinator.stop()
        old.connected(); old.routingCompleted(); old.destinationInfo(first); f.coordinator.applyShared(); f.coordinator.connect()
        assertEquals(1, f.gateway.routes.size); assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun repeatedShareActionWaitsForFreshReadAndSuppressesIdenticalRepublish() {
        val f = Fixture(); f.ready(); f.room(true)
        f.coordinator.shareCurrent(); f.coordinator.shareCurrent(); f.gateway.latest.destinationInfo(first)
        assertFalse(f.coordinator.state.sharing); assertTrue(f.published.isEmpty())
        f.coordinator.shareCurrent(); f.gateway.latest.destinationInfo(second)
        f.coordinator.shareCurrent()
        assertEquals(1, f.published.size)
    }

    @Test fun roomUpdateWhileShareReadIsPendingPreventsStalePublish() {
        val f = Fixture(); f.ready(); val epoch = f.room(true)
        f.coordinator.shareCurrent(); val old = f.gateway.latest
        f.coordinator.receive(epoch, 2, second); old.destinationInfo(first)
        assertTrue(f.published.isEmpty()); assertFalse(f.coordinator.state.sharing)
    }

    @Test fun shareErrorDoesNotClaimNativeChangeAndCanBeExplicitlyRetried() {
        val f = Fixture(); f.ready(); val epoch = f.room(true)
        f.coordinator.shareCurrent(); f.gateway.latest.destinationInfo(second)
        f.coordinator.shareFinished(epoch, 0, null, false)
        assertFalse(f.coordinator.state.sharing); assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.coordinator.shareCurrent(); f.gateway.latest.destinationInfo(second)
        assertEquals(2, f.published.size)
    }

    @Test fun reconnectSuspendsApplicationAndRetainsOnlyLastReceivedValue() {
        val f = Fixture(); f.ready(); f.room(); val old = f.apply()
        f.coordinator.suspendRoom(); f.coordinator.applyShared(); old.routingCompleted(); old.destinationInfo(first)
        assertEquals(first, f.coordinator.state.shared); assertFalse(f.coordinator.state.roomConnected)
        assertEquals(1, f.gateway.routes.size)
        f.room(value = second); val listener = f.apply(); listener.routingCompleted(); listener.destinationInfo(second)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun invalidSdkDestinationIsFailureWithoutClearingSharedValue() {
        val f = Fixture(); f.ready(); f.room(); f.coordinator.refresh(); f.gateway.latest.invalidDestination()
        assertTrue(f.coordinator.state.current is CurrentDestination.Failed)
        assertEquals(first, f.coordinator.state.shared); assertTrue(f.published.isEmpty())
    }

    @Test fun initialAuthoritativeEmptyLookupIsAbsenceWithoutDisconnectOrPublish() {
        val f = Fixture(); f.coordinator.connect(); f.gateway.latest.connected(); f.gateway.latest.destinationAbsent()
        assertTrue(f.coordinator.state.current is CurrentDestination.Absent)
        assertEquals(NativeConnection.CONNECTED, f.coordinator.state.connection)
        assertTrue(f.published.isEmpty()); assertTrue(f.gateway.routes.isEmpty())
    }

    @Test fun explicitShareOfEmptyLookupNeverPublishesZeroCoordinatesOrDeletesExistingSharedValue() {
        val f = Fixture(); f.ready(); f.room(true); f.coordinator.shareCurrent(); f.gateway.latest.destinationAbsent()
        assertTrue(f.coordinator.state.current is CurrentDestination.Absent)
        assertFalse(f.coordinator.state.sharing)
        assertEquals(first, f.coordinator.state.shared); assertEquals(1L, f.coordinator.state.revision)
        assertTrue(f.published.isEmpty())
    }

    @Test fun emptyLookupDuringRouteVerificationDoesNotConfirmCancelOrPropagateDeletion() {
        val f = Fixture(); f.ready(); f.room(true); val listener = f.apply()
        listener.routingCompleted(); listener.destinationAbsent()
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        assertEquals(first, f.coordinator.state.shared); assertTrue(f.published.isEmpty())
        listener.destinationChanged(); listener.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
    }

    @Test fun crossedEmptyLookupIsDiscardedBeforeDisplayingAbsence() {
        val f = Fixture(); f.coordinator.connect(); f.gateway.latest.connected()
        f.gateway.latest.destinationChanged(); f.gateway.latest.destinationAbsent()
        assertEquals(CurrentDestination.Loading, f.coordinator.state.current)
        assertEquals(2, f.gateway.reads)
        f.gateway.latest.destinationInfo(first)
        assertEquals(first, (f.coordinator.state.current as CurrentDestination.Present).value)
    }

    @Test fun boundedPollingConfirmsActualDestinationWithoutPretendingCompletionCallbackWasReceived() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        f.now += 2_000; f.coordinator.tick()
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        listener.routeStarted() // A delayed start must not regress the verification state.
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        listener.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertFalse(f.coordinator.state.routingCompletedObserved)
        assertTrue(f.coordinator.state.message.contains("탐색 완료 이벤트 미수신"))
    }

    @Test fun actualRoutingCompletedEventIsRecordedSeparatelyFromDestinationMatch() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        listener.routingCompleted()
        assertTrue(f.coordinator.state.routingCompletedObserved)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        listener.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertTrue(f.coordinator.state.message.contains("탐색 완료 이벤트 수신"))
    }

    @Test fun periodicReadsStaySingleFlightAndNeverExtendTheOriginalDeadline() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        f.now += 2_000; f.coordinator.tick(); val reads = f.gateway.reads
        f.coordinator.tick(); assertEquals(reads, f.gateway.reads)
        listener.destinationInfo(second)
        f.now += 1_999; f.coordinator.tick(); assertEquals(reads, f.gateway.reads)
        f.now += 1; f.coordinator.tick(); assertEquals(reads + 1, f.gateway.reads)
        listener.destinationAbsent()
        f.now = 46_000; f.coordinator.tick()
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying)
        assertEquals(first, f.coordinator.state.shared); assertTrue(f.published.isEmpty())
    }

    @Test fun responseAtDeadlineCannotConfirmEvenBeforeTheNextTimerTick() {
        val f = Fixture(); f.ready(); f.room(); val listener = f.apply()
        f.now = 44_000; f.coordinator.tick() // This final read has only two seconds of the original budget.
        f.now = 46_000; listener.destinationInfo(first)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying)
        assertNull(f.coordinator.state.confirmedAt)
    }

    @Test fun foregroundReturnResumesOnlyFreshVerificationWithoutAnotherRouteRequest() {
        val old = Fixture(); old.ready(); old.room(); old.apply()
        val pending = checkNotNull(old.coordinator.confirmationSnapshot()); old.coordinator.stop()
        val current = Fixture(); current.now += 5_000; current.room()
        assertTrue(current.coordinator.resumeConfirmation(pending)); current.gateway.latest.connected()
        assertTrue(current.gateway.routes.isEmpty())
        assertEquals(ApplyPhase.VERIFYING, current.coordinator.state.applying)
        current.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.CONFIRMED, current.coordinator.state.applying)
        assertFalse(current.coordinator.state.routingCompletedObserved)
        assertTrue(current.traces.any { it.first == "resumeVerification" })
    }

    @Test fun returnAfterOriginalDeadlineCannotRestoreSuccessEvenWhenNativeDestinationMatches() {
        val old = Fixture(); old.ready(); old.room(); old.apply()
        val pending = checkNotNull(old.coordinator.confirmationSnapshot()); old.coordinator.stop()
        val current = Fixture(); current.now = pending.deadline; current.room()
        assertFalse(current.coordinator.resumeConfirmation(pending))
        current.coordinator.connect(); current.gateway.latest.connected(); current.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.TIMED_OUT, current.coordinator.state.applying)
        assertNull(current.coordinator.state.confirmedAt); assertTrue(current.gateway.routes.isEmpty())
    }

    @Test fun newerRoomRevisionRejectsOldConfirmationEvenForTheSameCoordinates() {
        val old = Fixture(); old.ready(); old.room(); old.apply()
        val pending = checkNotNull(old.coordinator.confirmationSnapshot())
        val current = Fixture(); current.room(revision = 2)
        assertFalse(current.coordinator.resumeConfirmation(pending))
        assertEquals(2L, current.coordinator.state.revision)
        assertEquals(ApplyPhase.CANCELLED, current.coordinator.state.applying)
        assertTrue(current.gateway.routes.isEmpty())
    }

    @Test fun leaveCannotBeUndoneByResumingAnOldAttempt() {
        val old = Fixture(); old.ready(); val epoch = old.room(); old.apply()
        val pending = checkNotNull(old.coordinator.confirmationSnapshot()); old.coordinator.detachRoom(epoch)
        assertFalse(old.coordinator.resumeConfirmation(pending))
        assertFalse(old.coordinator.state.roomConnected); assertNull(old.coordinator.state.shared)
        assertNotEquals(ApplyPhase.CONFIRMED, old.coordinator.state.applying)
    }

    @Test fun freshProcessNeverInfersPriorSuccessFromRoomAndNativeValuesAlone() {
        val fresh = Fixture(); fresh.room(); fresh.coordinator.connect(); fresh.gateway.latest.connected()
        fresh.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.IDLE, fresh.coordinator.state.applying)
        assertNull(fresh.coordinator.state.confirmedAt); assertNull(fresh.coordinator.confirmationSnapshot())
        assertTrue(fresh.gateway.routes.isEmpty())
    }

    @Test fun resumedReadKeepsOriginalDeadlineRatherThanStartingAnotherFortyFiveSeconds() {
        val old = Fixture(); old.ready(); old.room(); old.apply()
        val pending = checkNotNull(old.coordinator.confirmationSnapshot()); old.coordinator.stop()
        val current = Fixture(); current.now = pending.deadline - 1_000; current.room()
        assertTrue(current.coordinator.resumeConfirmation(pending)); current.gateway.latest.connected()
        current.now = pending.deadline; current.gateway.latest.destinationInfo(first)
        assertEquals(ApplyPhase.TIMED_OUT, current.coordinator.state.applying)
        assertTrue(current.gateway.routes.isEmpty())
    }

    @Test fun previousRouteCancellationDuringReplacementCanConfirmOnlyFreshMatchingTargetWithoutReRequest() {
        val f = Fixture(); f.ready(); f.room(value = second); val old = f.apply()
        old.routingCompleted(); old.routeCancelled()
        old.destinationInfo(second); old.error("CAUSE_SEARCH_ROUTE") // Queued old-listener data is retired.
        assertNull(f.coordinator.state.confirmedAt)
        val fresh = f.gateway.latest; fresh.connected(); fresh.destinationInfo(first)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        f.now += 2_000; f.coordinator.tick(); fresh.destinationInfo(second)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(1, f.gateway.routes.size); assertTrue(f.published.isEmpty())
    }

    @Test fun cancellationFollowedByEmptyOrWrongDestinationAndLateTargetNeverConfirms() {
        val f = Fixture(); f.ready(); f.room(value = second); val old = f.apply(); old.routeCancelled()
        val fresh = f.gateway.latest; fresh.connected(); fresh.destinationAbsent()
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        f.now += 2_000; f.coordinator.tick(); fresh.destinationInfo(first)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        f.now = 44_000; f.coordinator.tick(); f.now = 46_000; fresh.destinationInfo(second)
        assertEquals(ApplyPhase.TIMED_OUT, f.coordinator.state.applying)
        assertNull(f.coordinator.state.confirmedAt); assertEquals(1, f.gateway.routes.size)
    }

    @Test fun globalCancellationWithoutPendingAttemptDoesNotAssumeDeletion() {
        val f = Fixture(); f.ready(); f.room(true); val old = f.gateway.latest; old.routeCancelled()
        assertFalse(f.coordinator.state.current is CurrentDestination.Absent)
        f.gateway.latest.connected(); f.gateway.latest.destinationInfo(second)
        assertEquals(second, (f.coordinator.state.current as CurrentDestination.Present).value)
        assertEquals(first, f.coordinator.state.shared); assertTrue(f.published.isEmpty())
    }

    @Test fun leaveAfterReplacementCancellationStillFencesTheResumedLookup() {
        val f = Fixture(); f.ready(); val epoch = f.room(value = second); f.apply().routeCancelled()
        val fresh = f.gateway.latest; fresh.connected()
        f.coordinator.detachRoom(epoch); fresh.destinationInfo(second)
        assertFalse(f.coordinator.state.roomConnected); assertNull(f.coordinator.state.shared)
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(1, f.gateway.routes.size)
    }
}
