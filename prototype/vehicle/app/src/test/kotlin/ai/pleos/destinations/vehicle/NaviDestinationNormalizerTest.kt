package ai.pleos.destinations.vehicle

import ai.pleos.playground.navi.data.DestinationInfo
import org.junit.Assert.*
import org.junit.Test

class NaviDestinationNormalizerTest {
    private fun empty() = DestinationInfo(latitude = 0.0, longitude = 0.0, displayLatitude = null,
        displayLongitude = null, name = "", address = "", remainDistance = 0, remainTime = 0, tollFare = 0,
        poiId = "", poiSubId = null, poiProvider = "")

    @Test fun exactOfficialEmptyResponseIsAbsence() {
        assertEquals(NativeDestinationResult.Absent, NaviDestinationNormalizer.normalize(empty()))
    }

    @Test fun blankOrPartialTextNeverBecomesAuthoritativeAbsence() {
        val initial = empty()
        for (value in listOf(initial.copy(name = " "), initial.copy(address = " "),
            initial.copy(poiId = " "), initial.copy(poiProvider = " "), initial.copy(poiId = "partial-poi"))) {
            assertEquals(NativeDestinationResult.Invalid, NaviDestinationNormalizer.normalize(value))
        }
    }

    @Test fun nonzeroOrInvalidCoordinatesWithEmptyFieldsAreNotDeletion() {
        for (value in listOf(empty().copy(latitude = 37.0), empty().copy(longitude = 127.0),
            empty().copy(latitude = Double.NaN), empty().copy(longitude = Double.POSITIVE_INFINITY))) {
            assertEquals(NativeDestinationResult.Invalid, NaviDestinationNormalizer.normalize(value))
        }
    }

    @Test fun partialMetadataOrRemainingRouteValuesCannotMatchTheEmptySentinel() {
        val initial = empty()
        for (value in listOf(initial.copy(poiSubId = "0"), initial.copy(displayLatitude = 0.0),
            initial.copy(displayLongitude = 0.0), initial.copy(remainDistance = 1), initial.copy(remainTime = 1), initial.copy(tollFare = 1))) {
            assertEquals(NativeDestinationResult.Invalid, NaviDestinationNormalizer.normalize(value))
        }
    }

    @Test fun namedDestinationAtZeroCoordinatesIsPreservedAndMissingPoiCannotBeInvented() {
        val result = NaviDestinationNormalizer.normalize(empty().copy(name = "합성 좌표 장소")) as NativeDestinationResult.Present
        assertEquals(0.0, result.destination.latitude, 0.0)
        assertFalse(result.destination.canRequestRoute)
    }

    @Test fun realPoiFieldsAndNullableSubIdArePreservedWithoutFallback() {
        val value = empty().copy(name = "42dot", latitude = 37.48544722, longitude = 127.03636666,
            address = "서울시 강남구 남부순환로 2621", poiId = "36khp37hh8gqcm5nqhe5", poiSubId = "0")
        val result = NaviDestinationNormalizer.normalize(value) as NativeDestinationResult.Present
        assertTrue(result.destination.canRequestRoute)
        assertEquals("0", result.destination.pleos!!.poiSubId)
        val incomplete = NaviDestinationNormalizer.normalize(value.copy(poiSubId = null)) as NativeDestinationResult.Present
        assertNull(incomplete.destination.pleos!!.poiSubId); assertTrue(incomplete.destination.canRequestRoute)
    }
}
