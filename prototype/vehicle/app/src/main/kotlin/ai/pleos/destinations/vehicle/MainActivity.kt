package ai.pleos.destinations.vehicle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.CurrentDestination
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NativeConnection
import ai.pleos.destinations.core.VehicleState
import java.text.DateFormat
import java.util.Date

/** Views attach to the service; onStop never tears down an opted-in automatic session. */
class MainActivity : Activity() {
    private var service: ConvoySessionService? = null
    private var bound = false
    private val credentials get() = service?.session?.credentials
    private val busy get() = service?.session?.busy ?: true
    private val leaving get() = service?.session?.leaving ?: false
    private val observer: (VehicleSession) -> Unit = { render(it.state) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val owner = (binder as ConvoySessionService.LocalBinder).service
            service = owner; owner.attachUi(observer)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; render(VehicleState()) }
    }
    private lateinit var nativeText: TextView
    private lateinit var currentText: TextView
    private lateinit var sharedText: TextView
    private lateinit var messageText: TextView
    private lateinit var roomText: TextView
    private lateinit var autoText: TextView
    private lateinit var autoStartButton: Button
    private lateinit var autoStopButton: Button
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

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildScreen(); render(VehicleState()) }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ConvoySessionService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onStop() {
        service?.detachUi(observer); service = null
        if (bound) { unbindService(connection); bound = false }
        inviteInput.text.clear()
        super.onStop()
    }
    private fun automaticStart() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 14)
        } else service?.requestAutomaticFromUi()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 14) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) service?.requestAutomaticFromUi()
            else messageText.text = "진행 상태와 중지를 표시하려면 알림을 허용한 뒤 자동 동행을 시작해 주세요."
        }
    }
    private fun submit(join: Boolean) {
        val secret = inviteInput.text.toString().trim(); inviteInput.text.clear()
        service?.manual { it.submit(join, baseInput.text.toString().trim(), nameInput.text.toString().trim(),
            idInput.text.toString().trim(), secret) }
    }
    private fun showInvitation() {
        val identity = credentials ?: return; val secret = identity.inviteSecret ?: return
        val text = text("공유방 ID\n${identity.roomId}\n\n초대 비밀\n$secret\n\n신뢰하는 참여자에게만 전달해 주세요.")
        text.setTextIsSelectable(true)
        val dialog = AlertDialog.Builder(this).setTitle("공유방 초대 정보").setView(text).setPositiveButton("닫기", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { text.text = "" }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
        roomText.text = service?.session?.roomMessage ?: "공유방 연결 준비 중"
        autoText.text = when (state.automatic) {
            AutomaticMode.OFF -> "자동 동행 꺼짐 · 시작을 선택하기 전에는 자동 공유·적용하지 않습니다."
            AutomaticMode.LEADER -> "동행 중 · 대표 순정 목적지 자동 공유 · 앱 복귀 불필요"
            AutomaticMode.FOLLOWER -> if (state.shared == null) "자동 따르기 켜짐 · 공유 목적지 대기 · 순정 안내 유지"
                else "자동 따르기 켜짐 · 최신 목적지 자동 적용·실제 조회 확인 · 앱 복귀 불필요"
        }
        autoStartButton.text = if (credentials?.role == "host") "동행 시작 · 자동 공유" else "자동 따르기 시작"
        autoStartButton.isEnabled = credentials != null && !busy && !leaving && state.automatic == AutomaticMode.OFF
        autoStopButton.isEnabled = state.automatic != AutomaticMode.OFF || service?.session?.pendingAutomatic == true
        val manual = state.automatic == AutomaticMode.OFF
        val applying = state.applying in listOf(ApplyPhase.CONNECTING, ApplyPhase.REQUESTED, ApplyPhase.ROUTING, ApplyPhase.VERIFYING)
        connectButton.isEnabled = service != null && state.connection != NativeConnection.CONNECTING && !applying
        refreshButton.isEnabled = state.connection == NativeConnection.CONNECTED && state.current != CurrentDestination.Loading && !applying && !state.sharing
        shareButton.visibility = if (state.host) View.VISIBLE else View.GONE
        clearButton.visibility = if (state.host) View.VISIBLE else View.GONE
        shareButton.isEnabled = manual && state.roomConnected && state.connection == NativeConnection.CONNECTED && !state.sharing && !applying && state.current != CurrentDestination.Loading
        clearButton.isEnabled = manual && state.roomConnected && state.shared != null && !state.sharing && !applying && state.current != CurrentDestination.Loading
        applyButton.isEnabled = manual && state.roomConnected && state.shared?.canRequestRoute == true && !state.sharing && !applying
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
        root.addView(text("정차 또는 동승자 조작용 · 자동 동행은 명시적으로 시작합니다."))
        card(root, "자동 동행").apply {
            autoText = text(""); autoText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; addView(autoText)
            addView(text("대표는 순정 목적지 변경을 자동 공유합니다. 참여자는 최신 목적지와 이후 변경의 자동 적용에 동의합니다. 내비 화면에서도 진행 알림으로 확인·중지할 수 있습니다."))
            autoStartButton = button("동행 시작") { automaticStart() }
            autoStopButton = button("자동 동행 중지") { service?.stopAutomatic() }
        }
        card(root, "현재 순정 내비").apply {
            nativeText = text(""); addView(nativeText)
            currentText = text(""); addView(currentText)
            connectButton = button("순정 내비 연결 / 다시 연결") { service?.manual { it.coordinator.connect() } }
            refreshButton = button("현재 목적지 다시 조회") { service?.manual { it.coordinator.refresh() } }
            shareButton = button("현재 순정 목적지 공유") { service?.manual { it.coordinator.shareCurrent() } }
        }
        card(root, "받은 공유 목적지").apply {
            sharedText = text(""); addView(sharedText)
            applyButton = button("받은 목적지를 순정 내비에 적용") { service?.manual { it.coordinator.applyShared() } }
            addView(text("자동 동행 중에는 앱으로 돌아오지 않아도 실제 목적지 확인을 계속합니다. 자동 동행이 꺼진 수동 적용은 45초 안에 돌아와 확인해 주세요."))
            clearButton = button("공유 목적지 지우기") {
                AlertDialog.Builder(this@MainActivity).setMessage("공유방 목적지를 지웁니다. 각 차량의 순정 안내는 유지됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("공유 목적지 지우기") { _, _ -> service?.manual { it.coordinator.clearShared() } }.show()
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
                service?.manual { it.reconnectRoom() }
            }
            leaveButton = button("공유방 나가기 / 대표는 종료") {
                AlertDialog.Builder(this@MainActivity).setMessage("${if (credentials?.role == "host") "대표가 나가면 공유방이 종료됩니다." else "이 차량이 공유방에서 나갑니다."} 순정 안내는 유지됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("나가기") { _, _ -> service?.manual { it.leave() } }.show()
            }
            forgetButton = button("이 기기 연결 지우기") { service?.manual { it.forget(true) } }
        }
        setContentView(scroll)
    }
}
