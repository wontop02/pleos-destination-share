package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PleosMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomProtocolTest {
    @Test fun metadataRoundTripPreservesActualNullableSubIdAndDisplayCoordinates() {
        val value = Destination("공개 예제", 37.48544722, 127.03636666,
            PleosMetadata("public-poi", null, "공개 주소", "", 37.485, 127.036))
        assertEquals(value, RoomProtocol.destination(RoomProtocol.destinationJson(value)))
        assertTrue(value.canRequestRoute)
        val legacy = value.copy(pleos = null)
        assertFalse(RoomProtocol.destinationJson(legacy).has("pleos"))
        assertEquals(legacy, RoomProtocol.destination(RoomProtocol.destinationJson(legacy)))
    }

    @Test fun baseUrlRejectsNonLoopbackCredentialsQueryFragmentAndRelativePaths() {
        for (url in listOf("https://example.com:443", "http://127.0.0.1:4182/?token=secret",
            "http://user:secret@127.0.0.1:4182", "http://127.0.0.1:4182/#secret", "http://127.0.0.1:4182/api", "http://127.0.0.1")) {
            assertThrows(Exception::class.java) { validateBaseUrl(url) }
        }
        assertEquals("127.0.0.1", validateBaseUrl("http://127.0.0.1:4182").host)
    }

    @Test fun credentialsStringNeverDisclosesBearerOrInvite() {
        val identity = RoomCredentials("http://127.0.0.1:4182", "a".repeat(24), "b".repeat(24), "host", "t".repeat(43), "i".repeat(43))
        assertFalse(identity.toString().contains(identity.token)); assertFalse(identity.toString().contains(identity.inviteSecret!!))
        assertThrows(IllegalArgumentException::class.java) {
            RoomCredentials("http://127.0.0.1:4182", "a".repeat(24), "b".repeat(24), "member", "t".repeat(43), "i".repeat(43))
        }
    }

    @Test fun malformedPayloadCannotBecomeAnEmptyDestinationOrTruncatedRevision() {
        assertThrows(Exception::class.java) { RoomProtocol.destination(JSONObject("""{"name":"X","latitude":"37","longitude":127}""")) }
        assertThrows(Exception::class.java) { RoomProtocol.update(JSONObject("""{"destination":null,"revision":1.5,"sequence":2}""")) }
        assertThrows(Exception::class.java) { RoomProtocol.update(JSONObject("""{"destination":{},"revision":1,"sequence":2}""")) }
    }

    @Test fun missingDestinationOrMetadataFieldIsRejectedInsteadOfInterpretedAsDeletionOrNull() {
        assertThrows(Exception::class.java) { RoomProtocol.update(JSONObject("""{"revision":1,"sequence":2}""")) }
        val value = Destination("공개 예제", 37.4, 127.0, PleosMetadata("poi", null, "", "", null, null))
        val body = RoomProtocol.destinationJson(value)
        body.getJSONObject("pleos").remove("poiSubId")
        assertThrows(Exception::class.java) { RoomProtocol.destination(body) }
    }

    @Test fun sseHandlesCommentsCrLfMultilineDataAndDoesNotRequireUrlCredentials() {
        val values = mutableListOf<Pair<String, JSONObject>>()
        val parser = SseFrames { name, value -> values.add(name to value) }
        listOf(": heartbeat", "id: 3", "event: destination", "data: {\"revision\": 3,", "data: \"sequence\": 3, \"destination\": null}", "").forEach(parser::line)
        assertEquals("destination", values.single().first)
        assertEquals(3, values.single().second.getInt("revision"))
        val tooBig = "x".repeat(131_073)
        assertThrows(IllegalArgumentException::class.java) { parser.line(tooBig) }
        val many = SseFrames { _, _ -> fail("unbounded frame must never dispatch") }
        many.line("data: " + "x".repeat(70_000))
        assertThrows(IllegalArgumentException::class.java) { many.line("data: " + "x".repeat(70_000)) }
    }
}
