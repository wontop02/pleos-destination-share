package ai.pleos.destinations.vehicle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class VoicePcmCaptureTest {
    private class Source(val mode: String) : VoicePcmSource {
        val idle = CountDownLatch(1); val closed = CountDownLatch(1)
        private val closing = AtomicBoolean()
        var closeCount = 0; var reads = 0
        override fun start() = mode != "not-recording"
        override fun read(samples: ShortArray): Int {
            reads++
            if (mode == "error") return -3
            if (mode == "maximum") { samples.fill(0x1234); return samples.size }
            if (reads == 1) { samples[0] = 0x1234; samples[1] = -2; samples[2] = 0; return 3 }
            idle.countDown(); return 0
        }
        override fun close() { if (closing.compareAndSet(false, true)) { closeCount++; closed.countDown() } }
    }
    @Test fun partialReadsAreLittleEndianAndFinishClosesBeforeDeliveringOnce() {
        val source = Source("partial"); val done = CountDownLatch(1); var bytes: ByteArray? = null
        val capture = VoicePcmCapture({ source }, { 0 }, { it() }, {}, { bytes = it; assertEquals(1, source.closeCount); done.countDown() }, { fail(it.name) })
        capture.start(); assertTrue(source.idle.await(2, TimeUnit.SECONDS)); capture.finish(); capture.finish()
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1, 0, 0), bytes)
        assertEquals(1, source.closeCount); bytes!!.fill(0)
    }
    @Test fun sampleLimitIsExactlyFifteenSecondsAndNeverOverflows() {
        val source = Source("maximum"); val done = CountDownLatch(1); var size = 0
        VoicePcmCapture({ source }, { 0 }, { it() }, {}, { size = it.size; it.fill(0); done.countDown() }, { fail(it.name) }).start()
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(480_000, size); assertEquals(1, source.closeCount)
    }
    @Test fun cancelAfterQueuedCompletionDropsAudioAndNeverSubmitsIt() {
        val source = Source("maximum"); val queued = mutableListOf<() -> Unit>(); val both = CountDownLatch(2)
        var delivered = 0
        val capture = VoicePcmCapture({ source }, { 0 }, { action -> synchronized(queued) { queued += action }; both.countDown() }, {}, { delivered++ }, { delivered++ })
        capture.start(); assertTrue(both.await(2, TimeUnit.SECONDS)); capture.cancel()
        synchronized(queued) { queued.toList() }.forEach { it() }
        assertEquals(0, delivered); assertEquals(1, source.closeCount)
    }
    @Test fun cancellingActiveCaptureStopsReadAndDropsAllData() {
        val source = Source("partial"); val done = CountDownLatch(1); var delivered = 0
        val capture = VoicePcmCapture({ source }, { 0 }, { it(); done.countDown() }, {}, { delivered++ }, { delivered++ })
        capture.start(); assertTrue(source.idle.await(2, TimeUnit.SECONDS)); capture.cancel()
        assertTrue(source.closed.await(2, TimeUnit.SECONDS)); assertEquals(0, delivered); assertEquals(1, source.closeCount)
    }
    @Test fun permissionAndRecordingOrReadFailureNeverBecomeReadyOrDeliverAudio() {
        listOf("permission", "not-recording", "error").forEach { mode ->
            val source = Source(mode); val done = CountDownLatch(1); var failure: VoiceInputFailure? = null; var delivered = 0
            VoicePcmCapture({ if (mode == "permission") throw SecurityException() else source }, { 0 }, { it() }, {},
                { delivered++ }, { failure = it; done.countDown() }).start()
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(0, delivered)
            assertEquals(if (mode == "permission") VoiceInputFailure.PERMISSION else VoiceInputFailure.MICROPHONE, failure)
            if (mode != "permission") assertEquals(1, source.closeCount)
        }
    }
    @Test fun permissionResultIsOneShotAndCannotRestoreAChangedForegroundScope() {
        val scope = VoiceInputScope("room", "member", 2, 3, 4)
        val changed = listOf(scope.copy(room = "other"), scope.copy(member = "other"), scope.copy(roomEpoch = 9),
            scope.copy(pageGeneration = 9), scope.copy(nativeEpoch = 9), null)
        changed.forEach { current -> val gate = VoicePermissionIntent(); gate.begin(scope); assertFalse(gate.consume(current, true)); assertFalse(gate.waiting) }
        val gate = VoicePermissionIntent(); gate.begin(scope); assertFalse(gate.consume(scope, false))
        gate.begin(scope); gate.cancel(); assertFalse(gate.consume(scope, true))
        gate.begin(scope); assertTrue(gate.consume(scope, true)); assertFalse(gate.consume(scope, true))
    }
}
