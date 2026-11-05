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

data class Destination(val name: String, val latitude: Double, val longitude: Double, val pleos: PleosMetadata? = null) {
    init {
        require(validText(name, 100) && name == name.trim())
        require(validCoordinates(latitude, longitude))
    }

    val canRequestRoute: Boolean get() = pleos?.let { it.poiId.isNotBlank() && (it.poiSubId?.isNotBlank() ?: true) } == true

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
