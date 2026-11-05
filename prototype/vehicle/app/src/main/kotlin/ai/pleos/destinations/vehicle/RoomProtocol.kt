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

data class ProposalPerson(val id: String, val name: String)
data class ProposalVote(val member: ProposalPerson, val response: String, val respondedAt: Long?)
data class DestinationProposal(val id: String, val version: Long, val status: String,
    val destination: Destination, val reason: String, val proposer: ProposalPerson,
    val createdAt: Long, val expiresAt: Long, val baseRevision: Long, val responses: List<ProposalVote>,
    val acceptedRevision: Long?, val endedAt: Long?, val invalidatedBy: String?)
data class DestinationUpdate(val revision: Long, val sequence: Long, val destination: Destination?,
    val proposal: DestinationProposal? = null, val hasProposal: Boolean = false, val serverTime: Long = 0)
data class RoomSnapshot(val update: DestinationUpdate, val role: String, val expiresAt: Long)
class RoomFailure(val status: Int, val code: String) : Exception("공유방 요청 실패 ($status)")

object RoomProtocol {
    private fun integer(body: JSONObject, key: String, minimum: Long = 0): Long {
        require(body.get(key) is Number)
        val value = body.getLong(key)
        require(value in minimum..9_007_199_254_740_991L && body.getDouble(key) == value.toDouble())
        return value
    }
    fun proposal(body: JSONObject): DestinationProposal {
        require(listOf("id", "version", "status", "destination", "reason", "proposer", "createdAt", "expiresAt",
            "baseRevision", "responses", "acceptedRevision", "endedAt", "invalidatedBy").all(body::has))
        fun person(value: JSONObject): ProposalPerson {
            val id = value.getString("id"); val name = value.getString("name")
            require(RoomCredentials.ID.matches(id) && name.isNotBlank() && name.length <= 40 && name.none { it.code < 32 || it.code == 127 })
            return ProposalPerson(id, name)
        }
        val id = body.getString("id"); require(RoomCredentials.ID.matches(id))
        val status = body.getString("status"); require(status in listOf("pending", "accepted", "rejected", "expired", "invalidated"))
        val created = integer(body, "createdAt"); val expires = integer(body, "expiresAt")
        require(expires > created && expires - created <= 120_000)
        val reason = body.getString("reason")
        require(reason.isNotBlank() && reason.length <= 200 && reason.none { it.code < 32 || it.code == 127 })
        val proposer = person(body.getJSONObject("proposer"))
        val votes = body.getJSONArray("responses"); require(votes.length() in 1..100)
        val responses = (0 until votes.length()).map { index ->
            val vote = votes.getJSONObject(index); val response = vote.getString("response")
            require(vote.has("respondedAt"))
            require(response in listOf("pending", "accepted", "rejected"))
            val at = if (vote.isNull("respondedAt")) null else integer(vote, "respondedAt")
            require((response == "pending") == (at == null) && (at == null || at in created..expires))
            ProposalVote(person(vote.getJSONObject("member")), response, at)
        }
        require(responses.map { it.member.id }.distinct().size == responses.size)
        require(responses.any { it.member == proposer && it.response == "accepted" })
        val baseRevision = integer(body, "baseRevision")
        val accepted = if (body.isNull("acceptedRevision")) null else integer(body, "acceptedRevision", 1)
        require((status == "accepted") == (accepted != null))
        if (accepted != null) require(accepted == baseRevision + 1 && responses.all { it.response == "accepted" })
        if (status == "pending") require(responses.any { it.response == "pending" } && responses.none { it.response == "rejected" })
        if (status == "rejected") require(responses.any { it.response == "rejected" })
        val target = destination(body.getJSONObject("destination")); require(target.canRequestRoute)
        val ended = if (body.isNull("endedAt")) null else integer(body, "endedAt")
        require((status == "pending") == (ended == null))
        if (status == "accepted" || status == "rejected") require(ended != null && ended >= created && ended < expires)
        return DestinationProposal(id, integer(body, "version", 1), status, target, reason, proposer,
            created, expires, baseRevision, responses, accepted, ended,
            if (body.isNull("invalidatedBy")) null else body.getString("invalidatedBy").also {
                require(it in listOf("membership_changed", "destination_changed", "room_closed"))
            })
    }
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
        val revision = integer(body, "revision"); val sequence = integer(body, "sequence")
        require(revision <= sequence)
        val hasProposal = body.has("proposal")
        return DestinationUpdate(revision, sequence, if (body.isNull("destination")) null else destination(body.getJSONObject("destination")),
            if (hasProposal && !body.isNull("proposal")) proposal(body.getJSONObject("proposal")) else null,
            hasProposal, if (hasProposal) integer(body, "serverTime") else 0)
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
