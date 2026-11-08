package ai.pleos.destinations.vehicle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleState
import ai.pleos.destinations.vehicle.VehicleViews.Role
import java.text.DateFormat
import java.util.Date

/** One stable set of views observes the existing service; page changes never grant consent. */
class MainActivity : Activity() {
    private enum class Page { HOME, COMPOSE, SETTINGS, WELCOME, ROOM_FORM }
    private var service: ConvoySessionService? = null
    private var bound = false
    private val session get() = service?.session
    private val credentials get() = session?.credentials
    private val busy get() = session?.busy ?: true
    private val leaving get() = session?.leaving ?: false
    private val observer: (VehicleSession) -> Unit = { render(it.state) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val owner = (binder as ConvoySessionService.LocalBinder).service
            service = owner; owner.attachUi(observer)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; render(VehicleState()) }
    }
    private lateinit var ui: VehicleViews
    private val pages = mutableMapOf<Page, ScrollView>()
    private var page = Page.HOME
    private lateinit var pageTitle: TextView
    private lateinit var backButton: Button
    private lateinit var settingsButton: Button
    private lateinit var stopButton: Button
    private lateinit var connectionText: TextView
    private lateinit var sharedCard: LinearLayout
    private lateinit var sharedName: TextView
    private lateinit var sharedAddress: TextView
    private lateinit var destinationStatus: TextView
    private lateinit var homeNotice: TextView
    private lateinit var changeButton: Button
    private lateinit var recoveryButton: Button
    private lateinit var automaticText: TextView
    private lateinit var automaticStartButton: Button
    private lateinit var proposalCard: LinearLayout
    private lateinit var proposalName: TextView
    private lateinit var proposalAddress: TextView
    private lateinit var proposalReason: TextView
    private lateinit var proposalProposer: TextView
    private lateinit var proposalStatus: TextView
    private lateinit var responseRow: LinearLayout
    private lateinit var acceptButton: Button
    private lateinit var rejectButton: Button
    private lateinit var voiceInput: VoiceInputController
    private lateinit var voiceStatus: TextView
    private lateinit var voiceTextInput: EditText
    private lateinit var voiceStartButton: Button
    private lateinit var voiceStopButton: Button
    private lateinit var voiceCancelButton: Button
    private lateinit var voiceAnalyzeButton: Button
    private lateinit var voiceCandidatesButton: Button
    private lateinit var voiceSummary: TextView
    private var updatingVoiceText = false
    private var observingVoice = false
    private var foreground = false
    private var lastVoiceTextRevision = -1L
    private data class VoiceReview(val scope: VoiceInputScope, val textRevision: Long,
        val snapshot: Long, val draft: VoiceDraft)
    private var voiceReview: VoiceReview? = null
    private var voiceSelected: Destination? = null
    private var voiceSelectedSnapshot = -1L
    private var voicePicker = false
    private val voiceHandler = Handler(Looper.getMainLooper())
    private val voiceTick = object : Runnable {
        override fun run() { if (foreground) { voiceInput.tick(); voiceHandler.postDelayed(this, 250) } }
    }
    private lateinit var placesStatus: TextView
    private lateinit var selectPlacesButton: Button
    private lateinit var reloadPlacesButton: Button
    private lateinit var selectedName: TextView
    private lateinit var selectedAddress: TextView
    private lateinit var cancelPlacesButton: Button
    private lateinit var reasonInput: EditText
    private lateinit var sendButton: Button
    private lateinit var examplesButton: Button
    private lateinit var settingsStatus: TextView
    private lateinit var nativeSummary: TextView
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var shareButton: Button
    private lateinit var applyButton: Button
    private lateinit var clearButton: Button
    private lateinit var inviteButton: Button
    private lateinit var reconnectRoomButton: Button
    private lateinit var leaveButton: Button
    private lateinit var forgetButton: Button
    private lateinit var retryGroup: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var resetButton: Button
    private lateinit var developerContent: LinearLayout
    private lateinit var developerText: TextView
    private lateinit var developerButton: Button
    private lateinit var joinFields: LinearLayout
    private lateinit var baseInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var idInput: EditText
    private lateinit var inviteInput: EditText
    private lateinit var submitRoomButton: Button
    private lateinit var formStatus: TextView
    private var joinForm = false
    private var formAttempted = false
    private var selectedExample: Destination? = null
    private var pickerDialog: AlertDialog? = null
    private var pickerRequested = false
    private var suppressPickerCancel = false
    private var shownSnapshot = -1L
    private var displayedProposal: DestinationProposal? = null
    private var lastPendingId: String? = null
    private var lastRoomKey: String? = null
    private var localNotice: String? = null
    private val placesLoadIntent = PlacesLoadIntent { SystemClock.elapsedRealtime() }
    private var composeGeneration = 0L
    private var placesLoadFailed = false
    private var afterNotificationPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val journal = EvidenceJournal(this)
        val trace: (String) -> Unit = { event -> journal.append("voice:$event", session?.savedPlaces?.nativeEpoch ?: -1, composeGeneration, null) }
        voiceInput = VoiceInputController(PleosVoiceInputGateway(this, trace), { SystemClock.elapsedRealtime() },
            { if (!observingVoice && ::voiceStatus.isInitialized) renderCurrent() }, trace)
        ui = VehicleViews(this); buildScreen(); render(VehicleState())
    }
    override fun onStart() {
        super.onStart()
        foreground = true; voiceHandler.post(voiceTick)
        bound = bindService(Intent(this, ConvoySessionService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onStop() {
        foreground = false; voiceHandler.removeCallbacks(voiceTick); voiceInput.observe(null); invalidateVoiceReview()
        placesLoadIntent.cancel(); composeGeneration += 1; placesLoadFailed = false
        pickerRequested = false; closePicker(); selectedExample = null
        service?.manual { it.savedPlaces?.cancel() }
        service?.detachUi(observer); service = null
        if (bound) { unbindService(connection); bound = false }
        inviteInput.text.clear()
        super.onStop()
    }
    @Deprecated("The platform back callback is sufficient for this single-Activity prototype.")
    override fun onBackPressed() {
        if (page == Page.HOME || page == Page.WELCOME) super.onBackPressed()
        else navigate(if (credentials == null) Page.WELCOME else Page.HOME)
    }

    private fun automaticStart() {
        localNotice = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 14)
        } else service?.requestAutomaticFromUi()
    }
    private fun proposalAction(action: (VehicleSession) -> Unit) {
        localNotice = null
        val start = { service?.requestProposalFromUi(action); Unit }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterNotificationPermission = start
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 17)
        } else start()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 14) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) service?.requestAutomaticFromUi()
            else localNotice = "알림을 허용한 뒤 동행을 시작해 주세요. 알림에서 중지할 수 있어요."
        }
        if (requestCode == 17) {
            val action = afterNotificationPermission; afterNotificationPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
            else localNotice = "알림을 허용한 뒤 다시 선택해 주세요. 제안 진행을 알림에서 확인해요."
        }
        renderCurrent()
    }

    private fun navigate(next: Page) { showPage(next); renderCurrent() }
    private fun showPage(next: Page) {
        val previous = page; page = next
        if (previous != next && (previous == Page.COMPOSE || next == Page.COMPOSE)) composeGeneration += 1
        pages.forEach { (key, view) -> view.visibility = if (key == next) View.VISIBLE else View.GONE }
        pageTitle.text = when (next) {
            Page.HOME, Page.WELCOME -> "동행"
            Page.COMPOSE -> "목적지 바꾸기"
            Page.SETTINGS -> "설정"
            Page.ROOM_FORM -> if (joinForm) "동행 참여" else "동행 만들기"
        }
        backButton.visibility = if (next == Page.HOME || next == Page.WELCOME) View.GONE else View.VISIBLE
        settingsButton.visibility = if (next == Page.SETTINGS) View.GONE else View.VISIBLE
        if (previous == Page.COMPOSE && next != Page.COMPOSE) {
            voiceInput.observe(null); invalidateVoiceReview()
            placesLoadIntent.cancel(); placesLoadFailed = false
            pickerRequested = false; closePicker(); selectedExample = null
            service?.manual { it.savedPlaces?.cancel() }
        }
        if (previous == Page.ROOM_FORM && next != Page.ROOM_FORM) inviteInput.text.clear()
    }
    private fun renderCurrent() = render(session?.state ?: VehicleState())

    private fun render(state: VehicleState) {
        val owner = session
        val p = owner?.proposal
        val accepted = owner?.acceptedProposal
        val roomKey = credentials?.let { "${it.roomId}/${it.memberId}" }
        if (roomKey != lastRoomKey) {
            placesLoadIntent.cancel(); placesLoadFailed = false
            lastRoomKey = roomKey; selectedExample = null
            closePicker(); pickerRequested = false; localNotice = null
        }
        if (p?.status == "pending" && p.id != lastPendingId) {
            placesLoadIntent.cancel(); placesLoadFailed = false
            lastPendingId = p.id
            selectedExample = null; closePicker(); pickerRequested = false
            if (page != Page.COMPOSE) service?.manual { it.savedPlaces?.cancel() }
            showPage(Page.HOME)
        }
        if (credentials == null && page in listOf(Page.HOME, Page.COMPOSE)) showPage(Page.WELCOME)
        if (credentials != null && page in listOf(Page.WELCOME, Page.ROOM_FORM)) showPage(Page.HOME)
        displayedProposal = p
        val active = state.automatic != AutomaticMode.OFF || owner?.pendingAutomatic == true || owner?.hasProposalLease == true
        stopButton.visibility = if (active) View.VISIBLE else View.GONE
        connectionText.text = VehiclePresentation.connection(state, credentials != null)
        sharedName.text = state.shared?.name ?: "아직 정한 곳이 없어요"
        sharedAddress.text = state.shared?.pleos?.address?.takeIf { it.isNotBlank() } ?: if (state.shared == null) "함께 갈 장소를 선택해 주세요" else "주소 정보 없음"
        val status = VehiclePresentation.destinationStatus(state, accepted)
        badge(destinationStatus, status)
        val proposalError = owner?.proposalMessage?.startsWith("제안 응답 확인 실패") == true
        val notice = localNotice ?: when {
            proposalError -> "제안 응답을 확인하지 못했어요. 설정에서 다시 연결한 뒤 확인해 주세요."
            p?.status == "rejected" -> "제안이 거절됐어요. 현재 안내는 그대로예요."
            p?.status == "expired" -> "응답 시간이 지나 제안이 끝났어요. 다시 제안해 주세요."
            p?.status == "invalidated" -> "동행이나 목적지가 바뀌어 제안이 끝났어요. 다시 확인해 주세요."
            else -> null
        }
        homeNotice.text = notice ?: ""; homeNotice.visibility = if (notice == null) View.GONE else View.VISIBLE
        val pending = p?.status == "pending"
        proposalCard.visibility = if (pending) View.VISIBLE else View.GONE
        sharedCard.visibility = if (pending) View.GONE else View.VISIBLE
        if (p != null && pending) {
            proposalName.text = p.destination.name
            proposalAddress.text = p.destination.pleos?.address ?: "주소 정보 없음"
            proposalReason.text = p.reason
            proposalProposer.text = "제안한 차량 · ${p.proposer.name}"
            val canRespond = owner?.canRespond(p.id, p.version) == true
            val ownVote = p.responses.firstOrNull { it.member.id == credentials?.memberId }?.response
            responseRow.visibility = if (canRespond || ownVote == "pending") View.VISIBLE else View.GONE
            acceptButton.isEnabled = canRespond; rejectButton.isEnabled = canRespond
            proposalStatus.text = when {
                !state.roomConnected -> "연결이 끊겼어요. 다시 연결한 뒤 응답해 주세요."
                owner?.proposalBusy == true -> "응답을 보내는 중이에요"
                p.proposer.id == credentials?.memberId -> "기다리는 중 · 다른 차량의 수락을 기다려요"
                ownVote == "accepted" -> "수락했어요 · 다른 차량을 기다리는 중"
                canRespond -> "모두 수락하면 내비에 반영해요"
                else -> "응답이 끝났거나 시간이 지났어요. 최신 제안을 확인해 주세요."
            }
        }
        changeButton.isEnabled = owner?.canPropose == true
        automaticStartButton.isEnabled = credentials != null && !busy && !leaving && state.automatic == AutomaticMode.OFF && owner?.pendingAutomatic != true
        automaticStartButton.visibility = if (state.automatic == AutomaticMode.OFF && owner?.pendingAutomatic != true) View.VISIBLE else View.GONE
        automaticText.text = when {
            owner?.pendingAutomatic == true -> "동행을 시작하려고 다시 연결하고 있어요"
            state.automatic == AutomaticMode.LEADER -> "동행 중 · 내비 목적지를 함께 공유해요"
            state.automatic == AutomaticMode.FOLLOWER -> "동행 중 · 함께 가는 곳을 내비에 반영해요"
            credentials?.role == "host" -> "대표 차량 · 시작하면 내비 목적지를 자동으로 공유해요"
            else -> "참여 차량 · 시작하면 함께 가는 곳을 내비에 자동 반영해요"
        }
        val applying = state.applying in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING)
        val currentAccepted = VehiclePresentation.currentAcceptedTarget(state, accepted)
        val retryAllowed = currentAccepted && !pending && state.roomConnected && owner?.hasProposalLease == false && !busy && !leaving && owner.proposalBusy == false
        val manualApply = state.automatic == AutomaticMode.OFF && state.roomConnected && state.shared?.canRequestRoute == true && !state.sharing && !applying
        recoveryButton.visibility = if (VehiclePresentation.showRecovery(state, accepted, pending, status)) View.VISIBLE else View.GONE
        recoveryButton.text = if (!state.roomConnected || state.connection == NativeConnection.FAILED) "연결 확인" else "다시 반영"
        recoveryButton.isEnabled = !state.roomConnected || state.connection == NativeConnection.FAILED || retryAllowed || manualApply
        renderVoice(state, owner)
        renderComposer(state, owner, proposalError)
        settingsStatus.text = VehiclePresentation.connection(state, credentials != null)
        nativeSummary.text = when (val current = state.current) {
            is CurrentDestination.Present -> "내비 목적지 · ${current.value.name}\n${current.value.pleos?.address ?: "주소 정보 없음"}"
            is CurrentDestination.Absent -> "내비에 설정된 목적지가 없어요"
            CurrentDestination.Loading -> "내비 목적지를 불러오는 중이에요"
            is CurrentDestination.Failed -> "내비 목적지를 읽지 못했어요. 다시 조회해 주세요."
            CurrentDestination.Unknown -> "내비 목적지를 아직 읽지 않았어요"
        }
        connectButton.isEnabled = service != null && state.connection != NativeConnection.CONNECTING && !applying
        refreshButton.isEnabled = state.connection == NativeConnection.CONNECTED && state.current != CurrentDestination.Loading && !applying && !state.sharing
        val manual = state.automatic == AutomaticMode.OFF
        shareButton.visibility = if (state.host) View.VISIBLE else View.GONE
        clearButton.visibility = if (state.host) View.VISIBLE else View.GONE
        shareButton.isEnabled = manual && state.roomConnected && state.connection == NativeConnection.CONNECTED && !state.sharing && !applying && state.current != CurrentDestination.Loading
        clearButton.isEnabled = manual && state.roomConnected && state.shared != null && !state.sharing && !applying && state.current != CurrentDestination.Loading
        applyButton.isEnabled = manualApply
        inviteButton.visibility = if (credentials?.inviteSecret != null) View.VISIBLE else View.GONE
        reconnectRoomButton.isEnabled = credentials != null && !busy && !leaving
        leaveButton.isEnabled = credentials != null && !leaving
        leaveButton.text = if (credentials?.role == "host") "동행방 종료" else "동행방 나가기"
        forgetButton.isEnabled = credentials != null && !leaving
        retryButton.visibility = if (currentAccepted && !pending) View.VISIBLE else View.GONE
        retryButton.isEnabled = retryAllowed
        resetButton.visibility = if (state.host && owner?.coordinator?.proposalPublicationHeld == true && !pending) View.VISIBLE else View.GONE
        resetButton.isEnabled = owner?.hasProposalLease == false
        retryGroup.visibility = if (retryButton.visibility == View.VISIBLE || resetButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (developerContent.visibility == View.VISIBLE) developerText.text = diagnosticText(state)
        joinFields.visibility = if (joinForm) View.VISIBLE else View.GONE
        submitRoomButton.text = if (joinForm) "참여하기" else "동행방 만들기"
        submitRoomButton.isEnabled = !busy && credentials == null
        formStatus.text = when {
            formAttempted && busy -> if (joinForm) "참여하는 중이에요" else "동행방을 만드는 중이에요"
            formAttempted && credentials == null -> "연결하지 못했어요. 이름과 초대 정보, 연결 주소를 확인한 뒤 다시 시도해 주세요."
            else -> ""
        }
    }

    private fun renderComposer(state: VehicleState, owner: VehicleSession?, proposalError: Boolean) {
        val controller = owner?.savedPlaces
        val context = placesContext(state, owner)
        when (placesLoadIntent.observe(context)) {
            PlacesLoadIntent.Action.LOAD -> { loadPlaces(owner); return }
            PlacesLoadIntent.Action.FAILED -> { pickerRequested = false; placesLoadFailed = true }
            PlacesLoadIntent.Action.CANCELLED -> { pickerRequested = false; placesLoadFailed = context.scope != null }
            else -> Unit
        }
        val saved = controller?.state
        val selected = selectedExample ?: saved?.selected
        selectedName.text = selected?.name ?: "장소를 선택해 주세요"
        selectedAddress.text = selected?.pleos?.address ?: "선택한 이름과 주소가 여기에 보여요"
        placesStatus.text = localNotice ?: if (proposalError) "전송을 확인하지 못했어요. 홈에서 제안을 확인하거나 설정에서 다시 연결해 주세요."
            else if (placesLoadIntent.waiting) "장소를 불러오는 중이에요"
            else if (placesLoadFailed) "장소를 불러오지 못했어요. 다시 불러와 주세요."
            else if (owner?.proposalBusy == true) "제안을 보내는 중이에요"
            else VehiclePresentation.places(saved, state.connection == NativeConnection.CONNECTED)
        selectPlacesButton.text = when {
            saved?.phase == SavedPlacesPhase.READY -> "저장한 장소 다시 선택 · 불러온 목록"
            placesLoadFailed || saved?.phase in listOf(SavedPlacesPhase.RECONNECT_REQUIRED, SavedPlacesPhase.EMPTY, SavedPlacesPhase.INVALID, SavedPlacesPhase.FAILED) -> "다시 불러오기"
            else -> "저장한 장소 선택"
        }
        selectPlacesButton.isEnabled = !voiceInput.capturing && placesLoadIntent.canRequest(context)
        reloadPlacesButton.visibility = if (saved?.phase == SavedPlacesPhase.READY) View.VISIBLE else View.GONE
        reloadPlacesButton.isEnabled = !voiceInput.capturing && placesLoadIntent.canRequest(context)
        cancelPlacesButton.visibility = if (placesLoadIntent.waiting || saved?.phase == SavedPlacesPhase.LOADING || selected != null) View.VISIBLE else View.GONE
        cancelPlacesButton.text = if (placesLoadIntent.waiting || saved?.phase == SavedPlacesPhase.LOADING) "불러오기 취소" else "선택 취소"
        sendButton.isEnabled = !voiceInput.capturing && selected != null && owner?.canPropose == true
        reasonInput.isEnabled = owner?.proposalBusy != true
        examplesButton.isEnabled = !voiceInput.capturing && owner?.canPropose == true
        if (pickerDialog != null && saved?.phase != SavedPlacesPhase.READY) closePicker()
        if (page == Page.COMPOSE && pickerRequested && saved?.phase == SavedPlacesPhase.READY && pickerDialog == null && shownSnapshot != saved.snapshot) {
            shownSnapshot = saved.snapshot; showSavedPlaces(controller, saved)
        }
    }

    private fun currentVoiceScope(state: VehicleState, owner: VehicleSession?): VoiceInputScope? =
        credentials?.takeIf { foreground && page == Page.COMPOSE && owner?.canPropose == true &&
            state.connection == NativeConnection.CONNECTED && !placesLoadIntent.waiting }?.let {
            VoiceInputScope(it.roomId, it.memberId, owner!!.coordinator.roomEpoch, composeGeneration,
                owner.savedPlaces?.nativeEpoch ?: -1)
        }

    private fun invalidateVoiceReview() {
        voiceReview = null
        if (voicePicker) { voicePicker = false; closePicker() }
        val selected = voiceSelected; val snapshot = voiceSelectedSnapshot
        voiceSelected = null; voiceSelectedSnapshot = -1L
        if (selected != null && session?.savedPlaces?.state?.selected === selected)
            service?.manual { it.savedPlaces?.clearSelection(snapshot) }
    }

    private fun renderVoice(state: VehicleState, owner: VehicleSession?) {
        observingVoice = true
        voiceInput.observe(currentVoiceScope(state, owner))
        observingVoice = false
        val input = voiceInput.state
        if (lastVoiceTextRevision != input.textRevision) {
            lastVoiceTextRevision = input.textRevision; invalidateVoiceReview()
        }
        val saved = owner?.savedPlaces?.state
        if (voiceReview?.let { it.scope != voiceInput.scope || it.snapshot != (saved?.snapshot ?: -1) ||
                (it.draft.candidates.isNotEmpty() && saved?.phase != SavedPlacesPhase.READY) } == true) invalidateVoiceReview()
        if (voiceTextInput.text.toString() != input.text) {
            updatingVoiceText = true; voiceTextInput.setText(input.text); updatingVoiceText = false
        }
        voiceTextInput.isEnabled = voiceInput.scope != null && !voiceInput.capturing
        voiceStartButton.isEnabled = voiceInput.scope != null && !voiceInput.capturing
        voiceStartButton.text = if (input.text.isBlank()) "말로 작성" else "다시 말하기"
        voiceStopButton.visibility = if (voiceInput.capturing) View.VISIBLE else View.GONE
        voiceStopButton.isEnabled = input.phase != VoiceInputPhase.FINISHING
        voiceCancelButton.visibility = if (input.phase != VoiceInputPhase.IDLE || input.text.isNotBlank()) View.VISIBLE else View.GONE
        voiceAnalyzeButton.isEnabled = voiceInput.scope != null && !voiceInput.capturing &&
            input.text.isNotBlank() && VoiceDraftAnalyzer.validText(input.text)
        voiceStatus.text = when (input.phase) {
            VoiceInputPhase.PREPARING -> "마이크를 준비하고 있어요. 잠시 기다려 주세요."
            VoiceInputPhase.LISTENING -> if (input.interim.isBlank()) "듣고 있어요. 짧게 말한 뒤 말하기 끝을 눌러 주세요." else "듣고 있어요 · ${input.interim}"
            VoiceInputPhase.FINISHING -> "들은 말을 확인하고 있어요"
            VoiceInputPhase.READY -> "들은 말을 고칠 수 있어요. 초안을 정리한 뒤 장소를 확인해 주세요."
            VoiceInputPhase.CANCELLED -> "음성 작성을 취소했어요. 직접 장소를 선택할 수 있어요."
            VoiceInputPhase.FAILED -> when (input.failure) {
                VoiceInputFailure.PERMISSION -> "마이크를 사용할 수 없어요. 직접 입력하거나 장소를 선택해 주세요."
                VoiceInputFailure.MICROPHONE -> "마이크 소리를 받지 못했어요. 다시 말하거나 직접 입력해 주세요."
                VoiceInputFailure.UNSUPPORTED -> "이 차량에서는 음성 인식을 사용할 수 없어요. 직접 입력하거나 장소를 선택해 주세요."
                VoiceInputFailure.TIMEOUT -> "시간 안에 말을 확인하지 못했어요. 다시 말하거나 직접 입력해 주세요."
                VoiceInputFailure.EMPTY -> "말을 듣지 못했어요. 다시 말하거나 직접 입력해 주세요."
                else -> "말을 확인하지 못했어요. 다시 말하거나 직접 입력해 주세요."
            }
            VoiceInputPhase.IDLE -> if (voiceInput.scope == null) "내비와 동행 연결을 확인한 뒤 말로 작성할 수 있어요." else "짧게 말하면 변경할 곳과 이유의 초안을 정리해요."
        }
        val draft = voiceReview?.draft
        voiceSummary.text = when {
            draft == null -> "초안만 만들어요. 확인하고 제안 보내기를 눌러야 전송해요."
            draft.intent == VoiceChangeIntent.NEGATED -> "변경하지 않는 말로 들었어요. 말을 고치거나 장소를 직접 선택해 주세요."
            draft.intent == VoiceChangeIntent.UNCLEAR -> "변경할 곳을 확인해 주세요. 말을 고치거나 장소를 직접 선택할 수 있어요."
            else -> "변경할 곳 · ${draft.clue}\n이유 초안 · ${draft.reason.ifBlank { "직접 적을 수 있어요" }}\n" +
                if (saved?.phase != SavedPlacesPhase.READY) "저장한 장소를 불러온 뒤 초안을 다시 정리해 주세요."
                else if (draft.candidates.isEmpty()) "이 말과 맞는 저장 장소를 찾지 못했어요. 목록에서 직접 선택해 주세요."
                else "저장한 실제 후보 ${draft.candidates.size}곳의 이름과 주소를 확인해 주세요."
        }
        voiceCandidatesButton.visibility = if (draft?.candidates?.isNotEmpty() == true) View.VISIBLE else View.GONE
        voiceCandidatesButton.isEnabled = voiceReview != null && !voiceInput.capturing
        voiceCandidatesButton.text = "후보 확인 · ${draft?.candidates?.size ?: 0}곳"
    }

    private fun startVoice() {
        if (voiceInput.scope == null || voiceInput.capturing) return
        invalidateVoiceReview(); selectedExample = null; pickerRequested = false; closePicker()
        session?.savedPlaces?.let { it.clearSelection(it.state.snapshot) }
        voiceInput.start()
    }
    private fun analyzeVoice() {
        val scope = voiceInput.scope ?: return
        val input = voiceInput.state
        if (voiceInput.capturing || input.text.isBlank() || !VoiceDraftAnalyzer.validText(input.text)) return
        invalidateVoiceReview()
        val saved = session?.savedPlaces?.state
        val choices = saved?.takeIf { it.phase == SavedPlacesPhase.READY }?.choices ?: emptyList()
        val draft = VoiceDraftAnalyzer.analyze(input.text, choices)
        voiceReview = VoiceReview(scope, input.textRevision, saved?.snapshot ?: -1, draft)
        reasonInput.setText(draft.reason)
        renderCurrent()
    }
    private fun showVoiceCandidates() {
        val review = voiceReview ?: return
        val controller = session?.savedPlaces ?: return
        val snapshot = controller.state
        if (voiceInput.scope != review.scope || voiceInput.state.textRevision != review.textRevision ||
            review.snapshot != snapshot.snapshot || snapshot.phase != SavedPlacesPhase.READY ||
            review.draft.candidates.isEmpty()) return
        closePicker(); voicePicker = true
        val choices = ui.column().apply { id = R.id.voice_candidate_choices; setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle("후보 이름과 주소 확인")
            .setView(ScrollView(this).apply { addView(choices) }).setNegativeButton("취소", null).create()
        pickerDialog = dialog
        review.draft.candidates.forEach { place -> ui.add(choices, ui.button("${place.name}\n${place.pleos?.address}", R.id.voice_candidate_choice) {
            if (voiceReview === review && voiceInput.scope == review.scope &&
                voiceInput.state.textRevision == review.textRevision && controller.select(snapshot.snapshot, place)) {
                selectedExample = null; voiceSelected = place; voiceSelectedSnapshot = snapshot.snapshot
            }
            pickerDialog = null; voicePicker = false; dialog.dismiss(); renderCurrent()
        }, 12) }
        dialog.setOnDismissListener { if (pickerDialog === dialog) pickerDialog = null; voicePicker = false }
        dialog.show(); styleDialogButtons(dialog)
    }

    private fun buildVoiceComposer(root: LinearLayout) {
        val card = ui.card(root)
        ui.add(card, ui.text("말로 초안 작성", true))
        ui.add(card, ui.text("정차 중이거나 동승자가 조작해 주세요. 저장한 장소에서만 골라요.").apply { setTextColor(ui.color(R.color.muted)) }, 12)
        voiceStartButton = ui.button("말로 작성", R.id.voice_start, Role.PRIMARY) { startVoice() }; ui.add(card, voiceStartButton, 20)
        voiceStatus = ui.text(id = R.id.voice_status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; ui.add(card, voiceStatus, 16)
        voiceStopButton = ui.button("말하기 끝", R.id.voice_stop) { voiceInput.stop() }
        voiceCancelButton = ui.button("음성 초안 지우기", R.id.voice_cancel) { voiceInput.cancel(); invalidateVoiceReview() }
        ui.pair(card, voiceStopButton, voiceCancelButton, 12)
        val field = ui.column(); ui.add(card, field, 20)
        voiceTextInput = ui.field(field, "들은 말 (고칠 수 있어요)", R.id.voice_text).apply {
            setSingleLine(false); minLines = 2; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "예: 카페 들르는 거 어때?"; filters = arrayOf(InputFilter.LengthFilter(400))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!updatingVoiceText) voiceInput.edit(s?.toString() ?: "") }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        voiceAnalyzeButton = ui.button("초안 정리", R.id.voice_analyze) { analyzeVoice() }; ui.add(card, voiceAnalyzeButton)
        voiceSummary = ui.text(id = R.id.voice_summary); ui.add(card, voiceSummary, 16)
        voiceCandidatesButton = ui.button("후보 확인", R.id.voice_candidates) { showVoiceCandidates() }; ui.add(card, voiceCandidatesButton, 16)
    }

    private fun placesContext(state: VehicleState, owner: VehicleSession?) = PlacesLoadContext(
        credentials?.takeIf { page == Page.COMPOSE && state.roomConnected }?.let {
            PlacesLoadScope(it.roomId, it.memberId, owner?.coordinator?.roomEpoch ?: -1, composeGeneration)
        }, state, owner?.savedPlaces?.state, owner?.savedPlaces?.canLoad == true,
        owner?.canPropose == true, owner?.hasProposalLease == true, owner?.pendingAutomatic == true)

    private fun requestPlaces(refresh: Boolean = false) {
        val owner = session ?: return
        val action = placesLoadIntent.begin(placesContext(owner.state, owner), refresh)
        if (action == PlacesLoadIntent.Action.NONE) return
        selectedExample = null; localNotice = null; placesLoadFailed = false
        pickerRequested = true; shownSnapshot = -1L
        when (action) {
            PlacesLoadIntent.Action.LOAD -> loadPlaces(owner)
            PlacesLoadIntent.Action.RECONNECT -> service?.manual {
                if (it.savedPlaces?.state?.selected != null || it.savedPlaces?.state?.phase == SavedPlacesPhase.READY) it.savedPlaces?.cancel()
                it.coordinator.connect()
            }
            else -> Unit
        }
        renderCurrent()
    }

    private fun loadPlaces(owner: VehicleSession?) {
        if (owner == null || session !== owner || page != Page.COMPOSE) { pickerRequested = false; return }
        service?.manual { if (it.savedPlaces?.load() != true) { pickerRequested = false; placesLoadFailed = true } }
    }

    private fun sendProposal() {
        if (voiceInput.capturing) return
        val reason = VehiclePresentation.reason(reasonInput.text.toString())
        if (reason == null) { reasonInput.error = "줄바꿈 없이 200자 이내로 적어 주세요"; return }
        val example = selectedExample
        val snapshot = session?.savedPlaces?.state
        val selected = snapshot?.selected
        if (example == null && selected == null) return
        proposalAction { owner ->
            if (example != null && selectedExample === example) owner.propose(example, reason)
            else if (example == null && owner.savedPlaces?.state?.snapshot == snapshot?.snapshot && owner.savedPlaces?.state?.selected === selected) owner.proposeSelectedPlace(reason)
        }
    }
    private fun respond(accept: Boolean) {
        val p = displayedProposal ?: return
        if (session?.canRespond(p.id, p.version) != true) return
        proposalAction { it.respond(p.id, p.version, accept) }
    }
    private fun recover() {
        val owner = session ?: return
        if (!owner.state.roomConnected || owner.state.connection == NativeConnection.FAILED) navigate(Page.SETTINGS)
        else if (VehiclePresentation.currentAcceptedTarget(owner.state, owner.acceptedProposal)) proposalAction { it.retryAcceptedProposal() }
        else service?.manual { it.coordinator.applyShared() }
    }
    private fun openRoomForm(join: Boolean) {
        joinForm = join; formAttempted = false; inviteInput.text.clear(); navigate(Page.ROOM_FORM)
    }
    private fun submit() {
        formAttempted = true
        val secret = inviteInput.text.toString().trim(); inviteInput.text.clear()
        service?.manual { it.submit(joinForm, baseInput.text.toString().trim(), nameInput.text.toString().trim(), idInput.text.toString().trim(), secret) }
    }
    private fun showInvitation() {
        val identity = credentials ?: return
        val secret = identity.inviteSecret ?: return
        val content = ui.text("동행방 ID\n${identity.roomId}\n\n초대 비밀\n$secret\n\n함께 가는 차량에만 전달해 주세요.").apply {
            setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16)); setTextIsSelectable(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle("초대 정보").setView(content).setPositiveButton("닫기", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { content.text = "" }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); styleDialogButtons(dialog)
    }
    private fun confirm(message: String, positive: String, action: () -> Unit) {
        val dialog = AlertDialog.Builder(this).setMessage(message).setNegativeButton("취소", null)
            .setPositiveButton(positive) { _, _ -> action() }.show()
        styleDialogButtons(dialog)
    }
    private fun styleDialogButtons(dialog: AlertDialog) {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach { which -> dialog.getButton(which)?.apply {
            textSize = 20f; minHeight = ui.dimension(R.dimen.button_height)
        } }
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 20f
    }
    private fun closePicker() {
        val previous = pickerDialog; pickerDialog = null
        suppressPickerCancel = true; previous?.dismiss(); suppressPickerCancel = false
    }
    private fun showSavedPlaces(controller: SavedPlacesController, snapshot: SavedPlacesState) {
        pickerRequested = false
        var selected = false
        val choices = ui.column().apply { id = R.id.saved_place_choices; setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12)) }
        val scroll = ScrollView(this).apply { addView(choices) }
        val dialog = AlertDialog.Builder(this).setTitle("저장한 장소 선택").setView(scroll).setNegativeButton("선택 취소", null).create()
        pickerDialog = dialog
        snapshot.choices.forEach { place -> ui.add(choices, ui.button("${place.name}\n${place.pleos?.address}", R.id.saved_place_choice) {
            if (controller.select(snapshot.snapshot, place)) { selected = true; selectedExample = null; voiceSelected = null }
            pickerDialog = null; dialog.dismiss(); renderCurrent()
        }, 12) }
        dialog.setOnDismissListener {
            if (pickerDialog === dialog) pickerDialog = null
            if (!selected && !suppressPickerCancel) controller.cancel(snapshot.snapshot)
        }
        dialog.show(); styleDialogButtons(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).id = R.id.saved_places_cancel
    }
    private fun showExamples() {
        val choices = ui.column().apply { setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle("예제 장소").setView(ScrollView(this).apply { addView(choices) }).setNegativeButton("닫기", null).create()
        ui.add(choices, ui.text("시연용 공개 장소예요. 일반 장소 검색은 지원하지 않아요."))
        PrototypePlaces.all.forEach { place -> ui.add(choices, ui.button("${place.name}\n${place.pleos?.address}") {
            service?.manual { it.savedPlaces?.cancel() }
            selectedExample = place; dialog.dismiss(); renderCurrent()
        }, 12) }
        dialog.show(); styleDialogButtons(dialog)
    }
    private fun badge(view: TextView, status: DestinationStatus) {
        view.text = status.text
        val ink = when (status.tone) { StatusTone.SUCCESS -> R.color.success; StatusTone.ATTENTION -> R.color.danger; StatusTone.WORKING -> R.color.primary; StatusTone.NEUTRAL -> R.color.muted }
        val fill = when (status.tone) { StatusTone.SUCCESS -> R.color.success_tint; StatusTone.ATTENTION -> R.color.danger_tint; StatusTone.WORKING -> R.color.primary_tint; StatusTone.NEUTRAL -> R.color.canvas }
        view.setTextColor(ui.color(ink)); view.background = ui.rounded(fill, R.dimen.control_radius)
        view.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
    }
    private fun diagnosticText(state: VehicleState): String {
        fun destination(value: Destination?) = value?.let { "${it.name}\n${it.pleos?.address ?: "주소 없음"}\n위도 ${it.latitude} · 경도 ${it.longitude}\n종류 ${it.kind}\nPOI ${it.pleos}" } ?: "없음"
        val current = state.current as? CurrentDestination.Present
        val confirmed = state.confirmedAt?.let { DateFormat.getTimeInstance().format(Date(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it))) }
        return "서버 주소 ${credentials?.baseUrl ?: baseInput.text}\n\n현재 내비 조회\n${destination(current?.value)}\n\n함께 가는 곳 · 버전 ${state.revision}\n${destination(state.shared)}\n\n선택 원본\n${destination(session?.savedPlaces?.state?.selected ?: selectedExample)}\n\n실제 확인 방식 ${state.confirmationKind ?: "확인 전"} · 시각 ${confirmed ?: "없음"}\n${state.message}\n${session?.roomMessage ?: ""}\n${session?.proposalMessage ?: ""}\n\n제안 상세\n${session?.proposal?.let { "버전 ${it.version} · 상태 ${it.status}\n" + it.responses.joinToString("\n") { vote -> "${vote.member.name}: ${vote.response}" } } ?: "없음"}"
    }

    private fun page(holder: FrameLayout, page: Page, id: Int): LinearLayout {
        val content = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), resources.getDimensionPixelSize(R.dimen.content_width))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec)), heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(32), ui.dp(12), ui.dp(32), ui.dp(32)) }
        val centering = FrameLayout(this).apply { addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
        val scroll = ScrollView(this).apply { this.id = id; isFillViewport = true; addView(centering); visibility = View.GONE }
        holder.addView(scroll, FrameLayout.LayoutParams(-1, -1)); pages[page] = scroll
        return content
    }
    private fun buildScreen() {
        val root = ui.column().apply { setBackgroundColor(ui.color(R.color.canvas)) }
        root.setOnApplyWindowInsetsListener { view, insets -> view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets }
        val header = ui.row().apply { setPadding(ui.dp(32), ui.dp(24), ui.dp(32), ui.dp(20)) }
        backButton = ui.button("홈으로", R.id.nav_home) { navigate(if (credentials == null) Page.WELCOME else Page.HOME) }
        header.addView(backButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(24) })
        pageTitle = ui.text("동행", true); header.addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        stopButton = ui.button("동행 중지", R.id.stop_convoy, Role.DANGER) { voiceInput.cancel(); invalidateVoiceReview(); placesLoadIntent.cancel(); pickerRequested = false; service?.stopAutomatic() }
        header.addView(stopButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(16) })
        settingsButton = ui.button("설정", R.id.nav_settings) { navigate(Page.SETTINGS) }; header.addView(settingsButton)
        root.addView(header)
        val holder = FrameLayout(this); root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        buildHome(page(holder, Page.HOME, R.id.home_page))
        buildComposer(page(holder, Page.COMPOSE, R.id.compose_page))
        buildSettings(page(holder, Page.SETTINGS, R.id.settings_page))
        buildWelcome(page(holder, Page.WELCOME, R.id.welcome_page))
        buildRoomForm(page(holder, Page.ROOM_FORM, R.id.room_form_page))
        setContentView(root); showPage(Page.HOME)
    }
    private fun buildHome(root: LinearLayout) {
        connectionText = ui.text(id = R.id.connection_status).apply { setTextColor(ui.color(R.color.muted)) }
        ui.add(root, connectionText)
        (connectionText.layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(20)
        homeNotice = ui.text().apply { setTextColor(ui.color(R.color.danger)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(root, homeNotice, 12)
        proposalCard = ui.card(root).apply { id = R.id.proposal_card }
        ui.add(proposalCard, ui.text("새 목적지 제안").apply { setTextColor(ui.color(R.color.primary)) })
        proposalName = ui.text(title = true, id = R.id.proposal_name); ui.add(proposalCard, proposalName, 16)
        proposalReason = ui.text(id = R.id.proposal_reason); ui.add(proposalCard, proposalReason, 12)
        proposalProposer = ui.text(id = R.id.proposal_proposer).apply { setTextColor(ui.color(R.color.muted)) }; ui.add(proposalCard, proposalProposer, 12)
        proposalStatus = ui.text(id = R.id.proposal_status).apply { setTextColor(ui.color(R.color.primary)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(proposalCard, proposalStatus, 16)
        responseRow = ui.row(); acceptButton = ui.button("수락", R.id.accept_proposal, Role.PRIMARY) { respond(true) }
        rejectButton = ui.button("거절", R.id.reject_proposal, Role.DANGER) { respond(false) }
        responseRow.addView(acceptButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
        responseRow.addView(rejectButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
        ui.add(proposalCard, responseRow, 24)
        proposalAddress = ui.text().apply { setTextColor(ui.color(R.color.muted)) }; ui.add(proposalCard, proposalAddress, 20)
        sharedCard = ui.card(root)
        ui.add(sharedCard, ui.text("함께 가는 곳").apply { setTextColor(ui.color(R.color.muted)) })
        sharedName = ui.text(title = true, id = R.id.shared_place_name).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.destination_size))
        }; ui.add(sharedCard, sharedName, 20)
        sharedAddress = ui.text(id = R.id.shared_place_address).apply { setTextColor(ui.color(R.color.muted)) }; ui.add(sharedCard, sharedAddress, 12)
        destinationStatus = ui.text(id = R.id.destination_status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; ui.add(sharedCard, destinationStatus, 24)
        changeButton = ui.button("목적지 바꾸기", R.id.change_destination, Role.PRIMARY) { navigate(Page.COMPOSE) }; ui.add(sharedCard, changeButton, 28)
        recoveryButton = ui.button("다시 반영", R.id.home_recovery) { recover() }; ui.add(sharedCard, recoveryButton, 12)
        automaticText = ui.text().apply { setTextColor(ui.color(R.color.muted)) }; ui.add(root, automaticText, 4)
        automaticStartButton = ui.button("동행 시작", R.id.start_convoy) { automaticStart() }; ui.add(root, automaticStartButton, 16)
    }
    private fun buildComposer(root: LinearLayout) {
        buildVoiceComposer(root)
        val card = ui.card(root)
        ui.add(card, ui.text("1  장소 선택", true))
        ui.add(card, ui.text("내비에 ☆ 저장한 장소만 보여요.").apply { setTextColor(ui.color(R.color.muted)) }, 12)
        selectPlacesButton = ui.button("저장한 장소 선택", R.id.select_saved_place, Role.PRIMARY) { requestPlaces() }; ui.add(card, selectPlacesButton, 24)
        placesStatus = ui.text(id = R.id.saved_places_status).apply { setTextColor(ui.color(R.color.muted)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(card, placesStatus, 16)
        reloadPlacesButton = ui.button("목록 새로 불러오기", R.id.reload_saved_places) { requestPlaces(refresh = true) }; ui.add(card, reloadPlacesButton, 12)
        cancelPlacesButton = ui.button("선택 취소", R.id.cancel_place_selection) {
            placesLoadIntent.cancel(); placesLoadFailed = false
            pickerRequested = false; closePicker(); selectedExample = null; service?.manual { it.savedPlaces?.cancel() }
        }; ui.add(card, cancelPlacesButton, 12)
        val preview = ui.card(root)
        ui.add(preview, ui.text("2  확인하고 보내기", true))
        selectedName = ui.text(title = true, id = R.id.selected_place_name); ui.add(preview, selectedName, 24)
        selectedAddress = ui.text(id = R.id.selected_place_address).apply { setTextColor(ui.color(R.color.muted)) }; ui.add(preview, selectedAddress, 12)
        ui.add(preview, ui.text("모든 차량이 수락하면 내비에 반영해요."), 24)
        val reasonGroup = ui.column(); ui.add(preview, reasonGroup, 24)
        reasonInput = ui.field(reasonGroup, "이유 (선택)", R.id.proposal_reason_input).apply {
            hint = "예: 여기서 함께 쉬어요"; filters = arrayOf(InputFilter.LengthFilter(200))
        }
        sendButton = ui.button("제안 보내기", R.id.send_proposal, Role.PRIMARY) { sendProposal() }; ui.add(preview, sendButton, 8)
        examplesButton = ui.button("예제 장소", R.id.example_places) { showExamples() }; ui.add(root, examplesButton)
    }
    private fun buildSettings(root: LinearLayout) {
        settingsStatus = ui.text().apply { setTextColor(ui.color(R.color.muted)) }; ui.add(root, settingsStatus)
        val connection = ui.card(root); ui.add(connection, ui.text("연결", true))
        nativeSummary = ui.text(); ui.add(connection, nativeSummary, 16)
        reconnectRoomButton = ui.button("동행방 다시 연결", R.id.reconnect_room) { service?.manual { it.reconnectRoom() } }
        connectButton = ui.button("내비 다시 연결", R.id.reconnect_native) {
            confirm("불러온 장소 선택이 해제돼요. 새로 저장한 장소는 다시 연결한 뒤 불러와 주세요.", "다시 연결") { service?.manual { it.coordinator.connect() } }
        }
        ui.pair(connection, reconnectRoomButton, connectButton, 24)
        refreshButton = ui.button("내비 목적지 다시 조회", R.id.refresh_native) { service?.manual { it.coordinator.refresh() } }; ui.add(connection, refreshButton, 12)
        retryGroup = ui.card(root); ui.add(retryGroup, ui.text("수락한 목적지", true))
        ui.add(retryGroup, ui.text("반영을 확인하지 못했다면 직접 다시 시도할 수 있어요."), 12)
        retryButton = ui.button("수락한 목적지 다시 반영", R.id.retry_accepted) { proposalAction { it.retryAcceptedProposal() } }; ui.add(retryGroup, retryButton, 20)
        resetButton = ui.button("대표 내비의 자동 공유 재개", R.id.reset_protection) {
            confirm("합의한 목적지의 보호를 풀고, 이후 대표 내비의 목적지 변경을 다시 공유해요.", "자동 공유 재개") { service?.manual { it.resetProposalProtection() } }
        }; ui.add(retryGroup, resetButton, 12)
        val manual = ui.card(root); ui.add(manual, ui.text("직접 공유 · 반영", true))
        ui.add(manual, ui.text("자동 동행을 끈 상태에서 사용해요. 직접 반영한 뒤에는 45초 안에 앱으로 돌아와 확인해 주세요."), 12)
        shareButton = ui.button("내비 목적지 공유", R.id.manual_share) { service?.manual { it.coordinator.shareCurrent() } }
        applyButton = ui.button("함께 가는 곳을 내비에 반영", R.id.manual_apply) { service?.manual { it.coordinator.applyShared() } }
        ui.pair(manual, shareButton, applyButton, 24)
        clearButton = ui.button("함께 가는 곳 지우기", R.id.clear_shared, Role.DANGER) {
            confirm("동행방의 목적지만 지워요. 각 차량의 내비 안내는 유지돼요.", "목적지 지우기") { service?.manual { it.coordinator.clearShared() } }
        }; ui.add(manual, clearButton, 12)
        val room = ui.card(root); ui.add(room, ui.text("동행방 관리", true))
        inviteButton = ui.button("초대 정보 보기", R.id.invitation) { showInvitation() }; ui.add(room, inviteButton, 20)
        leaveButton = ui.button("동행방 나가기", R.id.leave_room, Role.DANGER) {
            confirm(if (credentials?.role == "host") "대표가 나가면 동행방이 종료돼요. 각 차량의 내비 안내는 유지돼요." else "이 차량이 동행방에서 나가요. 내비 안내는 유지돼요.", if (credentials?.role == "host") "동행방 종료" else "나가기") { service?.manual { it.leave() } }
        }
        forgetButton = ui.button("이 기기 연결 지우기", R.id.forget_room, Role.DANGER) {
            confirm("이 기기의 참여 정보를 지워요. 동행방의 권한은 종료되거나 만료될 때까지 남을 수 있어요.", "연결 지우기") { service?.manual { it.forget(true) } }
        }; ui.pair(room, leaveButton, forgetButton, 12)
        developerButton = ui.button("개발 상세 보기", R.id.developer_details) {
            val open = developerContent.visibility != View.VISIBLE
            developerContent.visibility = if (open) View.VISIBLE else View.GONE
            developerButton.text = if (open) "개발 상세 닫기" else "개발 상세 보기"; renderCurrent()
        }; ui.add(root, developerButton)
        developerContent = ui.column().apply { id = R.id.developer_content; visibility = View.GONE }; ui.add(root, developerContent, 20)
        developerText = ui.text(); ui.add(developerContent, developerText)
    }
    private fun buildWelcome(root: LinearLayout) {
        val card = ui.card(root)
        ui.add(card, ui.text("함께 갈 곳을 정해요", true))
        ui.add(card, ui.text("동행방을 만들거나 초대받은 동행에 참여해 주세요."), 20)
        ui.pair(card, ui.button("동행 만들기", R.id.create_room_entry, Role.PRIMARY) { openRoomForm(false) }, ui.button("초대로 참여", R.id.join_room_entry) { openRoomForm(true) }, 32)
        ui.add(root, ui.text("정차 중이거나 동승자가 조작해 주세요.").apply { setTextColor(ui.color(R.color.muted)) }, 12)
    }
    private fun buildRoomForm(root: LinearLayout) {
        val card = ui.card(root)
        nameInput = ui.field(card, "차량 이름", R.id.vehicle_name_input, "차량 A").apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
        joinFields = ui.column(); ui.add(card, joinFields)
        idInput = ui.field(joinFields, "동행방 ID", R.id.room_id_input)
        inviteInput = ui.field(joinFields, "초대 비밀", R.id.invitation_input, password = true)
        submitRoomButton = ui.button("동행방 만들기", R.id.submit_room, Role.PRIMARY) { submit() }; ui.add(card, submitRoomButton, 12)
        formStatus = ui.text(id = R.id.room_form_status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; ui.add(card, formStatus, 20)
        val serverContent = ui.column().apply { visibility = View.GONE }
        val serverButton = ui.button("연결 주소 · 개발 설정", R.id.server_details) { serverContent.visibility = if (serverContent.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        ui.add(root, serverButton); ui.add(root, serverContent, 20)
        baseInput = ui.field(serverContent, "로컬 서버 주소", R.id.server_address_input, "http://127.0.0.1:4182")
    }
}
