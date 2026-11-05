package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PleosMetadata

/** Bounded public-place fixture, using the canonical actual SDK observations from #12. */
object PrototypePlaces {
    val all = listOf(
        Destination("42dot", 37.41289678, 127.09383464,
            PleosMetadata("36khp37hh8gqcm5nqhe5", null, "수정구 시흥동", "", null, null)),
        Destination("소프트웨어드림센터", 37.41275833, 127.09481944,
            PleosMetadata("36khp2hdj2cfcm5nqhe3", null, "수정구 시흥동", "", null, null)),
    )
}
