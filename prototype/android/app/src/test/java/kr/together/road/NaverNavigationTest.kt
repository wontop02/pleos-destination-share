package kr.together.road

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class NaverNavigationTest {
    private val destination = Destination("가상 공원 & #1 + 100% / 쉼터?", 37.5209436, 127.1230074, 1000)
    @Test fun requestEncodesNameExactlyOnceAndUsesAndroidApplicationId() {
        val value = NaverNavigation.url(destination, "kr.together.road")!!
        val uri = URI(value)
        assertEquals("nmap", uri.scheme); assertEquals("navigation", uri.host); assertNull(uri.rawFragment)
        assertFalse(value.contains(" ")); assertTrue(value.contains("%20")); assertTrue(value.contains("%2B")); assertTrue(value.contains("%26"))
        val fields = uri.rawQuery.split('&').associate { field -> field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), "UTF-8") }
        assertEquals(4, fields.size)
        assertEquals(destination.name, fields["dname"]); assertEquals("37.5209436", fields["dlat"]); assertEquals("127.1230074", fields["dlng"])
        assertEquals("kr.together.road", fields["appname"]); assertEquals("com.nhn.android.nmap", NaverNavigation.PACKAGE)
    }
    @Test fun officialRegionalBoundsAreInclusive() {
        for (latitude in listOf(31.43, 44.35)) for (longitude in listOf(122.37, 132.0)) {
            assertNotNull(NaverNavigation.url(destination.copy(latitude = latitude, longitude = longitude), "kr.together.road"))
        }
    }
    @Test fun unsupportedAndNonFiniteCoordinatesNeverProduceAnIntentUrl() {
        for (latitude in listOf(31.429999, 44.350001, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(NaverNavigation.url(destination.copy(latitude = latitude), "kr.together.road"))
        }
        for (longitude in listOf(122.369999, 132.000001, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(NaverNavigation.url(destination.copy(longitude = longitude), "kr.together.road"))
        }
    }
}
