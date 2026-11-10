package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real authenticated HTTP; deterministic native boundary, with no Android permission shortcut. */
class DestinationRequestsTest {
    companion object {
        private lateinit var node: Process
        private lateinit var base: String
        @JvmStatic @BeforeClass fun server() {
            val path = checkNotNull(System.getProperty("prototype.server.path")); require(File(path).isFile)
            val script = """
                import {pathToFileURL} from 'node:url';
                const source = process.argv[1]; process.argv[1] = 'destination-request-test';
                const {createPrototypeServer} = await import(pathToFileURL(source));
                const app = createPrototypeServer({cleanupIntervalMs: 0});
                app.server.listen(0, '127.0.0.1', () => console.log('http://127.0.0.1:' + app.server.address().port));
                process.on('SIGTERM', async () => { await app.close(); process.exit(0); });
            """.trimIndent()
            node = ProcessBuilder("node", "--input-type=module", "-e", script, path).start()
            val reader = Executors.newSingleThreadExecutor()
            try { base = checkNotNull(reader.submit<String?> { node.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)) }
            finally { reader.shutdownNow() }
        }
        @JvmStatic @AfterClass fun shutdown() {
            if (::node.isInitialized) { node.destroy(); if (!node.waitFor(5, TimeUnit.SECONDS)) node.destroyForcibly() }
        }
    }
    private val first = PrototypePlaces.all[0]
    private val second = PrototypePlaces.all[1]
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        val latest get() = listeners.last()
        override fun connect(listener: NavigationGateway.Listener) { listeners += listener }
        override fun readDestination() = Unit
        override fun requestRoute(destination: Destination) { routes += destination }
        override fun release() = Unit
    }
    private inner class Runtime(val identity: RoomCredentials, val api: RoomHttpClient) : AutoCloseable {
        var now = 1_000L
        val queue = LinkedBlockingQueue<() -> Unit>()
        val worker = Executors.newFixedThreadPool(2)
        val gateway = Gateway()
        val controller = DestinationRequests(gateway, { now }, worker, { queue.put(it) }, {})
        init { controller.scope(identity, true, api); sync() }
        fun sync() { val value = api.snapshot(identity).update; controller.consume(value.changeRequest, value.requestRevision, value.sequence, value.serverTime) }
        fun until(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < end) queue.poll(10, TimeUnit.MILLISECONDS)?.invoke()
            assertTrue("Request callback did not complete", condition())
        }
        fun read(target: Destination = first) { gateway.latest.connected(); gateway.latest.destinationInfo(target) }
        fun preview(target: Destination = first) { controller.refreshCurrent(); read(target); assertEquals(target, controller.preview) }
        fun send(target: Destination = first) {
            preview(target); controller.sendCurrent(); read(target)
            until { !controller.busy && controller.request?.sender?.id == identity.memberId }
        }
        override fun close() { controller.close(); worker.shutdownNow() }
    }
    @Test fun senderReReadsAfterPreviewAndNeverRequestsItsOwnRoute() {
        RoomHttpClient(base).use { api ->
            val a = api.create("발신 테스트").first; api.join(a.roomId, a.inviteSecret!!, "수신 테스트")
            Runtime(a, api).use { source ->
                source.preview(first); source.controller.sendCurrent(); source.read(second)
                assertNull(api.snapshot(a).update.changeRequest)
                assertEquals(second, source.controller.preview)
                assertTrue(source.controller.message.contains("바뀌었어요"))
                source.controller.sendCurrent(); source.read(second)
                source.until { !source.controller.busy && source.controller.request != null }
                assertEquals(second, source.controller.request!!.destination)
                assertTrue(source.gateway.routes.isEmpty())
                assertNull(api.snapshot(a).update.destination)
            }; api.leave(a)
        }
    }
    @Test fun pageExitCancelsPendingSendReadAndIgnoresItsLateNativeCallback() {
        RoomHttpClient(base).use { api ->
            val a = api.create("이탈 발신").first
            Runtime(a, api).use { source ->
                source.preview(); source.controller.sendCurrent()
                val old = source.gateway.latest
                source.controller.invalidatePreview()
                old.connected(); old.destinationInfo(first)
                assertNull(source.controller.preview); assertFalse(source.controller.canSend)
                assertFalse(source.controller.busy)
                assertNull(api.snapshot(a).update.changeRequest)
                assertTrue(source.gateway.routes.isEmpty())
            }; api.leave(a)
        }
    }
    @Test fun threeCarsAcceptIndependentlyAndDuplicateFramesNeverReissue() {
        RoomHttpClient(base).use { api ->
            val a = api.create("대표 요청").first; val b = api.join(a.roomId, a.inviteSecret!!, "수락 요청").first
            val c = api.join(a.roomId, a.inviteSecret!!, "미응답 요청").first
            Runtime(a, api).use { source -> Runtime(b, api).use { receiver -> Runtime(c, api).use { other ->
                source.send(); receiver.sync(); other.sync()
                assertTrue(receiver.gateway.routes.isEmpty()); assertTrue(other.gateway.routes.isEmpty())
                val id = receiver.controller.pending!!.id
                receiver.controller.respond(id, true); receiver.controller.respond(id, true)
                receiver.until { !receiver.controller.busy && receiver.controller.state.applying == ApplyPhase.CONNECTING }
                receiver.gateway.latest.connected()
                assertEquals(listOf(first), receiver.gateway.routes)
                assertTrue(other.gateway.routes.isEmpty()); assertEquals("pending", other.controller.ownResponse)
                other.controller.respond(id, false); other.until { !other.controller.busy }
                receiver.sync(); receiver.gateway.latest.routingCompleted(); receiver.gateway.latest.destinationInfo(first)
                assertEquals(ApplyPhase.CONFIRMED, receiver.controller.state.applying)
                repeat(3) { receiver.sync() }
                receiver.controller.respond(id, true)
                assertEquals(1, receiver.gateway.routes.size)
                assertNull(api.snapshot(a).update.destination)
            } } }; api.leave(a)
        }
    }
    @Test fun stopBeforeHttpAcceptanceCallbackRevokesRouteAuthority() {
        RoomHttpClient(base).use { api ->
            val a = api.create("정지 발신").first; val b = api.join(a.roomId, a.inviteSecret!!, "정지 수신").first
            Runtime(a, api).use { source -> Runtime(b, api).use { receiver ->
                source.send(); receiver.sync(); val id = receiver.controller.pending!!.id
                receiver.controller.respond(id, true); receiver.controller.stop()
                Thread.sleep(100)
                while (receiver.queue.isNotEmpty()) receiver.queue.poll()?.invoke()
                receiver.sync()
                assertTrue(receiver.gateway.routes.isEmpty()); assertFalse(receiver.controller.canRespond(id))
                assertFalse(receiver.controller.listening)
            } }; api.leave(a)
        }
    }
    @Test fun coldAcceptedSnapshotIsDisplayOnlyAndReplacementCancelsPendingAuthority() {
        RoomHttpClient(base).use { api ->
            val a = api.create("과거 발신").first; val b = api.join(a.roomId, a.inviteSecret!!, "과거 수신").first
            Runtime(a, api).use { source ->
                source.send(); val old = api.snapshot(b).update.changeRequest!!
                api.respondDestinationChange(b, old, true)
                Runtime(b, api).use { cold ->
                    assertEquals("accepted", cold.controller.ownResponse)
                    assertTrue(cold.gateway.routes.isEmpty()); assertFalse(cold.controller.canRespond(old.id))
                    source.send(second); cold.sync()
                    assertNotEquals(old.id, cold.controller.pending!!.id)
                    cold.controller.respond(old.id, true); assertTrue(cold.gateway.routes.isEmpty())
                }
            }; api.leave(a)
        }
    }
    @Test fun expiredPreviewAndRequestCannotSendOrAccept() {
        RoomHttpClient(base).use { api ->
            val a = api.create("만료 발신").first; val b = api.join(a.roomId, a.inviteSecret!!, "만료 수신").first
            Runtime(a, api).use { source -> Runtime(b, api).use { receiver ->
                source.preview(); source.now += 15_001; source.controller.tick()
                assertFalse(source.controller.canSend)
                source.controller.sendCurrent(); assertNull(api.snapshot(a).update.changeRequest)
                source.send(); receiver.sync(); val id = receiver.controller.pending!!.id
                receiver.controller.startListening(); receiver.now += 120_000; receiver.controller.tick()
                assertNull(receiver.controller.pending); assertFalse(receiver.controller.canRespond(id))
                receiver.controller.respond(id, true); assertTrue(receiver.gateway.routes.isEmpty())
            } }; api.leave(a)
        }
    }
    @Test fun issuedAcceptanceKeepsItsBoundedVerificationAfterResponseExpiry() {
        RoomHttpClient(base).use { api ->
            val a = api.create("만료 직전 발신").first; val b = api.join(a.roomId, a.inviteSecret!!, "만료 직전 수신").first
            Runtime(a, api).use { source -> Runtime(b, api).use { receiver ->
                source.send(); receiver.sync(); val value = receiver.controller.pending!!
                receiver.now += 119_000
                receiver.controller.respond(value.id, true)
                receiver.until { !receiver.controller.busy && receiver.controller.state.applying == ApplyPhase.CONNECTING }
                receiver.gateway.latest.connected()
                assertEquals(1, receiver.gateway.routes.size)
                receiver.now += 1_001; receiver.controller.tick()
                assertNull(receiver.controller.pending)
                assertTrue(receiver.controller.hasLocalAcceptance)
                assertTrue(receiver.controller.applying)
                val frame = api.snapshot(b).update
                receiver.controller.consume(frame.changeRequest!!.copy(status = "expired"), frame.requestRevision, frame.sequence + 1, frame.serverTime + 120_000)
                receiver.gateway.latest.routingCompleted(); receiver.gateway.latest.destinationInfo(first)
                assertEquals(ApplyPhase.CONFIRMED, receiver.controller.state.applying)
                assertEquals(1, receiver.gateway.routes.size)
                assertFalse(receiver.controller.canRespond(value.id))
            } }; api.leave(a)
        }
    }
}
