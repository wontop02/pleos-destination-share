package ai.pleos.destinations.core

import org.junit.Assert.*
import org.junit.Test

/** Actual public BookmarkInfo and independent post-route DestinationInfo from issue #19. */
class SelectedPlaceConfirmationTest {
    private val cafe = Destination("가든서비스", 37.41255277, 127.09591666,
        PleosMetadata("36khp2h36p68cm5nqhec", null, "경기도 성남시 수정구 창업로40번길6", "", null, null))
    private val guidance = cafe.copy(latitude = 37.412475, longitude = 127.09534722,
        pleos = cafe.pleos!!.copy(address = "수정구시흥동"))
    private class Gateway : NavigationGateway {
        lateinit var listener: NavigationGateway.Listener
        val requests = mutableListOf<Destination>()
        override fun connect(listener: NavigationGateway.Listener) { this.listener = listener }
        override fun readDestination() = Unit
        override fun requestRoute(destination: Destination) { requests.add(destination) }
        override fun release() = Unit
    }

    @Test fun cafeSelectionConfirmsPlaceWhilePreservingDistinctGuidancePoint() {
        // RED baseline: there is no selected-place request meaning yet. The legacy matcher
        // correctly rejects these coordinates; the missing capability is above that matcher.
        assertFalse(cafe.matchesNative(guidance))
        val gateway = Gateway()
        val coordinator = VehicleCoordinator(gateway, { 1_000L }, {}, { _, _ -> })
        coordinator.connect(); gateway.listener.connected(); gateway.listener.destinationInfo(guidance)
        coordinator.attachRoom(false, 1L, cafe)
        coordinator.applyShared(); gateway.listener.connected()
        gateway.listener.routingCompleted(); gateway.listener.destinationInfo(guidance)
        assertEquals(listOf(cafe), gateway.requests)
        assertEquals("selected place should confirm from complete identity, not coordinate equality",
            ApplyPhase.CONFIRMED, coordinator.state.applying)
        assertEquals(cafe, coordinator.state.shared)
        assertEquals(guidance, (coordinator.state.current as CurrentDestination.Present).value)
    }
}
