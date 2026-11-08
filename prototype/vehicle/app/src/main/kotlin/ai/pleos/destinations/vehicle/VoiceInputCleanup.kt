package ai.pleos.destinations.vehicle

/** Keep asynchronous stop work alive after UI authority has already been revoked. */
internal class VoiceInputCleanup(private val clock: () -> Long, private val trace: (String) -> Unit = {}) {
    private data class Pending(val deadline: Long, val release: () -> Unit)
    private val pending = mutableMapOf<Long, Pending>()
    val waiting: Boolean get() = pending.isNotEmpty()
    fun contains(id: Long) = id in pending
    fun retire(id: Long, stop: () -> Unit, release: () -> Unit) {
        if (id in pending) return
        pending[id] = Pending(clock() + 3_000, release)
        trace("sdkStopPending")
        runCatching(stop)
    }
    fun ended(id: Long) = finish(id, "sdkStoppedBeforeRelease")
    fun tick() {
        pending.filterValues { clock() >= it.deadline }.keys.toList().forEach {
            finish(it, "sdkStopWaitElapsed")
        }
    }
    private fun finish(id: Long, event: String) {
        val resource = pending.remove(id) ?: return
        trace(event); resource.release()
    }
}
