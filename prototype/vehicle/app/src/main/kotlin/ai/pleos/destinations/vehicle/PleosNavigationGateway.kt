package ai.pleos.destinations.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.NavigationGateway
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** Production adapter. No simulated SDK, fallback route service, or injected test listener. */
class PleosNavigationGateway(private val context: Context) : NavigationGateway, SavedPlacesGateway {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var helper: NaviHelper? = null
    private var placesListener: SavedPlacesGateway.Listener? = null
    override fun savedPlacesListener(listener: SavedPlacesGateway.Listener) { placesListener = listener }
    override fun readSavedPlaces() { checkNotNull(helper).getBookmarkInfo() }
    private var eventListener: NaviHelperEventListener? = null

    override fun connect(listener: NavigationGateway.Listener) {
        release()
        val epoch = generation
        val sdk = NaviHelper(context.applicationContext)
        helper = sdk
        fun deliver(block: () -> Unit) { main.post { if (epoch == generation && helper === sdk) block() } }
        val events = object : NaviHelperEventListener {
            override fun onDestinationChanged() = deliver { listener.destinationChanged() }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) = deliver {
                when (val result = NaviDestinationNormalizer.normalize(destinationInfo)) {
                    NativeDestinationResult.Absent -> listener.destinationAbsent()
                    is NativeDestinationResult.Present -> listener.destinationInfo(result.destination)
                    NativeDestinationResult.Invalid -> listener.invalidDestination()
                }
            }
            override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = deliver {
                placesListener?.bookmarks(epoch, savedPlaces(bookmarkInfoList))
            }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = deliver { listener.routeStarted() }
            override fun onRoutingCompleted() = deliver { listener.routingCompleted() }
            override fun onRouteCancelled() = deliver { listener.routeCancelled() }
            override fun onError(errorCode: NaviErrorCode) = deliver { placesListener?.bookmarkFailure(epoch); listener.error(errorCode.name) }
        }
        eventListener = events
        sdk.initialize(onConnected = {
            deliver {
                try { sdk.addListener(events); placesListener?.nativeSession(epoch, true); listener.connected() }
                catch (_: Exception) { listener.failed() }
            }
        }, onFailed = { deliver { listener.failed() } })
    }

    override fun readDestination() { checkNotNull(helper).getDestinationInfo() }

    override fun requestRoute(destination: Destination) {
        checkNotNull(helper).requestRoute(sdkRouteInfo(destination))
    }

    override fun release() {
        generation += 1
        placesListener?.nativeSession(generation, false)
        val previous = helper
        val previousListener = eventListener
        helper = null
        eventListener = null
        if (previous != null) {
            if (previousListener != null) runCatching { previous.removeListener(previousListener) }
            runCatching { previous.release() }
        }
    }
}

internal fun sdkRouteInfo(destination: Destination): RouteInfo {
    require(destination.canRequestRoute)
    val poi = checkNotNull(destination.pleos)
    return RouteInfo(
            latitude = destination.latitude,
            longitude = destination.longitude,
            displayLatitude = poi.displayLatitude,
            displayLongitude = poi.displayLongitude,
            poiName = destination.name,
            poiId = poi.poiId,
            address = poi.address,
            // Official two-emulator probe verified null source sub-ID with an empty SDK input.
            // Preserve null in room/model data; adapt only at this nonnull public SDK boundary.
            // Evidence: evidence/native-sdk-observations.json. Never invent the sub-ID "0".
            poiSubId = poi.poiSubId ?: "",
            routeOption = RouteOption.RECOMMENDED,
            poiProvider = poi.poiProvider,
            isMobileRequest = false,
    )
}
