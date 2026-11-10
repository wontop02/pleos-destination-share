package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import ai.pleos.destinations.core.VehicleCoordinator
import ai.pleos.destinations.core.VehicleState
import java.security.SecureRandom
import java.util.concurrent.ExecutorService

/** A separate request stream: receipt never becomes shared-destination/automatic consent. */
class DestinationRequests(
    gateway: NavigationGateway, private val clock: () -> Long,
    private val workers: ExecutorService, private val dispatch: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val evidence: (String, Long, Long, Boolean?) -> Unit = { _, _, _, _ -> },
) {
    val navigation = VehicleCoordinator(gateway, clock, ::nativeChanged, { _, _ -> error("Request controller cannot publish legacy destinations") },
        { event, epoch, operation, matched -> evidence("request:$event", epoch, operation, matched) })
    private var identity: RoomCredentials? = null
    private var client: RoomHttpClient? = null
    private var connected = false
    private var epoch = 0L
    private var closed = false
    private var nativeUsed = false
    private var lastSequence = -1L
    private var requestRevision = 0L
    private var deadline = 0L
    private var expiredLocally = false
    private var suppressedThrough = 0L
    private var previewAt = 0L
    private var queryStarted = 0L
    private var querying = false
    private var sendAfterRead: Destination? = null
    private var accepting: String? = null
    private var issued: String? = null
    private var operationDeadline = 0L
    var request: DestinationChangeRequest? = null; private set
    var preview: Destination? = null; private set
    var listening = false; private set
    var busy = false; private set
    var message = "목적지를 정한 뒤 다른 차량에 변경 요청을 보내세요."; private set
    val state: VehicleState get() = navigation.state
    val applying: Boolean get() = state.applying in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING)
    val hasLease: Boolean get() = listening || busy || clock() < operationDeadline
    val canPreview: Boolean get() = !closed && connected && client != null && !busy && !querying && !applying
    val canSend: Boolean get() = canPreview && preview != null && clock() - previewAt in 0..15_000
    val ownResponse: String? get() = request?.recipients?.firstOrNull { it.member.id == identity?.memberId }?.response
    val hasLocalAcceptance: Boolean get() = request != null && issued == request?.id
    val pending: DestinationChangeRequest? get() = request?.takeIf {
        connected && it.status == "active" && it.revision > suppressedThrough && clock() < deadline && ownResponse == "pending"
    }
    fun canRespond(id: String): Boolean = !closed && !busy && !applying && pending?.id == id

    fun scope(value: RoomCredentials?, authenticated: Boolean, api: RoomHttpClient?) {
        val same = identity?.let { old -> value != null && old.roomId == value.roomId && old.memberId == value.memberId &&
            old.baseUrl == value.baseUrl && old.token == value.token && old.role == value.role } ?: (value == null)
        val wasConnected = connected
        identity = value; connected = authenticated && value != null; client = api
        if (!same) {
            epoch += 1; resetNative(); request = null; requestRevision = 0; lastSequence = -1
            listening = false; suppressedThrough = 0; accepting = null; issued = null; deadline = 0
        } else if (wasConnected && !authenticated) {
            epoch += 1; resetNative(); accepting = null; issued = null
        }
    }

    fun consume(value: DestinationChangeRequest?, revision: Long, sequence: Long, serverTime: Long) {
        if (closed || sequence <= lastSequence) return
        lastSequence = sequence; requestRevision = revision
        val old = request
        // Expiry closes new response authority. An already accepted native operation keeps
        // its original 45-second verification bound; replacement/invalidated requests do not.
        val acceptedExpiry = value != null && old?.id == value.id && issued == value.id &&
            value.status == "expired" && value.recipients.any { it.member.id == identity?.memberId && it.response == "accepted" }
        if (old?.id != value?.id) expiredLocally = false
        if (old?.id != value?.id || (value != null && value.status != "active" && !acceptedExpiry)) {
            resetNative(); accepting = null; issued = null
        }
        request = value
        val remaining = value?.let { (it.expiresAt - serverTime).coerceIn(0, 120_000) } ?: 0
        deadline = if (old?.id == value?.id && deadline > 0) minOf(deadline, clock() + remaining) else clock() + remaining
        if (value != null && accepting == value.id && issued != value.id && ownResponse == "accepted" &&
            connected && value.status == "active" && clock() < deadline && value.revision > suppressedThrough) {
            issued = value.id; accepting = null; operationDeadline = clock() + 45_000
            nativeUsed = true
            navigation.attachRoom(false, value.revision, value.destination)
            evidence("request:explicitApply", navigation.roomEpoch, value.revision, null)
            navigation.applyShared()
            message = "수락했어요 · 내비에서 목적지를 확인하고 있어요."
        } else if (old?.id != value?.id && value != null) {
            message = if (value.sender.id == identity?.memberId) "다른 차량에 변경 요청을 보냈어요."
                else "새 목적지 변경 요청이 왔어요. 이름과 주소를 확인해 주세요."
        }
        changed()
    }

    fun startListening() {
        if (closed || identity == null) return
        listening = true; message = "변경 요청 알림을 받고 있어요. 수락 전에는 내비를 바꾸지 않아요."; changed()
    }
    fun stop() {
        if (closed) return
        epoch += 1; listening = false; suppressedThrough = requestRevision; accepting = null; issued = null
        resetNative(); message = "변경 요청 수신을 중지했어요. 현재 안내는 유지해요."; changed()
    }
    private fun resetNative() {
        preview = null; previewAt = 0; querying = false; sendAfterRead = null; busy = false; operationDeadline = 0
        if (nativeUsed) { nativeUsed = false; navigation.stopAutomatic(); navigation.detachRoom() }
    }
    fun invalidatePreview() {
        preview = null; previewAt = 0
        if (querying) {
            epoch += 1; querying = false; sendAfterRead = null; busy = false
            if (nativeUsed) { nativeUsed = false; navigation.stopAutomatic() }
        }
    }
    fun refreshCurrent() {
        if (!canPreview) return
        beginRead(null)
    }
    private fun beginRead(expected: Destination?) {
        preview = null; querying = true; sendAfterRead = expected; queryStarted = clock()
        busy = expected != null
        message = "내비에서 설정한 목적지를 새로 확인하고 있어요."
        nativeUsed = true; navigation.connect(); changed()
    }
    private fun nativeChanged(state: VehicleState) {
        if (closed) return
        val current = state.current
        if (querying && current is CurrentDestination.Present && current.observedAt >= queryStarted) {
            querying = false
            val expected = sendAfterRead; sendAfterRead = null
            if (!current.value.canRequestRoute || current.value.pleos?.address.isNullOrBlank()) {
                preview = null; busy = false; message = "보낼 수 있는 목적지 정보를 읽지 못했어요. 내비에서 다시 확인해 주세요."
            } else if (expected != null && expected == current.value) {
                send(current.value)
            } else {
                busy = false; preview = current.value; previewAt = clock()
                message = if (expected == null) "이름과 주소를 확인한 뒤 변경 요청을 보내세요."
                    else "목적지가 바뀌었어요. 새 이름과 주소를 확인한 뒤 다시 보내세요."
            }
        } else if (querying && (current is CurrentDestination.Failed || current is CurrentDestination.Absent)) {
            querying = false; sendAfterRead = null; busy = false; preview = null
            message = if (current is CurrentDestination.Absent) "내비에 목적지를 먼저 설정해 주세요." else "목적지를 읽지 못했어요. 다시 확인해 주세요."
        } else if (preview != null && current is CurrentDestination.Present && current.value != preview) {
            preview = null; message = "내비 목적지가 바뀌었어요. 다시 확인해 주세요."
        }
        changed()
    }
    fun sendCurrent() {
        if (!canSend) return
        beginRead(checkNotNull(preview)) // Never silently send a stale or newly substituted target.
    }
    private fun send(target: Destination) {
        val who = identity ?: return
        val api = client ?: return
        val requestId = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val revision = requestRevision
        message = "다른 차량에 변경 요청을 보내고 있어요."
        execute { api.requestDestinationChange(who, requestId, target, revision) }
    }
    fun respond(id: String, accept: Boolean) {
        if (!canRespond(id)) return
        val value = request ?: return; val who = identity ?: return; val api = client ?: return
        busy = true; accepting = if (accept) value.id else null
        message = if (accept) "수락을 확인하고 있어요." else "거절을 보내고 있어요. 현재 안내는 유지해요."
        execute { api.respondDestinationChange(who, value, accept) }
        changed()
    }
    private fun execute(action: () -> DestinationUpdate) {
        val generation = epoch
        workers.execute {
            val result = runCatching(action)
            dispatch {
                if (closed || generation != epoch) return@dispatch
                busy = false
                result.fold(onSuccess = {
                    if (it.hasChangeRequest) consume(it.changeRequest, it.requestRevision, it.sequence, it.serverTime)
                    else { accepting = null; message = "변경 요청을 지원하는 서버에 다시 연결해 주세요." }
                }, onFailure = {
                    accepting = null
                    message = "변경 요청을 확인하지 못했어요. 다시 연결해 최신 요청을 확인해 주세요."
                })
                changed()
            }
        }
    }
    fun tick() {
        if (closed) return
        navigation.tick()
        if (preview != null && clock() - previewAt > 15_000) {
            preview = null; message = "확인한 시간이 지났어요. 내비 목적지를 다시 확인해 주세요."; changed()
        }
        if (request?.status == "active" && clock() >= deadline && !expiredLocally) {
            expiredLocally = true; accepting = null
            if (issued != request?.id) {
                issued = null; resetNative(); message = "응답 시간이 지나 변경 요청이 끝났어요."
            }
            changed()
        }
        if (operationDeadline > 0 && clock() >= operationDeadline) { operationDeadline = 0; changed() }
    }
    fun close() { closed = true; epoch += 1; listening = false; if (nativeUsed) navigation.stop() }
}
