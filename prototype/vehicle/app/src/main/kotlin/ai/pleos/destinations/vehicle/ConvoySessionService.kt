package ai.pleos.destinations.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import ai.pleos.destinations.core.AutomaticMode
import ai.pleos.destinations.core.VehicleState
import java.util.concurrent.Executors

/** Ongoing, user-initiated convoy coordination; UI binding never owns transport/SDK lifetime. */
class ConvoySessionService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@ConvoySessionService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val serviceEpoch = SystemClock.elapsedRealtime()
    lateinit var session: VehicleSession; private set
    private lateinit var journal: EvidenceJournal
    private var foreground = false
    private var authorizedAction: (() -> Unit)? = null
    private var uiCount = 0
    private var destroyed = false
    private var ticking = false
    private var lastAlertId: String? = null
    private var lastState: VehicleState? = null
    private var lastProposal: DestinationProposal? = null
    private var lastAcceptedProposal: DestinationProposal? = null
    private var lastProposalLease = false
    private var lastProposalBusy = false
    private var lastProposalMessage = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (!destroyed && ticking) { session.tick(); main.postDelayed(this, 250) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        journal = EvidenceJournal(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "길맞춤 수신과 목적지 확인", NotificationManager.IMPORTANCE_LOW).apply {
                description = "사용자가 시작한 변경 요청 수신과 수락한 목적지의 실제 확인·중지, 기존 동행과 제안 진행"
                setShowBadge(false)
            })
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(REQUEST_CHANNEL, "목적지 변경 요청", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "다른 차량이 정한 목적지의 변경 요청 · 수락 전 안내 유지"
                setShowBadge(true)
            })
        session = VehicleSession(PleosNavigationGateway(applicationContext), SystemClock::elapsedRealtime,
            SecureSessionStore(this), Executors.newFixedThreadPool(3), { main.post(it) }, journal::append, PleosNavigationGateway(applicationContext))
        session.observe {
            if (lastState != it.state || lastProposal != it.proposal || lastAcceptedProposal != it.acceptedProposal || lastProposalLease != it.hasProposalLease || lastProposalBusy != it.proposalBusy || lastProposalMessage != it.proposalMessage) {
                lastState = it.state; lastProposal = it.proposal; lastAcceptedProposal = it.acceptedProposal; lastProposalLease = it.hasProposalLease; lastProposalBusy = it.proposalBusy; lastProposalMessage = it.proposalMessage
                journal.session("sessionState", it.state, session.coordinator.roomEpoch, serviceEpoch, uiCount,
                    it.proposal, it.hasProposalLease, it.proposalBusy, it.proposalMessage, it.coordinator.proposalPublicationHeld, it.acceptedProposal)
            }
            updateRequestNotification()
            if (foreground) {
                if (it.state.automatic == AutomaticMode.OFF && !it.pendingAutomatic && !it.hasProposalLease && !it.changeRequests.hasLease) finishForeground()
                else updateNotification()
            }
        }
        record("serviceCreate")
        ensureTicker(); session.start()
    }
    override fun onBind(intent: Intent?): IBinder = binder
    fun attachUi(observer: (VehicleSession) -> Unit) {
        uiCount += 1; ensureTicker()
        record("activityStartAttach")
        session.observe(observer)
    }
    fun detachUi(observer: (VehicleSession) -> Unit) {
        session.removeObserver(observer); uiCount = (uiCount - 1).coerceAtLeast(0)
        record("activityStopDetach")
    }
    fun manual(action: (VehicleSession) -> Unit) { ensureTicker(); action(session) }
    fun requestReceiveFromUi() {
        if (uiCount == 0 || session.credentials == null || session.busy || session.leaving) return
        requestCoordination { session.changeRequests.startListening(); if (!session.state.roomConnected) session.reconnectRoom() }
    }
    fun requestAutomaticFromUi() {
        if (uiCount == 0 || session.credentials == null || session.busy || session.leaving ||
            session.state.automatic != AutomaticMode.OFF || session.pendingAutomatic) return
        requestCoordination { session.startAutomatic() }
    }
    fun requestProposalFromUi(action: (VehicleSession) -> Unit) {
        if (uiCount == 0 || session.credentials == null || session.busy || session.leaving) return
        requestCoordination { action(session) }
    }
    private fun requestCoordination(action: () -> Unit) {
        if (authorizedAction != null) return
        if (foreground) { ensureTicker(); action(); return }
        authorizedAction = action
        startForegroundService(Intent(this, ConvoySessionService::class.java).setAction(ACTION_BEGIN))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEGIN -> {
                val action = authorizedAction; authorizedAction = null
                if (action != null) {
                    if (!foreground) {
                        foreground = true
                        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        else startForeground(NOTIFICATION, notification())
                    }
                    ensureTicker(); action()
                    journal.session("coordinationServiceStart", session.state, session.coordinator.roomEpoch, serviceEpoch, uiCount,
                        session.proposal, session.hasProposalLease, session.proposalBusy, session.proposalMessage, session.coordinator.proposalPublicationHeld, session.acceptedProposal)
                    if (session.state.automatic == AutomaticMode.OFF && !session.pendingAutomatic && !session.hasProposalLease && !session.changeRequests.hasLease) finishForeground()
                } else if (!foreground) stopSelfResult(startId)
            }
            ACTION_STOP -> stopAutomatic()
            else -> if (!foreground) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    fun stopAutomatic() {
        authorizedAction = null
        session.stopAutomatic()
        journal.session("automaticServiceStop", session.state, session.coordinator.roomEpoch, serviceEpoch, uiCount,
            session.proposal, session.hasProposalLease, session.proposalBusy, session.proposalMessage, session.coordinator.proposalPublicationHeld, session.acceptedProposal)
        finishForeground()
    }
    private fun record(event: String) = journal.session(event, session.state, session.coordinator.roomEpoch, serviceEpoch, uiCount,
        session.proposal, session.hasProposalLease, session.proposalBusy, session.proposalMessage, session.coordinator.proposalPublicationHeld, session.acceptedProposal)
    private fun ensureTicker() { if (!ticking) { ticking = true; main.post(ticker) } }
    private fun finishForeground() {
        foreground = false; authorizedAction = null
        ticking = false; main.removeCallbacks(ticker)
        getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun notification(): Notification {
        val state = session.state
        val role = if (session.changeRequests.hasLease) "길맞춤 · 변경 요청" else if (session.hasProposalLease) {
            if (session.credentials?.role == "host") "대표 · 목적지 제안" else "참여 · 목적지 제안"
        } else if (session.credentials?.role == "host") "대표 · 자동 공유" else "참여 · 자동 따르기"
        val status = when {
            session.changeRequests.busy -> "변경 요청 확인 중"
            session.changeRequests.listening -> "목적지 변경 요청 알림 받는 중"
            session.changeRequests.hasLease -> "수락한 목적지 실제 확인 중"
            session.pendingAutomatic -> "공유방 인증 확인 중"
            session.proposalBusy -> "제안 응답 확인 중 · 순정 안내 유지"
            session.hasProposalLease && !session.hasProposalOperation -> "제안 대기 · 전원 수락 전 안내 유지"
            session.hasProposalLease -> when (state.applying) {
                ai.pleos.destinations.core.ApplyPhase.CONFIRMED -> if (state.confirmationKind == ai.pleos.destinations.core.DestinationKind.PLEOS_PLACE)
                    "선택한 장소로 목적지 설정 확인" else "순정 목적지 좌표 일치 확인"
                ai.pleos.destinations.core.ApplyPhase.FAILED -> "순정 반영 실패 · 직접 재시도 필요"
                ai.pleos.destinations.core.ApplyPhase.TIMED_OUT -> "순정 확인 시간 초과 · 직접 재시도 필요"
                ai.pleos.destinations.core.ApplyPhase.REQUESTED -> "수락 확정 · 순정 요청 전송"
                else -> "수락 확정 · 실제 순정 목적지 확인 중"
            }
            !state.roomConnected -> "공유방 재연결 중"
            state.automatic == AutomaticMode.LEADER -> if (state.sharing) "목적지 변경 공유 중" else "순정 목적지 변경 대기"
            state.shared == null -> "공유 목적지 대기 · 순정 안내 유지"
            else -> when (state.applying) {
                ai.pleos.destinations.core.ApplyPhase.CONFIRMED -> if (state.confirmationKind == ai.pleos.destinations.core.DestinationKind.PLEOS_PLACE)
                    "선택한 장소로 목적지 설정 확인" else "순정 목적지 좌표 일치 확인"
                ai.pleos.destinations.core.ApplyPhase.REQUESTED -> "순정 내비 요청 전송 · 일치 확인 전"
                ai.pleos.destinations.core.ApplyPhase.ROUTING -> "경로 탐색 중 · 일치 확인 전"
                ai.pleos.destinations.core.ApplyPhase.VERIFYING -> "실제 순정 목적지 확인 중"
                ai.pleos.destinations.core.ApplyPhase.FAILED -> "순정 내비 확인 실패"
                ai.pleos.destinations.core.ApplyPhase.TIMED_OUT -> "확인 시간 초과"
                else -> "최신 목적지 자동 적용 대기"
            }
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, ConvoySessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("$status · 동행 중 · $role")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (session.changeRequests.hasLease) "알림 수신 중지" else "동행 중지", stop).build()).build()
    }
    private fun updateRequestNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val request = session.changeRequests.pending?.takeIf { session.changeRequests.listening }
        if (request == null) { manager.cancel(REQUEST_NOTIFICATION); lastAlertId = null; return }
        val open = PendingIntent.getActivity(this, 29, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (lastAlertId == request.id) return
        val text = "${request.sender.name} · ${request.destination.name}"
        val notification = Notification.Builder(this, REQUEST_CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("목적지 변경 요청").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n${request.destination.pleos?.address ?: ""}\n내용을 확인하고 수락해 주세요."))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(lastAlertId == request.id)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_EVENT)
            .build()
        manager.notify(REQUEST_NOTIFICATION, notification); lastAlertId = request.id
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
    override fun onTaskRemoved(rootIntent: Intent?) { stopAutomatic(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        destroyed = true; ticking = false; main.removeCallbacksAndMessages(null)
        record("serviceDestroy")
        getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION)
        session.close(); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val REQUEST_CHANNEL = "destination-change-requests"
        private const val REQUEST_NOTIFICATION = 1429
        private const val CHANNEL = "convoy-coordination"
        private const val NOTIFICATION = 1401
        private const val ACTION_BEGIN = "ai.pleos.destinations.vehicle.BEGIN_AUTOMATIC"
        private const val ACTION_STOP = "ai.pleos.destinations.vehicle.STOP_AUTOMATIC"
    }
}
