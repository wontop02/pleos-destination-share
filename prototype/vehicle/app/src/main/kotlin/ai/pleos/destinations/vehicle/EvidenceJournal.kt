package ai.pleos.destinations.vehicle

import android.content.Context
import ai.pleos.destinations.core.VehicleState
import org.json.JSONObject
import java.io.File

/** Private bounded trace: event names, correlation counters, time, and match booleans only. */
class EvidenceJournal(context: Context) {
    private val file = File(context.noBackupFilesDir, "navigation-events.jsonl")
    private val snapshot = File(context.noBackupFilesDir, "session-state.json")
    private val proposalSnapshot = File(context.noBackupFilesDir, "proposal-state.json")
    fun session(event: String, state: VehicleState, roomEpoch: Long, serviceEpoch: Long, uiCount: Int,
        proposal: DestinationProposal? = null, proposalLease: Boolean = false, proposalBusy: Boolean = false,
        proposalMessage: String = "", publicationHeld: Boolean = false) {
        runCatching {
            val value = JSONObject().put("at", System.currentTimeMillis()).put("event", event)
                .put("serviceEpoch", serviceEpoch).put("roomEpoch", roomEpoch).put("revision", state.revision)
                .put("role", if (state.host) "host" else "member").put("automatic", state.automatic.name)
                .put("applying", state.applying.name).put("connection", state.connection.name)
                .put("roomConnected", state.roomConnected).put("uiCount", uiCount)
                .put("confirmedAtElapsed", state.confirmedAt ?: JSONObject.NULL)
                .put("routingCompletedObserved", state.routingCompletedObserved)
                .put("proposalLease", proposalLease).put("proposalBusy", proposalBusy)
                .put("proposalMessage", proposalMessage).put("proposalPublicationHeld", publicationHeld)
                .put("proposal", proposal?.let { p -> JSONObject()
                    .put("id", p.id).put("version", p.version).put("status", p.status)
                    .put("candidateName", p.destination.name).put("candidatePoiId", p.destination.pleos?.poiId)
                    .put("proposerId", p.proposer.id).put("baseRevision", p.baseRevision)
                    .put("createdAt", p.createdAt).put("expiresAt", p.expiresAt)
                    .put("acceptedRevision", p.acceptedRevision ?: JSONObject.NULL)
                    .put("responses", org.json.JSONArray(p.responses.map { vote -> JSONObject()
                        .put("memberId", vote.member.id).put("response", vote.response) }))
                } ?: JSONObject.NULL)
            if (file.length() > 524_288) file.writeText("")
            file.appendText(value.toString() + "\n")
            snapshot.writeText(value.toString())
            proposalSnapshot.writeText(value.toString())
        }
    }
    fun append(event: String, epoch: Long, operation: Long, matched: Boolean?) {
        runCatching {
            if (file.length() > 524_288) file.writeText("")
            file.appendText(JSONObject().put("at", System.currentTimeMillis()).put("event", event)
                .put("nativeEpoch", epoch).put("operation", operation)
                .put("matched", matched ?: JSONObject.NULL).toString() + "\n")
        }
    }
}
