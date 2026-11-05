package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PleosMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomProtocolTest {
    private fun pendingFrame(): JSONObject = JSONObject("""{
        "revision":0,"sequence":2,"destination":null,"serverTime":1000,
        "proposal":{"id":"cccccccccccccccccccccccc","version":1,"status":"pending",
        "destination":{"name":"42dot","latitude":37.41289678,"longitude":127.09383464,
        "pleos":{"poiId":"36khp37hh8gqcm5nqhe5","poiSubId":null,"address":"수정구 시흥동","poiProvider":"","displayLatitude":null,"displayLongitude":null}},
        "reason":"함께 변경","proposer":{"id":"aaaaaaaaaaaaaaaaaaaaaaaa","name":"차량 A"},
        "createdAt":1000,"expiresAt":121000,"baseRevision":0,"acceptedRevision":null,"endedAt":null,"invalidatedBy":null,
        "responses":[{"member":{"id":"aaaaaaaaaaaaaaaaaaaaaaaa","name":"차량 A"},"response":"accepted","respondedAt":1000},
        {"member":{"id":"bbbbbbbbbbbbbbbbbbbbbbbb","name":"차량 B"},"response":"pending","respondedAt":null}]}}
    """)
    @Test fun optionalPendingAndAtomicAcceptedProposalMetadataRoundTripWithoutPromotingCandidate() {
        val body = pendingFrame(); val pending = RoomProtocol.update(body)
        assertNull(pending.destination); assertEquals(0L, pending.revision)
        assertEquals("42dot", pending.proposal!!.destination.name); assertTrue(pending.hasProposal)
        val p = body.getJSONObject("proposal")
        p.put("status", "accepted").put("version", 2).put("acceptedRevision", 1).put("endedAt", 2000)
        p.getJSONArray("responses").getJSONObject(1).put("response", "accepted").put("respondedAt", 2000)
        body.put("destination", p.getJSONObject("destination")).put("revision", 1).put("sequence", 3).put("serverTime", 2000)
        val accepted = RoomProtocol.update(body)
        assertEquals(accepted.destination, accepted.proposal!!.destination); assertEquals(1L, accepted.proposal!!.acceptedRevision)
        body.remove("proposal"); body.remove("serverTime")
        assertFalse(RoomProtocol.update(body).hasProposal)
    }
    @Test fun malformedOrUnboundedProposalCannotGrantVehicleAuthority() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 1.5) }, { it.put("expiresAt", 121001) }, { it.put("reason", "x".repeat(201)) },
            { it.put("status", "accepted").put("acceptedRevision", 1).put("endedAt", 2000) },
            { it.remove("acceptedRevision") }, { it.getJSONArray("responses").getJSONObject(1).remove("respondedAt") },
            { it.getJSONArray("responses").put(it.getJSONArray("responses").getJSONObject(0)) },
            { it.put("acceptedRevision", 1) }, { it.put("status", "rejected").put("endedAt", 2000) },
        )
        for (change in changes) {
            val body = pendingFrame(); change(body.getJSONObject("proposal"))
            assertThrows(Exception::class.java) { RoomProtocol.update(body) }
        }
        val noTime = pendingFrame(); noTime.remove("serverTime")
        assertThrows(Exception::class.java) { RoomProtocol.update(noTime) }
    }
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
    @Test fun retainedAcceptedProvenanceIsIndependentOfLatestCandidateAndMustMatchSharedRevisionAndTarget() {
        val body = pendingFrame(); val accepted = body.getJSONObject("proposal")
        accepted.put("status", "accepted").put("version", 2).put("acceptedRevision", 1).put("endedAt", 2000)
        accepted.getJSONArray("responses").getJSONObject(1).put("response", "accepted").put("respondedAt", 2000)
        val later = pendingFrame().getJSONObject("proposal").put("id", "d".repeat(24)).put("baseRevision", 1)
            .put("destination", RoomProtocol.destinationJson(PrototypePlaces.all[1]))
        body.put("proposal", later).put("acceptedProposal", accepted).put("destination", accepted.getJSONObject("destination"))
            .put("revision", 1).put("sequence", 4).put("serverTime", 2000)
        val update = RoomProtocol.update(body)
        assertEquals("pending", update.proposal!!.status); assertEquals("accepted", update.acceptedProposal!!.status)
        assertNotEquals(update.proposal!!.id, update.acceptedProposal!!.id)
        accepted.put("acceptedRevision", 2)
        assertThrows(Exception::class.java) { RoomProtocol.update(body) }
        accepted.put("acceptedRevision", 1)
        body.put("destination", RoomProtocol.destinationJson(PrototypePlaces.all[1]))
        assertThrows(Exception::class.java) { RoomProtocol.update(body) }
        body.put("acceptedProposal", JSONObject.NULL)
        assertNull(RoomProtocol.update(body).acceptedProposal)
    }

}
