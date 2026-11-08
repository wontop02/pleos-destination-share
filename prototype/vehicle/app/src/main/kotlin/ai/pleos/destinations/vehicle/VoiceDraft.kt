package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.DestinationKind
import ai.pleos.destinations.core.PlaceSource
import java.util.Locale

internal enum class VoiceChangeIntent { CHANGE, UNCLEAR, NEGATED }
internal data class VoiceDraft(val intent: VoiceChangeIntent, val clue: String, val reason: String,
    val candidates: List<Destination>)

/** Conservative local text rules, not a generative model or a place-search service. */
internal object VoiceDraftAnalyzer {
    private data class Category(val label: String, val utterance: List<String>, val place: List<String>, val reason: String)
    private val categories = listOf(
        Category("주유소", listOf("주유", "기름"), listOf("주유소", "주유"), "주유하고 가요."),
        Category("충전소", listOf("충전"), listOf("충전소", "충전"), "충전하고 가요."),
        Category("카페", listOf("카페", "커피"), listOf("카페", "커피", "cafe", "coffee"), "카페에 들러요."),
        Category("식당", listOf("식당", "음식점", "식사", "배고", "밥먹"), listOf("식당", "음식점", "레스토랑", "restaurant"), "식사하고 가요."),
        Category("휴게소", listOf("휴게소"), listOf("휴게소"), "휴게소에 들러요.")
    )
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    fun validText(value: String) = value.length <= 400 && value.none { it.isISOControl() }

    fun analyze(text: String, places: List<Destination>): VoiceDraft {
        if (!validText(text) || text.isBlank()) return VoiceDraft(VoiceChangeIntent.UNCLEAR, "", "", emptyList())
        val value = normalized(text)
        val negative = listOf("가지마", "가지말", "가지않", "가면안", "안가", "들르지마", "들르지말", "들르지않", "안들", "취소", "싫", "말자", "변경하지", "바꾸지")
        if (negative.any(value::contains)) return VoiceDraft(VoiceChangeIntent.NEGATED, "", "", emptyList())
        val action = listOf("가자", "갈까", "갈래", "가요", "갑시다", "들르", "들러", "들를", "들릴까", "어때", "바꾸", "바꿔", "변경", "이동", "가고싶")
        if (action.none(value::contains)) return VoiceDraft(VoiceChangeIntent.UNCLEAR, "", "", emptyList())
        val found = categories.filter { c -> c.utterance.any(value::contains) }
        // Multiple category clues and alternatives require manual confirmation; never rank by imagined proximity.
        if (found.size > 1 || listOf("아니면", "또는", "말고").any(value::contains))
            return VoiceDraft(VoiceChangeIntent.UNCLEAR, found.joinToString(" / ") { it.label }, "", emptyList())
        val supported = places.takeIf { it.size <= 200 }?.filter {
            it.kind == DestinationKind.PLEOS_PLACE && it.source == PlaceSource.PLEOS_BOOKMARK
        } ?: emptyList()
        val named = supported.filter { place -> normalized(place.name).let { it.length >= 2 && value.contains(it) } }
        val category = found.singleOrNull()
        val candidates = if (named.isNotEmpty()) named else if (category != null) supported.filter { place ->
            val name = normalized(place.name); category.place.any { name.contains(normalized(it)) }
        } else emptyList()
        return VoiceDraft(VoiceChangeIntent.CHANGE,
            if (named.size == 1) named.single().name else if (named.size > 1) "저장한 이름 ${named.size}곳" else category?.label ?: "장소 확인 필요",
            category?.reason ?: "", candidates.toList())
    }
}
