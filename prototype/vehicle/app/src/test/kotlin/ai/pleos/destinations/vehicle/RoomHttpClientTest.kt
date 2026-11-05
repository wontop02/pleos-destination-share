package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PleosMetadata
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** These JVM clients use the repository's real Node HTTP service on an ephemeral loopback port. */
class RoomHttpClientTest {
    private val destination = Destination("42dot", 37.48544722, 127.03636666,
        PleosMetadata("36khp37hh8gqcm5nqhe5", "0", "서울시 강남구 남부순환로 2621", "", null, null))

    companion object {
        private lateinit var node: Process
        private lateinit var base: String
        @JvmStatic @BeforeClass fun server() {
            val path = checkNotNull(System.getProperty("prototype.server.path"))
            require(File(path).isFile)
            val script = """
                import {pathToFileURL} from 'node:url';
                const source = process.argv[1];
                process.argv[1] = 'vehicle-http-fixture'; // Import as a library; do not trigger its CLI entry point.
                const {createPrototypeServer} = await import(pathToFileURL(source));
                const app = createPrototypeServer({cleanupIntervalMs: 0});
                app.server.listen(0, '127.0.0.1', () => console.log('http://127.0.0.1:' + app.server.address().port));
                process.on('SIGTERM', async () => { await app.close(); process.exit(0); });
            """.trimIndent()
            node = ProcessBuilder("node", "--input-type=module", "-e", script, path).start()
            val executor = Executors.newSingleThreadExecutor()
            try {
                base = requireNotNull(executor.submit<String?> { node.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)) {
                    "Node loopback fixture failed: " + node.errorStream.bufferedReader().readText()
                }
            }
            finally { executor.shutdownNow() }
            validateBaseUrl(base)
        }
        @JvmStatic @AfterClass fun shutdown() {
            if (::node.isInitialized) { node.destroy(); if (!node.waitFor(5, TimeUnit.SECONDS)) node.destroyForcibly() }
        }
    }

    @Test fun actualLoopbackMemberGetsLatestPoiSnapshotAndCannotPublishOrReadOtherRoom() {
        RoomHttpClient(base).use { hostApi -> RoomHttpClient(base).use { memberApi ->
            val host = hostApi.create("대표 테스트").first
            val member = memberApi.join(host.roomId, host.inviteSecret!!, "수신 테스트").first
            val outsider = hostApi.create("다른 공유방").first
            assertTrue(host.token != member.token) // Never include a bearer value in an assertion failure message.
            hostApi.publish(host, destination, 0)
            assertEquals(destination, memberApi.snapshot(member).update.destination)
            assertEquals(403, assertThrows(RoomFailure::class.java) { memberApi.publish(member, destination, 1) }.status)
            val wrongRoom = RoomCredentials(base, host.roomId, outsider.memberId, "host", outsider.token)
            assertEquals(403, assertThrows(RoomFailure::class.java) { hostApi.snapshot(wrongRoom) }.status)
            memberApi.leave(member)
            assertEquals(403, assertThrows(RoomFailure::class.java) { memberApi.snapshot(member) }.status)
            hostApi.leave(host); hostApi.leave(outsider)
            assertEquals(404, assertThrows(RoomFailure::class.java) { hostApi.snapshot(host) }.status)
        } }
    }

    @Test fun actualAuthenticatedSseDeliversRevisionThenClearAndReconnectSnapshot() {
        RoomHttpClient(base).use { hostApi -> RoomHttpClient(base).use { memberApi ->
            val host = hostApi.create("대표 SSE").first
            val member = memberApi.join(host.roomId, host.inviteSecret!!, "수신 SSE").first
            val queue = LinkedBlockingQueue<Pair<String, JSONObject>>()
            val executor = Executors.newSingleThreadExecutor()
            val stream = executor.submit { runCatching { memberApi.events(member) { event, body -> queue.put(event to body) } } }
            try {
                assertEquals("snapshot", queue.poll(3, TimeUnit.SECONDS)?.first)
                hostApi.publish(host, destination, 0)
                val received = checkNotNull(queue.poll(3, TimeUnit.SECONDS))
                assertEquals("destination", received.first)
                assertEquals(destination, RoomProtocol.update(received.second).destination)
                assertEquals(409, assertThrows(RoomFailure::class.java) { hostApi.publish(host, null, 0) }.status)
                hostApi.publish(host, null, 1)
                val clear = RoomProtocol.update(checkNotNull(queue.poll(3, TimeUnit.SECONDS)).second)
                assertNull(clear.destination); assertEquals(2L, clear.revision)
                RoomHttpClient(base).use { reconnect ->
                    assertNull(reconnect.snapshot(member).update.destination)
                    assertEquals(2L, reconnect.snapshot(member).update.revision)
                }
                hostApi.leave(host)
                assertEquals("closed", queue.poll(3, TimeUnit.SECONDS)?.first)
                stream.get(3, TimeUnit.SECONDS)
            } finally { memberApi.close(); stream.cancel(true); executor.shutdownNow() }
        } }
    }

    @Test fun nativeHttpDoesNotFollowRedirectsAndNeverForwardsBearerToRedirectTarget() {
        val calls = AtomicInteger()
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val responder = executor.submit {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { /* Drain headers without logging credentials. */ }
                socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            server.soTimeout = 1_000
            try { server.accept().use { calls.incrementAndGet() } } catch (_: SocketTimeoutException) { }
        }
        try {
            val url = "http://127.0.0.1:${server.localPort}"
            RoomHttpClient(url).use { api ->
                val identity = RoomCredentials(url, "a".repeat(24), "b".repeat(24), "member", "t".repeat(43))
                assertEquals(302, assertThrows(RoomFailure::class.java) { api.snapshot(identity) }.status)
                responder.get(3, TimeUnit.SECONDS)
                assertEquals(0, calls.get())
            }
        } finally { server.close(); executor.shutdownNow() }
    }
}
