package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.DestinationKind
import ai.pleos.destinations.core.PlaceSource
import ai.pleos.destinations.core.PleosMetadata
import org.junit.Assert.*
import org.junit.Test

class SdkRouteMappingTest {
    private val value = Destination("42dot", 37.41289678, 127.09383464,
        PleosMetadata("36khp37hh8gqcm5nqhe5", null, "수정구 시흥동", "", null, null))

    @Test fun nullSubIdIsAdaptedOnlyAtSdkBoundaryAndNeverChangedInRoomData() {
        val sdk = sdkRouteInfo(value)
        assertEquals("", sdk.poiSubId)
        assertEquals(value.name, sdk.poiName); assertEquals(value.pleos!!.poiId, sdk.poiId)
        assertEquals(value.latitude, sdk.latitude, 0.0); assertEquals(value.longitude, sdk.longitude, 0.0)
        assertEquals(value.pleos!!.address, sdk.address); assertEquals(value.pleos!!.poiProvider, sdk.poiProvider)
        assertNull(value.pleos!!.poiSubId)
        assertTrue(RoomProtocol.destinationJson(value).getJSONObject("pleos").isNull("poiSubId"))
    }

    @Test fun selectedPlaceRouteUsesTheOriginalBookmarkFieldsWithoutLearningTheObservedGuidancePoint() {
        val selected = Destination("가든서비스", 37.41255277, 127.09591666,
            PleosMetadata("36khp2h36p68cm5nqhec", null, "경기도 성남시 수정구 창업로40번길6", "", null, null),
            DestinationKind.PLEOS_PLACE, PlaceSource.PLEOS_BOOKMARK)
        val sdk = sdkRouteInfo(selected)
        assertEquals(37.41255277, sdk.latitude, 0.0); assertEquals(127.09591666, sdk.longitude, 0.0)
        assertEquals(selected.pleos!!.address, sdk.address); assertEquals(selected.name, sdk.poiName)
        assertEquals(selected.pleos!!.poiId, sdk.poiId); assertEquals("", sdk.poiProvider)
        assertEquals("", sdk.poiSubId); assertNull(selected.pleos!!.poiSubId)
        assertEquals(selected, RoomProtocol.destination(RoomProtocol.destinationJson(selected)))
    }

    @Test fun actualSubIdsArePreservedWithoutSubstitutingAZeroOrEmptyValue() {
        for (id in listOf("0", "specific-sub-poi")) {
            val source = value.copy(pleos = value.pleos!!.copy(poiSubId = id))
            assertEquals(id, sdkRouteInfo(source).poiSubId)
        }
    }

    @Test fun missingPoiIdentityOrLegacyMetadataCannotGenerateAnSdkRoute() {
        for (source in listOf(value.copy(pleos = null), value.copy(pleos = value.pleos!!.copy(poiId = "")),
            value.copy(pleos = value.pleos!!.copy(poiSubId = " ")))) {
            assertThrows(IllegalArgumentException::class.java) { sdkRouteInfo(source) }
        }
    }
}
