package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class RoomHttpClient(baseUrl: String) : AutoCloseable {
    private val base = validateBaseUrl(baseUrl).toString().trimEnd('/')
    private val active = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private val closed = AtomicBoolean(false)

    fun create(name: String): Pair<RoomCredentials, RoomSnapshot> {
        val body = request("POST", "/api/rooms", null, JSONObject().put("name", name))
        return RoomProtocol.credentials(base, body) to RoomProtocol.snapshot(body.getJSONObject("state"))
    }

    fun join(roomId: String, inviteSecret: String, name: String): Pair<RoomCredentials, RoomSnapshot> {
        require(RoomCredentials.ID.matches(roomId) && RoomCredentials.SECRET.matches(inviteSecret))
        val body = request("POST", "/api/rooms/$roomId/members", inviteSecret, JSONObject().put("name", name))
        return RoomProtocol.credentials(base, body) to RoomProtocol.snapshot(body.getJSONObject("state"))
    }

    fun snapshot(credentials: RoomCredentials) = RoomProtocol.snapshot(request("GET", roomPath(credentials), credentials.token))

    fun publish(credentials: RoomCredentials, destination: Destination?, expectedRevision: Long): DestinationUpdate {
        require(expectedRevision >= 0)
        val body = (destination?.let { RoomProtocol.destinationJson(it) } ?: JSONObject().put("destination", JSONObject.NULL))
            .put("expectedRevision", expectedRevision)
        return RoomProtocol.update(request("POST", "${roomPath(credentials)}/destination", credentials.token, body))
    }

    fun leave(credentials: RoomCredentials) {
        request("DELETE", roomPath(credentials) + if (credentials.role == "host") "" else "/members/me", credentials.token)
    }

    private fun roomPath(credentials: RoomCredentials): String {
        require(credentials.baseUrl.trimEnd('/') == base)
        return "/api/rooms/${credentials.roomId}"
    }

    private fun connection(method: String, path: String, token: String?): HttpURLConnection {
        check(!closed.get())
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false // Never forward bearer credentials on a redirect.
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        token?.let { require(RoomCredentials.SECRET.matches(it)); connection.setRequestProperty("Authorization", "Bearer $it") }
        active.add(connection)
        if (closed.get()) { active.remove(connection); connection.disconnect(); error("공유방 연결이 종료되었습니다.") }
        return connection
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject? = null): JSONObject {
        val connection = connection(method, path, token)
        try {
            body?.let {
                val bytes = it.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 4096)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { stream -> stream.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RoomFailure(status, safeError(connection.errorStream))
            return JSONObject(connection.inputStream.use { String(readBounded(it, 262_144), Charsets.UTF_8) })
        } finally { active.remove(connection); connection.disconnect() }
    }

    /** Blocking reader. The service owns its worker; UI detach never closes an automatic feed. */
    fun events(credentials: RoomCredentials, event: (String, JSONObject) -> Unit) {
        val connection = connection("GET", "${roomPath(credentials)}/events", credentials.token)
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "text/event-stream")
        try {
            val status = connection.responseCode
            if (status != 200) throw RoomFailure(status, safeError(connection.errorStream))
            check(connection.contentType?.startsWith("text/event-stream") == true)
            val parser = SseFrames(event)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!closed.get()) {
                    val line = readLineBounded(reader) ?: break
                    parser.line(line)
                }
            }
        } finally { active.remove(connection); connection.disconnect() }
    }

    private fun safeError(stream: InputStream?): String {
        val code = runCatching { stream?.use { JSONObject(String(readBounded(it, 4096), Charsets.UTF_8)).optString("code") } }.getOrNull()
        return if (code != null && Regex("[a-z_]{1,64}").matches(code)) code else "request_failed"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val connections = synchronized(active) { active.toList().also { active.clear() } }
        if (connections.isEmpty()) return
        // Some HTTP implementations wait for a pending SSE read inside disconnect(). Never let
        // that block the service main thread: consent/generation is fenced synchronously by owner.
        // Every read/connect already has a finite timeout; this one-shot closer owns no retries.
        Thread({ connections.forEach { runCatching { it.disconnect() } } }, "convoy-http-close").apply {
            isDaemon = true
            start()
        }
    }
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun readLineBounded(reader: BufferedReader): String? {
    val result = StringBuilder()
    while (true) {
        val value = reader.read()
        if (value < 0) return if (result.isEmpty()) null else result.toString().removeSuffix("\r")
        if (value == 10) return result.toString().removeSuffix("\r")
        require(result.length < 131_072)
        result.append(value.toChar())
    }
}
