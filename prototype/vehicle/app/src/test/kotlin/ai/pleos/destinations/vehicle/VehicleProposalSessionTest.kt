package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real authenticated HTTP/SSE; only the native boundary and time are deterministic fakes. */
class VehicleProposalSessionTest {
    companion object {
        private lateinit var node: Process
        private lateinit var output: BufferedReader
        private lateinit var base: String
        @JvmStatic @BeforeClass fun server() {
            val path = checkNotNull(System.getProperty("prototype.server.path")); require(File(path).isFile)
            val script = """
                import {pathToFileURL} from 'node:url'; import {createInterface} from 'node:readline';
                const source = process.argv[1]; process.argv[1] = 'proposal-session-fixture';
                const {createPrototypeServer} = await import(pathToFileURL(source)); let now = 0;
                const app = createPrototypeServer({clock: () => now, cleanupIntervalMs: 0});
                app.server.listen(0, '127.0.0.1', () => console.log('http://127.0.0.1:' + app.server.address().port));
                createInterface({input: process.stdin}).on('line', line => { now = Number(line); app.cleanupExpired(); console.log('clock-ok'); });
                process.on('SIGTERM', async () => { await app.close(); process.exit(0); });
            """.trimIndent()
            node = ProcessBuilder("node", "--input-type=module", "-e", script, path).start()
            output = node.inputStream.bufferedReader(); base = checkNotNull(output.readLine())
        }
        private fun serverTime(at: Long) { node.outputStream.write("$at\n".toByteArray()); node.outputStream.flush(); assertEquals("clock-ok", output.readLine()) }
        @JvmStatic @AfterClass fun shutdown() {
            if (::node.isInitialized) { node.destroy(); if (!node.waitFor(5, TimeUnit.SECONDS)) node.destroyForcibly() }
        }
    }
    private val first = PrototypePlaces.all[0]
    private val second = PrototypePlaces.all[1]
    private class Gateway : NavigationGateway {
        val listeners = mutableListOf<NavigationGateway.Listener>()
        val routes = mutableListOf<Destination>()
        var reads = 0
        val latest get() = listeners.last()
        override fun connect(listener: NavigationGateway.Listener) { listeners.add(listener) }
        override fun readDestination() { reads++ }
        override fun requestRoute(destination: Destination) { routes.add(destination) }
        override fun release() {}
    }
    private class Storage(var identity: RoomCredentials?) : SessionStorage {
        override fun load() = LoadedSession(identity, false)
        override fun save(credentials: RoomCredentials) { identity = credentials }
        override fun clear() { identity = null }
    }
    private class GatedExecutor : AbstractExecutorService() {
        private val delegate = Executors.newFixedThreadPool(3)
        var holdNext = false
        private var held: Runnable? = null
        override fun execute(command: Runnable) {
            if (holdNext) { holdNext = false; check(held == null); held = command } else delegate.execute(command)
        }
        fun release() {
            val task = checkNotNull(held); held = null; val completed = CountDownLatch(1)
            delegate.execute { try { task.run() } finally { completed.countDown() } }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        }
        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> { held = null; return delegate.shutdownNow() }
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
    }
    private inner class Runtime(identity: RoomCredentials, workers: ExecutorService = Executors.newFixedThreadPool(3)) : AutoCloseable {
        var now = 1_000L
        val queue = LinkedBlockingQueue<() -> Unit>()
        val gateway = Gateway()
        val session = VehicleSession(gateway, { now }, Storage(identity), workers, { queue.put(it) })
        init { session.start(); until { session.state.roomConnected }; gateway.latest.connected(); gateway.latest.destinationInfo(first) }
        fun until(test: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!test() && System.nanoTime() < deadline) queue.poll(10, TimeUnit.MILLISECONDS)?.invoke()
            assertTrue("Owner callback condition did not complete: ${session.proposalMessage}", test())
        }
        fun drain() { while (true) (queue.poll() ?: break).invoke() }
        fun advance(ms: Long = 300) { now += ms; session.tick() }
        fun requested() { gateway.latest.connected(); assertEquals(ApplyPhase.REQUESTED, session.state.applying) }
        fun match(target: Destination = second) { gateway.latest.routingCompleted(); gateway.latest.destinationInfo(target) }
        override fun close() { session.close(); drain(); PendingConfirmationCache.clear() }
    }
    private fun pending(a: Runtime, b: Runtime, target: Destination = second) {
        a.session.propose(target, "동행 목적지 변경")
        a.until { a.session.proposal?.status == "pending" && !a.session.proposalBusy }
        b.until { b.session.proposal?.id == a.session.proposal?.id }
    }
    private fun accept(a: Runtime, b: Runtime) {
        val p = b.session.proposal!!; b.session.respond(p.id, p.version, true)
        b.session.respond(p.id, p.version, true) // Double tap must consume the same local authority only once.
        a.until { a.session.proposal?.status == "accepted" }
        b.until { b.session.proposal?.status == "accepted" && !b.session.proposalBusy }
    }
    @Test fun autoOffBothVehiclesApplyOnlyAfterAcceptanceAndVerifyOffscreenOnce() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("차량 A").first; val member = api.join(host.roomId, host.inviteSecret!!, "차량 B").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); assertTrue(a.session.hasProposalLease); assertFalse(b.session.hasProposalLease)
                assertTrue(a.gateway.routes.isEmpty()); assertTrue(b.gateway.routes.isEmpty())
                assertNull(api.snapshot(host).update.destination)
                accept(a, b); assertEquals(1L, a.session.state.revision); assertEquals(1L, b.session.state.revision)
                a.requested(); b.requested(); a.advance(); b.advance(); a.drain(); b.drain()
                assertEquals(listOf(second), a.gateway.routes); assertEquals(listOf(second), b.gateway.routes)
                assertEquals(AutomaticMode.OFF, a.session.state.automatic); assertEquals(AutomaticMode.OFF, b.session.state.automatic)
                a.match(second.copy(pleos = second.pleos!!.copy(poiSubId = "0")))
                assertNotEquals(ApplyPhase.CONFIRMED, a.session.state.applying)
                a.advance(2_000); a.gateway.latest.destinationInfo(second); b.match()
                assertEquals(ApplyPhase.CONFIRMED, a.session.state.applying); assertEquals(ApplyPhase.CONFIRMED, b.session.state.applying)
                a.drain(); b.drain(); assertEquals(1, a.gateway.routes.size); assertEquals(1, b.gateway.routes.size)
                assertEquals(1L, api.snapshot(host).update.revision)
            } }; api.leave(host)
        }
    }
    @Test fun reverseMemberProposalAndHostAcceptanceUseTheSameBoundConsent() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 역방향").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 역방향").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(b, a, first); accept(b, a); a.requested(); b.requested(); a.match(first); b.match(first)
                assertEquals(listOf(first), a.gateway.routes); assertEquals(listOf(first), b.gateway.routes)
                assertEquals(ApplyPhase.CONFIRMED, a.session.state.applying); assertEquals(ApplyPhase.CONFIRMED, b.session.state.applying)
            } }; api.leave(host)
        }
    }
    @Test fun rejectAndExpiryNeverRequestANativeRouteAndReleaseProposalLease() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 거절").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 거절").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); val p = b.session.proposal!!; b.session.respond(p.id, p.version, false)
                a.until { a.session.proposal?.status == "rejected" }; b.until { !b.session.proposalBusy }
                assertFalse(a.session.hasProposalLease); assertFalse(b.session.hasProposalLease)
                pending(b, a); serverTime(120_000); a.now += 120_000; b.now += 120_000
                a.until { a.session.proposal?.status == "expired" }; b.until { b.session.proposal?.status == "expired" }
                a.session.tick(); b.session.tick(); a.session.respond(p.id, p.version, true)
                assertTrue(a.gateway.routes.isEmpty()); assertTrue(b.gateway.routes.isEmpty())
                assertEquals(0L, api.snapshot(host).update.revision); assertNull(api.snapshot(host).update.destination)
            } }; api.leave(host)
        }
    }
    @Test fun acceptanceAt119999msKeepsFull45SecondOperationLeaseBeyondProposalExpiry() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 막판").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 막판").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); serverTime(119_999); a.now += 119_999; b.now += 119_999
                accept(a, b); a.requested(); b.requested()
                val deadline = a.session.coordinator.confirmationSnapshot()!!.deadline
                assertEquals(a.now + 45_000, deadline)
                a.advance(2); b.advance(2); assertTrue(a.session.hasProposalLease); assertTrue(b.session.hasProposalLease)
                a.match(); b.match(); a.now = deadline - 1; a.session.tick(); assertTrue(a.session.hasProposalLease)
                a.now = deadline; a.session.tick(); assertFalse(a.session.hasProposalLease)
                assertEquals(ApplyPhase.CONFIRMED, a.session.state.applying); assertEquals(1, a.gateway.routes.size)
            } }; api.leave(host)
        }
    }
    @Test fun issuedOperationReconnectResumesOnlyQueriesAndKeepsOriginalDeadline() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 再接続").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 再接続").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); accept(a, b); a.requested(); val old = a.gateway.latest
                val deadline = a.session.coordinator.confirmationSnapshot()!!.deadline
                a.advance(20_000); a.session.reconnectRoom(); a.until { a.session.state.roomConnected && !a.session.busy }
                a.gateway.latest.connected(); old.destinationInfo(second); old.routingCompleted()
                assertNotEquals(ApplyPhase.CONFIRMED, a.session.state.applying)
                a.gateway.latest.destinationInfo(second)
                assertEquals(ApplyPhase.CONFIRMED, a.session.state.applying); assertEquals(listOf(second), a.gateway.routes)
                assertEquals(deadline, a.session.coordinator.confirmationSnapshot()!!.deadline)
                a.drain(); a.advance(); assertEquals(1, a.gateway.routes.size)
            } }; api.leave(host)
        }
    }
    @Test fun coldResumeAcceptedSnapshotNeverRestoresConsentOrRoutesEvenOnGenericAutomaticStart() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 과거").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 과거").first
            val p = api.propose(host, "a".repeat(24), second, "변경", 0).proposal!!; api.respond(member, p, true)
            for (identity in listOf(host, member)) Runtime(identity).use { r ->
                assertEquals("accepted", r.session.proposal!!.status); assertFalse(r.session.hasProposalLease)
                r.session.startAutomatic(); r.advance(); r.gateway.latest.destinationInfo(first); r.advance(1_000)
                assertTrue(r.gateway.routes.isEmpty()); assertEquals(1L, api.snapshot(host).update.revision)
                r.session.stopAutomatic(); r.session.reconnectRoom(); r.until { r.session.state.roomConnected }
                r.session.retryAcceptedProposal(); r.requested(); r.match()
                assertEquals(listOf(second), r.gateway.routes)
            }
            api.leave(host)
        }
    }
    @Test fun stopBeforeQueuedAcceptedFrameAndLateCallbacksCancelsProposalAuthority() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 중지").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 중지").first
            Runtime(host).use { a ->
                a.session.propose(second, "변경"); a.until { a.session.proposal?.status == "pending" && !a.session.proposalBusy }
                val p = a.session.proposal!!; val old = a.gateway.latest; api.respond(member, p, true)
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (a.queue.isEmpty() && System.nanoTime() < limit) Thread.sleep(5)
                assertFalse(a.queue.isEmpty()); a.session.stopAutomatic(); a.drain(); a.advance(60_000)
                old.connected(); old.destinationChanged(); old.routingCompleted(); old.destinationInfo(second)
                assertTrue(a.gateway.routes.isEmpty()); assertFalse(a.session.hasProposalLease)
                a.session.reconnectRoom(); a.until { a.session.state.roomConnected }; a.advance()
                assertTrue(a.gateway.routes.isEmpty())
            }; api.leave(host)
        }
    }
    @Test fun threeMemberPartialVoteAndMembershipInvalidationProduceNoCandidateRoutes() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 셋").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 셋").first
            val third = api.join(host.roomId, host.inviteSecret!!, "세 번째").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); val p = b.session.proposal!!; b.session.respond(p.id, p.version, true)
                b.until { !b.session.proposalBusy }; a.until { a.session.proposal!!.version == 2L }
                a.advance(); b.advance(); assertTrue(a.gateway.routes.isEmpty()); assertTrue(b.gateway.routes.isEmpty())
                assertEquals(0L, api.snapshot(host).update.revision)
                api.leave(third); a.until { a.session.proposal?.status == "invalidated" }; b.until { b.session.proposal?.status == "invalidated" }
                assertFalse(a.session.hasProposalLease); assertFalse(b.session.hasProposalLease)
                a.session.respond(p.id, p.version, true); b.session.respond(p.id, p.version, true)
                a.advance(); b.advance(); assertTrue(a.gateway.routes.isEmpty()); assertTrue(b.gateway.routes.isEmpty())
            } }; api.leave(host)
        }
    }
    @Test fun activeLeaderOldObservationAndFailedAcceptedApplyCannotUndoAgreedDestination() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 보호").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 보호").first
            api.publish(host, first, 0)
            Runtime(host).use { a -> Runtime(member).use { b ->
                a.session.startAutomatic(); a.advance() // Leader has an old native observation in flight.
                pending(b, a); a.gateway.latest.destinationInfo(first); accept(b, a); a.requested()
                val old = a.gateway.latest; a.gateway.latest.failed(); old.destinationInfo(second); old.routingCompleted()
                a.advance(5_000); a.gateway.latest.connected(); a.gateway.latest.destinationInfo(first)
                a.advance(45_000); a.gateway.latest.destinationInfo(first); a.drain()
                assertEquals(ApplyPhase.FAILED, a.session.state.applying); assertTrue(a.session.coordinator.proposalPublicationHeld)
                assertEquals(2L, api.snapshot(host).update.revision); assertEquals(second, api.snapshot(host).update.destination)
                assertEquals(listOf(second), a.gateway.routes)
                a.session.stopAutomatic(); old.connected(); old.destinationChanged(); a.advance()
                assertEquals(1, a.gateway.routes.size)
            } }; api.leave(host)
        }
    }
    @Test fun queuedOldLeaderPublicationCannotBlockAcceptedApplyOrCancelItsVerification() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 이전 공유 경합").first; val member = api.join(host.roomId, host.inviteSecret!!, "참여 이전 공유 경합").first
            api.publish(host, first, 0); val workers = GatedExecutor()
            Runtime(host, workers).use { a ->
                a.session.startAutomatic(); a.advance(); workers.holdNext = true; a.gateway.latest.destinationInfo(second)
                assertTrue(a.session.state.sharing)
                val p = api.propose(member, "d".repeat(24), first, "다시 함께 변경", 1).proposal!!
                a.until { a.session.proposal?.id == p.id }; assertFalse(a.session.state.sharing)
                a.session.respond(p.id, p.version, true)
                a.until { a.session.proposal?.status == "accepted" && !a.session.proposalBusy }
                a.requested(); workers.release(); a.advance(); a.drain(); a.match(first)
                assertEquals(ApplyPhase.CONFIRMED, a.session.state.applying); assertEquals(listOf(first), a.gateway.routes)
                assertEquals(2L, api.snapshot(host).update.revision); assertEquals(first, api.snapshot(host).update.destination)
            }; api.leave(host)
        }
    }
    private fun laterTerminalProposalKeepsEarlierFailedTarget(mode: String) {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 실패 후 새 제안").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 실패 후 새 제안").first
            api.publish(host, first, 0)
            Runtime(host).use { a -> Runtime(member).use { b ->
                a.session.startAutomatic(); a.advance(); a.gateway.latest.destinationInfo(first)
                pending(b, a); accept(b, a); a.requested(); b.requested()
                a.gateway.latest.failed(); b.gateway.latest.failed()
                a.advance(45_000); b.advance(45_000)
                a.gateway.latest.connected(); a.gateway.latest.destinationInfo(first)
                assertFalse(a.session.hasProposalLease); assertTrue(a.session.coordinator.proposalPublicationHeld)
                val failedId = a.session.proposal!!.id
                b.session.propose(first, "새 후보는 이전 수락 실패를 해제하지 않음")
                b.until { b.session.proposal?.id != failedId && b.session.proposal?.status == "pending" && !b.session.proposalBusy }
                a.until { a.session.proposal?.id == b.session.proposal?.id }
                when (mode) {
                    "rejected" -> {
                        val p = a.session.proposal!!; a.session.respond(p.id, p.version, false)
                    }
                    "expired" -> serverTime(120_000)
                    "invalidated" -> api.join(host.roomId, host.inviteSecret!!, "추가 회원")
                    else -> error("Unknown test mode")
                }
                a.until { a.session.proposal?.status == mode && !a.session.proposalBusy }
                b.until { b.session.proposal?.status == mode }
                a.advance(); a.gateway.latest.destinationInfo(first)
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
                while (System.nanoTime() < until) a.queue.poll(5, TimeUnit.MILLISECONDS)?.invoke()
                val state = api.snapshot(host).update
                assertEquals("A later $mode proposal must not republish the previous native target", second, state.destination)
                assertEquals(2L, state.revision); assertTrue(a.session.coordinator.proposalPublicationHeld)
                assertEquals(listOf(second), a.gateway.routes)
                if (mode == "rejected") {
                    a.session.retryAcceptedProposal(); a.requested(); a.match(second)
                    assertEquals(ApplyPhase.CONFIRMED, a.session.state.applying)
                    a.advance(45_000); a.gateway.latest.destinationInfo(second)
                    assertFalse(a.session.coordinator.proposalPublicationHeld)
                    assertEquals(listOf(second, second), a.gateway.routes)
                    assertEquals(2L, api.snapshot(host).update.revision)
                } else if (mode == "invalidated") {
                    a.session.resetProposalProtection(); a.advance(); a.gateway.latest.destinationInfo(first)
                    a.until { a.session.state.revision == 3L }
                    assertEquals(first, api.snapshot(host).update.destination)
                }
            } }
            api.leave(host)
        }
    }
    @Test fun laterRejectionCannotReleaseFailedAgreementProtection() = laterTerminalProposalKeepsEarlierFailedTarget("rejected")
    @Test fun laterExpiryCannotReleaseFailedAgreementProtection() = laterTerminalProposalKeepsEarlierFailedTarget("expired")
    @Test fun laterMembershipInvalidationCannotReleaseFailedAgreementProtection() = laterTerminalProposalKeepsEarlierFailedTarget("invalidated")

    @Test fun laterProposalCannotHideHistoricalAcceptanceOnColdAutomaticResume() {
        for (mode in listOf("pending", "rejected", "expired", "invalidated")) {
            serverTime(0)
            RoomHttpClient(base).use { api ->
                val host = api.create("대표 과거 수락 출처").first
                val member = api.join(host.roomId, host.inviteSecret!!, "참여 과거 수락 출처").first
                val accepted = api.propose(host, "e".repeat(24), second, "과거 수락", 0).proposal!!
                api.respond(member, accepted, true)
                val later = api.propose(member, "f".repeat(24), first, "새 제안", 1).proposal!!
                when (mode) {
                    "rejected" -> api.respond(host, later, false)
                    "expired" -> serverTime(120_000)
                    "invalidated" -> api.join(host.roomId, host.inviteSecret!!, "새 회원")
                }
                for (identity in listOf(member, host)) Runtime(identity).use { r ->
                    assertEquals(mode, r.session.proposal!!.status)
                    assertEquals(accepted.id, r.session.acceptedProposal!!.id)
                    r.session.startAutomatic(); r.advance(); r.gateway.latest.connected(); r.gateway.latest.destinationInfo(first)
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)
                    while (System.nanoTime() < until) r.queue.poll(5, TimeUnit.MILLISECONDS)?.invoke()
                    assertTrue("A later $mode proposal cannot turn an old accepted snapshot into a new route", r.gateway.routes.isEmpty())
                    assertEquals(1L, api.snapshot(host).update.revision)
                    assertEquals(second, api.snapshot(host).update.destination)
                }
                api.leave(host)
            }
        }
    }

    private fun completedAgreementRequiresExplicitRetryAfterReconnect(stop: Boolean) {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 완료 후 재연결").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 완료 후 재연결").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); accept(a, b); a.requested(); b.requested(); a.match(); b.match()
                val acceptedId = b.session.acceptedProposal!!.id
                if (stop) b.session.startAutomatic()
                a.advance(45_000); b.advance(45_000)
                assertFalse(b.session.hasProposalLease); assertFalse(b.session.coordinator.proposalPublicationHeld)
                if (stop) b.session.stopAutomatic()
                b.session.reconnectRoom(); b.until { b.session.state.roomConnected && !b.session.busy }
                b.gateway.latest.connected(); b.gateway.latest.destinationInfo(second)
                assertEquals(acceptedId, b.session.acceptedProposal!!.id)
                b.session.startAutomatic(); b.advance(); b.gateway.latest.connected()
                assertEquals("A completed accepted snapshot cannot restore generic apply authority after reconnect, stop=$stop", listOf(second), b.gateway.routes)
                b.session.retryAcceptedProposal(); b.requested(); b.match()
                assertEquals(listOf(second, second), b.gateway.routes)
                assertEquals(ApplyPhase.CONFIRMED, b.session.state.applying)
                assertEquals(1L, api.snapshot(host).update.revision)
            } }
            api.leave(host)
        }
    }
    @Test fun completedAgreementDoesNotReplayAfterStopReconnectAndGenericStart() = completedAgreementRequiresExplicitRetryAfterReconnect(true)
    @Test fun completedAgreementDoesNotReplayAfterOffModeReconnectAndGenericStart() = completedAgreementRequiresExplicitRetryAfterReconnect(false)
    @Test fun replayFenceAllowsANewExplicitAcceptedIdAndANewerDirectDestination() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val host = api.create("대표 새 수락 ID").first
            val member = api.join(host.roomId, host.inviteSecret!!, "참여 새 수락 ID").first
            Runtime(host).use { a -> Runtime(member).use { b ->
                pending(a, b); accept(a, b); a.requested(); b.requested(); a.match(); b.match()
                val oldId = b.session.acceptedProposal!!.id
                a.advance(45_000); b.advance(45_000)
                b.session.stopAutomatic(); b.session.reconnectRoom(); b.until { b.session.state.roomConnected && !b.session.busy }
                b.gateway.latest.connected(); b.gateway.latest.destinationInfo(second)
                b.session.startAutomatic(); b.advance(); b.gateway.latest.connected()
                assertEquals(listOf(second), b.gateway.routes)
                pending(a, b, first); assertNotEquals(oldId, b.session.proposal!!.id)
                accept(a, b); a.requested(); b.requested(); a.match(first); b.match(first)
                assertEquals(listOf(second, first), b.gateway.routes)
                api.publish(host, second, 2)
                b.until { b.session.state.revision == 3L }; b.advance(); b.requested(); b.match(second)
                assertEquals(listOf(second, first, second), b.gateway.routes)
                assertEquals(ApplyPhase.CONFIRMED, b.session.state.applying)
            } }
            api.leave(host)
        }
    }

    @Test fun forgettingOldRoomDoesNotFenceANewRoomsDirectDestinationAtTheSameRevision() {
        serverTime(0)
        RoomHttpClient(base).use { api ->
            val oldHost = api.create("이전 방 대표").first
            val member = api.join(oldHost.roomId, oldHost.inviteSecret!!, "이전 방 참여").first
            val p = api.propose(oldHost, "a".repeat(24), second, "이전 방 수락", 0).proposal!!
            api.respond(member, p, true)
            val newHost = api.create("새 방 대표").first; api.publish(newHost, first, 0)
            Runtime(member).use { r ->
                r.session.startAutomatic(); r.advance(); r.gateway.latest.connected()
                assertTrue(r.gateway.routes.isEmpty()); assertEquals(1L, r.session.state.revision)
                r.session.forget(true)
                r.session.submit(true, base, "새 방 참여", newHost.roomId, newHost.inviteSecret!!)
                r.until { r.session.state.roomConnected && !r.session.busy && r.session.credentials?.roomId == newHost.roomId }
                r.gateway.latest.connected(); r.gateway.latest.destinationInfo(second)
                assertNull(r.session.acceptedProposal); assertEquals(1L, r.session.state.revision)
                r.session.startAutomatic(); r.advance(); r.requested(); r.match(first)
                assertEquals(listOf(first), r.gateway.routes)
                assertEquals(ApplyPhase.CONFIRMED, r.session.state.applying)
            }
            api.leave(newHost); api.leave(oldHost)
        }
    }

}
