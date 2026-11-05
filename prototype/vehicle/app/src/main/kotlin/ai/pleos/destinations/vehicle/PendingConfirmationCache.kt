package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.PendingRouteConfirmation

/** One volatile attempt; no token, saved view state, disk persistence, or success reconstruction. */
internal object PendingConfirmationCache {
    private data class Scoped(val roomId: String, val memberId: String, val pending: PendingRouteConfirmation)
    private var value: Scoped? = null
    fun save(roomId: String, memberId: String, pending: PendingRouteConfirmation) { value = Scoped(roomId, memberId, pending) }
    fun hasFor(roomId: String, memberId: String) = value?.let { it.roomId == roomId && it.memberId == memberId } == true
    fun takeFor(roomId: String, memberId: String): PendingRouteConfirmation? {
        val candidate = value
        value = null
        return candidate?.takeIf { it.roomId == roomId && it.memberId == memberId }?.pending
    }
    fun clear() { value = null }
}
