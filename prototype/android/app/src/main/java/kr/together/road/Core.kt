package kr.together.road

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val identifier = Regex("[a-f0-9]{24}")
fun checkedId(value: String): String = value.also { require(identifier.matches(it)) { "공유방 ID 형식이 올바르지 않아요." } }
class Session(val server: String, val roomId: String, val memberId: String, val role: String, val token: String, val inviteSecret: String? = null) {
    init { checkedId(roomId); checkedId(memberId); require(role in listOf("host", "member")); require(Regex("[A-Za-z0-9_-]{43}").matches(token)) }
    override fun toString() = "Session(credentials redacted)"
    fun json() = JSONObject().put("server", server).put("roomId", roomId).put("memberId", memberId).put("role", role).put("token", token).put("inviteSecret", inviteSecret)
    companion object { fun parse(o: JSONObject) = Session(o.getString("server"), o.getString("roomId"), o.getString("memberId"), o.getString("role"), o.getString("token"), o.optString("inviteSecret").takeIf { it.isNotEmpty() && it != "null" }) }
}
class Endpoint(input: String, private val debug: Boolean) {
    val uri: URI
    val origin: String
    val emulatorHost: String?
    init {
        val parsed = try { URI(input.trim().trimEnd('/')) } catch (_: Exception) { throw IllegalArgumentException("서버 주소를 확인해 주세요.") }
        require(parsed.userInfo == null && parsed.rawQuery == null && parsed.rawFragment == null && parsed.rawPath.orEmpty().isEmpty() && parsed.host != null && parsed.port in -1..65535 && parsed.port != 0) { "서버는 인증 정보·경로 없는 주소만 입력해 주세요." }
        require(parsed.scheme == "https" || (debug && parsed.scheme == "http" && parsed.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))) { "HTTPS가 필요해요. 디버그만 에뮬레이터·localhost HTTP를 허용해요." }
        uri = parsed
        origin = parsed.toASCIIString()
        emulatorHost = if (debug && parsed.scheme == "http" && parsed.host == "10.0.2.2") "127.0.0.1:${if (parsed.port < 0) 80 else parsed.port}" else null
    }
    fun invitation(text: String): Pair<String, String> {
        val link = try { URI(text.trim()) } catch (_: Exception) { throw IllegalArgumentException("초대 링크를 확인해 주세요.") }
        val equivalentHost = link.host == uri.host || (debug && uri.scheme == "http" && link.host in listOf("127.0.0.1", "localhost", "10.0.2.2") && uri.host in listOf("127.0.0.1", "localhost", "10.0.2.2"))
        require(link.scheme == uri.scheme && link.port == uri.port && equivalentHost && link.userInfo == null && link.rawQuery == null && link.path in listOf("", "/")) { "선택한 서버에서 만든 초대 링크가 필요해요." }
        val match = Regex("join=([a-f0-9]{24})\\.([A-Za-z0-9_-]{43})").matchEntire(link.rawFragment.orEmpty()) ?: throw IllegalArgumentException("초대 링크 형식을 확인해 주세요.")
        return match.groupValues[1] to match.groupValues[2]
    }
    fun inviteLink(session: Session): String {
        val originForShare = if (emulatorHost != null) "http://$emulatorHost" else origin
        return "$originForShare/#join=${session.roomId}.${session.inviteSecret ?: error("초대 권한이 없어요.")}"
    }
}
data class Destination(val name: String, val latitude: Double, val longitude: Double, val changedAt: Long) {
    companion object { fun parse(o: JSONObject) = Destination(o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"), o.getLong("changedAt")) }
}
object NaverNavigation {
    const val PACKAGE = "com.nhn.android.nmap"
    /** Official navigation request only; starting its handler is not evidence of route guidance. */
    fun url(destination: Destination, applicationId: String): String? {
        if (!destination.latitude.isFinite() || !destination.longitude.isFinite() ||
            destination.latitude !in 31.43..44.35 || destination.longitude !in 122.37..132.0) return null
        val query = listOf("dlat" to destination.latitude.toString(), "dlng" to destination.longitude.toString(),
            "dname" to destination.name, "appname" to applicationId).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
        }
        return "nmap://navigation?$query"
    }
}
data class Voice(val id: String, val senderId: String, val senderName: String, val sequence: Long, val acceptedAt: Long, val autoPlayUntil: Long, val sampleCount: Int, val byteLength: Int) {
    val durationMs get() = sampleCount / 16.0
    companion object {
        fun parse(o: JSONObject): Voice {
            val sender = o.getJSONObject("sender")
            return Voice(checkedId(o.getString("id")), checkedId(sender.getString("id")), sender.getString("name"), o.getLong("sequence"), o.getLong("acceptedAt"), o.getLong("autoPlayUntil"), o.getInt("sampleCount"), o.getInt("byteLength")).also {
                require(it.sampleCount in 16000..240000 && it.byteLength in 32044..484096 && it.autoPlayUntil == it.acceptedAt + 30000 && o.getInt("sampleRate") == 16000 && o.getInt("channels") == 1 && o.getString("format") == "pcm_s16le") { "지원하지 않는 원음 정보예요." }
            }
        }
    }
}
class ServerClock(private val tick: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var anchor: Long? = null
    private var anchoredAt = 0L
    @Synchronized fun now(): Long = anchor?.let { it + (tick() - anchoredAt).coerceAtLeast(0) } ?: System.currentTimeMillis()
    @Synchronized fun update(serverTime: Long, roundTripMs: Long = 0) { anchor = maxOf(if (anchor == null) Long.MIN_VALUE else now(), serverTime + roundTripMs.coerceAtLeast(0)); anchoredAt = tick() }
}
class VoiceQueue(private val selfId: String, private val now: () -> Long) {
    private val seen = HashSet<String>()
    private val pending = ArrayList<Voice>()
    var enabled = false; private set
    private var enabledAt = Long.MAX_VALUE
    fun enable() { enabledAt = now(); enabled = true }
    fun disable() { enabled = false; pending.clear() }
    fun fresh(v: Voice) = v.autoPlayUntil == v.acceptedAt + 30000 && v.acceptedAt <= now() && now() < v.autoPlayUntil
    fun offer(list: List<Voice>) {
        for (v in list) if (seen.add(v.id) && enabled && v.senderId != selfId && v.acceptedAt >= enabledAt && fresh(v)) pending.add(v)
        pending.sortWith(compareBy<Voice> { it.sequence }.thenBy { it.acceptedAt }.thenBy { it.id })
    }
    fun take(): Voice? { if (!enabled) return null; while (pending.isNotEmpty()) { val v = pending.removeAt(0); if (fresh(v)) return v }; return null }
}
class Epoch {
    private val counter = AtomicLong()
    fun current() = counter.get()
    fun advance() = counter.incrementAndGet()
    fun valid(value: Long) = value == counter.get()
}
class RetryClip(bytes: ByteArray, val capturedAt: Long, val clientMessageId: String = UUID.randomUUID().toString()) {
    val bytes = bytes.copyOf()
    fun fresh(now: Long) = now >= capturedAt && now - capturedAt < 30000
    fun discard() { bytes.fill(0) }
}
data class Pcm(val bytes: ByteArray, val sampleCount: Int)
object Wav {
    const val MAX_BYTES = 484096
    fun encode(samples: ShortArray, count: Int = samples.size): ByteArray {
        require(count in 16000..240000 && count <= samples.size) { "원음은 1–15초여야 해요." }
        val b = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + count * 2); b.put("WAVEfmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(count * 2)
        for (index in 0 until count) b.putShort(samples[index])
        return b.array()
    }
    fun decode(bytes: ByteArray): Pcm {
        require(bytes.size in 44..MAX_BYTES) { "WAV 크기가 올바르지 않아요." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int) = String(bytes, at, 4, Charsets.ISO_8859_1)
        require(word(0) == "RIFF" && word(8) == "WAVE" && b.getInt(4) == bytes.size - 8) { "WAV 컨테이너가 올바르지 않아요." }
        var cursor = 12; var format = false; var pcm: ByteArray? = null
        while (cursor < bytes.size) {
            require(cursor + 8 <= bytes.size)
            val kind = word(cursor); val size = b.getInt(cursor + 4); val start = cursor + 8
            require(size >= 0 && size <= bytes.size - start)
            val end = start + size; val padded = end + size % 2; require(padded <= bytes.size)
            if (kind == "fmt ") {
                require(!format && pcm == null && size in listOf(16, 18))
                require(size != 18 || b.getShort(start + 16).toInt() == 0)
                require(b.getShort(start).toInt() == 1 && b.getShort(start + 2).toInt() == 1 && b.getInt(start + 4) == 16000 && b.getInt(start + 8) == 32000 && b.getShort(start + 12).toInt() == 2 && b.getShort(start + 14).toInt() == 16) { "PCM16 mono 16kHz 원음이 필요해요." }
                format = true
            } else if (kind == "data") { require(format && pcm == null && size % 2 == 0 && size / 2 in 16000..240000); pcm = bytes.copyOfRange(start, end) }
            cursor = padded
        }
        val result = requireNotNull(pcm); require(bytes.size - result.size <= 4096)
        return Pcm(result, result.size / 2)
    }
}
data class ServerEvent(val type: String, val data: JSONObject)
class SseDecoder(private val receive: (ServerEvent) -> Unit) {
    private val line = StringBuilder(); private val data = StringBuilder(); private var event = "message"; private var cr = false; private var total = 0
    fun feed(chars: CharArray, count: Int) {
        for (index in 0 until count) {
            val c = chars[index]
            if (c == '\n' && cr) { cr = false; continue }
            if (c == '\r' || c == '\n') { consume(line.toString()); line.setLength(0); cr = c == '\r' }
            else { cr = false; line.append(c); require(line.length + total <= 262144) { "이벤트 크기가 상한을 넘었어요." } }
        }
    }
    private fun consume(value: String) {
        if (value.isEmpty()) {
            if (data.isNotEmpty()) receive(ServerEvent(event, JSONObject(data.toString().trimEnd('\n'))))
            event = "message"; data.setLength(0); total = 0; return
        }
        total += value.length; require(total <= 262144)
        if (value.startsWith(':')) return
        val key = value.substringBefore(':'); val content = value.substringAfter(':', "").removePrefix(" ")
        if (key == "event") event = content
        if (key == "data") data.append(content).append('\n')
    }
}
