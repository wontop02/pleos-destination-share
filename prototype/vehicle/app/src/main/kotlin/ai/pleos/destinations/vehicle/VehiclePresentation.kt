package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleState

internal enum class StatusTone { NEUTRAL, WORKING, SUCCESS, ATTENTION }
internal data class DestinationStatus(val text: String, val tone: StatusTone)

/** Product copy is projected from state; raw diagnostics never become a home status. */
internal object VehiclePresentation {
    fun currentAcceptedTarget(state: VehicleState, accepted: DestinationProposal?) = accepted != null &&
        accepted.status == "accepted" && accepted.acceptedRevision == state.revision && accepted.destination == state.shared

    fun confirmedCurrentTarget(state: VehicleState, accepted: DestinationProposal?): Boolean {
        val target = state.shared ?: return false
        val confirmation = state.confirmation ?: return false
        val current = state.current as? CurrentDestination.Present ?: return false
        return state.roomConnected && state.connection == NativeConnection.CONNECTED &&
            state.applying == ApplyPhase.CONFIRMED && state.confirmedAt != null &&
            confirmation.source == target && confirmation.kind == target.kind &&
            target.matchesRequestedDestination(confirmation.actual) && current.value == confirmation.actual &&
            (accepted == null || currentAcceptedTarget(state, accepted))
    }

    fun destinationStatus(state: VehicleState, accepted: DestinationProposal?): DestinationStatus = when {
        state.applying == ApplyPhase.CANCELLED -> DestinationStatus("목적지 확인을 멈췄어요. 내비 안내는 그대로예요.", StatusTone.NEUTRAL)
        !state.roomConnected -> DestinationStatus("연결이 끊겼어요. 설정에서 다시 연결해 주세요.", StatusTone.ATTENTION)
        state.connection == NativeConnection.FAILED -> DestinationStatus("내비에 연결하지 못했어요. 설정에서 다시 연결해 주세요.", StatusTone.ATTENTION)
        confirmedCurrentTarget(state, accepted) -> DestinationStatus("내비에서 목적지를 확인했어요", StatusTone.SUCCESS)
        state.applying in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING) ->
            DestinationStatus("내비에 반영 중", StatusTone.WORKING)
        state.applying == ApplyPhase.VERIFYING -> DestinationStatus("내비에서 목적지를 확인하고 있어요", StatusTone.WORKING)
        state.applying in listOf(ApplyPhase.FAILED, ApplyPhase.TIMED_OUT) ->
            DestinationStatus("내비 반영을 확인하지 못했어요. 다시 시도해 주세요.", StatusTone.ATTENTION)
        state.sharing -> DestinationStatus("함께 가는 곳을 공유하고 있어요", StatusTone.WORKING)
        currentAcceptedTarget(state, accepted) -> DestinationStatus("수락한 목적지예요. 필요하면 다시 반영해 주세요.", StatusTone.NEUTRAL)
        state.shared == null -> DestinationStatus("목적지를 바꾸거나 동행을 시작해 주세요", StatusTone.NEUTRAL)
        else -> DestinationStatus("함께 정한 목적지예요. 내비 반영은 아직 확인 전이에요.", StatusTone.NEUTRAL)
    }

    fun connection(state: VehicleState, hasRoom: Boolean): String {
        val room = if (!hasRoom) "동행방 없음" else if (state.roomConnected) "동행방 연결됨" else "동행방 연결 끊김"
        val native = when (state.connection) {
            NativeConnection.CONNECTED -> "내비 연결됨"
            NativeConnection.CONNECTING -> "내비 연결 중"
            NativeConnection.DISCONNECTED -> "내비 연결 필요"
            NativeConnection.FAILED -> "내비 연결 실패"
        }
        val auto = when (state.automatic) {
            AutomaticMode.OFF -> "자동 동행 꺼짐"
            AutomaticMode.LEADER -> "자동 공유 중"
            AutomaticMode.FOLLOWER -> "자동 반영 중"
        }
        return "$room · $native · $auto"
    }

    fun places(state: SavedPlacesState?, connected: Boolean): String = when {
        !connected -> "내비 연결이 필요해요. 설정에서 연결해 주세요."
        state == null -> "이 내비에서는 저장한 장소를 불러올 수 없어요."
        state.phase == SavedPlacesPhase.LOADING -> "저장한 장소를 불러오는 중이에요"
        state.phase == SavedPlacesPhase.READY -> "불러온 목록이에요. 새로 저장한 곳은 설정에서 내비를 다시 연결해 주세요."
        state.phase == SavedPlacesPhase.EMPTY -> "저장한 장소가 없어요. 내비에서 ☆로 저장한 뒤 다시 연결해 주세요."
        state.phase == SavedPlacesPhase.INVALID -> "사용할 수 있는 장소 정보가 없어요. 내비를 다시 연결해 주세요."
        state.phase == SavedPlacesPhase.FAILED -> "장소를 불러오지 못했어요. 내비를 다시 연결해 주세요."
        state.phase == SavedPlacesPhase.RECONNECT_REQUIRED -> "불러오기가 끝났거나 취소됐어요. 설정에서 내비를 다시 연결해 주세요."
        else -> "이름과 주소를 확인한 뒤 장소를 선택해 주세요."
    }

    fun reason(raw: String): String? {
        val value = raw.trim().ifEmpty { "이 장소로 목적지 변경을 제안합니다." }
        return value.takeIf { it.length <= 200 && it.none { char -> char.code < 32 || char.code == 127 } }
    }
}
