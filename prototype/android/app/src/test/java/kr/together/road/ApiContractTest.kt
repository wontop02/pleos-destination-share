package kr.together.road

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real native-client HTTP requests to the unmodified Node service. PCM is fictional generated data. */
class ApiContractTest {
    private lateinit var node: Process
    private lateinit var client: ApiClient
    private val scopes = arrayListOf<RequestScope>()
    private fun scope() = RequestScope().also(scopes::add)
    @Before fun start() {
        val server = File(System.getProperty("pleos.repo"), "prototype/server.mjs").toURI().toASCIIString()
        val code = "import {createPrototypeServer} from ${JSONObject.quote(server)};const app=createPrototypeServer();app.server.listen(0,'127.0.0.1',()=>console.log(app.server.address().port));"
        node = ProcessBuilder("node", "--input-type=module", "-e", code).redirectErrorStream(true).start()
        val port = node.inputStream.bufferedReader().readLine().toInt()
        client = ApiClient(Endpoint("http://127.0.0.1:$port", true))
    }
    @After fun stop() { scopes.forEach(RequestScope::cancel); if (::node.isInitialized) { node.destroy(); if (!node.waitFor(2, TimeUnit.SECONDS)) node.destroyForcibly() } }
    private fun session(reply: Reply) = reply.body.let { Session(client.endpoint.origin, it.getString("roomId"), it.getString("memberId"), it.getString("role"), it.getString("token"), it.optString("inviteSecret").takeIf(String::isNotEmpty)) }
    private fun host() = session(client.create("가상 대표", scope()))
    private fun member(host: Session) = session(client.join(host.roomId, host.inviteSecret!!, "가상 참여자", scope()))
    private fun pcm(frequency: Int = 200) = Wav.encode(ShortArray(16000) { (kotlin.math.sin(it * frequency * 2 * Math.PI / 16000) * 1000).toInt().toShort() })
    @Test fun createJoinDestinationAndIsolationUseDistinctBearerCapabilities() {
        val host = host(); val member = member(host); val other = host()
        assertNotEquals(host.token, member.token); assertNotEquals(host.token, host.inviteSecret)
        val dest = JSONObject().put("name", "가상 공원 & 쉼터").put("latitude", 37.52).put("longitude", 127.12)
        val update = client.request("POST", "/api/rooms/${host.roomId}/destination", host.token, scope(), dest)
        assertEquals(1, update.body.getLong("revision"))
        assertEquals("가상 공원 & 쉼터", client.snapshot(member, scope()).body.getJSONObject("destination").getString("name"))
        assertEquals(403, assertThrows(ApiFailure::class.java) { client.request("POST", "/api/rooms/${host.roomId}/destination", member.token, scope(), dest) }.status)
        assertEquals(403, assertThrows(ApiFailure::class.java) { client.request("GET", "/api/rooms/${host.roomId}", other.token, scope()) }.status)
        assertEquals(403, assertThrows(ApiFailure::class.java) { client.request("GET", "/api/rooms/${host.roomId}", host.inviteSecret, scope()) }.status)
    }
    @Test fun realRawWavDownloadIdempotencyConflictAndMalformedAudio() {
        val host = host(); val member = member(host); val clip = RetryClip(pcm(), 1000)
        val first = client.upload(host, clip, scope()); val voice = Voice.parse(first.body.getJSONObject("message"))
        val download = client.download(member, voice, scope()); assertArrayEquals(Wav.decode(clip.bytes).bytes, download.bytes)
        val duplicate = client.upload(host, clip, scope()); assertTrue(duplicate.body.getBoolean("duplicate")); assertEquals(voice.id, duplicate.body.getJSONObject("message").getString("id")); assertEquals(voice.acceptedAt, duplicate.body.getJSONObject("message").getLong("acceptedAt"))
        assertEquals(409, assertThrows(ApiFailure::class.java) { client.upload(host, RetryClip(pcm(300), 1000, clip.clientMessageId), scope()) }.status)
        assertEquals(422, assertThrows(ApiFailure::class.java) { client.upload(host, RetryClip("not WAV".toByteArray(), 1000), scope()) }.status)
        assertEquals(1, client.snapshot(member, scope()).body.getJSONArray("voiceMessages").length())
    }
    @Test fun bearerSseDeliversSnapshotVoiceAndLatestSnapshotAfterReconnect() {
        val host = host(); val member = member(host); val events = LinkedBlockingQueue<ServerEvent>(); val stream = scope()
        val thread = Thread { try { client.events(member, stream) { event, _ -> events.add(event) } } catch (_: Exception) {} }.apply { start() }
        assertEquals("snapshot", events.poll(3, TimeUnit.SECONDS)!!.type)
        val uploaded = client.upload(host, RetryClip(pcm(), 0), scope()); val voice = events.poll(3, TimeUnit.SECONDS)!!
        assertEquals("voice", voice.type); assertEquals(uploaded.body.getJSONObject("message").getString("id"), voice.data.getJSONObject("message").getString("id"))
        val cancelStarted = System.nanoTime(); stream.cancel(); thread.join(800)
        assertTrue("Stop/leave must not block on the fifteen-second SSE heartbeat", (System.nanoTime() - cancelStarted) / 1000000 < 1000)
        assertFalse("Cancelling an event stream must stop its worker", thread.isAlive)
        val snapshot = client.snapshot(member, scope()).body; assertEquals(1, snapshot.getJSONArray("voiceMessages").length())
        val now = snapshot.getLong("serverTime"); val queue = VoiceQueue(member.memberId) { now + 31000 }; queue.enable(); queue.offer(listOf(Voice.parse(snapshot.getJSONArray("voiceMessages").getJSONObject(0)))); assertNull(queue.take())
    }
    @Test fun leaveAndCloseInvalidateCredentialsAndVoiceAccess() {
        val host = host(); val member = member(host); val voice = Voice.parse(client.upload(member, RetryClip(pcm(), 0), scope()).body.getJSONObject("message"))
        client.request("DELETE", "/api/rooms/${host.roomId}/members/me", member.token, scope())
        assertEquals(403, assertThrows(ApiFailure::class.java) { client.snapshot(member, scope()) }.status)
        assertEquals(16000, client.download(host, voice, scope()).sampleCount)
        client.request("DELETE", "/api/rooms/${host.roomId}", host.token, scope())
        assertEquals(404, assertThrows(ApiFailure::class.java) { client.download(host, voice, scope()) }.status)
    }
}
