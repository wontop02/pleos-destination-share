package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleCoordinator
import ai.pleos.destinations.core.VehicleState
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom

interface SessionStorage {
    fun load(): LoadedSession
    fun save(credentials: RoomCredentials)
    fun clear()
}
data class LoadedSession(val credentials: RoomCredentials?, val damaged: Boolean)

/** Single service-owned runtime. Every state mutation/callback is serialized on the owner thread. */
class VehicleSession(
    gateway: NavigationGateway,
    private val clock: () -> Long,
    private val storage: SessionStorage,
    private val workers: ExecutorService,
    private val dispatch: (() -> Unit) -> Unit,
    private val evidence: (String, Long, Long, Boolean?) -> Unit = { _, _, _, _ -> },
) {
    private val observers = linkedSetOf<(VehicleSession) -> Unit>()
    val coordinator = VehicleCoordinator(gateway, clock, { emit() }, ::publish, evidence)
    val state: VehicleState get() = coordinator.state
    var credentials: RoomCredentials? = null; private set
    var busy = false; private set
    var leaving = false; private set
    var roomMessage = "공유방에 연결해 주세요."; private set
    var pendingAutomatic = false; private set
    var proposal: DestinationProposal? = null; private set
    var acceptedProposal: DestinationProposal? = null; private set
    var proposalBusy = false; private set
    var proposalMessage = "목적지 변경을 제안하면 다른 참여자 전원의 수락을 기다립니다."; private set
    private data class Consent(val room: String, val member: String, val proposal: String, val version: Long,
        val baseRevision: Long, val target: Destination, val deadline: Long, val issued: Boolean = false,
        val operationDeadline: Long = 0)
    private var consent: Consent? = null
    private var proposalDeadline = 0L
    val hasProposalOperation: Boolean get() = consent?.issued == true
    val hasProposalLease: Boolean get() = proposalBusy || consent?.let {
        clock() < if (it.issued) it.operationDeadline else it.deadline
    } == true
    val canPropose: Boolean get() = !closed && !busy && !leaving && !proposalBusy && state.roomConnected &&
        proposal?.status != "pending" && consent == null && !state.sharing &&
        state.applying !in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING)
    fun canRespond(id: String, version: Long): Boolean {
        val p = proposal ?: return false; val identity = credentials ?: return false
        return !closed && !busy && !leaving && !proposalBusy && state.roomConnected && p.id == id &&
            p.version == version && p.status == "pending" && clock() < proposalDeadline &&
            p.responses.any { it.member.id == identity.memberId && it.response == "pending" }
    }
    private var started = false
    private var closed = false
    private var lifecycleEpoch = 0L
    private var feedSerial = 0L
    private var feedRunning = AtomicBoolean(false)
    private var client: RoomHttpClient? = null
    private var temporaryClient: RoomHttpClient? = null
    private var lastSequence = -1L
    private val publicationGeneration = AtomicLong()

    fun observe(observer: (VehicleSession) -> Unit) { observers.add(observer); observer(this) }
    fun removeObserver(observer: (VehicleSession) -> Unit) { observers.remove(observer) }
    private fun emit() { observers.toList().forEach { it(this) } }
    fun start() {
        if (started || closed) return
        started = true
        val loaded = storage.load()
        if (loaded.damaged) roomMessage = "보안 저장 정보를 읽을 수 없어 지웠습니다. 다시 참여해 주세요."
        val awaitingAuth = loaded.credentials?.let { PendingConfirmationCache.hasFor(it.roomId, it.memberId) } == true
        if (!awaitingAuth) { PendingConfirmationCache.clear(); coordinator.connect() }
        loaded.credentials?.let(::resumeRoom)
        emit()
    }
    private fun ui(epoch: Long, action: () -> Unit) = dispatch { if (!closed && epoch == lifecycleEpoch) action() }
    private fun stopFeed() {
        feedSerial += 1; feedRunning.set(false)
        client?.close(); client = null
    }

    fun submit(join: Boolean, base: String, name: String, roomId: String, secret: String) {
        if (closed || busy || credentials != null) return
        if (runCatching { validateBaseUrl(base) }.isFailure || name.isBlank() || name.length > 40 ||
            (join && (!RoomCredentials.ID.matches(roomId) || !RoomCredentials.SECRET.matches(secret)))) {
            roomMessage = "로컬 주소·이름을 확인해 주세요. 참여 시 공유방 ID와 초대 비밀도 필요합니다."; emit(); return
        }
        busy = true; roomMessage = if (join) "공유방 참여 중" else "공유방 만드는 중"; emit()
        val epoch = lifecycleEpoch
        val api = RoomHttpClient(base); temporaryClient = api
        workers.execute {
            val result = runCatching { if (join) api.join(roomId, secret, name) else api.create(name) }
            ui(epoch) {
                temporaryClient = null; busy = false
                result.fold(onSuccess = { (identity, snapshot) ->
                    try { storage.save(identity); attach(identity, snapshot, api) }
                    catch (_: Exception) {
                        roomMessage = "보안 저장 실패 · 연결하지 않았습니다. 서버 정리를 시도합니다."
                        workers.execute { runCatching { api.leave(identity) }; api.close() }
                    }
                }, onFailure = { roomMessage = failureMessage(it); api.close() })
                emit()
            }
        }
    }

    fun reconnectRoom() {
        if (closed || busy || leaving) return
        credentials?.let { stopFeed(); coordinator.suspendRoom(consent?.issued == true); resumeRoom(it) }
    }
    private fun resumeRoom(identity: RoomCredentials) {
        credentials = identity; busy = true; roomMessage = "저장한 공유방 인증 확인 중"; emit()
        val epoch = lifecycleEpoch
        val api = RoomHttpClient(identity.baseUrl); temporaryClient = api
        workers.execute {
            val result = runCatching { api.snapshot(identity) }
            ui(epoch) {
                temporaryClient = null; busy = false
                result.fold(onSuccess = { attach(identity, it, api) }, onFailure = {
                    api.close(); roomMessage = failureMessage(it)
                    pendingAutomatic = false
                    if (it is RoomFailure && it.status in listOf(401, 403, 404)) forget(false)
                })
                emit()
            }
        }
    }
    private fun attach(identity: RoomCredentials, snapshot: RoomSnapshot, api: RoomHttpClient) {
        require(identity.role == snapshot.role)
        val keep = credentials === identity && (state.automatic != AutomaticMode.OFF || consent?.issued == true)
        stopFeed(); credentials = identity; client = api; lastSequence = -1
        protectFrame(snapshot.update) // Fence old leader observations before attach/connect emits callbacks.
        coordinator.attachRoom(snapshot.role == "host", snapshot.update.revision, snapshot.update.destination, keep)
        consume(snapshot.update)
        val pending = PendingConfirmationCache.takeFor(identity.roomId, identity.memberId)
        if (pending != null) {
            if (!coordinator.resumeConfirmation(pending) && state.connection == NativeConnection.DISCONNECTED) coordinator.connect()
        } else if (state.connection == NativeConnection.DISCONNECTED) coordinator.connect()
        roomMessage = "${if (snapshot.role == "host") "대표 차량" else "참여 차량"} · 공유방 연결됨"
        startFeed(identity, api)
        if (pendingAutomatic) { pendingAutomatic = false; coordinator.startAutomatic() }
    }
    private fun startFeed(identity: RoomCredentials, api: RoomHttpClient) {
        val running = AtomicBoolean(true); feedRunning = running
        val serial = feedSerial; val epoch = lifecycleEpoch
        fun deliver(action: () -> Unit) = ui(epoch) {
            if (running.get() && serial == feedSerial && credentials === identity) action()
        }
        workers.execute {
            var wait = 500L
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    api.events(identity) { event, body ->
                        when (event) {
                            "snapshot", "destination", "proposal", "members" -> {
                                val snapshot = if (event == "snapshot") RoomProtocol.snapshot(body) else null
                                val update = snapshot?.update ?: RoomProtocol.update(body)
                                deliver {
                                    if (snapshot != null && !state.roomConnected) {
                                        require(snapshot.role == identity.role)
                                        protectFrame(update)
                                        coordinator.attachRoom(snapshot.role == "host", update.revision, update.destination, true)
                                    }
                                    consume(update)
                                    roomMessage = "${if (identity.role == "host") "대표 차량" else "참여 차량"} · 목적지 수신 연결됨"; emit()
                                }
                                wait = 500
                            }
                            "closed" -> deliver { roomMessage = "공유방이 종료되었거나 참여 권한이 해제되었습니다."; forget(false) }
                        }
                    }
                    if (running.get()) deliver { connectionLost() }
                } catch (error: Exception) {
                    if (running.get()) deliver {
                        if (error is RoomFailure && error.status in listOf(401, 403, 404)) {
                            roomMessage = failureMessage(error); forget(false)
                        } else connectionLost()
                    }
                }
                if (running.get()) {
                    try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
                    wait = (wait * 2).coerceAtMost(4_000)
                }
            }
        }
    }
    private fun connectionLost() {
        if (state.roomConnected) coordinator.suspendRoom(consent?.issued == true)
        roomMessage = "공유방 연결 끊김 · 재연결 중. 마지막 받은 목적지입니다."; emit()
    }
    private fun publish(roomEpoch: Long, destination: Destination?) {
        val identity = credentials ?: return; val api = client ?: return
        val expectedRevision = state.revision; val epoch = lifecycleEpoch
        val generation = publicationGeneration.get()
        workers.execute {
            if (generation != publicationGeneration.get()) return@execute
            val result = runCatching { api.publish(identity, destination, expectedRevision) }
            ui(epoch) {
                if (credentials !== identity || coordinator.roomEpoch != roomEpoch || generation != publicationGeneration.get()) return@ui
                result.fold(onSuccess = { coordinator.shareFinished(roomEpoch, it.revision, it.destination, true) }, onFailure = {
                    coordinator.shareFinished(roomEpoch, 0, null, false); roomMessage = failureMessage(it)
                    if (it is RoomFailure && it.status == 409) {
                        stopFeed(); coordinator.suspendRoom(consent?.issued == true); resumeRoom(identity)
                    } else if (it is RoomFailure && it.status in listOf(401, 403, 404)) forget(false)
                })
                emit()
            }
        }
    }

    private fun holdPublication(acceptedRevision: Long? = null) {
        publicationGeneration.incrementAndGet()
        coordinator.protectProposal(acceptedRevision)
    }

    private fun protectFrame(update: DestinationUpdate) {
        val p = update.proposal
        val accepted = update.acceptedProposal ?: p?.takeIf {
            it.status == "accepted" && it.acceptedRevision == update.revision && it.destination == update.destination
        }
        // Replay authority is independent of whether publication protection already completed.
        if (accepted != null) coordinator.rememberAcceptedRevision(update.revision)
        // Retained provenance must not re-arm protection already completed/reset in this process.
        if (accepted != null && (acceptedProposal?.id != accepted.id || acceptedProposal?.acceptedRevision != accepted.acceptedRevision)) {
            holdPublication(update.revision)
        }
        if (p?.status == "pending") holdPublication()
    }

    /** HTTP and SSE share one monotonic reducer; accepted metadata and destination are atomic. */
    private fun consume(update: DestinationUpdate) {
        if (update.sequence <= lastSequence) return
        lastSequence = update.sequence
        protectFrame(update)
        if (update.hasProposal) {
            val old = proposal
            proposal = update.proposal
            proposalDeadline = update.proposal?.let { p ->
                val remaining = (p.expiresAt - update.serverTime).coerceIn(0, 120_000)
                if (old?.id == p.id && proposalDeadline > 0) minOf(proposalDeadline, clock() + remaining) else clock() + remaining
            } ?: 0
        }
        acceptedProposal = if (update.hasAcceptedProposal) update.acceptedProposal else
            update.proposal?.takeIf { it.status == "accepted" && it.acceptedRevision == update.revision && it.destination == update.destination }
                ?: acceptedProposal?.takeIf { it.acceptedRevision == update.revision && it.destination == update.destination }
        coordinator.receive(coordinator.roomEpoch, update.revision, update.destination)
        val p = proposal
        val authority = consent
        val identity = credentials
        val sameIssuedTarget = authority?.issued == true && state.revision == authority.baseRevision + 1 && state.shared == authority.target
        if (authority != null && (identity == null || identity.roomId != authority.room || identity.memberId != authority.member ||
            (authority.issued && !sameIssuedTarget) || (!sameIssuedTarget && p != null &&
                (p.id != authority.proposal || p.baseRevision != authority.baseRevision || p.destination != authority.target || p.version < authority.version)))) {
            consent = null
        }
        when (p?.status) {
            "pending" -> proposalMessage = if (p.responses.any { it.member.id == identity?.memberId && it.response == "accepted" })
                "제안 대기 · 전원 수락 전에는 순정 안내를 유지합니다." else "새 목적지 제안 · 수락 또는 거절해 주세요."
            "accepted" -> {
                val live = consent
                val currentCommit = p.acceptedRevision == update.revision && p.destination == update.destination
                proposalMessage = "서버 수락 확정 · 실제 순정 목적지 일치 확인 전"
                if (currentCommit && live != null && live.proposal == p.id && !live.issued && clock() < live.deadline &&
                    p.responses.any { it.member.id == identity?.memberId && it.response == "accepted" }) {
                    // Consume authority before SDK callbacks or a duplicate HTTP/SSE frame can run.
                    consent = live.copy(issued = true, operationDeadline = clock() + 45_000)
                    evidence("proposalApply", coordinator.roomEpoch, update.revision, null)
                    coordinator.applyShared()
                } else if (live == null || !currentCommit) {
                    consent = null
                    proposalMessage = if (currentCommit) "과거 수락 확정 · 자동 재요청 없음 · 필요하면 직접 다시 적용해 주세요."
                        else "이전 제안 수락 기록 · 현재 공유 목적지와 다릅니다."
                }
            }
            "rejected", "expired", "invalidated" -> {
                if (consent?.issued != true) consent = null
                coordinator.releasePendingProposalProtection()
                proposalMessage = when (p.status) {
                    "rejected" -> "제안 거절 · 현재 순정 안내 유지"
                    "expired" -> "120초 미응답 · 제안 만료 · 현재 순정 안내 유지"
                    else -> "제안 무효 · 참여자 또는 공유 목적지 변경 · 현재 순정 안내 유지"
                }
            }
        }
        emit()
    }

    fun propose(target: Destination, reason: String) {
        val identity = credentials ?: return; val api = client ?: return
        if (!canPropose || !target.canRequestRoute || reason.isBlank() || reason.length > 200 || reason.any { it.code < 32 || it.code == 127 }) return
        val id = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val revision = state.revision
        proposalBusy = true
        consent = Consent(identity.roomId, identity.memberId, id, 1, revision, target, clock() + 120_000)
        proposalMessage = "제안 보내는 중 · 순정 안내 유지"
        holdPublication(); emit()
        proposalRequest(identity, { api.propose(identity, id, target, reason.trim(), revision) })
    }

    fun respond(id: String, version: Long, accept: Boolean) {
        if (!canRespond(id, version)) return
        val p = proposal ?: return; val identity = credentials ?: return; val api = client ?: return
        proposalBusy = true
        if (accept) consent = Consent(identity.roomId, identity.memberId, p.id, p.version, p.baseRevision, p.destination, proposalDeadline)
        proposalMessage = if (accept) "수락 보내는 중 · 전원 동의 확인 전" else "거절 보내는 중 · 순정 안내 유지"
        holdPublication(); emit()
        proposalRequest(identity, { api.respond(identity, p, accept) })
    }

    private fun proposalRequest(identity: RoomCredentials, request: () -> DestinationUpdate) {
        val epoch = lifecycleEpoch
        workers.execute {
            val result = runCatching(request)
            ui(epoch) {
                if (credentials !== identity) return@ui
                result.fold(onSuccess = { consume(it) }, onFailure = {
                    proposalMessage = "제안 응답 확인 실패 · ${failureMessage(it)}"
                    if (it is RoomFailure && it.status in listOf(401, 403, 404)) { consent = null; forget(false) }
                    else if (it is RoomFailure && it.status in listOf(400, 409, 413, 429)) {
                        consent = null
                        proposalBusy = false
                        reconnectRoom()
                    }
                })
                proposalBusy = false; emit()
            }
        }
    }

    fun retryAcceptedProposal() {
        val p = acceptedProposal ?: return; val identity = credentials ?: return
        if (closed || busy || leaving || proposalBusy || !state.roomConnected || p.status != "accepted" ||
            p.acceptedRevision != state.revision || p.destination != state.shared || hasProposalLease || proposal?.status == "pending") return
        consent = Consent(identity.roomId, identity.memberId, p.id, p.version, p.baseRevision, p.destination,
            clock(), issued = true, operationDeadline = clock() + 45_000)
        holdPublication(state.revision)
        proposalMessage = "직접 다시 적용 · 실제 순정 목적지 일치 확인 전"
        coordinator.applyShared(); emit()
    }

    fun resetProposalProtection() {
        if (hasProposalLease || proposal?.status == "pending") return
        coordinator.releaseProposalProtection(); proposalMessage = "자동 공유 재개 · 이후 대표의 실제 순정 목적지 변경을 다시 공유합니다."; emit()
    }

    fun startAutomatic() {
        if (closed || credentials == null || leaving || state.automatic != AutomaticMode.OFF || pendingAutomatic) return
        PendingConfirmationCache.clear()
        if (state.roomConnected) coordinator.startAutomatic()
        else { pendingAutomatic = true; reconnectRoom() }
        emit()
    }
    /** Fences pending I/O before closing transports, SDK listeners, timers, and consent. */
    fun stopAutomatic() {
        if (closed) return
        lifecycleEpoch += 1; pendingAutomatic = false
        consent = null; proposalBusy = false; proposalDeadline = 0
        proposalMessage = "동행 중지 · 제안 동의와 적용 권한 종료 · 순정 안내 유지"
        stopFeed(); temporaryClient?.close(); temporaryClient = null
        busy = false; leaving = false
        PendingConfirmationCache.clear(); coordinator.stopAutomatic(); coordinator.detachRoom()
        roomMessage = "자동 동행 중지 · 다시 시작하려면 명시적으로 선택해 주세요."; emit()
    }
    fun leave() {
        val identity = credentials ?: return
        if (closed || leaving) return
        stopAutomatic(); leaving = true; roomMessage = "공유방 나가는 중 · 순정 안내는 유지됩니다."; emit()
        val api = RoomHttpClient(identity.baseUrl); temporaryClient = api; val epoch = lifecycleEpoch
        workers.execute {
            val result = runCatching { api.leave(identity) }; api.close()
            ui(epoch) {
                temporaryClient = null; leaving = false
                if (credentials !== identity) return@ui
                result.fold(onSuccess = { roomMessage = "공유방 나가기 완료"; forget(false) }, onFailure = {
                    if (it is RoomFailure && it.status in listOf(401, 403, 404)) { roomMessage = "공유방 권한 종료 확인"; forget(false) }
                    else { roomMessage = "서버 나가기 확인 실패 · 다시 시도하거나 이 기기 연결을 지워 주세요."; emit() }
                })
            }
        }
    }
    fun forget(localOnly: Boolean) {
        val reason = roomMessage
        stopAutomatic(); storage.clear(); credentials = null; proposal = null; acceptedProposal = null; proposalDeadline = 0
        coordinator.forgetRoomProposalHistory()
        roomMessage = reason
        if (localOnly) roomMessage = "이 기기 인증 정보를 지웠습니다. 서버 권한은 종료 또는 최대 6시간 만료 전까지 남을 수 있습니다."
        emit()
    }
    fun tick() {
        if (closed) return
        coordinator.tick()
        consent?.let { live ->
            val deadline = if (live.issued) live.operationDeadline else live.deadline
            if (clock() >= deadline) {
                consent = null
                if (live.issued && state.applying == ApplyPhase.CONFIRMED) coordinator.completeAcceptedProposalProtection(live.baseRevision + 1)
                if (!live.issued) proposalMessage = "제안 동의 기한 만료 · 순정 안내 유지 · 서버 상태를 기다립니다."
                emit()
            }
        }
    }
    fun close() {
        if (closed) return
        if (state.automatic == AutomaticMode.OFF && consent?.issued != true) coordinator.confirmationSnapshot()?.let { pending ->
            credentials?.let { PendingConfirmationCache.save(it.roomId, it.memberId, pending) }
        }
        closed = true; lifecycleEpoch += 1; pendingAutomatic = false; consent = null; proposalBusy = false
        stopFeed(); temporaryClient?.close(); temporaryClient = null
        coordinator.stop(); observers.clear(); workers.shutdownNow()
    }
    private fun failureMessage(error: Throwable): String = when (error) {
        is RoomFailure -> when (error.status) {
            401, 403 -> "공유방 인증 또는 대표 권한을 확인해 주세요."
            404 -> "공유방이 종료되었거나 만료되었습니다."
            409 -> "더 최신 목적지가 있습니다. 다시 조회합니다."
            429 -> "공유방 연결 상한입니다. 잠시 후 직접 다시 시도해 주세요."
            else -> "공유방 요청 실패 (${error.status}) · 다시 시도해 주세요."
        }
        else -> "로컬 서버 연결 실패 · 주소와 연결 상태를 확인해 주세요."
    }
}
