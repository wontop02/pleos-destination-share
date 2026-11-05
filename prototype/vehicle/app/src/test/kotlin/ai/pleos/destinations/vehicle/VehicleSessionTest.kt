package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import ai.pleos.destinations.core.PleosMetadata
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the real service-owned runtime against authenticated HTTP/SSE with detached UI. */
class VehicleSessionTest {
    companion object {
        private lateinit var node: Process
        private lateinit var base: String
        @JvmStatic @BeforeClass fun server() {
            val path = checkNotNull(System.getProperty("prototype.server.path")); require(File(path).isFile)
            val script = """
                import {pathToFileURL} from 'node:url';
                const source = process.argv[1]; process.argv[1] = 'vehicle-session-fixture';
                const {createPrototypeServer} = await import(pathToFileURL(source));
                const app = createPrototypeServer({cleanupIntervalMs: 0});
                app.server.listen(0, '127.0.0.1', () => console.log('http://127.0.0.1:' + app.server.address().port));
                process.on('SIGTERM', async () => { await app.close(); process.exit(0); });
            """.trimIndent()
            node = ProcessBuilder("node", "--input-type=module", "-e", script, path).start()
            val executor = Executors.newSingleThreadExecutor()
            try { base = checkNotNull(executor.submit<String?> { node.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)) }
            finally { executor.shutdownNow() }
        }
        @JvmStatic @AfterClass fun shutdown() {
            if (::node.isInitialized) { node.destroy(); if (!node.waitFor(5, TimeUnit.SECONDS)) node.destroyForcibly() }
        }
    }
    private val first = Destination("42dot", 37.48544722, 127.03636666,
        PleosMetadata("36khp37hh8gqcm5nqhe5", null, "서울시 강남구 남부순환로 2621", "", null, null))
    private val second = Destination("소프트웨어드림센터", 37.41275833, 127.09481944,
        PleosMetadata("36khp2hdj2cfcm5nqhe3", null, "경기도 성남시 수정구 창업로40번길 6", "", null, null))
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        val latest get() = listeners.last()
        var releases = 0
        override fun connect(listener: NavigationGateway.Listener) { listeners.add(listener) }
        override fun readDestination() {}
        override fun requestRoute(destination: Destination) { routes.add(destination) }
        override fun release() { releases++ }
    }
    private class Storage(var identity: RoomCredentials?) : SessionStorage {
        override fun load() = LoadedSession(identity, false)
        override fun save(credentials: RoomCredentials) { identity = credentials }
        override fun clear() { identity = null }
    }
    private inner class Runtime(identity: RoomCredentials) : AutoCloseable {
        var now = 1_000L
        val queue = LinkedBlockingQueue<() -> Unit>()
        val gateway = Gateway()
        val storage = Storage(identity)
        val session = VehicleSession(gateway, { now }, storage, Executors.newFixedThreadPool(3), { queue.put(it) })
        init { session.start() }
        fun until(test: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!test() && System.nanoTime() < deadline) queue.poll(20, TimeUnit.MILLISECONDS)?.invoke()
            assertTrue("Owner callback condition did not complete", test())
        }
        fun drain() { while (true) (queue.poll() ?: break).invoke() }
        fun tick() { now += 300; session.tick() }
        override fun close() { session.close(); drain(); PendingConfirmationCache.clear() }
    }
    @Test fun uiAttachDetachDoesNotDuplicateRuntimeAndSseAutomaticallyAppliesOffscreen() {
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 runtime").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 runtime").first
            api.publish(host, first, 0)
            Runtime(member).use { r ->
                r.until { r.session.state.roomConnected }
                r.gateway.latest.connected(); r.gateway.latest.destinationInfo(second)
                var updates = 0; val observer: (VehicleSession) -> Unit = { updates++ }
                r.session.observe(observer); r.session.observe(observer)
                r.session.startAutomatic(); r.tick(); r.gateway.latest.connected()
                assertEquals(listOf(first), r.gateway.routes)
                r.session.removeObserver(observer); val detachedUpdates = updates
                r.gateway.latest.routingCompleted(); r.gateway.latest.destinationInfo(first)
                assertEquals(ApplyPhase.CONFIRMED, r.session.state.applying); assertEquals(detachedUpdates, updates)
                api.publish(host, second, 1)
                r.until { r.session.state.revision == 2L }; r.tick(); r.gateway.latest.connected()
                r.gateway.latest.routingCompleted(); r.gateway.latest.destinationInfo(second)
                assertEquals(ApplyPhase.CONFIRMED, r.session.state.applying)
                val sessions = r.gateway.listeners.size
                r.session.observe(observer); r.session.start(); r.tick()
                assertEquals(sessions, r.gateway.listeners.size); assertEquals(listOf(first, second), r.gateway.routes)
                assertEquals(2L, api.snapshot(host).update.revision) // Member/native feedback never publishes.
            }
            api.leave(host)
        }
    }
    @Test fun stopBeforeQueuedSseDeliveryFencesAsyncResultAndFutureNativeRequests() {
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 stop").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 stop").first
            Runtime(member).use { r ->
                r.until { r.session.state.roomConnected }; r.gateway.latest.connected(); r.gateway.latest.destinationInfo(first)
                r.session.startAutomatic(); api.publish(host, second, 0)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (r.queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
                assertFalse(r.queue.isEmpty())
                val old = r.gateway.latest
                val stopAt = System.nanoTime()
                r.session.stopAutomatic()
                assertTrue("Stop must fence consent without waiting for the SSE heartbeat", System.nanoTime() - stopAt < TimeUnit.SECONDS.toNanos(1))
                r.drain(); r.tick(); old.destinationChanged(); old.destinationInfo(second)
                assertEquals(AutomaticMode.OFF, r.session.state.automatic); assertFalse(r.session.state.roomConnected)
                assertTrue(r.gateway.routes.isEmpty()); assertEquals(0L, r.session.state.revision.coerceAtLeast(0))
                r.session.startAutomatic(); r.until { r.session.state.automatic == AutomaticMode.FOLLOWER }
                r.tick(); r.gateway.latest.connected(); assertEquals(listOf(second), r.gateway.routes)
            }
            api.leave(host)
        }
    }
    @Test fun serverCloseRevokesConsentAndLateNativeCallbackCannotConfirmOrReroute() {
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 closed").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 closed").first
            api.publish(host, first, 0)
            Runtime(member).use { r ->
                r.until { r.session.state.roomConnected }; r.gateway.latest.connected(); r.gateway.latest.destinationInfo(second)
                r.session.startAutomatic(); r.tick(); r.gateway.latest.connected(); val old = r.gateway.latest
                api.leave(host); r.until { r.session.credentials == null }
                old.routingCompleted(); old.destinationInfo(first); r.tick()
                assertEquals(AutomaticMode.OFF, r.session.state.automatic); assertNull(r.storage.identity)
                assertNotEquals(ApplyPhase.CONFIRMED, r.session.state.applying); assertEquals(1, r.gateway.routes.size)
            }
        }
    }
    @Test fun processReplacementRestoresOnlyCredentialsAndNeverAutomaticConsent() {
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 process").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 process").first
            api.publish(host, first, 0)
            Runtime(member).use { r -> r.until { r.session.state.roomConnected }; r.session.startAutomatic(); assertEquals(AutomaticMode.FOLLOWER, r.session.state.automatic) }
            Runtime(member).use { r ->
                r.until { r.session.state.roomConnected }; r.gateway.latest.connected(); r.gateway.latest.destinationInfo(second); r.tick()
                assertEquals(AutomaticMode.OFF, r.session.state.automatic); assertTrue(r.gateway.routes.isEmpty())
            }
            api.leave(host)
        }
    }
    @Test fun closeDuringAuthenticationDiscardsQueuedAttachWithoutRestoringConsent() {
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 race").first
            val r = Runtime(host)
            r.session.close(); r.drain(); r.tick()
            assertFalse(r.session.state.roomConnected); assertEquals(AutomaticMode.OFF, r.session.state.automatic)
            assertTrue(r.gateway.routes.isEmpty()); api.leave(host)
        }
    }
}
