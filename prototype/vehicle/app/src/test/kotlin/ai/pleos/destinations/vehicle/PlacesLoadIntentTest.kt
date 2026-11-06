package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleState
import org.junit.Assert.*
import org.junit.Test

class PlacesLoadIntentTest {
    private val place = PrototypePlaces.all.first()
    private val scope = PlacesLoadScope("room", "member", 4, 2)
    private val vehicle = VehicleState(connection = NativeConnection.CONNECTED, roomConnected = true,
        current = CurrentDestination.Present(place, 10))
    private fun context(phase: SavedPlacesPhase = SavedPlacesPhase.RECONNECT_REQUIRED, snapshot: Long = 10,
        canLoad: Boolean = false) = PlacesLoadContext(scope, vehicle, SavedPlacesState(phase, snapshot),
        canLoad, canPropose = true, hasProposalLease = false, pendingAutomatic = false)
    private fun newConnection(base: PlacesLoadContext, snapshot: Long = 13) = base.copy(
        vehicle = vehicle.copy(connection = NativeConnection.CONNECTING, current = CurrentDestination.Unknown),
        places = SavedPlacesState(SavedPlacesPhase.IDLE, snapshot), canLoad = true)

    @Test fun enteringOrReopeningComposerNeverCreatesReadOrReconnectIntent() {
        val intent = PlacesLoadIntent { 0 }
        listOf(context(), newConnection(context()), context(SavedPlacesPhase.READY, 14, true)).forEach {
            assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(it))
            assertFalse(intent.waiting)
        }
    }

    @Test fun readySnapshotOpensDirectlyAndRefreshRequiresAnExplicitTap() {
        val ready = context(SavedPlacesPhase.READY, canLoad = true)
        val intent = PlacesLoadIntent { 0 }
        assertEquals(PlacesLoadIntent.Action.LOAD, intent.begin(ready))
        assertFalse(intent.waiting)
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(ready))
        assertEquals(PlacesLoadIntent.Action.RECONNECT, intent.begin(ready, refresh = true))
        assertTrue(intent.waiting)
    }

    @Test fun cancelledOrRejectedFlowReadsOnceAfterNewConnectionAndItsDestinationRead() {
        val old = context()
        val intent = PlacesLoadIntent { 0 }
        assertEquals(PlacesLoadIntent.Action.RECONNECT, intent.begin(old))
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(old))
        // Retiring the old helper and observing the new one cannot reuse its old destination.
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(old.copy(places = old.places!!.copy(snapshot = 11))))
        val fresh = newConnection(old)
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(fresh))
        val connected = fresh.copy(vehicle = vehicle.copy(current = CurrentDestination.Unknown))
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(connected))
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(connected.copy(vehicle = vehicle.copy(current = CurrentDestination.Loading))))
        assertEquals(PlacesLoadIntent.Action.LOAD, intent.observe(connected.copy(vehicle = vehicle)))
        assertFalse(intent.waiting)
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(connected.copy(vehicle = vehicle)))
    }

    @Test fun roomMemberEpochPageExitAndColdInstanceCannotResumeTheIntent() {
        val old = context()
        val differentScopes = listOf(scope.copy(room = "other"), scope.copy(member = "other"),
            scope.copy(roomEpoch = 5), scope.copy(pageGeneration = 3), null)
        differentScopes.forEach { changed ->
            val intent = PlacesLoadIntent { 0 }
            assertEquals(PlacesLoadIntent.Action.RECONNECT, intent.begin(old))
            assertEquals(PlacesLoadIntent.Action.CANCELLED, intent.observe(newConnection(old).copy(scope = changed)))
            assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(newConnection(old).copy(vehicle = vehicle)))
        }
        assertEquals(PlacesLoadIntent.Action.NONE, PlacesLoadIntent { 0 }.observe(newConnection(old).copy(vehicle = vehicle)))
    }

    @Test fun activeRouteReadShareProposalAndAutomaticStartFenceExplicitRefresh() {
        val old = context()
        val conflicts = listOf(
            old.copy(vehicle = vehicle.copy(connection = NativeConnection.CONNECTING)),
            old.copy(vehicle = vehicle.copy(current = CurrentDestination.Loading)),
            old.copy(places = old.places!!.copy(phase = SavedPlacesPhase.LOADING)),
            old.copy(vehicle = vehicle.copy(sharing = true)), old.copy(canPropose = false),
            old.copy(hasProposalLease = true), old.copy(pendingAutomatic = true),
        ) + listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING).map {
            old.copy(vehicle = vehicle.copy(applying = it))
        }
        conflicts.forEach {
            val intent = PlacesLoadIntent { 0 }
            assertFalse(intent.canRequest(it))
            assertEquals(PlacesLoadIntent.Action.NONE, intent.begin(it, refresh = true))
            assertFalse(intent.waiting)
        }
    }

    @Test fun conflictingWorkDuringReconnectCancelsWithoutDelayedLoad() {
        val old = context()
        listOf(old.copy(canPropose = false), old.copy(hasProposalLease = true), old.copy(pendingAutomatic = true),
            old.copy(vehicle = vehicle.copy(sharing = true)), old.copy(vehicle = vehicle.copy(applying = ApplyPhase.REQUESTED))).forEach {
            val intent = PlacesLoadIntent { 0 }
            intent.begin(old)
            assertEquals(PlacesLoadIntent.Action.CANCELLED, intent.observe(it))
            assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(newConnection(old).copy(vehicle = vehicle)))
        }
    }

    @Test fun laterNativeGenerationCannotConsumeAnEarlierExplicitIntent() {
        val old = context()
        val intent = PlacesLoadIntent { 0 }
        intent.begin(old)
        intent.observe(newConnection(old))
        val replaced = newConnection(old, snapshot = 14)
        assertEquals(PlacesLoadIntent.Action.CANCELLED, intent.observe(replaced))
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(replaced.copy(vehicle = vehicle)))
    }

    @Test fun explicitCancelFailureAndTimeoutNeverLeaveAuthorityToReadLater() {
        val old = context()
        val cancelled = PlacesLoadIntent { 0 }
        cancelled.begin(old); cancelled.cancel()
        assertEquals(PlacesLoadIntent.Action.NONE, cancelled.observe(newConnection(old).copy(vehicle = vehicle)))
        val failed = PlacesLoadIntent { 0 }
        failed.begin(old); failed.observe(newConnection(old))
        assertEquals(PlacesLoadIntent.Action.FAILED, failed.observe(old.copy(vehicle = vehicle.copy(connection = NativeConnection.FAILED))))
        assertEquals(PlacesLoadIntent.Action.NONE, failed.observe(newConnection(old).copy(vehicle = vehicle)))
        var now = 0L
        val timedOut = PlacesLoadIntent { now }
        timedOut.begin(old); now = 25_000
        assertEquals(PlacesLoadIntent.Action.FAILED, timedOut.observe(old))
        assertFalse(timedOut.waiting)
    }

    @Test fun previousFailureDoesNotCancelTheNewExplicitConnectionBeforeItStarts() {
        val old = context().copy(vehicle = vehicle.copy(connection = NativeConnection.FAILED))
        val intent = PlacesLoadIntent { 0 }
        assertEquals(PlacesLoadIntent.Action.RECONNECT, intent.begin(old))
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(old.copy(places = old.places!!.copy(snapshot = 11))))
        val fresh = newConnection(old)
        assertEquals(PlacesLoadIntent.Action.NONE, intent.observe(fresh))
        assertEquals(PlacesLoadIntent.Action.LOAD, intent.observe(fresh.copy(vehicle = vehicle)))
    }
}
