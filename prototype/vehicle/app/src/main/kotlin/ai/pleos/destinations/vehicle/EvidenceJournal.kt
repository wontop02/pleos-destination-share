package ai.pleos.destinations.vehicle

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Private bounded trace: event names, correlation counters, time, and match booleans only. */
class EvidenceJournal(context: Context) {
    private val file = File(context.noBackupFilesDir, "navigation-events.jsonl")
    fun append(event: String, epoch: Long, operation: Long, matched: Boolean?) {
        runCatching {
            if (file.length() > 524_288) file.writeText("")
            file.appendText(JSONObject().put("at", System.currentTimeMillis()).put("event", event)
                .put("nativeEpoch", epoch).put("operation", operation)
                .put("matched", matched ?: JSONObject.NULL).toString() + "\n")
        }
    }
}
