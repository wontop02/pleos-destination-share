package ai.pleos.destinations.vehicle

import org.junit.Assert.*
import org.junit.Test

class VoiceInputCleanupTest {
    @Test fun cancellationStopsImmediatelyButRetainsResourcesUntilEndConfirmation() {
        var now = 0L; val calls = mutableListOf<String>()
        val cleanup = VoiceInputCleanup({ now })
        cleanup.retire(1, { calls += "stop" }, { calls += "release" })
        assertEquals(listOf("stop"), calls); assertTrue(cleanup.waiting)
        now = 500; cleanup.tick(); assertEquals(listOf("stop"), calls)
        cleanup.ended(1); assertEquals(listOf("stop", "release"), calls); assertFalse(cleanup.waiting)
        cleanup.ended(1); now = 3_000; cleanup.tick(); assertEquals(2, calls.size)
    }
    @Test fun absentEndCallbackUsesOneBoundedWaitAndDuplicateCloseDoesNotExtendIt() {
        var now = 0L; var stops = 0; var releases = 0
        val cleanup = VoiceInputCleanup({ now })
        cleanup.retire(7, { stops++ }, { releases++ })
        now = 2_999; cleanup.retire(7, { stops++ }, { releases++ }); cleanup.tick()
        assertEquals(1, stops); assertEquals(0, releases)
        now = 3_000; cleanup.tick(); assertEquals(1, releases); assertFalse(cleanup.waiting)
    }
    @Test fun oldEndSignalsCannotReleaseOtherResourcesAndStopFailureStillRetainsCleanup() {
        var now = 0L; var first = 0; var second = 0
        val cleanup = VoiceInputCleanup({ now })
        cleanup.retire(1, { throw IllegalStateException() }, { first++ })
        cleanup.retire(2, {}, { second++ }); cleanup.ended(100)
        assertEquals(0, first); assertEquals(0, second)
        cleanup.ended(1); assertEquals(1, first); assertEquals(0, second); assertTrue(cleanup.waiting)
        now = 3_000; cleanup.tick(); assertEquals(1, second)
    }
}
