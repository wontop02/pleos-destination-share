package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.PleosMetadata
import ai.pleos.playground.navi.data.DestinationInfo

sealed class NativeDestinationResult {
    data object Absent : NativeDestinationResult()
    data class Present(val destination: Destination) : NativeDestinationResult()
    data object Invalid : NativeDestinationResult()
}

object NaviDestinationNormalizer {
    fun normalize(info: DestinationInfo): NativeDestinationResult {
        // Exact empty response from the dedicated official Pleos service, never a blank-name heuristic.
        val empty = info.latitude == 0.0 && info.longitude == 0.0 && info.displayLatitude == null &&
            info.displayLongitude == null && info.name == "" && info.address == "" && info.poiId == "" &&
            info.poiSubId == null && info.poiProvider == "" && info.remainDistance == 0 &&
            info.remainTime == 0 && info.tollFare == 0
        if (empty) return NativeDestinationResult.Absent
        return try {
            NativeDestinationResult.Present(Destination(info.name.trim(), info.latitude, info.longitude,
                PleosMetadata(info.poiId, info.poiSubId, info.address, info.poiProvider,
                    info.displayLatitude, info.displayLongitude)))
        } catch (_: IllegalArgumentException) { NativeDestinationResult.Invalid }
    }
}
