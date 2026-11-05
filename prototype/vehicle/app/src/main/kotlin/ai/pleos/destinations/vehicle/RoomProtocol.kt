package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PleosMetadata
import org.json.JSONObject
import java.net.URI

class RoomCredentials(val baseUrl: String, val roomId: String, val memberId: String, val role: String,
    val token: String, val inviteSecret: String? = null) {
    init {
        validateBaseUrl(baseUrl)
        require(ID.matches(roomId) && ID.matches(memberId))
        require(role in listOf("host", "member") && SECRET.matches(token))
        require(inviteSecret == null || (role == "host" && SECRET.matches(inviteSecret)))
    }
    override fun toString() = "RoomCredentials([redacted])"
    companion object {
        val ID = Regex("[a-f0-9]{24}")
        val SECRET = Regex("[A-Za-z0-9_-]{43}")
    }
}

fun validateBaseUrl(value: String): URI {
    val uri = URI(value)
    require(uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost"))
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    require(uri.port in 1..65535)
    return uri
}

data class DestinationUpdate(val revision: Long, val sequence: Long, val destination: Destination?)
data class RoomSnapshot(val update: DestinationUpdate, val role: String, val expiresAt: Long)
class RoomFailure(val status: Int, val code: String) : Exception("공유방 요청 실패 ($status)")

object RoomProtocol {
    fun destinationJson(value: Destination): JSONObject = JSONObject()
        .put("name", value.name).put("latitude", value.latitude).put("longitude", value.longitude).apply {
            value.pleos?.let { poi -> put("pleos", JSONObject()
                .put("poiId", poi.poiId).put("poiSubId", poi.poiSubId ?: JSONObject.NULL).put("address", poi.address)
                .put("poiProvider", poi.poiProvider)
                .put("displayLatitude", poi.displayLatitude ?: JSONObject.NULL)
                .put("displayLongitude", poi.displayLongitude ?: JSONObject.NULL)) }
        }

    fun destination(value: JSONObject): Destination {
        fun number(body: JSONObject, key: String): Double {
            require(body.get(key) is Number)
            return body.getDouble(key)
        }
        fun text(body: JSONObject, key: String): String {
            require(body.get(key) is String)
            return body.getString(key)
        }
        val poi = if (!value.has("pleos")) null else value.getJSONObject("pleos").let {
            require(listOf("poiId", "poiSubId", "address", "poiProvider", "displayLatitude", "displayLongitude").all(it::has))
            PleosMetadata(text(it, "poiId"), if (it.isNull("poiSubId")) null else text(it, "poiSubId"), text(it, "address"), text(it, "poiProvider"),
                if (it.isNull("displayLatitude")) null else number(it, "displayLatitude"),
                if (it.isNull("displayLongitude")) null else number(it, "displayLongitude"))
        }
        return Destination(text(value, "name"), number(value, "latitude"), number(value, "longitude"), poi)
    }

    fun update(body: JSONObject): DestinationUpdate {
        require(body.has("destination"))
        require(body.get("revision") is Number && body.get("sequence") is Number)
        val revision = body.getLong("revision")
        val sequence = body.getLong("sequence")
        require(revision >= 0 && sequence >= 0 && revision <= sequence && sequence <= 9_007_199_254_740_991L)
        require(body.getDouble("revision") == revision.toDouble() && body.getDouble("sequence") == sequence.toDouble())
        return DestinationUpdate(revision, sequence, if (body.isNull("destination")) null else destination(body.getJSONObject("destination")))
    }

    fun snapshot(body: JSONObject): RoomSnapshot {
        val role = body.getJSONObject("self").getString("role")
        require(role == "host" || role == "member")
        return RoomSnapshot(update(body), role, body.getJSONObject("room").getLong("expiresAt"))
    }

    fun credentials(baseUrl: String, body: JSONObject): RoomCredentials = RoomCredentials(baseUrl,
        body.getString("roomId"), body.getString("memberId"), body.getString("role"), body.getString("token"),
        if (body.has("inviteSecret")) body.getString("inviteSecret") else null)

    fun storage(value: RoomCredentials): JSONObject = JSONObject()
        .put("baseUrl", value.baseUrl).put("roomId", value.roomId).put("memberId", value.memberId)
        .put("role", value.role).put("token", value.token)
        .apply { value.inviteSecret?.let { put("inviteSecret", it) } }
}

/** Authentication is never encoded in an SSE id or URL; malformed/unbounded frames abort. */
class SseFrames(private val event: (String, JSONObject) -> Unit) {
    private var type = "message"
    private val data = StringBuilder()
    fun line(value: String) {
        require(value.length <= 131_072)
        if (value.isEmpty()) {
            if (data.isNotEmpty()) event(type, JSONObject(data.toString().trimEnd('\n')))
            type = "message"; data.setLength(0)
            return
        }
        if (value.startsWith(":")) return
        val index = value.indexOf(':')
        val field = if (index < 0) value else value.substring(0, index)
        val text = if (index < 0) "" else value.substring(index + 1).removePrefix(" ")
        when (field) {
            "event" -> { require(text.length <= 64); type = text }
            "data" -> { require(data.length + text.length + 1 <= 131_072); data.append(text).append('\n') }
        }
    }
}
