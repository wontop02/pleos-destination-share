package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleState

internal data class PlacesLoadScope(val room: String, val member: String, val roomEpoch: Long, val pageGeneration: Long)
internal data class PlacesLoadContext(val scope: PlacesLoadScope?, val vehicle: VehicleState,
    val places: SavedPlacesState?, val canLoad: Boolean, val canPropose: Boolean,
    val hasProposalLease: Boolean, val pendingAutomatic: Boolean)

/** Volatile UI intent for one explicit refresh; it owns no SDK, consent or route operation. */
internal class PlacesLoadIntent(private val clock: () -> Long) {
    enum class Action { NONE, RECONNECT, LOAD, CANCELLED, FAILED }
    private data class Request(val scope: PlacesLoadScope, val beforeSnapshot: Long, val deadline: Long,
        var nativeSnapshot: Long? = null, var sawConnecting: Boolean = false)
    private var request: Request? = null
    val waiting: Boolean get() = request != null

    private fun conflictFree(context: PlacesLoadContext) = context.scope != null && context.canPropose &&
        !context.hasProposalLease && !context.pendingAutomatic && !context.vehicle.sharing &&
        context.vehicle.applying !in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING)

    fun canRequest(context: PlacesLoadContext) = !waiting && context.places != null && conflictFree(context) &&
        context.vehicle.connection != NativeConnection.CONNECTING && context.vehicle.current != CurrentDestination.Loading &&
        context.places.phase != SavedPlacesPhase.LOADING

    fun begin(context: PlacesLoadContext, refresh: Boolean = false): Action {
        if (!canRequest(context)) return Action.NONE
        if (!refresh && context.canLoad) return Action.LOAD
        request = Request(checkNotNull(context.scope), checkNotNull(context.places).snapshot, clock() + 25_000)
        return Action.RECONNECT
    }

    fun cancel() { request = null }
    private fun finish(action: Action): Action { cancel(); return action }

    fun observe(context: PlacesLoadContext): Action {
        val pending = request ?: return Action.NONE
        if (context.scope != pending.scope) return finish(Action.CANCELLED)
        if (context.vehicle.connection == NativeConnection.CONNECTING) pending.sawConnecting = true
        if ((context.vehicle.connection == NativeConnection.FAILED && pending.sawConnecting) || clock() >= pending.deadline) return finish(Action.FAILED)
        val places = context.places
        if (!conflictFree(context) || places == null || places.phase == SavedPlacesPhase.LOADING) return finish(Action.CANCELLED)
        val generation = pending.nativeSnapshot
        if (generation != null && generation != places.snapshot) return finish(Action.CANCELLED)
        // The gateway announces the new bookmark epoch before the coordinator's connected callback.
        if (generation == null && places.snapshot > pending.beforeSnapshot && context.canLoad &&
            places.phase == SavedPlacesPhase.IDLE && context.vehicle.connection == NativeConnection.CONNECTING) {
            pending.nativeSnapshot = places.snapshot
        }
        // Wait for that same connection's initial destination read, never interrupt it with a refresh.
        val observed = context.vehicle.current is CurrentDestination.Present || context.vehicle.current is CurrentDestination.Absent
        if (pending.nativeSnapshot == places.snapshot && context.canLoad && places.phase == SavedPlacesPhase.IDLE &&
            context.vehicle.connection == NativeConnection.CONNECTED && observed) return finish(Action.LOAD)
        return Action.NONE
    }
}
