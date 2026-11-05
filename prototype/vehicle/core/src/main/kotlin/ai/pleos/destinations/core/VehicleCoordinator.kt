package ai.pleos.destinations.core

enum class NativeConnection { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
sealed class CurrentDestination {
    data object Unknown : CurrentDestination()
    data object Loading : CurrentDestination()
    data class Present(val value: Destination, val observedAt: Long) : CurrentDestination()
    data class Absent(val observedAt: Long) : CurrentDestination()
    data class Failed(val reason: String) : CurrentDestination()
}
enum class AutomaticMode { OFF, LEADER, FOLLOWER }
enum class ApplyPhase { IDLE, CONNECTING, REQUESTED, ROUTING, VERIFYING, CONFIRMED, FAILED, TIMED_OUT, CANCELLED }
data class PendingRouteConfirmation(val id: Long, val revision: Long, val target: Destination,
    val deadline: Long, val routingCompletedObserved: Boolean)
data class VehicleState(
    val connection: NativeConnection = NativeConnection.DISCONNECTED,
    val current: CurrentDestination = CurrentDestination.Unknown,
    val roomConnected: Boolean = false,
    val host: Boolean = false,
    val shared: Destination? = null,
    val revision: Long = -1,
    val applying: ApplyPhase = ApplyPhase.IDLE,
    val message: String = "순정 내비에 연결해 주세요.",
    val sharing: Boolean = false,
    val confirmedAt: Long? = null,
    val routingCompletedObserved: Boolean = false,
    val automatic: AutomaticMode = AutomaticMode.OFF,
)

/**
 * The SDK has no request IDs or source timestamps. Keep one read in flight; discard a read
 * crossed by a change event. Retire the SDK listener session on timeout, cancellation,
 * supersession, or room change. Routing callbacks only trigger a subsequent real read.
 */
class VehicleCoordinator(
    private val gateway: NavigationGateway,
    private val clock: () -> Long,
    private val changed: (VehicleState) -> Unit,
    private val publish: (roomEpoch: Long, destination: Destination?) -> Unit,
    private val evidence: (event: String, nativeEpoch: Long, operation: Long, matched: Boolean?) -> Unit = { _, _, _, _ -> },
) {
    var state = VehicleState(); private set
    var roomEpoch = 0L; private set
    private var nativeEpoch = 0L
    private var nextId = 0L
    private var connectDeadline = 0L
    private var read: Read? = null
    private var operation: Operation? = null
    private var lastConfirmedRevision = -1L
    private var lastConfirmation: PendingRouteConfirmation? = null
    private var disposed = false
    private var autoDue = Long.MAX_VALUE
    private var autoObserveAt = Long.MAX_VALUE
    private var autoAttemptedRevision = -1L
    private var reconnectPending: PendingRouteConfirmation? = null
    private var observedForPublish: Destination? = null
    private var hasObservedForPublish = false
    private var pendingProposalHeld = false
    val proposalPublicationHeld: Boolean get() = pendingProposalHeld || proposalRevision >= 0
    private var proposalRevision = -1L
    private enum class Purpose { OBSERVE, SHARE, VERIFY }
    private data class Read(val id: Long, val purpose: Purpose, val room: Long, val deadline: Long, var dirty: Boolean = false)
    private data class Operation(val id: Long, val room: Long, val revision: Long, val target: Destination,
        val deadline: Long, var requested: Boolean = false, var nextPollAt: Long = Long.MAX_VALUE,
        val resumeOnly: Boolean = false, val earlierCompletionObserved: Boolean = false)

    private fun update(next: VehicleState) { state = next; changed(next) }
    private fun trace(event: String, matched: Boolean? = null) = evidence(event, nativeEpoch, operation?.id ?: 0, matched)
    private fun retire() {
        nativeEpoch += 1 // Fence queued callbacks before asking the SDK to remove the listener.
        read = null
        gateway.release()
    }

    /** Called before processing a pending/accepted room frame, including the initial snapshot. */
    fun protectProposal(acceptedRevision: Long? = null) {
        if (acceptedRevision == null) pendingProposalHeld = true else pendingProposalHeld = false
        hasObservedForPublish = false
        // The HTTP request may already exist, but it cannot keep a new explicit apply blocked.
        // Its revision CAS and the session's publication generation fence its eventual result.
        if (state.sharing) update(state.copy(sharing = false))
        if (acceptedRevision != null) {
            proposalRevision = acceptedRevision
            autoAttemptedRevision = acceptedRevision // Only the bound explicit consent may apply it.
        }
        trace("proposalPublicationHeld")
    }

    /** Success window ended or the user explicitly reset protection; take a fresh native read. */
    fun releaseProposalProtection() {
        pendingProposalHeld = false; proposalRevision = -1
        hasObservedForPublish = false; autoObserveAt = clock()
        trace("proposalPublicationReleased")
    }

    /** A later unsuccessful candidate cannot release an earlier accepted target's protection. */
    fun releasePendingProposalProtection() {
        pendingProposalHeld = false
        hasObservedForPublish = false; autoObserveAt = clock()
        trace("pendingProposalReleased")
    }

    /** Complete only this successful accepted operation; a newer pending hold stays active. */
    fun completeAcceptedProposalProtection(revision: Long) {
        if (proposalRevision != revision) return
        proposalRevision = -1
        hasObservedForPublish = false; autoObserveAt = clock()
        trace("acceptedProposalReleased")
    }

    /** Consent is volatile and role-bound; persistence/UI attachment cannot call this implicitly. */
    fun startAutomatic(): Boolean {
        if (disposed || !state.roomConnected || state.automatic != AutomaticMode.OFF) return false
        autoAttemptedRevision = proposalRevision
        hasObservedForPublish = false
        update(state.copy(automatic = if (state.host) AutomaticMode.LEADER else AutomaticMode.FOLLOWER,
            message = if (state.host) "동행 시작 · 순정 목적지 자동 공유 중" else "자동 따르기 시작 · 최신 목적지를 자동 적용합니다."))
        trace("automaticStart")
        autoDue = clock() + 300
        autoObserveAt = clock()
        if (state.connection == NativeConnection.DISCONNECTED || state.connection == NativeConnection.FAILED) startSession()
        return true
    }

    fun stopAutomatic() {
        if (disposed) return
        update(state.copy(automatic = AutomaticMode.OFF))
        autoDue = Long.MAX_VALUE; autoObserveAt = Long.MAX_VALUE
        reconnectPending = null; hasObservedForPublish = false
        operation = null; lastConfirmation = null; lastConfirmedRevision = -1
        retire()
        update(state.copy(connection = NativeConnection.DISCONNECTED, current = CurrentDestination.Unknown,
            applying = ApplyPhase.CANCELLED, sharing = false, confirmedAt = null,
            message = "자동 동행 중지 · 순정 안내는 유지됩니다."))
        trace("automaticStop")
    }

    fun connect() {
        if (disposed || operation != null) return
        startSession()
    }

    private fun startSession() {
        retire()
        val epoch = nativeEpoch
        connectDeadline = minOf(clock() + 15_000, operation?.deadline ?: Long.MAX_VALUE)
        update(state.copy(connection = NativeConnection.CONNECTING, current = CurrentDestination.Unknown,
            applying = if (state.applying == ApplyPhase.CONFIRMED) ApplyPhase.IDLE else state.applying, confirmedAt = null,
            routingCompletedObserved = false,
            message = "순정 내비 연결 중"))
        val listener = object : NavigationGateway.Listener {
            private fun active() = !disposed && nativeEpoch == epoch
            override fun connected() { if (active()) onConnected() }
            override fun failed() { if (active()) nativeFailure("순정 내비 연결 실패 · Pleos 서비스와 권한을 확인해 주세요.") }
            override fun destinationChanged() { if (active()) onChange() }
            override fun destinationInfo(destination: Destination) { if (active()) onInfo(destination) }
            override fun destinationAbsent() { if (active()) onAbsent() }
            override fun invalidDestination() { if (active()) nativeFailure("목적지 응답을 해석할 수 없습니다. 목적지 없음으로 처리하지 않았습니다.") }
            override fun routeStarted() {
                if (active() && operation?.requested == true) {
                    trace("onRouteStarted")
                    if (state.applying == ApplyPhase.REQUESTED && !state.routingCompletedObserved) {
                        update(state.copy(applying = ApplyPhase.ROUTING, message = "순정 경로 탐색 중 · 목적지 일치 확인 전"))
                    }
                }
            }
            override fun routingCompleted() {
                if (active()) {
                    trace("onRoutingCompleted")
                    if (operation?.requested == true) {
                        update(state.copy(routingCompletedObserved = true))
                        verify()
                    }
                }
            }
            override fun routeCancelled() { if (active()) onCancelled() }
            override fun error(code: String) {
                if (active()) { trace("onError:$code"); nativeFailure("순정 내비 오류: $code · 다시 연결해 주세요.") }
            }
        }
        try { gateway.connect(listener) }
        catch (_: Exception) { if (nativeEpoch == epoch) nativeFailure("순정 내비 초기화 실패 · 다시 연결해 주세요.") }
    }

    private fun onConnected() {
        if (clock() >= connectDeadline) {
            nativeFailure("순정 내비 연결 시간 초과 · Pleos 서비스가 필요합니다.", true); return
        }
        trace("connected")
        update(state.copy(connection = NativeConnection.CONNECTED))
        val pending = operation
        if (pending == null) beginRead(Purpose.OBSERVE)
        else if (pending.resumeOnly) {
            update(state.copy(routingCompletedObserved = pending.earlierCompletionObserved))
            trace("resumeVerification")
            verify() // A fresh observation, never another route request.
        }
        else {
            pending.requested = true
            pending.nextPollAt = clock() + 2_000
            update(state.copy(applying = ApplyPhase.REQUESTED, message = "순정 내비 요청 전송 · 실제 결과 확인 전"))
            trace("requestRoute")
            try { gateway.requestRoute(pending.target) }
            catch (_: Exception) { nativeFailure("순정 내비 요청 실패 · 변경 여부를 확인하지 못했습니다.") }
        }
    }

    fun attachRoom(host: Boolean, revision: Long, destination: Destination?, keepAutomatic: Boolean = false): Long {
        require(revision >= 0)
        if (proposalRevision >= 0 && revision > proposalRevision) completeAcceptedProposalProtection(proposalRevision)
        if (!keepAutomatic && state.automatic != AutomaticMode.OFF) stopAutomatic()
        roomEpoch += 1
        invalidateApply("공유방 변경으로 이전 확인을 중지했습니다.")
        lastConfirmedRevision = -1
        update(state.copy(roomConnected = true, host = host, revision = revision, shared = destination, sharing = false))
        if (keepAutomatic) {
            val pending = reconnectPending; reconnectPending = null
            if (pending != null && pending.revision == revision && pending.target == destination) resumeConfirmation(pending)
            autoDue = clock() + 300; autoObserveAt = clock()
        }
        return roomEpoch
    }

    fun detachRoom(epoch: Long = roomEpoch) {
        if (epoch != roomEpoch) return
        if (state.automatic != AutomaticMode.OFF) stopAutomatic()
        roomEpoch += 1
        invalidateApply("공유방 연결 종료 · 순정 안내는 별도로 유지됩니다.")
        update(state.copy(roomConnected = false, host = false, shared = null, revision = -1, sharing = false))
    }

    fun suspendRoom(preserveConfirmation: Boolean = false) {
        reconnectPending = if (preserveConfirmation || state.automatic == AutomaticMode.FOLLOWER) confirmationSnapshot() else null
        roomEpoch += 1
        invalidateApply("공유방 재연결 중 · 이전 적용 확인을 중지했습니다.")
        update(state.copy(roomConnected = false, sharing = false, message = "공유방 재연결 중 · 마지막 받은 목적지"))
    }

    fun receive(epoch: Long, revision: Long, destination: Destination?) {
        if (!state.roomConnected || epoch != roomEpoch || revision <= state.revision) return
        if (proposalRevision >= 0 && revision > proposalRevision) completeAcceptedProposalProtection(proposalRevision)
        val unchanged = destination == state.shared
        if (unchanged && state.automatic == AutomaticMode.FOLLOWER) {
            operation = operation?.copy(revision = revision)
            if (lastConfirmedRevision == state.revision) lastConfirmedRevision = revision
            lastConfirmation = lastConfirmation?.copy(revision = revision)
            if (autoAttemptedRevision == state.revision) autoAttemptedRevision = revision
            update(state.copy(revision = revision))
        } else {
            invalidateApply("새 공유 목적지 수신 · 이전 확인 중지")
            update(state.copy(revision = revision, shared = destination,
                message = if (destination == null) "공유 목적지 없음 · 순정 안내 유지 · 자동 따르기 대기"
                    else if (state.automatic == AutomaticMode.FOLLOWER) "새 공유 목적지 수신 · 자동 적용 대기"
                    else "새 공유 목적지 수신 · 직접 적용해 주세요."))
            if (state.automatic == AutomaticMode.FOLLOWER) autoDue = clock() + 300
        }
        trace("roomDestination")
    }

    private fun invalidateApply(message: String) {
        lastConfirmation = null
        val hadPending = operation != null || read?.purpose == Purpose.SHARE
        if (hadPending) {
            operation = null
            retire()
            update(state.copy(connection = NativeConnection.DISCONNECTED, current = CurrentDestination.Unknown,
                applying = ApplyPhase.CANCELLED, sharing = false, confirmedAt = null, message = message))
        } else if (state.applying == ApplyPhase.CONFIRMED) {
            update(state.copy(applying = ApplyPhase.IDLE, confirmedAt = null))
        }
    }

    fun refresh() {
        if (!disposed && operation == null && read == null && state.connection == NativeConnection.CONNECTED) beginRead(Purpose.OBSERVE)
    }

    fun shareCurrent() {
        if (!state.roomConnected || !state.host || state.sharing || operation != null || read != null) return
        if (state.connection != NativeConnection.CONNECTED) {
            update(state.copy(message = "순정 내비 연결 후 현재 목적지를 조회해 공유해 주세요.")); return
        }
        releaseProposalProtection() // Explicit direct sharing replaces the agreed target.
        update(state.copy(sharing = true))
        beginRead(Purpose.SHARE)
    }

    fun clearShared() {
        if (!state.roomConnected || !state.host || state.sharing || operation != null || read != null || state.shared == null) return
        releaseProposalProtection()
        update(state.copy(sharing = true, message = "공유 목적지 지우는 중 · 순정 안내는 유지됩니다."))
        publish(roomEpoch, null)
    }

    fun shareFinished(epoch: Long, revision: Long, destination: Destination?, success: Boolean) {
        if (!state.roomConnected || epoch != roomEpoch) return
        if (proposalPublicationHeld && (!success || revision <= state.revision)) return
        if (success) receive(epoch, revision, destination)
        update(state.copy(sharing = false, message = if (success) "공유방 서버 저장 완료 · 참여 차량에 전달됨" else "공유 실패 · 순정 내비는 변경하지 않았습니다."))
        trace(if (success) "shareStored" else "shareFailed")
        if (success && state.automatic == AutomaticMode.LEADER) publishObserved()
    }

    fun applyShared() {
        val target = state.shared ?: return
        if (disposed || !state.roomConnected || operation != null || state.sharing) return
        if (!target.canRequestRoute) {
            update(state.copy(message = "순정 POI 정보가 없는 목적지입니다. 대표 차량에서 다시 공유해 주세요.")); return
        }
        if (lastConfirmedRevision == state.revision &&
            (state.current as? CurrentDestination.Present)?.value?.let { target.matchesNative(it) } == true) {
            update(state.copy(message = "이 공유 목적지는 이미 일치를 확인했습니다. 다시 요청하지 않았습니다.")); return
        }
        operation = Operation(++nextId, roomEpoch, state.revision, target, clock() + 45_000)
        if (state.automatic == AutomaticMode.FOLLOWER) autoAttemptedRevision = state.revision
        lastConfirmation = null
        update(state.copy(applying = ApplyPhase.CONNECTING, confirmedAt = null))
        startSession() // A new listener cannot accept queued callbacks from the previous session.
    }

    /** Volatile handoff only. Process loss does not reconstruct a successful attempt. */
    fun confirmationSnapshot(): PendingRouteConfirmation? {
        if (disposed) return null
        val active = operation
        if (active?.requested == true) return PendingRouteConfirmation(active.id, active.revision, active.target,
            active.deadline, state.routingCompletedObserved)
        return lastConfirmation?.takeIf { state.applying == ApplyPhase.CONFIRMED && clock() < it.deadline }
    }

    /** Caller must first authenticate the same room/member and attach its latest snapshot. */
    fun resumeConfirmation(pending: PendingRouteConfirmation): Boolean {
        if (disposed || !state.roomConnected || state.revision != pending.revision || state.shared != pending.target) {
            update(state.copy(applying = ApplyPhase.CANCELLED, confirmedAt = null,
                message = "공유방 또는 목적지가 달라 이전 요청 확인을 재개하지 않았습니다."))
            return false
        }
        if (clock() >= pending.deadline) {
            update(state.copy(applying = ApplyPhase.TIMED_OUT, confirmedAt = null,
                message = "이전 요청의 45초 확인 시간이 지났습니다. 변경 성공을 확인하지 못했습니다."))
            return false
        }
        nextId = maxOf(nextId, pending.id)
        operation = Operation(pending.id, roomEpoch, pending.revision, pending.target, pending.deadline,
            requested = true, resumeOnly = true, earlierCompletionObserved = pending.routingCompletedObserved)
        update(state.copy(applying = ApplyPhase.CONNECTING, confirmedAt = null))
        startSession()
        return true
    }

    private fun onChange() {
        trace("onDestinationChanged")
        if (operation == null && proposalRevision == state.revision) {
            recentConfirmation()?.let { pending ->
                lastConfirmation = null; lastConfirmedRevision = -1
                resumeConfirmation(pending)
                return
            }
        }
        if (state.automatic == AutomaticMode.LEADER && operation == null) {
            if (read != null) read!!.dirty = true
            autoObserveAt = clock() + 300
            return
        }
        if (operation == null && state.automatic == AutomaticMode.FOLLOWER) {
            recentConfirmation()?.let { pending ->
                lastConfirmation = null; lastConfirmedRevision = -1
                resumeConfirmation(pending) // Late route events still require a fresh strict read.
                return
            }
        }
        if (state.applying == ApplyPhase.CONFIRMED) {
            update(state.copy(applying = ApplyPhase.IDLE, confirmedAt = null))
        }
        if (operation?.requested == true) verify()
        else if (read != null) read!!.dirty = true
        else beginRead(Purpose.OBSERVE)
    }

    private fun verify() {
        update(state.copy(applying = ApplyPhase.VERIFYING, message = "순정 목적지 후속 조회 중 · 일치 확인 전"))
        if (read != null) read!!.dirty = true else beginRead(Purpose.VERIFY)
    }

    private fun beginRead(purpose: Purpose) {
        if (state.connection != NativeConnection.CONNECTED || read != null) return
        val deadline = minOf(clock() + 8_000, operation?.deadline ?: Long.MAX_VALUE)
        if (deadline <= clock()) {
            nativeFailure("45초 동안 순정 목적지 일치를 확인하지 못했습니다. 다시 연결해 확인해 주세요.", true); return
        }
        read = Read(++nextId, purpose, roomEpoch, deadline)
        update(state.copy(current = CurrentDestination.Loading,
            message = if (purpose == Purpose.VERIFY) "순정 목적지 후속 조회 중 · 일치 확인 전" else "현재 순정 목적지 조회 중"))
        trace("getDestinationInfo")
        try { gateway.readDestination() }
        catch (_: Exception) { nativeFailure("순정 목적지 조회 요청 실패") }
    }

    private fun onInfo(destination: Destination) {
        val pendingRead = takeRead() ?: return // Unsolicited/duplicate/late information cannot complete an operation.
        trace("onDestinationInfo")
        if (pendingRead.dirty) {
            trace("discardCrossedRead")
            beginRead(if (operation != null) Purpose.VERIFY else pendingRead.purpose)
            return
        }
        val lostConfirmation = state.applying == ApplyPhase.CONFIRMED && state.shared?.matchesNative(destination) != true
        update(state.copy(current = CurrentDestination.Present(destination, clock()),
            applying = if (lostConfirmation) ApplyPhase.IDLE else state.applying,
            confirmedAt = if (lostConfirmation) null else state.confirmedAt))
        when (pendingRead.purpose) {
            Purpose.SHARE -> {
                if (pendingRead.room != roomEpoch || !state.roomConnected || !state.host) return
                if (proposalPublicationHeld) {
                    update(state.copy(sharing = false, message = "제안 진행 중 · 이전 목적지 공유 보류")); return
                }
                if (destination == state.shared) {
                    update(state.copy(sharing = false, message = "같은 목적지가 이미 공유되어 있습니다. 다시 전송하지 않았습니다."))
                } else {
                    update(state.copy(message = "조회한 순정 목적지를 공유방에 저장하는 중"))
                    publish(roomEpoch, destination)
                }
            }
            Purpose.VERIFY -> {
                val active = operation ?: return
                if (active.room != roomEpoch || active.revision != state.revision || !active.requested) return
                val matched = active.target.matchesNative(destination)
                trace("destinationMatch", matched)
                if (matched) {
                    lastConfirmedRevision = active.revision
                    lastConfirmation = PendingRouteConfirmation(active.id, active.revision, active.target,
                        active.deadline, state.routingCompletedObserved)
                    operation = null
                    update(state.copy(applying = ApplyPhase.CONFIRMED, confirmedAt = clock(),
                        message = if (state.routingCompletedObserved) "순정 목적지 일치 확인 · 탐색 완료 이벤트 수신"
                            else "순정 목적지 일치 확인 · 확인 시점에 탐색 완료 이벤트 미수신"))
                } else {
                    active.nextPollAt = clock() + 2_000
                    update(state.copy(message = "순정 목적지가 요청과 다릅니다. 45초 제한 안에서 다시 확인합니다."))
                }
            }
            Purpose.OBSERVE -> {
                if (state.shared?.matchesNative(destination) != true) lastConfirmedRevision = -1
                if (state.automatic == AutomaticMode.LEADER) {
                    observedForPublish = destination; hasObservedForPublish = true
                    autoObserveAt = clock() + 15_000
                    publishObserved()
                } else update(state.copy(message = "현재 순정 목적지 조회 완료 · 자동 공유하지 않습니다."))
            }
        }
    }

    private fun publishObserved() {
        if (state.automatic != AutomaticMode.LEADER || !state.host || !state.roomConnected ||
            proposalPublicationHeld || state.sharing || !hasObservedForPublish || observedForPublish == state.shared) return
        update(state.copy(sharing = true, message = "실제 순정 목적지 변경 자동 공유 중"))
        trace("automaticPublish")
        publish(roomEpoch, observedForPublish)
    }

    private fun recentConfirmation(): PendingRouteConfirmation? = lastConfirmation?.takeIf {
        state.roomConnected && state.revision == it.revision && state.shared == it.target && clock() < it.deadline
    }

    private fun onCancelled() {
        trace("onRouteCancelled")
        val active = operation
        val recent = if (active == null) recentConfirmation() else null
        lastConfirmation = null
        lastConfirmedRevision = -1
        if (recent != null) {
            // A read can match the existing route before its late replacement-cancel callback.
            // Re-observe this exact recent intent; never restore success or issue another route.
            resumeConfirmation(recent)
            return
        }
        // This global callback can describe cancellation of the previous route during replacement.
        // Keep the original intent/deadline, retire its listener, and observe without re-requesting.
        if (active?.requested == true) operation = active.copy(resumeOnly = true,
            earlierCompletionObserved = state.routingCompletedObserved, nextPollAt = Long.MAX_VALUE)
        update(state.copy(confirmedAt = null, sharing = if (state.automatic == AutomaticMode.LEADER) state.sharing else false))
        startSession()
    }

    private fun onAbsent() {
        val pendingRead = takeRead() ?: return
        trace("onDestinationInfoAbsent")
        if (pendingRead.dirty) {
            trace("discardCrossedRead")
            beginRead(if (operation != null) Purpose.VERIFY else pendingRead.purpose)
            return
        }
        if (pendingRead.purpose == Purpose.OBSERVE && state.automatic == AutomaticMode.LEADER) {
            observedForPublish = null; hasObservedForPublish = true
            autoObserveAt = clock() + 15_000
            publishObserved()
        }
        lastConfirmedRevision = -1
        lastConfirmation = null
        if (pendingRead.purpose == Purpose.VERIFY) operation?.nextPollAt = clock() + 2_000
        update(state.copy(current = CurrentDestination.Absent(clock()),
            sharing = if (pendingRead.purpose == Purpose.SHARE) false else state.sharing,
            applying = if (state.applying == ApplyPhase.CONFIRMED) ApplyPhase.IDLE else state.applying,
            confirmedAt = null,
            message = when (pendingRead.purpose) {
                Purpose.OBSERVE -> "순정 목적지 없음 · 실제 조회 응답 확인"
                Purpose.SHARE -> "순정 목적지가 없어 공유하지 않았습니다. 공유값 삭제는 대표가 직접 선택해 주세요."
                Purpose.VERIFY -> "순정 목적지 없음 · 요청 반영은 아직 확인되지 않았습니다."
            }))
    }

    private fun takeRead(): Read? {
        val pending = read ?: return null
        if (operation?.let { clock() >= it.deadline } == true) {
            trace("routeTimeout")
            nativeFailure("45초 동안 순정 목적지 일치를 확인하지 못했습니다. 다시 연결해 확인해 주세요.", true)
            return null
        }
        if (clock() >= pending.deadline) {
            trace("readTimeout")
            nativeFailure("8초 동안 목적지 조회 응답이 없습니다. 목적지 없음으로 처리하지 않았습니다.", true)
            return null
        }
        read = null
        return pending
    }

    private fun nativeFailure(message: String, timeout: Boolean = false) {
        val wasApplying = operation != null
        operation = null
        lastConfirmation = null
        retire()
        if (state.automatic != AutomaticMode.OFF) autoObserveAt = clock() + 5_000
        update(state.copy(connection = NativeConnection.FAILED, current = CurrentDestination.Failed(message), sharing = false,
            applying = if (wasApplying) { if (timeout) ApplyPhase.TIMED_OUT else ApplyPhase.FAILED }
                else if (state.applying == ApplyPhase.CONFIRMED) ApplyPhase.IDLE else state.applying,
            confirmedAt = null, message = message))
    }

    fun tick() {
        if (disposed) return
        if (state.roomConnected && state.automatic != AutomaticMode.OFF && operation == null) {
            if (state.connection == NativeConnection.FAILED && clock() >= autoObserveAt) {
                autoObserveAt = clock() + 5_000; startSession(); return
            }
            if (state.automatic == AutomaticMode.LEADER && state.connection == NativeConnection.CONNECTED &&
                read == null && clock() >= autoObserveAt) {
                autoObserveAt = clock() + 15_000; beginRead(Purpose.OBSERVE); return
            }
            if (state.automatic == AutomaticMode.FOLLOWER && clock() >= autoDue && state.shared != null &&
                autoAttemptedRevision != state.revision) {
                autoDue = Long.MAX_VALUE; autoAttemptedRevision = state.revision
                trace("automaticApply")
                applyShared()
            }
        }
        when {
            operation?.let { clock() >= it.deadline } == true -> { trace("routeTimeout"); nativeFailure("45초 동안 순정 목적지 일치를 확인하지 못했습니다. 다시 연결해 확인해 주세요.", true) }
            state.connection == NativeConnection.CONNECTING && clock() >= connectDeadline -> nativeFailure("순정 내비 연결 시간 초과 · Pleos 서비스가 필요합니다.", true)
            read?.let { clock() >= it.deadline } == true -> { trace("readTimeout"); nativeFailure("8초 동안 목적지 조회 응답이 없습니다. 목적지 없음으로 처리하지 않았습니다.", true) }
            operation?.let { it.requested && clock() >= it.nextPollAt } == true && read == null -> {
                trace("periodicVerification")
                verify()
            }
        }
    }

    fun stop() {
        if (disposed) return
        disposed = true
        state = state.copy(automatic = AutomaticMode.OFF)
        operation = null
        retire()
    }
}
