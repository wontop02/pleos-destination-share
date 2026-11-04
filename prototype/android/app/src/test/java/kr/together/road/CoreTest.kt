package kr.together.road

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoreTest {
    private val self = "a".repeat(24); private val other = "b".repeat(24)
    private fun voice(id: Int, seq: Long = id.toLong(), at: Long = 1000, sender: String = other) = Voice(id.toString(16).padStart(24, '0'), sender, "가상 참여자", seq, at, at + 30000, 16000, 32044)
    @Test fun wavBoundariesRoundTripSignedPcm() {
        val samples = ShortArray(16000); samples[0] = Short.MIN_VALUE; samples[1] = Short.MAX_VALUE; samples[2] = -101
        val encoded = Wav.encode(samples); val pcm = Wav.decode(encoded); val reader = ByteBuffer.wrap(pcm.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32044, encoded.size); assertEquals(16000, pcm.sampleCount)
        assertEquals(Short.MIN_VALUE, reader.short); assertEquals(Short.MAX_VALUE, reader.short); assertEquals((-101).toShort(), reader.short)
        assertEquals(240000, Wav.decode(Wav.encode(ShortArray(240000))).sampleCount)
        assertThrows(IllegalArgumentException::class.java) { Wav.encode(ShortArray(15999)) }
        assertThrows(IllegalArgumentException::class.java) { Wav.encode(ShortArray(240001)) }
    }
    @Test fun malformedContainerFormatAndCountsFail() {
        val valid = Wav.encode(ShortArray(16000))
        val mutations: List<(ByteBuffer) -> Unit> = listOf(
            { it.putInt(4, 100) }, { it.putShort(20, 3) }, { it.putShort(22, 2) }, { it.putInt(24, 48000) },
            { it.putInt(28, 16000) }, { it.putShort(32, 4) }, { it.putShort(34, 8) }, { it.putInt(40, Int.MAX_VALUE) }, { it.putInt(40, 31999) }, { it.put(0, 0xD2.toByte()) },
        )
        mutations.forEach { edit -> val bytes = valid.copyOf(); edit(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)); assertThrows(IllegalArgumentException::class.java) { Wav.decode(bytes) } }
        assertThrows(IllegalArgumentException::class.java) { Wav.decode(valid.copyOf(43)) }
        assertThrows(IllegalArgumentException::class.java) { Wav.decode(ByteArray(Wav.MAX_BYTES + 1)) }
    }
    @Test fun wavSupportsBoundedUnknownChunksButNotDuplicates() {
        val bytes = Wav.encode(ShortArray(16000)); val junk = ByteBuffer.allocate(bytes.size + 12).order(ByteOrder.LITTLE_ENDIAN)
        junk.put(bytes, 0, 12); junk.put("JUNK".toByteArray()); junk.putInt(3); junk.put(byteArrayOf(1, 2, 3, 0)); junk.put(bytes, 12, bytes.size - 12); junk.putInt(4, junk.capacity() - 8)
        assertEquals(16000, Wav.decode(junk.array()).sampleCount)
        val duplicate = ByteBuffer.allocate(bytes.size + 10).order(ByteOrder.LITTLE_ENDIAN); duplicate.put(bytes); duplicate.put("data".toByteArray()); duplicate.putInt(2); duplicate.putShort(0); duplicate.putInt(4, duplicate.capacity() - 8)
        assertThrows(IllegalArgumentException::class.java) { Wav.decode(duplicate.array()) }
    }
    @Test fun autoplayRequiresNewExplicitSessionAndExcludesSelfAndPreviouslySeenHistory() {
        var now = 2000L; val queue = VoiceQueue(self) { now }
        queue.offer(listOf(voice(1))); queue.enable(); queue.offer(listOf(voice(1), voice(2), voice(3, at = 2001, sender = self)))
        assertNull(queue.take()); now = 2010; queue.offer(listOf(voice(4, at = 2005), voice(5, at = 2005, sender = self))); assertEquals(4, queue.take()!!.id.toInt(16)); assertNull(queue.take())
    }
    @Test fun autoplayOrdersPendingAndDoesNotReplaySnapshotDuplicates() {
        var now = 1000L; val queue = VoiceQueue(self) { now }; queue.enable(); now = 1100
        val second = voice(2, at = 1050); val first = voice(1, at = 1050)
        queue.offer(listOf(second, first, first)); assertEquals(first, queue.take()); assertEquals(second, queue.take()); assertNull(queue.take())
        queue.offer(listOf(first, second)); assertNull(queue.take())
    }
    @Test fun freshnessIsCheckedAgainBeforePlayAndOffDiscardsPending() {
        var now = 1000L; val queue = VoiceQueue(self) { now }; queue.enable(); queue.offer(listOf(voice(1), voice(2))); now = 31000; assertNull(queue.take())
        queue.disable(); now = 32000; queue.enable(); queue.offer(listOf(voice(3, at = now))); queue.disable(); queue.enable(); queue.offer(listOf(voice(3, at = now))); assertNull(queue.take())
        assertFalse(queue.fresh(voice(4).copy(autoPlayUntil = 900000)))
    }
    @Test fun clockUsesConservativeRttAndMonotonicElapsed() {
        var tick = 0L; val clock = ServerClock { tick }; clock.update(1000, 200); assertEquals(1200, clock.now()); tick = 100; assertEquals(1300, clock.now())
        clock.update(1100); assertEquals(1300, clock.now()); tick = 300; assertEquals(1500, clock.now()); clock.update(1800); assertEquals(1800, clock.now())
    }
    @Test fun retryKeepsUuidAndExactCopiedBytesUntilThirtySecondDeadline() {
        val bytes = byteArrayOf(1, 2, 3); val clip = RetryClip(bytes, 1000); val id = clip.clientMessageId; bytes.fill(9)
        assertArrayEquals(byteArrayOf(1, 2, 3), clip.bytes); assertTrue(clip.fresh(30999)); assertFalse(clip.fresh(31000)); assertFalse(clip.fresh(999)); assertEquals(id, clip.clientMessageId)
        clip.discard(); assertArrayEquals(ByteArray(3), clip.bytes)
    }
    @Test fun replacedEpochRejectsOldCompletionAndCancellationScopeNeverReopens() {
        val epoch = Epoch(); val old = epoch.current(); epoch.advance(); assertFalse(epoch.valid(old)); assertTrue(epoch.valid(epoch.current()))
        val scope = RequestScope(); scope.cancel(); assertThrows(java.util.concurrent.CancellationException::class.java) { scope.check() }
    }
    @Test fun debugAliasIsNarrowAndReleaseRequiresHttps() {
        val alias = Endpoint("http://10.0.2.2:4180", true); assertEquals("127.0.0.1:4180", alias.emulatorHost)
        val secret = "x".repeat(43); val link = "http://127.0.0.1:4180/#join=$self.$secret"
        assertEquals(self to secret, alias.invitation(link))
        listOf(link.replace("127.0.0.1", "attacker.invalid"), link.replace(":4180", ":4181"), link.replace("http:", "https:"), link.replace("/#", "/?token=x#")).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { alias.invitation(invalid) } }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("http://10.0.2.2:4180", false) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("http://192.168.1.1:4180", true) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("https://token@example.com", false) }
        assertNull(Endpoint("https://10.0.2.2:4180", false).emulatorHost)
    }
    @Test fun sessionStringNeverContainsBearerAndSnapshotParsingChecksFormat() {
        val s = Session("http://127.0.0.1:4180", self, other, "host", "t".repeat(43), "s".repeat(43))
        assertFalse(s.toString().contains(s.token)); assertEquals(s.token, Session.parse(s.json()).token)
        val meta = JSONObject().put("id", self).put("sender", JSONObject().put("id", other).put("name", "가상"))
            .put("sequence", 1).put("acceptedAt", 1000).put("autoPlayUntil", 31000).put("sampleCount", 16000).put("byteLength", 32044).put("sampleRate", 16000).put("channels", 1).put("format", "pcm_s16le")
        assertEquals(1000.0, Voice.parse(meta).durationMs, 0.0); meta.put("sampleRate", 48000); assertThrows(IllegalArgumentException::class.java) { Voice.parse(meta) }
    }
    @Test fun sseHandlesSplitCrLfKoreanMultilineAndCompleteFrameOnly() {
        val results = arrayListOf<ServerEvent>(); val decoder = SseDecoder(results::add)
        ": ping\r\nevent: voice\r\ndata: {\r\ndata: \"name\":\"가상 동행\"}\r\n\r\n".forEach { decoder.feed(charArrayOf(it), 1) }
        assertEquals(1, results.size); assertEquals("voice", results[0].type); assertEquals("가상 동행", results[0].data.getString("name"))
        "data: {\"incomplete\":true}".let { decoder.feed(it.toCharArray(), it.length) }; assertEquals(1, results.size)
    }
    @Test fun sseRejectsUnboundedLines() {
        val input = "data: " + "x".repeat(262145)
        assertThrows(IllegalArgumentException::class.java) { SseDecoder {}.feed(input.toCharArray(), input.length) }
    }
}
