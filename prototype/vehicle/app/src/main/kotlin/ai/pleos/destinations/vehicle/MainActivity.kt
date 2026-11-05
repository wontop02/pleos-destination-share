package ai.pleos.destinations.vehicle

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.destinations.core.ApplyPhase
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleCoordinator
import ai.pleos.destinations.core.VehicleState
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(3)
    private lateinit var store: SecureSessionStore
    private lateinit var coordinator: VehicleCoordinator
    private var active = false
    private var lifecycleEpoch = 0L
    private var feedSerial = 0L
    private var feedRunning = AtomicBoolean(false)
    private var credentials: RoomCredentials? = null
    private var client: RoomHttpClient? = null
    private var temporaryClient: RoomHttpClient? = null
    private var lastSequence = -1L
    private var busy = false
    private var leaving = false
    private var roomMessage = "공유방에 연결해 주세요."
    private lateinit var nativeText: TextView
    private lateinit var currentText: TextView
    private lateinit var sharedText: TextView
    private lateinit var messageText: TextView
    private lateinit var roomText: TextView
    private lateinit var roomForm: LinearLayout
    private lateinit var baseInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var idInput: EditText
    private lateinit var inviteInput: EditText
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var shareButton: Button
    private lateinit var clearButton: Button
    private lateinit var applyButton: Button
    private lateinit var inviteButton: Button
    private lateinit var leaveButton: Button
    private lateinit var forgetButton: Button
    private lateinit var reconnectRoomButton: Button
    private lateinit var createButton: Button
    private lateinit var joinButton: Button
    private val ticker = object : Runnable {
        override fun run() { if (active) { coordinator.tick(); main.postDelayed(this, 500) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureSessionStore(this)
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        active = true
        lifecycleEpoch += 1
        val journal = EvidenceJournal(this)
        coordinator = VehicleCoordinator(PleosNavigationGateway(this), SystemClock::elapsedRealtime,
            { render(it) }, { epoch, destination -> publish(epoch, destination) }, journal::append)
        val loaded = store.load()
        if (loaded.damaged) roomMessage = "보안 저장 정보를 읽을 수 없어 지웠습니다. 다시 참여해 주세요."
        val awaitingAuth = loaded.credentials?.let { PendingConfirmationCache.hasFor(it.roomId, it.memberId) } == true
        if (!awaitingAuth) { PendingConfirmationCache.clear(); coordinator.connect() }
        loaded.credentials?.let { resumeRoom(it) }
        render(coordinator.state)
        main.post(ticker)
    }

    override fun onStop() {
        coordinator.confirmationSnapshot()?.let { pending ->
            credentials?.let { PendingConfirmationCache.save(it.roomId, it.memberId, pending) }
        }
        active = false
        lifecycleEpoch += 1
        main.removeCallbacks(ticker)
        stopFeed()
        temporaryClient?.close(); temporaryClient = null
        coordinator.stop()
        inviteInput.text.clear()
        busy = false; leaving = false
        super.onStop()
    }

    override fun onDestroy() { workers.shutdownNow(); super.onDestroy() }

    private fun ui(epoch: Long, action: () -> Unit) { main.post { if (active && epoch == lifecycleEpoch) action() } }
    private fun stopFeed() {
        feedSerial += 1
        feedRunning.set(false)
        client?.close(); client = null
    }

    private fun submit(join: Boolean) {
        if (busy || credentials != null) return
        val base = baseInput.text.toString().trim()
        val name = nameInput.text.toString().trim()
        val roomId = idInput.text.toString().trim()
        val secret = inviteInput.text.toString().trim()
        if (runCatching { validateBaseUrl(base) }.isFailure || name.isBlank() || name.length > 40 ||
            (join && (!RoomCredentials.ID.matches(roomId) || !RoomCredentials.SECRET.matches(secret)))) {
            roomMessage = "로컬 주소·이름을 확인해 주세요. 참여 시 공유방 ID와 초대 비밀도 필요합니다."
            render(coordinator.state); return
        }
        inviteInput.text.clear()
        busy = true; roomMessage = if (join) "공유방 참여 중" else "공유방 만드는 중"
        render(coordinator.state)
        val epoch = lifecycleEpoch
        val api = RoomHttpClient(base); temporaryClient = api
        workers.execute {
            val result = runCatching { if (join) api.join(roomId, secret, name) else api.create(name) }
            ui(epoch) {
                temporaryClient = null
                busy = false
                result.fold(onSuccess = { (identity, snapshot) ->
                    try { store.save(identity); attach(identity, snapshot, api) }
                    catch (_: Exception) {
                        roomMessage = "보안 저장 실패 · 연결하지 않았습니다. 서버 정리를 시도합니다."
                        workers.execute { runCatching { api.leave(identity) }; api.close() }
                    }
                }, onFailure = { roomMessage = failureMessage(it); api.close() })
                render(coordinator.state)
            }
        }
    }

    private fun resumeRoom(identity: RoomCredentials) {
        credentials = identity
        busy = true; roomMessage = "저장한 공유방 인증 확인 중"
        val epoch = lifecycleEpoch
        val api = RoomHttpClient(identity.baseUrl); temporaryClient = api
        workers.execute {
            val result = runCatching { api.snapshot(identity) }
            ui(epoch) {
                temporaryClient = null
                busy = false
                result.fold(onSuccess = { attach(identity, it, api) }, onFailure = {
                    api.close(); roomMessage = failureMessage(it)
                    if (it is RoomFailure && it.status in listOf(401, 403, 404)) forget(false)
                })
                render(coordinator.state)
            }
        }
    }

    private fun attach(identity: RoomCredentials, snapshot: RoomSnapshot, api: RoomHttpClient) {
        require(identity.role == snapshot.role)
        stopFeed()
        credentials = identity
        client = api
        lastSequence = snapshot.update.sequence
        coordinator.attachRoom(snapshot.role == "host", snapshot.update.revision, snapshot.update.destination)
        val pending = PendingConfirmationCache.takeFor(identity.roomId, identity.memberId)
        if (pending != null) {
            if (!coordinator.resumeConfirmation(pending) && coordinator.state.connection == NativeConnection.DISCONNECTED) coordinator.connect()
        } else if (coordinator.state.connection == NativeConnection.DISCONNECTED) coordinator.connect()
        roomMessage = "${if (snapshot.role == "host") "대표 차량" else "참여 차량"} · 공유방 연결됨"
        startFeed(identity, api)
    }

    private fun startFeed(identity: RoomCredentials, api: RoomHttpClient) {
        val running = AtomicBoolean(true); feedRunning = running
        val serial = feedSerial
        val epoch = lifecycleEpoch
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
                                    if (snapshot != null && !coordinator.state.roomConnected) {
                                        require(snapshot.role == identity.role)
                                        coordinator.attachRoom(snapshot.role == "host", update.revision, update.destination)
                                        lastSequence = update.sequence
                                    } else if (update.sequence > lastSequence) {
                                        lastSequence = update.sequence
                                        coordinator.receive(coordinator.roomEpoch, update.revision, update.destination)
                                    }
                                    roomMessage = "${if (identity.role == "host") "대표 차량" else "참여 차량"} · 새 목적지 수신 연결됨"
                                    render(coordinator.state)
                                }
                                wait = 500
                            }
                            "closed" -> deliver {
                                roomMessage = "공유방이 종료되었거나 참여 권한이 해제되었습니다."
                                forget(false)
                            }
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
        if (coordinator.state.roomConnected) coordinator.suspendRoom()
        roomMessage = "공유방 연결 끊김 · 재연결 중. 마지막 받은 목적지입니다."
        render(coordinator.state)
    }

    private fun publish(roomEpoch: Long, destination: Destination?) {
        val identity = credentials ?: return
        val api = client ?: return
        val expectedRevision = coordinator.state.revision
        val epoch = lifecycleEpoch
        workers.execute {
            val result = runCatching { api.publish(identity, destination, expectedRevision) }
            ui(epoch) {
                if (credentials !== identity || coordinator.roomEpoch != roomEpoch) return@ui
                result.fold(onSuccess = { coordinator.shareFinished(roomEpoch, it.revision, it.destination, true) },
                    onFailure = {
                        coordinator.shareFinished(roomEpoch, 0, null, false)
                        roomMessage = failureMessage(it)
                        if (it is RoomFailure && it.status == 409) {
                            // Reconnect's snapshot obtains the latest authoritative destination.
                            stopFeed(); coordinator.suspendRoom(); resumeRoom(identity)
                        } else if (it is RoomFailure && it.status in listOf(401, 403, 404)) forget(false)
                    })
                render(coordinator.state)
            }
        }
    }

    private fun leave() {
        val identity = credentials ?: return
        if (leaving) return
        PendingConfirmationCache.clear()
        stopFeed()
        coordinator.detachRoom()
        leaving = true; roomMessage = "공유방 나가는 중 · 순정 안내는 유지됩니다."
        render(coordinator.state)
        val api = RoomHttpClient(identity.baseUrl); temporaryClient = api
        val epoch = lifecycleEpoch
        workers.execute {
            val result = runCatching { api.leave(identity) }; api.close()
            ui(epoch) {
                temporaryClient = null; leaving = false
                if (credentials !== identity) return@ui
                result.fold(onSuccess = { roomMessage = "공유방 나가기 완료"; forget(false) }, onFailure = {
                    if (it is RoomFailure && it.status in listOf(401, 403, 404)) { roomMessage = "공유방 권한 종료 확인"; forget(false) }
                    else { roomMessage = "서버 나가기 확인 실패 · 다시 시도하거나 이 기기 연결을 지워 주세요."; render(coordinator.state) }
                })
            }
        }
    }

    private fun forget(localOnly: Boolean) {
        PendingConfirmationCache.clear()
        stopFeed(); store.clear(); credentials = null
        coordinator.detachRoom()
        busy = false; leaving = false
        if (localOnly) roomMessage = "이 기기 인증 정보를 지웠습니다. 서버 권한은 종료 또는 최대 6시간 만료 전까지 남을 수 있습니다."
        render(coordinator.state)
    }

    private fun showInvitation() {
        val identity = credentials ?: return
        val secret = identity.inviteSecret ?: return
        val text = text("공유방 ID\n${identity.roomId}\n\n초대 비밀\n$secret\n\n신뢰하는 참여자에게만 전달해 주세요.")
        text.setTextIsSelectable(true)
        val dialog = AlertDialog.Builder(this).setTitle("공유방 초대 정보").setView(text).setPositiveButton("닫기", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { text.text = "" }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    private fun render(state: VehicleState) {
        nativeText.text = when (state.connection) {
            NativeConnection.DISCONNECTED -> "순정 내비 미연결"
            NativeConnection.CONNECTING -> "순정 내비 연결 중"
            NativeConnection.CONNECTED -> "순정 내비 연결됨"
            NativeConnection.FAILED -> "순정 내비 연결 또는 조회 실패"
        }
        currentText.text = when (val current = state.current) {
            CurrentDestination.Unknown -> "현재 목적지 미조회"
            CurrentDestination.Loading -> "현재 목적지 조회 중"
            is CurrentDestination.Present -> destinationText(current.value) + "\n조회 시각 " + time(current.observedAt)
            is CurrentDestination.Absent -> "순정 목적지 없음 · 실제 조회 응답 확인"
            is CurrentDestination.Failed -> "목적지 조회 실패 · ${current.reason}"
        }
        sharedText.text = state.shared?.let {
            destinationText(it) + "\n공유 버전 ${state.revision}\n" +
                if (it.canRequestRoute) "순정 POI 정보 있음 · 직접 적용 가능" else "순정 POI 정보 없음 · 차량 적용 불가"
        } ?: "받은 목적지 없음"
        if (!state.roomConnected && state.shared != null) sharedText.append("\n연결 끊김 · 마지막 받은 목적지")
        messageText.text = state.message + (state.confirmedAt?.let { "\n확인 시각 ${time(it)}" } ?: "") +
            if (state.applying == ApplyPhase.TIMED_OUT) "\n이전 요청 확인 시간 초과 · 변경 성공을 확인하지 못했습니다." else ""
        roomText.text = roomMessage
        val applying = state.applying in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING)
        connectButton.isEnabled = state.connection != NativeConnection.CONNECTING && !applying
        refreshButton.isEnabled = state.connection == NativeConnection.CONNECTED && state.current != CurrentDestination.Loading && !applying && !state.sharing
        shareButton.visibility = if (state.host) View.VISIBLE else View.GONE
        clearButton.visibility = if (state.host) View.VISIBLE else View.GONE
        shareButton.isEnabled = state.roomConnected && state.connection == NativeConnection.CONNECTED && !state.sharing && !applying && state.current != CurrentDestination.Loading
        clearButton.isEnabled = state.roomConnected && state.shared != null && !state.sharing && !applying && state.current != CurrentDestination.Loading
        applyButton.isEnabled = state.roomConnected && state.shared?.canRequestRoute == true && !state.sharing && !applying
        inviteButton.visibility = if (credentials?.inviteSecret != null) View.VISIBLE else View.GONE
        leaveButton.visibility = if (credentials != null) View.VISIBLE else View.GONE
        forgetButton.visibility = if (credentials != null) View.VISIBLE else View.GONE
        reconnectRoomButton.visibility = if (credentials != null) View.VISIBLE else View.GONE
        reconnectRoomButton.isEnabled = !busy && !leaving
        leaveButton.isEnabled = !leaving
        forgetButton.isEnabled = !leaving
        roomForm.visibility = if (credentials == null) View.VISIBLE else View.GONE
        createButton.isEnabled = !busy
        joinButton.isEnabled = !busy
    }

    private fun time(elapsed: Long): String = DateFormat.getTimeInstance().format(Date(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsed)))
    private fun destinationText(value: Destination) = "${value.name}\n위도 ${value.latitude} · 경도 ${value.longitude}"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, title: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(getColor(R.color.ink))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(if (title) R.dimen.title_size else R.dimen.body_size))
        setPadding(0, dp(8), 0, dp(8))
        if (title) { setTypeface(typeface, Typeface.BOLD); isAccessibilityHeading = true }
    }
    private fun LinearLayout.button(label: String, action: () -> Unit): Button = Button(this@MainActivity).also {
        it.text = label; it.isAllCaps = false; it.textSize = 20f; it.minHeight = resources.getDimensionPixelSize(R.dimen.button_height)
        it.setOnClickListener { action() }; addView(it, LinearLayout.LayoutParams(-1, -2))
    }
    private fun LinearLayout.field(label: String, initial: String = "", password: Boolean = false): EditText {
        val labelView = text(label); addView(labelView)
        return EditText(this@MainActivity).also {
            it.id = View.generateViewId(); labelView.labelFor = it.id
            it.setText(initial); it.textSize = 20f; it.setSingleLine(true); it.isSaveEnabled = false
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            it.inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addView(it, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun card(parent: LinearLayout, heading: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(android.graphics.Color.WHITE)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        addView(text(heading, true))
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        scroll.addView(root)
        root.addView(text("동행 목적지", true))
        root.addView(text("정차 또는 동승자 조작용 · 받은 목적지는 직접 적용합니다."))
        card(root, "현재 순정 내비").apply {
            nativeText = text(""); addView(nativeText)
            currentText = text(""); addView(currentText)
            connectButton = button("순정 내비 연결 / 다시 연결") { coordinator.connect() }
            refreshButton = button("현재 목적지 다시 조회") { coordinator.refresh() }
            shareButton = button("현재 순정 목적지 공유") { coordinator.shareCurrent() }
        }
        card(root, "받은 공유 목적지").apply {
            sharedText = text(""); addView(sharedText)
            applyButton = button("받은 목적지를 순정 내비에 적용") { coordinator.applyShared() }
            addView(text("순정 내비가 열리면 45초 안에 이 앱으로 돌아와 확인해 주세요. 돌아온 뒤 경로를 다시 요청하지 않고 실제 목적지만 확인합니다."))
            clearButton = button("공유 목적지 지우기") {
                AlertDialog.Builder(this@MainActivity).setMessage("공유방 목적지를 지웁니다. 각 차량의 순정 안내는 유지됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("공유 목적지 지우기") { _, _ -> coordinator.clearShared() }.show()
            }
            messageText = text(""); messageText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; addView(messageText)
        }
        card(root, "공유방").apply {
            roomText = text(""); roomText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; addView(roomText)
            roomForm = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }; addView(roomForm)
            roomForm.apply {
                baseInput = field("로컬 서버 주소", "http://127.0.0.1:4182")
                nameInput = field("차량 이름", "차량 A")
                createButton = button("대표 차량으로 공유방 만들기") { submit(false) }
                idInput = field("참여할 공유방 ID")
                inviteInput = field("초대 비밀", password = true)
                joinButton = button("공유방 참여") { submit(true) }
            }
            inviteButton = button("초대 정보 보기") { showInvitation() }
            reconnectRoomButton = button("공유방 다시 연결") {
                credentials?.let { stopFeed(); coordinator.suspendRoom(); resumeRoom(it); render(coordinator.state) }
            }
            leaveButton = button("공유방 나가기 / 대표는 종료") {
                AlertDialog.Builder(this@MainActivity).setMessage("${if (credentials?.role == "host") "대표가 나가면 공유방이 종료됩니다." else "이 차량이 공유방에서 나갑니다."} 순정 안내는 유지됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("나가기") { _, _ -> leave() }.show()
            }
            forgetButton = button("이 기기 연결 지우기") { forget(true) }
        }
        setContentView(scroll)
    }
}
