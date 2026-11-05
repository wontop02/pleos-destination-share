package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PendingRouteConfirmation
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PendingConfirmationCacheTest {
    private val pending = PendingRouteConfirmation(1, 2, Destination("합성 장소", 37.4, 127.0), 46_000, false)
    @After fun clear() { PendingConfirmationCache.clear() }

    @Test fun sameAuthenticatedRoomAndMemberCanConsumeTheVolatileAttemptOnlyOnce() {
        PendingConfirmationCache.save("room-a", "member-a", pending)
        assertTrue(PendingConfirmationCache.hasFor("room-a", "member-a"))
        assertEquals(pending, PendingConfirmationCache.takeFor("room-a", "member-a"))
        assertNull(PendingConfirmationCache.takeFor("room-a", "member-a"))
    }

    @Test fun differentRoomOrMemberCannotReuseTheAttempt() {
        for ((room, member) in listOf("room-b" to "member-a", "room-a" to "member-b")) {
            PendingConfirmationCache.save("room-a", "member-a", pending)
            assertFalse(PendingConfirmationCache.hasFor(room, member))
            assertNull(PendingConfirmationCache.takeFor(room, member))
            assertNull(PendingConfirmationCache.takeFor("room-a", "member-a"))
        }
    }

    @Test fun clearingForLeaveOrProcessLifetimeResetRetainsNoSuccessfulState() {
        PendingConfirmationCache.save("room-a", "member-a", pending)
        PendingConfirmationCache.clear()
        assertNull(PendingConfirmationCache.takeFor("room-a", "member-a"))
    }
}
