package kr.together.road

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Platform views, one-column layout, no WebView or embedded web client. */
class MainActivity : Activity() {
    private val room get() = (application as RoadApplication).room
    private lateinit var column: LinearLayout
    private lateinit var globalStatus: TextView
    private var renderedSession: String? = "uninitialized"
    private var connectionText: TextView? = null; private var destinationText: TextView? = null
    private var navigation: Button? = null; private var navigationFeedback: TextView? = null
    private var lastNavigationDestination: Destination? = null
    private var namesText: TextView? = null; private var recordingText: TextView? = null; private var timerText: TextView? = null
    private var playbackText: TextView? = null; private var automatic: Button? = null; private var record: Button? = null
    private var finish: Button? = null; private var cancel: Button? = null; private var retry: Button? = null; private var discard: Button? = null
    private var voiceColumn: LinearLayout? = null; private var lastVoiceIds = ""
    private var destinationSend: Button? = null; private var exit: Button? = null
    private var create: Button? = null; private var join: Button? = null
    private val observer: () -> Unit = { render() }
    private val ink = Color.rgb(23, 51, 47); private val teal = Color.rgb(8, 124, 120)
    private val page = Color.rgb(243, 246, 245); private val muted = Color.rgb(82, 103, 98)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onStart() { super.onStart(); room.foreground(true); room.observe(observer) }
    override fun onStop() { room.removeObserver(observer); room.foreground(false); super.onStop() }
    private fun text(parent: LinearLayout, value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).also {
        it.text = value; it.textSize = size; it.setTextColor(ink); if (bold) it.setTypeface(null, Typeface.BOLD)
        it.setPadding(0, dp(4), 0, dp(8)); parent.addView(it, LinearLayout.LayoutParams(-1, -2))
    }
    private fun card(title: String): LinearLayout = LinearLayout(this).also {
        it.orientation = LinearLayout.VERTICAL; it.setPadding(dp(16), dp(16), dp(16), dp(16))
        it.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(214, 225, 221)) }
        column.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }); text(it, title, 20f, true)
    }
    private fun button(parent: LinearLayout, label: String, secondary: Boolean = false, action: () -> Unit): Button = Button(this).also {
        it.text = label; it.textSize = 16f; it.isAllCaps = false; it.minHeight = dp(48)
        it.setTextColor(if (secondary) teal else Color.WHITE)
        it.background = GradientDrawable().apply { setColor(if (secondary) Color.WHITE else teal); cornerRadius = dp(10).toFloat(); if (secondary) setStroke(dp(1), teal) }
        it.setPadding(dp(12), dp(12), dp(12), dp(12)); it.setOnClickListener { action() }
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
    }
    private fun input(parent: LinearLayout, label: String, value: String = "", numeric: Boolean = false, max: Int = 100): EditText {
        val caption = text(parent, label, 16f, true)
        return EditText(this).also {
            it.id = View.generateViewId(); caption.labelFor = it.id; it.setText(value); it.textSize = 16f; it.setTextColor(ink); it.minHeight = dp(48); it.isSingleLine = true
            it.isSaveEnabled = false; it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            it.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
            it.filters = arrayOf(android.text.InputFilter.LengthFilter(max)); parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
    }
    private fun build() {
        renderedSession = room.session?.roomId; lastVoiceIds = ""; lastNavigationDestination = null
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)); setBackgroundColor(page) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column); setOnApplyWindowInsetsListener { view, insets -> view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets } }
        setContentView(scroll); scroll.requestApplyInsets()
        text(column, "목적지와 짧은 말, 함께", 16f, true).setTextColor(teal); text(column, "함께 가는 길", 30f, true)
        text(column, "정차 중이거나 동승자가 조작해 주세요. 잠금·내비 사용 중 첫 소리는 기기별 검증 전이에요.").setTextColor(muted)
        globalStatus = text(column, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val session = room.session
        if (session == null) {
            val entry = card("목적지 공유방")
            val server = input(entry, "로컬 서버 주소", BuildConfig.DEFAULT_SERVER, max = 200)
            val name = input(entry, "내 이름", max = 40)
            create = button(entry, "공유방 만들기") { room.create(server.text.toString(), name.text.toString()) }
            val invite = input(entry, "초대 링크", max = 400)
            join = button(entry, "초대받은 공유방 참여", true) { room.join(server.text.toString(), invite.text.toString(), name.text.toString()) }
            text(entry, "디버그 로컬 시연이에요. 초대 링크는 신뢰하는 사람에게만 전달해 주세요.").setTextColor(muted)
            return
        }
        val state = card(if (session.role == "host") "대표 차량" else "공유방 참여자")
        connectionText = text(state, "", bold = true); namesText = text(state, "")
        val dest = card("받은 목적지"); destinationText = text(dest, "", 20f, true)
        navigation = button(dest, "네이버 지도에 경로 요청") { requestNavigation() }
        navigationFeedback = text(dest, NAVIGATION_HINT).apply { setTextColor(muted); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val capture = card("내 목소리 그대로")
        text(capture, "1–15초 녹음해 보내요. 15초가 되면 녹음을 마치고 전송해요.")
        timerText = text(capture, "0.0 / 15초", 24f, true)
        recordingText = text(capture, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        record = button(capture, "마이크로 녹음 시작") { requestMicrophone() }
        finish = button(capture, "중지하고 보내기") { room.finishRecording() }
        cancel = button(capture, "녹음 취소", true) { room.cancelRecording() }
        retry = button(capture, "같은 원음 다시 보내기") { room.sendClip() }
        discard = button(capture, "전송하지 못한 원음 지우기", true) { room.discardClip() }
        val receive = card("원음 자동 수신")
        text(receive, "직접 켠 뒤 받은 새 원음만 30초 안에 순서대로 재생해요. 내 원음과 보관 이력은 자동 재생하지 않아요.")
        automatic = button(receive, "자동 수신 시작") { if (room.receiving) room.stopReceiving() else requestNotifications() }
        text(receive, "켜진 동안 지속 알림에서 언제든 중지할 수 있어요. 서비스가 종료되면 직접 다시 시작해야 해요.").setTextColor(muted)
        playbackText = text(receive, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        button(receive, "현재 재생·대기열 중지", true) { room.stopSound() }
        val history = card("원음 목록 · 직접 재생")
        voiceColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; history.addView(this) }
        if (session.role == "host") {
            val edit = card("목적지 변경")
            text(edit, "대표 차량이 직접 입력해요. 차량 내비와는 아직 연결되지 않았어요.").setTextColor(muted)
            val name = input(edit, "장소 이름", "올림픽공원")
            val lat = input(edit, "위도", "37.5209436", true); val lng = input(edit, "경도", "127.1230074", true)
            destinationSend = button(edit, "목적지 전달하기") { room.changeDestination(name.text.toString(), lat.text.toString(), lng.text.toString()) }
            button(edit, "초대 링크 복사", true) {
                room.invitation()?.let { link ->
                    val clip = ClipData.newPlainText("공유방 초대", link)
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); room.notice("초대 링크를 복사했어요.")
                }
            }
        }
        val management = card("공유방 관리")
        text(management, "6시간 뒤 만료돼요. 대표 차량이 종료하면 보관한 원음도 삭제돼요.").setTextColor(muted)
        exit = button(management, if (session.role == "host") "공유방 종료하고 나가기" else "공유방 나가기", true) { room.leave() }
    }
    private fun render() {
        if (renderedSession != room.session?.roomId || !::column.isInitialized) build()
        globalStatus.text = room.status
        if (room.session == null) { create?.isEnabled = !room.busy; join?.isEnabled = !room.busy; return }
        connectionText?.text = getString(R.string.connection_status, room.connection, if (room.receiving) "켜짐" else "꺼짐")
        namesText?.text = getString(R.string.members_status, room.names.size, room.names.joinToString("\n"), if (room.expiresAt > 0) getString(R.string.room_expiry, formatTime(room.expiresAt)) else "")
        destinationText?.text = room.destination?.let { "${it.name}\n위도 ${it.latitude} · 경도 ${it.longitude}\n${formatTime(it.changedAt)} 수신" } ?: "대표 차량이 목적지를 보내면 여기에 표시돼요."
        navigation?.visibility = if (room.destination == null) View.GONE else View.VISIBLE
        navigationFeedback?.visibility = navigation?.visibility ?: View.GONE
        if (lastNavigationDestination != room.destination) { lastNavigationDestination = room.destination; navigationFeedback?.text = NAVIGATION_HINT }
        timerText?.text = String.format(Locale.KOREA, "%.1f / 15초", room.elapsed); recordingText?.text = room.recording
        playbackText?.text = room.playback
        record?.visibility = if (room.isRecording) View.GONE else View.VISIBLE; record?.isEnabled = !room.uploading && room.pendingClip == null && !room.busy
        finish?.visibility = if (room.isRecording) View.VISIBLE else View.GONE; cancel?.visibility = if (room.isRecording) View.VISIBLE else View.GONE
        retry?.visibility = if (room.pendingClip != null && !room.uploading) View.VISIBLE else View.GONE; discard?.visibility = retry?.visibility ?: View.GONE
        automatic?.text = if (room.receiving) "자동 수신 중지" else "자동 수신 시작"; automatic?.isEnabled = !room.isRecording && !room.uploading && !room.busy
        destinationSend?.isEnabled = !room.busy; exit?.isEnabled = !room.busy
        val ids = room.voiceList.joinToString { it.id }
        if (lastVoiceIds != ids || voiceColumn?.childCount == 0) {
            lastVoiceIds = ids; voiceColumn?.removeAllViews()
            voiceColumn?.let { parent ->
                if (room.voiceList.isEmpty()) text(parent, "아직 주고받은 원음이 없어요.")
                room.voiceList.forEach { voice ->
                    text(parent, "${voice.senderName}${if (voice.senderId == room.session?.memberId) " (나)" else ""} · ${formatTime(voice.acceptedAt)} · ${String.format(Locale.KOREA, "%.1f", voice.durationMs / 1000)}초", bold = true)
                    button(parent, "${voice.senderName} 원음 재생", true) { room.manual(voice) }
                }
            }
        }
    }
    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) room.beginRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
    }
    private fun requestNavigation() {
        val destination = room.destination ?: return
        fun report(value: String) { room.notice(value); navigationFeedback?.text = value }
        val url = NaverNavigation.url(destination, BuildConfig.APPLICATION_ID)
        if (url == null) { report("이 좌표는 네이버 지도 경로 요청 범위 밖이에요."); return }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(NaverNavigation.PACKAGE)
        try {
            if (intent.resolveActivity(packageManager) == null) { report("네이버 지도 앱이 없거나 이 경로 요청을 처리할 수 없어요."); return }
            startActivity(intent)
            report("네이버 지도에 경로 요청을 보냈어요. 실제 길 안내 시작은 지도 앱에서 확인해 주세요.")
        } catch (_: ActivityNotFoundException) { report("네이버 지도 앱이 없거나 이 경로 요청을 처리할 수 없어요.") }
        catch (_: SecurityException) { report("Android가 네이버 지도 경로 요청을 허용하지 않았어요.") }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS)
        else startReceiving()
    }
    private fun startReceiving() {
        if (!room.visible || isFinishing) return
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { room.notice("알림이 거절되어 자동 수신을 시작하지 않았어요. 앱 알림을 허용한 뒤 직접 다시 눌러 주세요."); return }
        try { startForegroundService(Intent(this, ReceiveService::class.java).setAction(ReceiveService.START)) }
        catch (_: Exception) { room.notice("Android가 자동 수신 시작을 허용하지 않았어요. 열린 화면에서 직접 다시 눌러 주세요.") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allowed = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == MIC) { if (allowed && room.visible && hasWindowFocus()) room.beginRecording() else room.notice(if (allowed) "마이크 권한을 허용했어요. 열린 화면에서 녹음을 다시 눌러 주세요." else "마이크 권한이 거절되어 녹음하지 않았어요. 앱 권한을 확인한 뒤 다시 눌러 주세요.") }
        if (requestCode == NOTIFICATIONS) { if (allowed) startReceiving() else room.notice("알림 권한이 거절되어 자동 수신을 시작하지 않았어요.") }
    }
    private fun formatTime(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(at))
    companion object { private const val MIC = 10; private const val NOTIFICATIONS = 11; private const val NAVIGATION_HINT = "네이버 지도 앱 설치가 필요해요. 경로 요청 후 실제 길 안내 시작은 앱에서 확인해 주세요." }
}
