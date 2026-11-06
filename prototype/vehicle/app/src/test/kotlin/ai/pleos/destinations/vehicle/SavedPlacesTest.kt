package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.DestinationKind
import ai.pleos.destinations.core.PlaceSource
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.BookmarkInfo
import org.junit.Assert.*
import org.junit.Test

class SavedPlacesTest {
    private fun bookmark() = BookmarkInfo(name = "가든서비스", latitude = 37.41255277, longitude = 127.09591666,
        poiId = "36khp2h36p68cm5nqhec", poiSubId = null, address = "경기도 성남시 수정구 창업로40번길 6",
        category = BookmarkType.values().first(), poiProvider = "", displayLatitude = null, displayLongitude = null)
    private val place get() = savedPlaces(listOf(bookmark()))!!.single()
    private class Gateway : SavedPlacesGateway {
        lateinit var listener: SavedPlacesGateway.Listener
        var requests = 0
        var fail = false
        override fun savedPlacesListener(listener: SavedPlacesGateway.Listener) { this.listener = listener }
        override fun readSavedPlaces() { requests++; if (fail) error("read failure") }
    }
    private inner class Fixture {
        var now = 1_000L
        val gateway = Gateway()
        var changes = 0
        val controller = SavedPlacesController(gateway, { now }, { changes++ })
        val scope = PlaceScope("room-a", "member-a", 1)
        init { controller.scope(scope); gateway.listener.nativeSession(1, true) }
        fun receive(values: List<Destination>? = listOf(place), epoch: Long = 1) = gateway.listener.bookmarks(epoch, values)
    }

    @Test fun publicBookmarkNormalizationPreservesEveryOriginalFieldAndOnlySupportedPlaceMeaning() {
        val original = bookmark(); val normalized = savedPlaces(listOf(original))!!.single()
        assertEquals(DestinationKind.PLEOS_PLACE, normalized.kind); assertEquals(PlaceSource.PLEOS_BOOKMARK, normalized.source)
        assertEquals(original.name, normalized.name); assertEquals(original.latitude, normalized.latitude, 0.0)
        assertEquals(original.longitude, normalized.longitude, 0.0); assertEquals(original.address, normalized.pleos!!.address)
        assertEquals(original.poiId, normalized.pleos!!.poiId); assertNull(normalized.pleos!!.poiSubId)
        assertEquals("", normalized.pleos!!.poiProvider); assertNull(normalized.pleos!!.displayLatitude)
        val invalid = listOf(original.copy(name = " ${original.name}"), original.copy(poiId = ""), original.copy(poiSubId = ""),
            original.copy(poiProvider = "unsupported"), original.copy(latitude = Double.NaN), original.copy(longitude = 181.0),
            original.copy(displayLatitude = 37.0), original.copy(address = ""))
        invalid.forEach {
            assertNull(savedPlaces(listOf(it)))
            assertEquals(listOf(normalized), savedPlaces(listOf(it, original)))
        }
        assertNull(savedPlaces(List(201) { original }))
    }

