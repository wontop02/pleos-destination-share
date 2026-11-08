package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.DestinationKind
import ai.pleos.destinations.core.PlaceSource
import ai.pleos.destinations.core.PleosMetadata
import org.junit.Assert.*
import org.junit.Test

class VoiceDraftTest {
    // Synthetic unit inputs are not real POIs or runtime evidence.
    private fun place(name: String, id: String) = Destination(name, 37.4, 127.0,
        PleosMetadata(id, null, "테스트 주소", "", null, null), DestinationKind.PLEOS_PLACE, PlaceSource.PLEOS_BOOKMARK)
    private val cafe = place("테스트 카페", "unit-cafe")
    private val fuel = place("테스트 주유소", "unit-fuel")
    private val food = place("테스트 식당", "unit-food")
    private val named = place("가든서비스", "unit-named")
    private val places = listOf(cafe, fuel, food, named)

    @Test fun ordinaryCafeFuelAndRestaurantUtterancesUseOnlyOriginalPlaceObjects() {
        listOf("카페 들르는 거 어때?" to cafe, "여기서 주유하고 가자" to fuel, "식당에 들르자" to food).forEach { (text, expected) ->
            val result = VoiceDraftAnalyzer.analyze(text, places)
            assertEquals(VoiceChangeIntent.CHANGE, result.intent)
            assertSame(expected, result.candidates.single()); assertTrue(result.reason.isNotBlank())
            assertTrue(result.reason.length <= 200); assertTrue(result.reason.none(Char::isISOControl))
        }
    }
    @Test fun exactStoredNameMatchesWithoutReplacingItsMetadataOrGuessingItsCategory() {
        val result = VoiceDraftAnalyzer.analyze("가든서비스에 들르는 거 어때?", places)
        assertSame(named, result.candidates.single()); assertEquals("", result.reason)
        assertEquals("가든서비스", result.clue)
        assertTrue(VoiceDraftAnalyzer.analyze("카페 들르자", listOf(named)).candidates.isEmpty())
    }
    @Test fun duplicateNamesRequireAddressSelectionAndNeverSelectTheFirstAutomatically() {
        val second = cafe.copy(pleos = cafe.pleos!!.copy(poiId = "unit-cafe-2", address = "다른 테스트 주소"))
        val result = VoiceDraftAnalyzer.analyze("테스트 카페 가자", listOf(cafe, second))
        assertEquals(2, result.candidates.size); assertSame(cafe, result.candidates[0]); assertSame(second, result.candidates[1])
    }
    @Test fun negativeUnclearAlternativeAndMultipleCategoryPhrasesNeverYieldCandidates() {
        listOf("카페 가지 말자", "주유소에 가지마", "카페는 안 가", "식당 들르지 말자", "변경하지 마", "카페?", "안녕하세요", "카페 아니면 식당 들를까", "카페 말고 가든서비스 가자").forEach { text ->
            val result = VoiceDraftAnalyzer.analyze(text, places)
            assertNotEquals(text, VoiceChangeIntent.CHANGE, result.intent); assertTrue(text, result.candidates.isEmpty())
            assertEquals("", result.reason)
        }
    }
    @Test fun missingSnapshotUnknownPlaceControlCharactersAndOversizeNeverInventMetadata() {
        assertTrue(VoiceDraftAnalyzer.analyze("카페 들르자", emptyList()).candidates.isEmpty())
        val unknown = VoiceDraftAnalyzer.analyze("없는장소로 가자", places)
        assertEquals(VoiceChangeIntent.CHANGE, unknown.intent); assertTrue(unknown.candidates.isEmpty())
        listOf("", "a".repeat(401), "카페\n가자", "카페\u0000가자").forEach {
            assertEquals(VoiceChangeIntent.UNCLEAR, VoiceDraftAnalyzer.analyze(it, places).intent)
        }
    }
    @Test fun exactPointExamplesAndOversizedListsAreNotVoiceSearchCandidates() {
        assertTrue(VoiceDraftAnalyzer.analyze("42dot으로 가자", PrototypePlaces.all).candidates.isEmpty())
        assertTrue(VoiceDraftAnalyzer.analyze("카페 가자", List(201) { cafe }).candidates.isEmpty())
    }
    @Test fun prohibitionUnwillingnessAndInabilityNeverCreateAnOppositeReasonOrCandidate() {
        listOf("카페에 들르면 안 돼", "카페에 가고 싶지 않아", "카페에 들르면 안 됩니다", "카페에 들러서는 안 된다",
            "카페에 들르고 싶지는 않아요", "카페는 원하지 않아", "카페는 원치 않아", "카페에 못 가", "카페에 들르지 못해",
            "카페 방문은 불가능해", "카페는 안 돼요 그래도 가자", "카페에 가고 싶지 않은데 가자",
            "카페에 안 갈래", "카페로 안 바꿀까").forEach { text ->
            val actual = VoiceDraftAnalyzer.analyze(text, places)
            assertNotEquals(text, VoiceChangeIntent.CHANGE, actual.intent)
            assertEquals(text, "", actual.reason); assertTrue(text, actual.candidates.isEmpty())
        }
    }
    @Test fun explicitPositiveDesiresKeepTheOriginalStoredCandidate() {
        listOf("카페에 들르고 싶어", "카페에 가고 싶어", "카페에 들르고 싶어요", "카페에 가고 싶어요").forEach { text ->
            val actual = VoiceDraftAnalyzer.analyze(text, places)
            assertEquals(text, VoiceChangeIntent.CHANGE, actual.intent)
            assertSame(text, cafe, actual.candidates.single()); assertEquals(text, "카페에 들러요.", actual.reason)
        }
    }
    @Test fun uncertainQuotedAndIncompleteActionsDoNotImplyAChange() {
        listOf("카페에 들르면", "카페에 가고 싶은데 잘 모르겠어", "카페에 가고 싶어 라는 말의 뜻은 뭐야",
            "카페에 가는 게 어려워", "카페에 갈 수는 없어", "카페에 가고 싶은 건 아니야", "카페는 빼고 가자").forEach { text ->
            val actual = VoiceDraftAnalyzer.analyze(text, places)
            assertNotEquals(text, VoiceChangeIntent.CHANGE, actual.intent)
            assertEquals(text, "", actual.reason); assertTrue(text, actual.candidates.isEmpty())
        }
    }
    @Test fun ordinaryPlaceNameSyllablesAreNotTreatedAsStandaloneNegationWords() {
        listOf("안동카페", "연못카페").forEachIndexed { index, name ->
            val original = place(name, "unit-positive-$index")
            val actual = VoiceDraftAnalyzer.analyze("${name}에 가고 싶어", listOf(original))
            assertEquals(VoiceChangeIntent.CHANGE, actual.intent); assertSame(original, actual.candidates.single())
        }
    }
}
