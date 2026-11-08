package ai.pleos.destinations.vehicle

internal enum class VoiceInputFailure { PERMISSION, MICROPHONE, UNSUPPORTED, ENGINE, EMPTY, TIMEOUT, INVALID }
internal interface VoiceInputGateway {
    interface Listener {
        fun listening()
        fun text(value: String, final: Boolean)
        fun ended()
        fun failed(reason: VoiceInputFailure)
    }
    fun start(listener: Listener)
    fun stop()
    fun close()
}
internal data class VoiceInputScope(val room: String, val member: String, val roomEpoch: Long,
    val pageGeneration: Long, val nativeEpoch: Long)
internal enum class VoiceInputPhase { IDLE, PREPARING, LISTENING, FINISHING, READY, CANCELLED, FAILED }
internal data class VoiceInputState(val phase: VoiceInputPhase = VoiceInputPhase.IDLE,
    val text: String = "", val interim: String = "", val textRevision: Long = 0,
    val failure: VoiceInputFailure? = null)

/** Foreground-only volatile input. It has no proposal, saved-place, service or navigation capability. */
internal class VoiceInputController(private val gateway: VoiceInputGateway, private val clock: () -> Long,
    private val changed: () -> Unit, private val trace: (String) -> Unit = {}) {
    var state = VoiceInputState(); private set
    var scope: VoiceInputScope? = null; private set
    private var generation = 0L
    private var deadline = 0L
    val capturing get() = state.phase in listOf(VoiceInputPhase.PREPARING, VoiceInputPhase.LISTENING, VoiceInputPhase.FINISHING)
    private fun update(value: VoiceInputState) { state = value; changed() }
    private fun retire() { generation += 1; deadline = 0; gateway.close() }
    fun observe(value: VoiceInputScope?) {
        if (scope == value) return
        scope = value; retire(); trace("scopeCleared")
        update(VoiceInputState(textRevision = state.textRevision + 1))
    }
    fun start(): Boolean {
        if (scope == null || capturing) return false
        retire(); val token = generation; deadline = clock() + 10_000
        trace("startMicrophone")
        update(VoiceInputState(VoiceInputPhase.PREPARING, textRevision = state.textRevision + 1))
        try { gateway.start(object : VoiceInputGateway.Listener {
            private fun active(): Boolean {
                if (token != generation || scope == null || !capturing) return false
                if (clock() >= deadline) { tick(); return false }
                return true
            }
            override fun listening() {
                if (!active() || state.phase != VoiceInputPhase.PREPARING) return
                deadline = clock() + 15_000; trace("listening")
                update(state.copy(phase = VoiceInputPhase.LISTENING))
            }
            override fun text(value: String, final: Boolean) {
                if (!active()) return
                if (state.phase == VoiceInputPhase.PREPARING) return
                if (!VoiceDraftAnalyzer.validText(value)) { fail(VoiceInputFailure.INVALID); return }
                if (!final) { update(state.copy(interim = value)); return }
                if (value.isBlank()) { fail(VoiceInputFailure.EMPTY); return }
                retire(); trace("finalText")
                update(VoiceInputState(VoiceInputPhase.READY, value.trim(), textRevision = state.textRevision + 1))
            }
            override fun ended() {
                if (!active()) return
                if (state.phase == VoiceInputPhase.PREPARING) return
                trace("endedAwaitingFinal"); deadline = minOf(deadline, clock() + 2_000)
                update(state.copy(phase = VoiceInputPhase.FINISHING))
            }
            override fun failed(reason: VoiceInputFailure) { if (active()) fail(reason) }
        }) } catch (_: SecurityException) { if (token == generation) fail(VoiceInputFailure.PERMISSION) }
        catch (_: Exception) { if (token == generation) fail(VoiceInputFailure.ENGINE) }
        return true
    }
    fun stop() {
        if (!capturing || state.phase == VoiceInputPhase.FINISHING) return
        if (state.phase == VoiceInputPhase.PREPARING) { cancel(); return }
        deadline = clock() + 3_000; trace("stopMicrophone")
        update(state.copy(phase = VoiceInputPhase.FINISHING))
        try { gateway.stop() } catch (_: Exception) { fail(VoiceInputFailure.ENGINE) }
    }
    fun cancel() {
        retire(); trace("cancelled")
        update(VoiceInputState(VoiceInputPhase.CANCELLED, textRevision = state.textRevision + 1))
    }
    fun edit(text: String) {
        if (scope == null || capturing || text == state.text) return
        trace("edited")
        update(VoiceInputState(if (text.isBlank()) VoiceInputPhase.IDLE else VoiceInputPhase.READY,
            text, textRevision = state.textRevision + 1))
    }
    fun tick() {
        if (!capturing || clock() < deadline) return
        if (state.phase == VoiceInputPhase.LISTENING) stop() else fail(VoiceInputFailure.TIMEOUT)
    }
    private fun fail(reason: VoiceInputFailure) {
        retire(); trace("failure${reason.name}")
        update(VoiceInputState(VoiceInputPhase.FAILED, textRevision = state.textRevision + 1, failure = reason))
    }
}
