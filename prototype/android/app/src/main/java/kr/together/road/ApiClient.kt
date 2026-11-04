package kr.together.road

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** OkHttp cancellation closes the socket immediately; never block the main thread on an SSE read. */
class RequestScope {
    private val cancelled = AtomicBoolean()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    fun check() { if (cancelled.get()) throw CancellationException() }
    fun add(call: Call) { check(); calls.add(call); if (cancelled.get()) { call.cancel(); calls.remove(call); check() } }
    fun remove(call: Call) { calls.remove(call) }
    fun cancel() { cancelled.set(true); calls.forEach { it.cancel() }; calls.clear() }
}
class ApiFailure(val status: Int, val code: String, message: String) : IOException(message)
data class Reply(val body: JSONObject, val roundTripMs: Long)
class ApiClient(val endpoint: Endpoint) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private fun request(path: String, method: String, token: String?): Request.Builder {
        require(path.startsWith("/api/rooms") && !path.contains('?') && !path.contains('#'))
        val builder = Request.Builder().url(endpoint.origin + path).header("Accept", "application/json")
        endpoint.emulatorHost?.let { builder.header("Host", it) }
        token?.let { require(Regex("[A-Za-z0-9_-]{43}").matches(it)); builder.header("Authorization", "Bearer $it") }
        return builder.method(method, if (method == "POST") ByteArray(0).toRequestBody() else null)
    }
    private fun bounded(input: InputStream, max: Int, scope: RequestScope): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { scope.check(); val count = input.read(buffer); if (count < 0) break; if (output.size() + count > max) throw IOException("응답 크기가 상한을 넘었어요."); output.write(buffer, 0, count) }
        scope.check(); return output.toByteArray()
    }
    private fun check(response: Response, scope: RequestScope) {
        if (!response.isSuccessful) {
            val bytes = response.body?.byteStream()?.use { bounded(it, 8192, scope) }
            val body = try { JSONObject(bytes?.toString(Charsets.UTF_8).orEmpty()) } catch (_: Exception) { JSONObject() }
            throw ApiFailure(response.code, body.optString("code", "http_error"), body.optString("error", "서버 요청에 실패했어요. (${response.code})"))
        }
    }
    private fun <T> execute(request: Request, scope: RequestScope, consume: (Response) -> T): T {
        val call = http.newCall(request); scope.add(call)
        try { return call.execute().use { response -> scope.check(); check(response, scope); consume(response) } }
        finally { scope.remove(call) }
    }
    fun request(method: String, path: String, token: String?, scope: RequestScope, json: JSONObject? = null, audio: ByteArray? = null, clientMessageId: String? = null): Reply {
        val start = System.nanoTime(); val builder = request(path, method, token)
        if (json != null || audio != null) {
            val bytes = audio ?: json.toString().toByteArray(Charsets.UTF_8)
            builder.method(method, bytes.toRequestBody((if (audio == null) "application/json; charset=utf-8" else "audio/wav").toMediaType()))
            clientMessageId?.let { require(Regex("[A-Za-z0-9_-]{16,80}").matches(it)); builder.header("X-Client-Message-Id", it) }
        }
        return execute(builder.build(), scope) { response ->
            val bytes = requireNotNull(response.body).byteStream().use { bounded(it, 262144, scope) }
            Reply(JSONObject(bytes.toString(Charsets.UTF_8)), (System.nanoTime() - start) / 1_000_000)
        }
    }
    fun create(name: String, scope: RequestScope) = request("POST", "/api/rooms", null, scope, JSONObject().put("name", name))
    fun join(room: String, invite: String, name: String, scope: RequestScope) = request("POST", "/api/rooms/${checkedId(room)}/members", invite, scope, JSONObject().put("name", name))
    fun snapshot(s: Session, scope: RequestScope) = request("GET", "/api/rooms/${s.roomId}", s.token, scope)
    fun upload(s: Session, clip: RetryClip, scope: RequestScope) = request("POST", "/api/rooms/${s.roomId}/voice", s.token, scope, audio = clip.bytes, clientMessageId = clip.clientMessageId)
    fun download(s: Session, voice: Voice, scope: RequestScope): Pcm = execute(request("/api/rooms/${s.roomId}/voice/${checkedId(voice.id)}/audio", "GET", s.token).build(), scope) { response ->
        val body = requireNotNull(response.body); if (body.contentLength() > Wav.MAX_BYTES) throw IOException("원음 크기가 상한을 넘었어요.")
        val bytes = body.byteStream().use { bounded(it, Wav.MAX_BYTES, scope) }; require(bytes.size == voice.byteLength) { "원음 크기가 수신 정보와 달라요." }
        Wav.decode(bytes).also { require(it.sampleCount == voice.sampleCount) { "원음 표본 수가 수신 정보와 달라요." } }
    }
    fun events(s: Session, scope: RequestScope, receive: (ServerEvent, Long) -> Unit) {
        val started = System.nanoTime()
        execute(request("/api/rooms/${s.roomId}/events", "GET", s.token).header("Accept", "text/event-stream").build(), scope) { response ->
            require(response.header("Content-Type")?.startsWith("text/event-stream") == true)
            var first = true
            val parser = SseDecoder { event -> receive(event, if (first) (System.nanoTime() - started) / 1_000_000 else 0); first = false }
            requireNotNull(response.body).charStream().use { reader -> val buffer = CharArray(2048); while (true) { scope.check(); val count = reader.read(buffer); if (count < 0) break; parser.feed(buffer, count) } }
            scope.check(); throw IOException("실시간 연결이 끊겼어요.")
        }
    }
}
