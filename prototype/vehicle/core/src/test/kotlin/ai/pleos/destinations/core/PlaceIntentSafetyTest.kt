package ai.pleos.destinations.core

import org.junit.Assert.*
import org.junit.Test

class PlaceIntentSafetyTest {
    private val source = Destination("가든서비스", 37.41255277, 127.09591666,
        PleosMetadata("36khp2h36p68cm5nqhec", null, "경기도 성남시 수정구 창업로40번길6", "", null, null),
        DestinationKind.PLEOS_PLACE, PlaceSource.PLEOS_BOOKMARK)
    private val actual = Destination(source.name, 37.412475, 127.09534722,
        source.pleos!!.copy(address = "수정구시흥동"))
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        var reads = 0
        val listener get() = listeners.last()
        override fun connect(listener: NavigationGateway.Listener) { listeners.add(listener) }
        override fun readDestination() { reads++ }
        override fun requestRoute(destination: Destination) { routes.add(destination) }
        override fun release() = Unit
    }
    private inner class Fixture(host: Boolean = false, target: Destination = source) {
        var now = 1_000L
        val gateway = Gateway()
        val published = mutableListOf<Destination?>()
        val coordinator = VehicleCoordinator(gateway, { now }, {}, { _, value -> published.add(value) })
        init {
            coordinator.connect(); gateway.listener.connected(); gateway.listener.destinationInfo(actual)
            coordinator.attachRoom(host, 1, target)
        }
        fun apply(): NavigationGateway.Listener {
            coordinator.applyShared(); gateway.listener.connected(); return gateway.listener
        }
        fun confirm(): NavigationGateway.Listener = apply().also { it.routingCompleted(); it.destinationInfo(actual) }
    }

    @Test fun identityIsCompleteAndSourceDtoCannotMasqueradeAsNativeObservation() {
        assertTrue(source.matchesRequestedDestination(actual)); assertFalse(source.matchesNative(actual))
        assertFalse(source.matchesRequestedDestination(source))
        for (wrong in listOf(actual.copy(name = "다른 이름"), actual.copy(pleos = null),
            actual.copy(pleos = actual.pleos!!.copy(poiId = "different")),
            actual.copy(pleos = actual.pleos!!.copy(poiSubId = "0")),
            actual.copy(pleos = actual.pleos!!.copy(poiId = "")),
            actual.copy(pleos = actual.pleos!!.copy(poiProvider = "external")))) {
            val f = Fixture(); val listener = f.apply()
            listener.routingCompleted(); listener.destinationInfo(wrong)
            assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
            assertNull(f.coordinator.state.confirmationKind); assertNull(f.coordinator.state.confirmation)
            assertEquals(source, f.coordinator.state.shared)
        }
    }

    @Test fun incompleteOrUnsupportedSelectionsAndInvalidGuidanceCoordinatesRejectAtTheModelSeam() {
        val changes: List<() -> Destination> = listOf(
            { source.copy(source = null) }, { source.copy(pleos = null) },
            { source.copy(pleos = source.pleos!!.copy(poiId = "")) },
            { source.copy(pleos = source.pleos!!.copy(poiSubId = "")) },
            { source.copy(pleos = source.pleos!!.copy(poiProvider = "unsupported")) },
            { source.copy(pleos = source.pleos!!.copy(address = "")) },
            { actual.copy(latitude = Double.NaN) }, { actual.copy(longitude = Double.POSITIVE_INFINITY) },
            { actual.copy(latitude = 90.01) }, { actual.copy(longitude = -180.01) },
        )
        changes.forEach { construct -> assertThrows(IllegalArgumentException::class.java) { construct() } }
    }

    @Test fun legacyExactPointStillRejectsTheSameIdAtTheCafeGuidanceCoordinates() {
        val exact = source.copy(kind = DestinationKind.EXACT_POINT, source = null)
        val f = Fixture(target = exact); val listener = f.apply()
        listener.routingCompleted(); listener.destinationInfo(actual)
        assertEquals(ApplyPhase.VERIFYING, f.coordinator.state.applying)
        assertFalse(exact.matchesNative(actual)); assertNull(f.coordinator.state.confirmationKind)
        listener.destinationChanged(); listener.destinationInfo(exact)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(DestinationKind.EXACT_POINT, f.coordinator.state.confirmationKind)
    }

    @Test fun routeReturnAndStartAloneDoNotConfirmAndOnlyFreshIssuedReadCanConfirm() {
        val f = Fixture(); val listener = f.apply()
        listener.destinationInfo(actual); listener.routeStarted()
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        f.now += 2_000; f.coordinator.tick(); listener.destinationInfo(actual)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertFalse(f.coordinator.state.routingCompletedObserved)
        val confirmation = f.coordinator.state.confirmation!!
        assertEquals(source, confirmation.source); assertEquals(actual, confirmation.actual)
        assertNotEquals(confirmation.source.latitude, confirmation.actual.latitude)
        assertFalse(f.coordinator.state.message.contains("탐색 완료"))
    }

    @Test fun errorTimeoutStopRoomChangeAndOldSdkEpochNeverRevivePlaceConfirmation() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.gateway.listener.error("CAUSE_SEARCH_ROUTE") },
            { it.gateway.listener.invalidDestination() },
            { it.now += 45_000; it.coordinator.tick() },
            { it.coordinator.stopAutomatic() }, { it.coordinator.stop() },
            { it.coordinator.detachRoom() }, { it.coordinator.attachRoom(false, 1, source) },
            { it.coordinator.receive(it.coordinator.roomEpoch, 2, actual.copy(name = "새 대상")) },
        )
        for (mutation in mutations) {
            val f = Fixture(); val old = f.apply(); old.routingCompleted()
            mutation(f); old.destinationInfo(actual); old.routingCompleted(); old.routeStarted()
            assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
            assertNull(f.coordinator.state.confirmationKind); assertNull(f.coordinator.state.confirmation)
            assertEquals(1, f.gateway.routes.size)
        }
    }

    @Test fun cancelRequiresANewSdkReadAndKeepsOriginalDeadlineWithoutAnotherRouteRequest() {
        val f = Fixture(); val old = f.apply(); old.routingCompleted()
        f.now += 40_000; old.routeCancelled(); old.destinationInfo(actual)
        assertNotEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        val fresh = f.gateway.listener; fresh.connected(); fresh.destinationInfo(actual)
        assertEquals(ApplyPhase.CONFIRMED, f.coordinator.state.applying)
        assertEquals(46_000L, f.coordinator.confirmationSnapshot()!!.deadline)
        assertEquals(1, f.gateway.routes.size)
        // A late cancellation outside the original window cannot restore the previous success.
        f.now = 46_000; fresh.routeCancelled(); fresh.destinationInfo(actual)
        assertNull(f.coordinator.state.confirmationKind)
        assertEquals(1, f.gateway.routes.size)
    }

    @Test fun acceptedPlaceDoesNotRepublishOrReapplyAfterProtectionEndsButDifferentNativeIdentityStillPublishes() {
        val f = Fixture(host = true); f.coordinator.startAutomatic(); f.coordinator.protectProposal(1)
        val listener = f.confirm()
        assertEquals(DestinationKind.PLEOS_PLACE, f.coordinator.state.confirmationKind)
        f.now += 46_000; f.coordinator.completeAcceptedProposalProtection(1)
        repeat(4) {
            f.coordinator.tick(); listener.destinationInfo(actual)
            f.coordinator.applyShared(); f.now += 15_000
        }
        assertEquals(1, f.gateway.routes.size); assertTrue(f.published.isEmpty())
        assertEquals(source, f.coordinator.state.shared)
        listener.destinationChanged(); f.now += 300; f.coordinator.tick()
        val different = actual.copy(name = "실제 다른 목적지", pleos = actual.pleos!!.copy(poiId = "different-poi"))
        listener.destinationInfo(different)
        assertEquals(listOf(different), f.published)
        assertNull(f.coordinator.state.confirmationKind)
    }

    @Test fun samePlaceCoalescesAfterReconnectRestartAndColdObservationWithoutManufacturingConfirmation() {
        for (lifetime in listOf("reconnect", "restart", "cold")) {
            val f = Fixture(host = true)
            if (lifetime != "cold") {
                f.coordinator.startAutomatic(); f.coordinator.protectProposal(1); f.confirm()
                f.now += 46_000; f.coordinator.completeAcceptedProposalProtection(1)
                if (lifetime == "reconnect") {
                    f.coordinator.suspendRoom(true); f.coordinator.attachRoom(true, 1, source, true)
                } else {
                    f.coordinator.stopAutomatic(); f.coordinator.attachRoom(true, 1, source)
                    f.coordinator.startAutomatic(); f.gateway.listener.connected()
                }
            } else f.coordinator.startAutomatic()
            assertNull(f.coordinator.state.confirmationKind)
            f.coordinator.tick(); f.gateway.listener.destinationInfo(actual)
            assertTrue("$lifetime must not publish a coordinate-only difference", f.published.isEmpty())
            assertNull(f.coordinator.state.confirmationKind)
            assertEquals(source, f.coordinator.state.shared)
            assertEquals(if (lifetime == "cold") 0 else 1, f.gateway.routes.size)
        }
    }

    @Test fun ordinaryExactLeaderStillPublishesASameIdCoordinateChange() {
        val exact = source.copy(kind = DestinationKind.EXACT_POINT, source = null)
        val f = Fixture(host = true, target = exact)
        f.coordinator.startAutomatic(); f.coordinator.tick(); f.gateway.listener.destinationInfo(actual)
        assertEquals(listOf(actual), f.published)
        assertEquals(DestinationKind.EXACT_POINT, f.published.single()!!.kind)
    }
}
