package ai.pleos.destinations.vehicle

/** One foreground capture, with no storage, network or SDK capability. */
internal interface VoicePcmSource {
    fun start(): Boolean
    fun read(samples: ShortArray): Int
    fun close()
}

internal class VoicePcmCapture(private val sourceFactory: () -> VoicePcmSource,
    private val clock: () -> Long, private val dispatch: (() -> Unit) -> Unit,
    private val listening: () -> Unit, private val completed: (ByteArray) -> Unit,
    private val failed: (VoiceInputFailure) -> Unit) {
    companion object { const val MAX_BYTES = 480_000 }
    private val lock = Any()
    private val memory = ByteArray(MAX_BYTES)
    private var length = 0
    private var source: VoicePcmSource? = null
    private var delivery: ByteArray? = null
    private var worker: Thread? = null
    private var finishing = false
    private var cancelled = false
    private var started = false
    fun start() = synchronized(lock) {
        check(!started); started = true
        worker = Thread(::record, "vehicle-voice-capture").apply { isDaemon = true; start() }
    }
    fun finish() { stop(false) }
    fun cancel() { stop(true) }
    private fun stop(cancel: Boolean) {
        val owner = synchronized(lock) {
            if (cancel) { cancelled = true; memory.fill(0); delivery?.fill(0); delivery = null }
            finishing = true; source
        }
        // Nonblocking reads and this close/interrupt prevent a capture thread waiting on input.
        owner?.close(); worker?.interrupt()
    }
    private fun record() {
        val samples = ShortArray(1_600)
        var failure: VoiceInputFailure? = null
        var owner: VoicePcmSource? = null
        try {
            if (synchronized(lock) { finishing }) return
            val recordingSource = sourceFactory(); owner = recordingSource
            val recording = synchronized(lock) {
                if (finishing) false else { source = recordingSource; recordingSource.start() }
            }
            if (!recording) {
                if (!synchronized(lock) { cancelled }) failure = VoiceInputFailure.MICROPHONE
                return
            }
            dispatch { if (!synchronized(lock) { finishing }) listening() }
            val deadline = clock() + 15_000
            while (!synchronized(lock) { finishing }) {
                if (clock() >= deadline) break
                val count = recordingSource.read(samples)
                if (synchronized(lock) { finishing }) break
                if (count < 0 || count > samples.size) { failure = VoiceInputFailure.MICROPHONE; break }
                if (count == 0) { try { Thread.sleep(10) } catch (_: InterruptedException) { }; continue }
                synchronized(lock) {
                    if (!finishing) {
                        val take = minOf(count, (MAX_BYTES - length) / 2)
                        for (index in 0 until take) {
                            val sample = samples[index].toInt()
                            memory[length++] = sample.toByte(); memory[length++] = (sample shr 8).toByte()
                        }
                        if (length == MAX_BYTES) finishing = true
                    }
                }
                samples.fill(0)
            }
        } catch (_: SecurityException) { failure = VoiceInputFailure.PERMISSION }
        catch (_: Exception) { if (!synchronized(lock) { finishing }) failure = VoiceInputFailure.MICROPHONE }
        finally {
            if (runCatching { owner?.close() }.isFailure && !synchronized(lock) { cancelled }) failure = VoiceInputFailure.MICROPHONE
            samples.fill(0)
            val output = synchronized(lock) {
                source = null
                val bytes = if (!cancelled && failure == null && length > 0) memory.copyOf(length) else null
                memory.fill(0); finishing = true; delivery = bytes; bytes
            }
            dispatch {
                if (synchronized(lock) { cancelled }) output?.fill(0)
                else if (failure != null) failed(failure!!)
                else if (output == null) failed(VoiceInputFailure.EMPTY)
                else { synchronized(lock) { delivery = null }; completed(output) }
            }
        }
    }
}
