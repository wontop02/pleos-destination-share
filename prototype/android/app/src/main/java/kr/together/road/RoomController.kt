package kr.together.road

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class RoadApplication : Application() {
    lateinit var room: RoomController; private set
    override fun onCreate() { super.onCreate(); room = RoomController(this) }
}

/** All public operations and state notifications are serialized on the main looper. */
class RoomController(application: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private val secrets = SecretStore(application)
    private val listeners = LinkedHashSet<() -> Unit>()
    private val roomEpoch = Epoch(); private val playEpoch = Epoch(); private val streamEpoch = Epoch()
    private var roomScope = RequestScope(); private var streamScope: RequestScope? = null; private var playScope: RequestScope? = null
    private var api: ApiClient? = null
    private var clock = ServerClock { SystemClock.elapsedRealtime() }
    private var queue: VoiceQueue? = null
    private val voices = LinkedHashMap<String, Voice>()
    private var revision = -1L
    private var recorder: MicCapture? = null
    private var recordBegan = 0L
    private var retryTimer: Runnable? = null
    private var expiryTimer: Runnable? = null
    private val player = NativePlayer(application) { stopReceiving("오디오 포커스가 바뀌어 자동 수신을 중지했어요. 직접 다시 시작해 주세요.") }
    var session: Session? = null; private set
    var status = "공유방을 만들거나 초대 링크로 참여해 주세요."; private set
    var connection = "연결 전"; private set
    var destination: Destination? = null; private set
    var names = emptyList<String>(); private set
    @Volatile var expiresAt = 0L; private set
    @Volatile var receiving = false; private set
    @Volatile var visible = false; private set
    var busy = false; private set
    var uploading = false; private set
    var playback = "재생 중인 원음이 없어요."; private set
    var recording = "마이크로 1–15초 녹음해 보내요."; private set
    var elapsed = 0.0; private set
    var pendingClip: RetryClip? = null; private set
    val isRecording get() = recorder != null
    val voiceList get() = voices.values.sortedBy { it.sequence }

    init { secrets.load()?.let { stored -> try { enter(stored, null) } catch (_: Exception) { secrets.clear() } } }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }
    private fun errorText(error: Exception) = when (error) {
        is ApiFailure -> error.message ?: "서버 요청에 실패했어요."
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "요청값을 확인해 주세요."
        is SecurityException -> "권한이 거절됐어요. 앱 권한을 확인한 뒤 직접 다시 눌러 주세요."
        else -> "연결 또는 오디오 처리가 중단됐어요. 상태를 확인한 뒤 직접 다시 시도해 주세요."
    }
    fun notice(text: String) { status = text; changed() }
    fun foreground(show: Boolean) {
        visible = show
        if (show) connect() else {
            cancelRecording("화면이 숨겨져 녹음을 취소했어요. 원음을 보내지 않았어요.")
            if (!uploading) clearClip()
            if (!receiving) { disconnect(); stopPlayback(); playback = "화면이 숨겨져 수동 재생을 중지했어요." }
        }
        changed()
    }
    fun create(server: String, name: String) = entry(server) { client, scope -> client.create(name, scope) }
    fun join(server: String, invitation: String, name: String) = entry(server) { client, scope ->
        val (room, secret) = client.endpoint.invitation(invitation); client.join(room, secret, name, scope)
    }
    private fun entry(server: String, action: (ApiClient, RequestScope) -> Reply) {
        if (busy || session != null) return
        val client = try { ApiClient(Endpoint(server, BuildConfig.DEBUG)) } catch (error: Exception) { notice(errorText(error)); return }
        val scope = RequestScope(); busy = true; notice("공유방에 연결하고 있어요.")
        io.execute {
            try {
                val reply = action(client, scope); val body = reply.body
                val created = Session(client.endpoint.origin, body.getString("roomId"), body.getString("memberId"), body.getString("role"), body.getString("token"), body.optString("inviteSecret").takeIf { it.isNotEmpty() })
                main.post { busy = false; try { enter(created, body.getJSONObject("state"), reply.roundTripMs); notice("공유방에 연결됐어요.") } catch (error: Exception) { notice(errorText(error)) } }
            } catch (error: Exception) { main.post { busy = false; notice(errorText(error)) } }
        }
    }
    private fun enter(value: Session, state: JSONObject?, rtt: Long = 0) {
        val client = ApiClient(Endpoint(value.server, BuildConfig.DEBUG))
        reset(); secrets.save(value); session = value; api = client
        clock = ServerClock { SystemClock.elapsedRealtime() }; queue = VoiceQueue(value.memberId, clock::now)
        connection = "연결 중"; if (state != null) snapshot(state, rtt)
        connect(); changed()
    }
    private fun reset() {
        expiryTimer?.let { main.removeCallbacks(it) }; expiryTimer = null
        roomEpoch.advance(); disconnect(); receiving = false; queue?.disable(); stopPlayback(); recorder?.stop(true); recorder = null
        roomScope.cancel(); roomScope = RequestScope(); clearClip(); session = null; api = null; destination = null; revision = -1; voices.clear(); names = emptyList(); expiresAt = 0
        secrets.clear(); elapsed = 0.0; recording = "마이크로 1–15초 녹음해 보내요."; uploading = false; busy = false
    }
    private fun invalidate(text: String) { reset(); connection = "연결 종료"; notice(text) }
    private fun disconnect() { streamEpoch.advance(); streamScope?.cancel(); streamScope = null }
    private fun connect() {
        val member = session ?: return; val client = api ?: return
        if ((!visible && !receiving) || streamScope != null) return
        val scope = RequestScope(); streamScope = scope; val epoch = roomEpoch.current(); val stream = streamEpoch.advance()
        connection = "연결 중"; changed()
        io.execute {
            var delay = 1000L
            while (roomEpoch.valid(epoch) && streamEpoch.valid(stream)) {
                if (expiresAt > 0 && clock.now() >= expiresAt) { main.post { if (roomEpoch.valid(epoch)) invalidate("공유방이 만료되어 자동 수신을 종료했어요.") }; return@execute }
                try {
                    client.events(member, scope) { event, rtt -> if (event.type == "snapshot") delay = 1000; main.post { if (roomEpoch.valid(epoch) && streamEpoch.valid(stream)) handle(event, rtt) } }
                } catch (_: CancellationException) { return@execute }
                catch (error: Exception) {
                    if (!roomEpoch.valid(epoch) || !streamEpoch.valid(stream)) return@execute
                    if (error is ApiFailure && error.status in listOf(401, 403, 404)) { main.post { if (roomEpoch.valid(epoch)) invalidate("공유방이 종료됐거나 참여 권한이 없어요.") }; return@execute }
                    main.post { if (roomEpoch.valid(epoch) && streamEpoch.valid(stream)) { connection = "재연결 중"; status = "연결이 끊겨 다시 연결해요. 마지막 받은 목적지는 유지해요."; changed() } }
                    try { var waited = 0L; while (waited < delay) { scope.check(); Thread.sleep(100); waited += 100 } } catch (_: Exception) { return@execute }
                    delay = minOf(delay * 2, 10000)
                }
            }
        }
    }
    private fun snapshot(body: JSONObject, rtt: Long) {
        clock.update(body.getLong("serverTime"), rtt); expiresAt = body.getJSONObject("room").getLong("expiresAt")
        expiryTimer?.let { main.removeCallbacks(it) }
        val epoch = roomEpoch.current()
        expiryTimer = Runnable { if (roomEpoch.valid(epoch)) invalidate("공유방이 만료되어 수신을 종료했어요. 보관한 원음 접근도 종료돼요.") }.also { main.postDelayed(it, (expiresAt - clock.now()).coerceAtLeast(0)) }
        applyDestination(body)
        val members = body.getJSONArray("members"); names = (0 until members.length()).map { val m = members.getJSONObject(it); m.getString("name") + if (m.getString("role") == "host") " · 대표 차량" else " · 참여자" }
        val messages = body.getJSONArray("voiceMessages"); require(messages.length() <= 100)
        receive((0 until messages.length()).map { Voice.parse(messages.getJSONObject(it)) })
    }
    private fun applyDestination(body: JSONObject) {
        val next = body.getLong("revision"); if (next < revision) return; revision = next
        destination = if (body.isNull("destination")) null else Destination.parse(body.getJSONObject("destination"))
    }
    private fun handle(event: ServerEvent, rtt: Long) {
        try {
            clock.update(event.data.getLong("serverTime"), rtt)
            when (event.type) {
                "snapshot" -> { val reconnecting = connection == "재연결 중"; snapshot(event.data, 0); connection = "연결됨"; if (reconnecting) status = "다시 연결해 최신 목적지와 원음 목록을 받았어요." }
                "destination" -> { applyDestination(event.data); status = "새 목적지를 받았어요." }
                "members" -> { val members = event.data.getJSONArray("members"); names = (0 until members.length()).map { members.getJSONObject(it).getString("name") } }
                "voice" -> receive(listOf(Voice.parse(event.data.getJSONObject("message"))))
                "closed" -> { invalidate(if (event.data.optString("reason") == "expired") "공유방이 만료됐어요. 보관한 원음도 삭제됐어요." else "공유방 연결이 종료됐어요."); return }
            }
            changed()
        } catch (_: Exception) { stopReceiving("서버 수신 형식을 처리하지 못해 자동 수신을 중지했어요.") }
    }
    private fun receive(list: List<Voice>) { list.forEach { voices[it.id] = it }; queue?.offer(list); drain() }
    fun changeDestination(name: String, latitude: String, longitude: String) {
        val s = session ?: return; if (s.role != "host" || busy) return
        val lat = latitude.toDoubleOrNull(); val lng = longitude.toDoubleOrNull()
        if (name.isBlank() || name.length > 100 || lat == null || lng == null || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) { notice("장소명과 올바른 위도·경도를 입력해 주세요."); return }
        val client = api ?: return; val epoch = roomEpoch.current(); val scope = roomScope; busy = true; changed()
        io.execute { try {
            val result = client.request("POST", "/api/rooms/${s.roomId}/destination", s.token, scope, JSONObject().put("name", name).put("latitude", lat).put("longitude", lng))
            main.post { if (roomEpoch.valid(epoch)) { busy = false; clock.update(result.body.getLong("serverTime"), result.roundTripMs); applyDestination(result.body); notice("새 목적지를 전달했어요.") } }
        } catch (error: Exception) { main.post { if (roomEpoch.valid(epoch)) { busy = false; notice(errorText(error)) } } } }
    }
    fun invitation(): String? = session?.takeIf { it.role == "host" }?.let { api?.endpoint?.inviteLink(it) }
    fun leave() {
        val s = session ?: return; val client = api ?: return; if (busy) return
        stopReceiving("공유방 연결을 종료하고 있어요."); cancelRecording(); clearClip()
        val epoch = roomEpoch.current(); val scope = roomScope; busy = true; changed()
        io.execute { try {
            client.request("DELETE", "/api/rooms/${s.roomId}" + if (s.role == "host") "" else "/members/me", s.token, scope)
            main.post { if (roomEpoch.valid(epoch)) invalidate(if (s.role == "host") "공유방을 종료하고 보관한 원음을 삭제했어요." else "공유방에서 나갔어요.") }
        } catch (error: Exception) { main.post { if (roomEpoch.valid(epoch)) { busy = false; if (s.role == "member") invalidate("서버 연결을 확인하지 못했어요. 이 앱에서는 나갔어요.") else notice(errorText(error)) } } } }
    }
    fun startReceiving(): Boolean {
        if (session == null || expiresAt == 0L || clock.now() >= expiresAt) { notice("먼저 살아 있는 공유방 연결을 확인해 주세요."); return false }
        if (isRecording || uploading) { notice("녹음과 전송을 마친 뒤 자동 수신을 시작해 주세요."); return false }
        receiving = true; queue?.enable(); connect(); notice("자동 수신 켜짐 · 새 원음을 기다려요. 기기별 잠금 수신은 검증 전이에요."); return true
    }
    fun stopReceiving(text: String = "자동 수신을 중지했어요. 현재 재생과 대기열도 지웠어요.") {
        receiving = false; queue?.disable(); stopPlayback(); if (!visible) disconnect(); notice(text)
    }
    private fun stopPlayback() { playEpoch.advance(); playScope?.cancel(); playScope = null; player.stop(); playback = "재생을 중지했어요." }
    fun stopSound() { stopReceiving() }
    private fun drain() { if (receiving && playScope == null) queue?.take()?.let { play(it, true) } }
    fun manual(voice: Voice) { if (!visible || isRecording) { notice("녹음을 마치거나 취소한 뒤 열린 화면에서 재생해 주세요."); return }; stopReceiving("자동 수신 꺼짐 · 수동 재생을 선택했어요."); play(voice, false) }
    private fun play(voice: Voice, automatic: Boolean) {
        val s = session ?: return; val client = api ?: return
        stopPlayback(); val epoch = roomEpoch.current(); val play = playEpoch.current(); val scope = RequestScope(); playScope = scope
        playback = "${voice.senderName}의 원음을 불러오고 있어요."; changed()
        io.execute { try {
            scope.check(); val pcm = client.download(s, voice, scope); scope.check()
            main.post {
                if (!roomEpoch.valid(epoch) || !playEpoch.valid(play)) { pcm.bytes.fill(0); return@post }
                if (expiresAt > 0 && clock.now() >= expiresAt) { pcm.bytes.fill(0); invalidate("공유방이 만료되어 재생하지 않았어요."); return@post }
                if ((automatic && (!receiving || queue?.fresh(voice) != true)) || (!automatic && !visible)) {
                    pcm.bytes.fill(0); playScope = null; playback = "자동 재생 유효시간이 지나 재생하지 않았어요. 목록에서 직접 재생할 수 있어요."; changed(); drain(); return@post
                }
                try {
                    player.play(pcm) { finished -> if (roomEpoch.valid(epoch) && playEpoch.valid(play)) { playScope = null; playback = if (finished) "${voice.senderName}의 원음 재생을 마쳤어요." else "원음 재생을 중지했어요."; changed(); if (finished) drain() } }
                    playback = "${voice.senderName}의 원음을 재생하고 있어요."; changed()
                } catch (error: Exception) { playScope = null; if (automatic) stopReceiving("자동 재생 실패 · ${errorText(error)}") else notice("재생 실패 · ${errorText(error)}") }
                finally { pcm.bytes.fill(0) }
            }
        } catch (error: Exception) { main.post { if (roomEpoch.valid(epoch) && playEpoch.valid(play)) { playScope = null; if (error is ApiFailure && error.status in listOf(401, 403, 404)) invalidate("공유방 또는 원음 접근 권한이 종료됐어요.") else if (automatic) stopReceiving("자동 재생 실패 · ${errorText(error)}") else notice("재생 실패 · ${errorText(error)}") } } } }
    }
    fun beginRecording() {
        if (!visible || session == null || isRecording || uploading || pendingClip != null) return
        stopReceiving("녹음 중에는 자동 수신과 재생을 중지해요.")
        val capture = MicCapture(); recorder = capture; elapsed = 0.0; recording = "마이크를 열고 있어요."; val epoch = roomEpoch.current(); changed()
        io.execute { try {
            val bytes = capture.run { main.post { if (roomEpoch.valid(epoch) && recorder === capture && visible) { recordBegan = SystemClock.elapsedRealtime(); recording = "녹음 중 · 중지하고 보내거나 취소해 주세요."; recordTick(capture) } else capture.stop(true) } }
            main.post { if (roomEpoch.valid(epoch) && recorder === capture && visible) {
                recorder = null; elapsed = Wav.decode(bytes).sampleCount / 16000.0
                val clip = RetryClip(bytes, SystemClock.elapsedRealtime()); bytes.fill(0); pendingClip = clip
                retryTimer = Runnable { if (pendingClip === clip && !uploading) { clearClip(); recording = "30초가 지나 전송하지 못한 원음을 지웠어요."; changed() } }.also { main.postDelayed(it, 30000) }
                sendClip()
            } else bytes.fill(0) }
        } catch (error: Exception) { main.post { if (roomEpoch.valid(epoch) && recorder === capture) { recorder = null; recording = if (error is CancellationException) "녹음을 취소했어요." else errorText(error); changed() } } } }
    }
    private fun recordTick(capture: MicCapture) { if (recorder !== capture) return; elapsed = ((SystemClock.elapsedRealtime() - recordBegan) / 1000.0).coerceAtMost(15.0); changed(); main.postDelayed({ recordTick(capture) }, 200) }
    fun finishRecording() { recorder?.stop(); recording = "녹음을 마무리하고 있어요."; changed() }
    fun cancelRecording(text: String = "녹음을 취소했어요. 원음을 보내지 않았어요.") { recorder?.stop(true); recorder = null; recording = text; changed() }
    fun discardClip() { if (!uploading) { clearClip(); recording = "전송하지 못한 원음을 지웠어요."; changed() } }
    private fun clearClip() { retryTimer?.let { main.removeCallbacks(it) }; retryTimer = null; pendingClip?.discard(); pendingClip = null }
    fun sendClip() {
        val s = session ?: return; val client = api ?: return; val clip = pendingClip ?: return; if (uploading) return
        if (!visible || !clip.fresh(SystemClock.elapsedRealtime())) { clearClip(); recording = "오래됐거나 화면이 숨겨진 원음은 보내지 않았어요."; changed(); return }
        val epoch = roomEpoch.current(); val scope = roomScope; uploading = true; recording = "원음을 전송하고 있어요."; changed()
        io.execute { try {
            val result = client.upload(s, clip, scope)
            main.post { if (roomEpoch.valid(epoch)) { uploading = false; clock.update(result.body.getLong("serverTime"), result.roundTripMs); receive(listOf(Voice.parse(result.body.getJSONObject("message")))); clearClip(); recording = if (result.body.getBoolean("duplicate")) "이미 접수된 원음이에요. 중복으로 전달하지 않았어요." else "서버가 원음을 접수했어요. 상대의 재생 여부는 상대 화면에서 확인해 주세요."; changed() } }
        } catch (error: Exception) { main.post { if (roomEpoch.valid(epoch)) { uploading = false; if (!visible || !clip.fresh(SystemClock.elapsedRealtime()) || (error is ApiFailure && error.status in listOf(400, 403, 404, 409, 413, 415, 422, 507))) clearClip(); recording = "전송 실패 · ${errorText(error)}"; changed() } } } }
    }
}
