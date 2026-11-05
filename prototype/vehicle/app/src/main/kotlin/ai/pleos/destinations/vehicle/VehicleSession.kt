package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleCoordinator
import ai.pleos.destinations.core.VehicleState
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

interface SessionStorage {
    fun load(): LoadedSession
    fun save(credentials: RoomCredentials)
    fun clear()
}
data class LoadedSession(val credentials: RoomCredentials?, val damaged: Boolean)

/** Single service-owned runtime. Every state mutation/callback is serialized on the owner thread. */
class VehicleSession(
    gateway: NavigationGateway,
    clock: () -> Long,
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
    private var started = false
    private var closed = false
    private var lifecycleEpoch = 0L
    private var feedSerial = 0L
    private var feedRunning = AtomicBoolean(false)
    private var client: RoomHttpClient? = null
    private var temporaryClient: RoomHttpClient? = null
    private var lastSequence = -1L

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
        credentials?.let { stopFeed(); coordinator.suspendRoom(); resumeRoom(it) }
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
        val keep = credentials === identity && state.automatic != AutomaticMode.OFF
        stopFeed(); credentials = identity; client = api; lastSequence = snapshot.update.sequence
        coordinator.attachRoom(snapshot.role == "host", snapshot.update.revision, snapshot.update.destination, keep)
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
                            "snapshot", "destination" -> {
                                val snapshot = if (event == "snapshot") RoomProtocol.snapshot(body) else null
                                val update = snapshot?.update ?: RoomProtocol.update(body)
                                deliver {
                                    if (snapshot != null && !state.roomConnected) {
                                        require(snapshot.role == identity.role)
                                        coordinator.attachRoom(snapshot.role == "host", update.revision, update.destination, true)
                                        lastSequence = update.sequence
                                    } else if (update.sequence > lastSequence) {
                                        lastSequence = update.sequence
                                        coordinator.receive(coordinator.roomEpoch, update.revision, update.destination)
                                    }
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
        if (state.roomConnected) coordinator.suspendRoom()
        roomMessage = "공유방 연결 끊김 · 재연결 중. 마지막 받은 목적지입니다."; emit()
    }
    private fun publish(roomEpoch: Long, destination: Destination?) {
        val identity = credentials ?: return; val api = client ?: return
        val expectedRevision = state.revision; val epoch = lifecycleEpoch
        workers.execute {
            val result = runCatching { api.publish(identity, destination, expectedRevision) }
            ui(epoch) {
                if (credentials !== identity || coordinator.roomEpoch != roomEpoch) return@ui
                result.fold(onSuccess = { coordinator.shareFinished(roomEpoch, it.revision, it.destination, true) }, onFailure = {
                    coordinator.shareFinished(roomEpoch, 0, null, false); roomMessage = failureMessage(it)
                    if (it is RoomFailure && it.status == 409) {
                        stopFeed(); coordinator.suspendRoom(); resumeRoom(identity)
                    } else if (it is RoomFailure && it.status in listOf(401, 403, 404)) forget(false)
                })
                emit()
            }
        }
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
        stopAutomatic(); storage.clear(); credentials = null
        roomMessage = reason
        if (localOnly) roomMessage = "이 기기 인증 정보를 지웠습니다. 서버 권한은 종료 또는 최대 6시간 만료 전까지 남을 수 있습니다."
        emit()
    }
    fun tick() { if (!closed) coordinator.tick() }
    fun close() {
        if (closed) return
        if (state.automatic == AutomaticMode.OFF) coordinator.confirmationSnapshot()?.let { pending ->
            credentials?.let { PendingConfirmationCache.save(it.roomId, it.memberId, pending) }
        }
        closed = true; lifecycleEpoch += 1; pendingAutomatic = false
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
