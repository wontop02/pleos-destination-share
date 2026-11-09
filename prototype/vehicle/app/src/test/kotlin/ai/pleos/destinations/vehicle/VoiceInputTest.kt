package ai.pleos.destinations.vehicle

import org.junit.Assert.*
import org.junit.Test

class VoiceInputTest {
    private class Gateway : VoiceInputGateway {
        val listeners = mutableListOf<VoiceInputGateway.Listener>()
        var starts = 0; var stops = 0; var closes = 0; var denied = false
        override fun start(listener: VoiceInputGateway.Listener) { starts++; if (denied) throw SecurityException(); listeners += listener }
        override fun stop() { stops++ }
        override fun close() { closes++ }
    }
    private class Fixture {
        var now = 0L; val gateway = Gateway(); val trace = mutableListOf<String>()
        val scope = VoiceInputScope("room", "member", 3, 7, 11)
        val controller = VoiceInputController(gateway, { now }, {}, trace::add)
        init { controller.observe(scope) }
        fun begin(): VoiceInputGateway.Listener { assertTrue(controller.start()); return gateway.listeners.last() }
    }
    @Test fun observingOrEditingCannotStartMicrophoneAndDoubleTapStartsOnce() {
        val f = Fixture()
        f.controller.edit("카페 들르는 거 어때?"); f.controller.observe(f.scope); f.controller.tick()
        assertEquals(0, f.gateway.starts)
        val listener = f.begin(); assertFalse(f.controller.start()); assertEquals(1, f.gateway.starts)
        listener.listening(); assertEquals(VoiceInputPhase.LISTENING, f.controller.state.phase)
    }
    @Test fun onlyFinalTextCompletesAndClosesTheMicrophone() {
        val f = Fixture(); val listener = f.begin(); listener.listening()
        listener.text("카페", false)
        assertEquals("", f.controller.state.text); assertEquals("카페", f.controller.state.interim)
        listener.ended(); assertEquals(VoiceInputPhase.FINISHING, f.controller.state.phase)
        listener.text("카페 들르는 거 어때?", true)
        assertEquals(VoiceInputPhase.READY, f.controller.state.phase)
        assertEquals("카페 들르는 거 어때?", f.controller.state.text); assertFalse(f.controller.capturing)
        val done = f.controller.state; listener.text("다른 곳 가자", true); listener.failed(VoiceInputFailure.ENGINE)
        assertEquals(done, f.controller.state); assertTrue(f.gateway.closes >= 3)
        assertTrue(f.trace.none { it.contains("카페") })
    }
    @Test fun actualReadinessStartsTheCaptureWindowAndDuplicateReadyCannotExtendIt() {
        val f = Fixture(); val listener = f.begin()
        listener.text("준비 전 결과 가자", true); listener.ended()
        assertEquals(VoiceInputPhase.PREPARING, f.controller.state.phase); assertEquals("", f.controller.state.text)
        f.now = 9_000; listener.listening()
        assertEquals(VoiceInputPhase.LISTENING, f.controller.state.phase)
        f.now = 23_999; listener.listening(); assertEquals(VoiceInputPhase.LISTENING, f.controller.state.phase)
        f.now = 24_000; f.controller.tick()
        assertEquals(VoiceInputPhase.FINISHING, f.controller.state.phase); assertEquals(1, f.gateway.stops)
    }
    @Test fun cancelledAndRetriedRunsRejectOldCallbacks() {
        val f = Fixture(); val old = f.begin(); old.listening(); f.controller.cancel()
        old.text("카페 가자", true); assertEquals("", f.controller.state.text)
        val current = f.begin(); old.listening(); old.text("오래된 곳 가자", true)
        assertEquals(VoiceInputPhase.PREPARING, f.controller.state.phase)
        current.listening(); current.text("새 장소 가자", true)
        assertEquals("새 장소 가자", f.controller.state.text)
    }
    @Test fun roomMemberEpochPageAndNativeChangesDisposeAndClearAllVoiceContent() {
        val f = Fixture()
        val scopes = listOf(f.scope.copy(room = "other"), f.scope.copy(member = "other"),
            f.scope.copy(roomEpoch = 4), f.scope.copy(pageGeneration = 8), f.scope.copy(nativeEpoch = 12), null)
        scopes.forEach { next ->
            f.controller.observe(f.scope); val old = f.begin(); old.listening()
            f.controller.observe(next); old.text("카페 가자", true)
            assertEquals("", f.controller.state.text); assertEquals(VoiceInputPhase.IDLE, f.controller.state.phase)
        }
    }
    @Test fun stopWaitsBrieflyForFinalButNeverPromotesAnInterimResult() {
        val f = Fixture(); val listener = f.begin(); listener.listening(); listener.text("카페", false)
        f.controller.stop(); f.controller.stop(); assertEquals(1, f.gateway.stops)
        assertEquals(VoiceInputPhase.FINISHING, f.controller.state.phase)
        f.now = 15_000; f.controller.tick()
        assertEquals(VoiceInputFailure.TIMEOUT, f.controller.state.failure); assertEquals("", f.controller.state.text)
        listener.text("카페 가자", true); assertEquals("", f.controller.state.text)
    }
    @Test fun preparationAndFinalCallbacksCheckDeadlinesWithoutWaitingForTick() {
        val preparing = Fixture(); val early = preparing.begin(); preparing.now = 10_000; early.listening()
        assertEquals(VoiceInputFailure.TIMEOUT, preparing.controller.state.failure)
        val finishing = Fixture(); val listener = finishing.begin(); listener.listening(); listener.ended()
        finishing.now = 15_000; listener.text("카페 가자", true)
        assertEquals(VoiceInputFailure.TIMEOUT, finishing.controller.state.failure)
        val capture = Fixture(); val late = capture.begin(); late.listening(); capture.now = 15_000
        late.text("늦은 말 가자", true)
        assertEquals(VoiceInputPhase.FINISHING, capture.controller.state.phase); assertEquals("", capture.controller.state.text)
        assertEquals(1, capture.gateway.stops)
    }
    @Test fun unsupportedPermissionFailureEmptyAndInvalidResultsRemainEditable() {
        VoiceInputFailure.values().forEach { reason ->
            val f = Fixture(); val listener = f.begin(); listener.failed(reason)
            assertEquals(reason, f.controller.state.failure); assertFalse(f.controller.capturing)
            f.controller.edit("주유하고 가자"); assertEquals(VoiceInputPhase.READY, f.controller.state.phase)
            assertEquals(1, f.gateway.starts)
        }
        val denied = Fixture(); denied.gateway.denied = true; assertTrue(denied.controller.start())
        assertEquals(VoiceInputFailure.PERMISSION, denied.controller.state.failure)
        listOf("", "a".repeat(401), "카페\u0000가자").forEach { value ->
            val f = Fixture(); val listener = f.begin(); listener.listening(); listener.text(value, true)
            assertEquals(VoiceInputPhase.FAILED, f.controller.state.phase); assertEquals("", f.controller.state.text)
        }
    }
    @Test fun coldEngineFinalAndEndedKeepTheOriginalFifteenSecondResultWindow() {
        val f = Fixture(); val listener = f.begin(); listener.listening(); f.controller.stop()
        f.now = 9_500; listener.ended(); listener.text("카페 가자", true)
        assertEquals(VoiceInputPhase.READY, f.controller.state.phase)
        val timeout = Fixture(); val other = timeout.begin(); other.listening(); timeout.controller.stop()
        timeout.now = 14_999; other.ended(); timeout.now = 15_000; timeout.controller.tick()
        assertEquals(VoiceInputFailure.TIMEOUT, timeout.controller.state.failure)
    }
}