    @Test fun loadIsReadOnlyBoundedAndDuplicateTapCannotIssueSecondRead() {
        val f = Fixture(); assertTrue(f.controller.load()); assertFalse(f.controller.load())
        assertEquals(1, f.gateway.requests); assertEquals(SavedPlacesPhase.LOADING, f.controller.state.phase)
        assertNull(f.controller.state.selected)
        f.receive(); val snapshot = f.controller.state
        assertTrue(f.controller.select(snapshot.snapshot, snapshot.choices.single()))
        assertEquals(place, f.controller.state.selected)
        val changes = f.changes
        assertTrue(f.controller.load()); assertEquals(1, f.gateway.requests)
        assertEquals(changes + 1, f.changes) // UI observes reopening the cached snapshot.
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.choices as MutableList<Destination>).clear() }
    }

    @Test fun snapshotCannotSelectAnEntryAfterCancelReconnectOrRoomMemberReplacement() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.controller.cancel() }, { it.gateway.listener.nativeSession(2, false); it.gateway.listener.nativeSession(2, true) },
            { it.controller.scope(it.scope.copy(member = "member-b")) },
            { it.controller.scope(it.scope.copy(room = "room-b")) },
            { it.controller.scope(it.scope.copy(roomEpoch = 2)) }, { it.controller.scope(null) },
        )
        changes.forEach { change ->
            val f = Fixture(); f.controller.load(); f.receive(); val old = f.controller.state
            change(f); assertFalse(f.controller.select(old.snapshot, old.choices.single()))
            assertNull(f.controller.state.selected); assertTrue(f.controller.state.choices.isEmpty())
        }
    }

    @Test fun timeoutAndCancellationPoisonTheReadEpochAndLateCallbacksCannotSatisfyNewRequests() {
        for (cancel in listOf(false, true)) {
            val f = Fixture(); f.controller.load()
            if (cancel) f.controller.cancel() else { f.now += 8_000; f.controller.tick() }
            assertFalse(f.controller.load()); f.receive()
            assertEquals(SavedPlacesPhase.RECONNECT_REQUIRED, f.controller.state.phase)
            assertNull(f.controller.state.selected); assertTrue(f.controller.state.choices.isEmpty())
            f.gateway.listener.nativeSession(2, false); f.gateway.listener.nativeSession(2, true)
            assertTrue(f.controller.load()); f.receive(epoch = 1)
            assertEquals(SavedPlacesPhase.LOADING, f.controller.state.phase)
            f.receive(epoch = 2); assertEquals(SavedPlacesPhase.READY, f.controller.state.phase)
            assertEquals(2, f.gateway.requests)
        }
    }

    @Test fun staleDialogCancelCannotCancelANewerReadOrSelection() {
        val f = Fixture(); f.controller.load(); f.receive(); val old = f.controller.state
        f.gateway.listener.nativeSession(2, false); f.gateway.listener.nativeSession(2, true)
        f.controller.scope(f.scope.copy(member = "new-member", roomEpoch = 2))
        assertTrue(f.controller.load()); assertFalse(f.controller.cancel(old.snapshot))
        assertEquals(SavedPlacesPhase.LOADING, f.controller.state.phase)
        f.receive(epoch = 2); val current = f.controller.state
        assertTrue(f.controller.select(current.snapshot, current.choices.single()))
        assertFalse(f.controller.cancel(old.snapshot)); assertEquals(place, f.controller.state.selected)
    }

    @Test fun deadlineIsCheckedAtCallbackEvenWithoutATick() {
        val f = Fixture(); f.controller.load(); f.now += 8_000; f.receive()
        assertEquals(SavedPlacesPhase.RECONNECT_REQUIRED, f.controller.state.phase)
        assertTrue(f.controller.state.choices.isEmpty())
    }

    @Test fun emptyInvalidAndFailureStatesNeverSelectOrRetryInTheAmbiguousEpoch() {
        val empty = Fixture(); empty.controller.load(); empty.receive(emptyList())
        assertEquals(SavedPlacesPhase.EMPTY, empty.controller.state.phase); assertFalse(empty.controller.canLoad)
        val invalid = Fixture(); invalid.controller.load(); invalid.receive(null)
        assertEquals(SavedPlacesPhase.INVALID, invalid.controller.state.phase); assertFalse(invalid.controller.canLoad)
        val wrongKind = Fixture(); wrongKind.controller.load(); wrongKind.receive(listOf(PrototypePlaces.all.first()))
        assertEquals(SavedPlacesPhase.INVALID, wrongKind.controller.state.phase)
        val failed = Fixture(); failed.gateway.fail = true; failed.controller.load()
        assertEquals(SavedPlacesPhase.FAILED, failed.controller.state.phase); failed.receive()
        assertNull(failed.controller.state.selected); assertFalse(failed.controller.canLoad)
    }

    @Test fun roomChangeDuringReadFencesLateResultAndRequiresExplicitNativeReconnect() {
        val f = Fixture(); f.controller.load(); f.controller.scope(f.scope.copy(member = "other-member")); f.receive()
        assertEquals(SavedPlacesPhase.RECONNECT_REQUIRED, f.controller.state.phase)
        assertFalse(f.controller.load()); assertTrue(f.controller.state.choices.isEmpty())
    }
}
