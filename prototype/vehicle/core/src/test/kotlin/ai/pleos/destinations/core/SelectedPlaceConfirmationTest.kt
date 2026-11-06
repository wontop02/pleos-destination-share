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
        // The frozen RED ran this same production seam with the only available legacy meaning.
        // The explicit selected-place intent supplies the missing capability; exact stays strict.
        assertFalse(cafe.matchesNative(guidance))
        val gateway = Gateway()
        val selected = cafe.copy(kind = DestinationKind.PLEOS_PLACE, source = PlaceSource.PLEOS_BOOKMARK)
        val coordinator = VehicleCoordinator(gateway, { 1_000L }, {}, { _, _ -> })
        coordinator.connect(); gateway.listener.connected(); gateway.listener.destinationInfo(guidance)
        coordinator.attachRoom(false, 1L, selected)
        coordinator.applyShared(); gateway.listener.connected()
        gateway.listener.routingCompleted(); gateway.listener.destinationInfo(guidance)
        assertEquals(listOf(selected), gateway.requests)
        assertEquals("selected place should confirm from complete identity, not coordinate equality",
            ApplyPhase.CONFIRMED, coordinator.state.applying)
        assertEquals(selected, coordinator.state.shared)
        assertEquals(DestinationKind.PLEOS_PLACE, coordinator.state.confirmationKind)
        assertEquals(guidance, (coordinator.state.current as CurrentDestination.Present).value)
    }
}
