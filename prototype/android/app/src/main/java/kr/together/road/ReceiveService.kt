package kr.together.road

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** User-started receive-only media session. Never sticky, boot-started, or microphone-enabled. */
class ReceiveService : Service() {
    private val room get() = (application as RoadApplication).room
    private var attached = false
    private var ownedRoom: String? = null
    private val observer: () -> Unit = { if (!room.receiving) stopSelf() else notifyState() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START) { room.stopReceiving(); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) { room.notice("자동 수신에는 지속 알림 허용이 필요해요. 알림이 거절되어 시작하지 않았어요."); stopSelf(); return START_NOT_STICKY }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "원음 자동 수신", NotificationManager.IMPORTANCE_LOW).apply { description = "사용자가 시작한 원음 수신의 연결 상태와 중지 조작"; setSound(null, null); enableVibration(false) })
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, notification())
            if (!room.startReceiving()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            ownedRoom = room.session?.roomId
            if (!attached) { attached = true; room.observe(observer) }
        } catch (_: Exception) { room.stopReceiving("Android가 자동 수신 서비스 시작을 허용하지 않았어요. 열린 화면에서 직접 다시 눌러 주세요."); stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiveService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("함께 가는 길 · 원음 자동 수신")
            .setContentText("${room.connection} · ${if (room.receiving) "새 원음 대기 / 재생" else "시작 중"}")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "수신 중지", stop).build()).build()
    }
    private fun notifyState() { getSystemService(NotificationManager::class.java).notify(ID, notification()) }
    override fun onTaskRemoved(rootIntent: Intent?) { room.stopReceiving("앱 작업을 닫아 자동 수신을 중지했어요."); stopSelf() }
    override fun onDestroy() {
        if (attached) room.removeObserver(observer); attached = false
        if (room.receiving && ownedRoom == room.session?.roomId) room.stopReceiving("자동 수신 서비스가 종료됐어요. 직접 다시 시작할 때까지 수신하지 않아요.")
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    companion object { const val START = "kr.together.road.START_RECEIVE"; const val STOP = "kr.together.road.STOP_RECEIVE"; private const val CHANNEL = "voice_receive"; private const val ID = 4107 }
}
