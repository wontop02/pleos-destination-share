package ai.pleos.destinations.core

import kotlin.math.abs

data class PleosMetadata(
    val poiId: String,
    val poiSubId: String?,
    val address: String,
    val poiProvider: String,
    val displayLatitude: Double?,
    val displayLongitude: Double?,
) {
    init {
        require(validText(poiId, 256, true) && (poiSubId == null || validText(poiSubId, 256, true)))
        require(validText(address, 500, true) && validText(poiProvider, 128, true))
        require((displayLatitude == null) == (displayLongitude == null))
        require(displayLatitude == null || validCoordinates(displayLatitude, displayLongitude!!))
    }
}

enum class DestinationKind(val wire: String) { EXACT_POINT("exact_point"), PLEOS_PLACE("pleos_place") }
enum class PlaceSource(val wire: String) { PLEOS_BOOKMARK("pleos_bookmark") }

data class Destination(val name: String, val latitude: Double, val longitude: Double,
    val pleos: PleosMetadata? = null, val kind: DestinationKind = DestinationKind.EXACT_POINT,
    val source: PlaceSource? = null) {
    init {
        require(validText(name, 100) && name == name.trim())
        require(validCoordinates(latitude, longitude))
        when (kind) {
            DestinationKind.EXACT_POINT -> require(source == null)
            DestinationKind.PLEOS_PLACE -> {
                require(source == PlaceSource.PLEOS_BOOKMARK)
                val metadata = requireNotNull(pleos)
                // Only the provider actually exposed by the supported native bookmark path.
                require(metadata.poiProvider == "" && metadata.poiId.isNotBlank())
                require(metadata.poiId == metadata.poiId.trim())
                require(metadata.poiSubId == null || (metadata.poiSubId.isNotBlank() && metadata.poiSubId == metadata.poiSubId.trim()))
                require(metadata.address.isNotBlank())
            }
        }
    }

    val canRequestRoute: Boolean get() = pleos?.let { it.poiId.isNotBlank() && (it.poiSubId?.isNotBlank() ?: true) } == true

    /** Request meaning only. Native observations and direct sharing always remain exact points. */
    fun matchesRequestedDestination(actual: Destination): Boolean = when (kind) {
        DestinationKind.EXACT_POINT -> matchesNative(actual)
        DestinationKind.PLEOS_PLACE -> source == PlaceSource.PLEOS_BOOKMARK &&
            actual.kind == DestinationKind.EXACT_POINT && actual.source == null &&
            name == actual.name && pleos?.poiProvider == actual.pleos?.poiProvider &&
            pleos?.poiId == actual.pleos?.poiId && pleos?.poiSubId == actual.pleos?.poiSubId
    }

    /** Strict POI identity and ~1 m coordinate tolerance. An SDK callback alone is never a match. */
    fun matchesNative(actual: Destination): Boolean {
        val expectedPoi = pleos ?: return false
        val actualPoi = actual.pleos ?: return false
        return name == actual.name && abs(latitude - actual.latitude) <= 0.00001 &&
            abs(longitude - actual.longitude) <= 0.00001 && expectedPoi.poiId == actualPoi.poiId &&
            expectedPoi.poiSubId == actualPoi.poiSubId && expectedPoi.poiProvider == actualPoi.poiProvider
    }
}

private fun validText(value: String, max: Int, empty: Boolean = false): Boolean =
    value.length <= max && (empty || value.isNotBlank()) && value.none { it.code < 32 || it.code == 127 }

private fun validCoordinates(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
