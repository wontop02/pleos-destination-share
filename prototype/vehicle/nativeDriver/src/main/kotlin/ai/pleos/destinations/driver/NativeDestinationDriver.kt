package ai.pleos.destinations.driver

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Test-only, self-targeted process. Calls real NaviHelper; no Activity, production hook, or mock.
 * Its instrumentation can terminate only this driver package, never the convoy service package.
 */
class NativeDestinationDriver : Instrumentation() {
    private var mode = "query"
    private var probeArguments = Bundle()
    private val routeRequested = AtomicBoolean(false)
    private val routeRequestCount = AtomicInteger()
    private val navigationScreenRequestCount = AtomicInteger()
    private val navigationScreenError = AtomicReference<String?>()
    private val tripRequestCount = AtomicInteger()
    private val tripError = AtomicReference<String?>()
    private val nativeRouteStartCallbackCount = AtomicInteger()
    private val routeStartedSeen = AtomicBoolean(false)
    private val routingCompletedSeen = AtomicBoolean(false)
    private val routeError = AtomicReference<String?>()
    private val generation = AtomicInteger()
    private val events = mutableListOf<JSONObject>()
    private val report = JSONObject().put("sdk", "ai.pleos.playground:NaviHelper:3.0.5")
    private var sdk: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private val query = AtomicReference<Query?>()
    private val bookmarkQuery = AtomicReference<BookmarkQuery?>()
    private val tripQuery = AtomicReference<TripQuery?>()
    private val signals = LinkedBlockingQueue<String>()
    private var queryId = 0
    private class Query(val id: Int) {
        val done = CountDownLatch(1)
        @Volatile var value: DestinationInfo? = null
    }
    private class BookmarkQuery(val id: Int, val publicNames: Set<String>) {
        val done = CountDownLatch(1)
        @Volatile var value: JSONObject? = null
    }
    private class TripQuery(val id: Int, val publicName: String) {
        @Volatile var value: JSONObject? = null
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probeArguments = arguments?.let(::Bundle) ?: Bundle()
        mode = arguments?.getString("mode") ?: "query"
        start()
    }

    override fun onStart() {
        var status = "BLOCKED"
        try {
            require(mode in listOf("query", "observe", "permission-info", "search-screen", "bookmarks", "trip-public-poi", "route-public-poi", "route-coordinate-only", "route-42dot", "route-dream-center"))
            report.put("mode", mode).put("startedAt", System.currentTimeMillis())
                .put("targetPackage", targetContext.packageName).put("processId", android.os.Process.myPid())
            if (mode == "permission-info") {
                val info = targetContext.packageManager.getPermissionInfo("pleos.car.permission.NAVI_CUSTOM_MAP", 0)
                report.put("permissionInfo", JSONObject().put("name", info.name).put("sourcePackage", info.packageName)
                    .put("protectionLevel", info.protectionLevel).put("protection", info.protection)
                    .put("protectionFlags", info.protectionFlags).put("flags", info.flags)
                    .put("normalProtection", info.protection == PermissionInfo.PROTECTION_NORMAL)
                    .put("dangerousProtection", info.protection == PermissionInfo.PROTECTION_DANGEROUS)
                    .put("signatureProtection", info.protection == PermissionInfo.PROTECTION_SIGNATURE))
                    .put("reason", "public_package_manager_permission_definition_observed")
                status = "OBSERVED"
            } else if (!connect()) report.put("reason", "initial_service_connection_failed_or_timed_out")
            else {
                val initial = read()
                report.put("initialDestination", initial?.let(::destinationJson) ?: JSONObject.NULL)
                if (mode == "search-screen") {
                    status = requestSearchScreen(initial)
                } else if (mode == "bookmarks") {
                    status = readPublicBookmarks(initial)
                } else if (mode == "observe") {
                    status = observe(initial)
                } else if (mode == "query") {
                    status = if (initial == null) "BLOCKED" else "OBSERVED"
                    report.put("reason", if (initial == null) "destination_query_unanswered_not_assumed_absent" else "actual_destination_callback_received")
                } else {
                    releaseSdk() // Late initial-query callbacks cannot become post-request evidence.
                    signals.clear()
                    if (!connect()) report.put("reason", "route_session_connection_failed_or_timed_out")
                    else {
                        if (mode == "trip-public-poi") {
                            status = requestPublicTrip(initial)
                        } else if (mode == "route-public-poi") {
                            status = requestPublicPoi(initial)
                        } else if (mode == "route-coordinate-only") {
                            status = requestCoordinates(initial)
                        } else {
                        val dreamCenter = mode == "route-dream-center"
                        // Public SDK example POI IDs, with actual canonical observations from #12.
                        // The SDK returns nullable sub-ID and canonical entrance coordinates.
                        val name = if (dreamCenter) "소프트웨어드림센터" else "42dot"
                        val poiId = if (dreamCenter) "36khp2hdj2cfcm5nqhe3" else "36khp37hh8gqcm5nqhe5"
                        val latitude = if (dreamCenter) 37.41275833 else 37.41289678
                        val longitude = if (dreamCenter) 127.09481944 else 127.09383464
                        val address = "수정구 시흥동"
                        val expectedSubId: String? = null
                        val requestSubId = ""
                        report.put("sourceExpected", JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude)
                            .put("poiId", poiId).put("poiSubId", JSONObject.NULL).put("poiProvider", "").put("address", address))
                            .put("requestPoiSubId", requestSubId)
                        val route = RouteInfo(latitude = latitude, longitude = longitude, poiName = name,
                            poiId = poiId, poiSubId = requestSubId, address = address)
                        signals.clear()
                        routeRequested.set(true)
                        routeRequestCount.incrementAndGet()
                        trace("requestRoute_public_poi")
                        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
                        runOnMainSync { checkNotNull(sdk).requestRoute(route) }
                        var nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
                        var observed = false
                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                            val wait = (nextPoll - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).coerceAtMost(remaining.coerceAtLeast(1))
                            val signal = signals.poll(wait, TimeUnit.MILLISECONDS)
                            if (signal?.startsWith("error:") == true) { report.put("reason", signal); break }
                            if (signal == null && android.os.SystemClock.elapsedRealtime() < nextPoll) continue
                            val budget = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtMost(8_000)
                            if (budget <= 0) break
                            val actual = read(budget)
                            if (actual == null) { report.put("reason", "post_request_query_timeout"); break }
                            observed = true
                            report.put("postRequestDestination", destinationJson(actual))
                            val matched = actual.name == name && actual.poiId == poiId &&
                                actual.poiSubId == expectedSubId && actual.poiProvider == "" &&
                                abs(actual.latitude - latitude) <= 0.00001 && abs(actual.longitude - longitude) <= 0.00001
                            trace("post_request_destination_match", JSONObject().put("matched", matched))
                            if (routeError.get() != null) { report.put("reason", routeError.get()); break }
                            if (matched) { status = "VERIFIED"; report.put("reason", "actual_post_request_destination_matched"); break }
                            nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
                        }
                        if (!report.has("reason")) {
                            status = if (observed) "FAILED" else "BLOCKED"
                            report.put("reason", if (observed) "post_request_destination_did_not_match" else "no_completion_or_destination_event_within_45s")
                        }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            status = "FAILED"
            report.put("reason", "probe_exception").put("failureType", error.javaClass.simpleName)
        } finally {
            releaseSdk()
            report.put("status", status).put("finishedAt", System.currentTimeMillis())
                .put("routeStartedObserved", routeStartedSeen.get()).put("routingCompletedObserved", routingCompletedSeen.get())
                .put("routeRequestCount", routeRequestCount.get())
                .put("navigationScreenRequestCount", navigationScreenRequestCount.get())
                .put("tripRequestCount", tripRequestCount.get()).put("nativeRouteStartCallbackCount", nativeRouteStartCallbackCount.get())
            synchronized(events) { report.put("events", JSONArray(events)) }
            runCatching { File(targetContext.noBackupFilesDir, "native-driver.json").writeText(report.toString(2)) }
            val result = Bundle().apply { putString("probe_json", report.toString()) }
            finish(if (status == "VERIFIED" || status == "OBSERVED") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    /** One supplied-target information request; guidance invariance is measured, not assumed. */
    private fun requestPublicTrip(initial: DestinationInfo?): String {
        if (initial == null) {
            report.put("reason", "initial_query_unanswered_trip_request_not_sent")
            return "BLOCKED"
        }
        val input = parsePublicDestinationInput()
        fun text(key: String, limit: Int, allowEmpty: Boolean): String {
            require(input.get(key) is String)
            return input.getString(key).also {
                require(it.length <= limit && (allowEmpty || it.isNotBlank()) && it.none(Char::isISOControl))
            }
        }
        val name = text("name", 100, false)
        val latitude = input.getDouble("latitude")
        val longitude = input.getDouble("longitude")
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        val poiId = text("poiId", 256, false)
        require(input.has("poiSubId"))
        val subId = if (input.isNull("poiSubId")) null else text("poiSubId", 256, false)
        val provider = text("poiProvider", 128, true)
        val address = text("address", 500, true)
        require(input.has("displayLatitude") && input.has("displayLongitude"))
        val displayLatitude = if (input.isNull("displayLatitude")) null else input.getDouble("displayLatitude")
        val displayLongitude = if (input.isNull("displayLongitude")) null else input.getDouble("displayLongitude")
        require((displayLatitude == null) == (displayLongitude == null))
        require(displayLatitude == null || (displayLatitude.isFinite() && displayLatitude in -90.0..90.0 &&
            checkNotNull(displayLongitude).isFinite() && displayLongitude in -180.0..180.0))
        if (provider != "") {
            report.put("reason", "trip_probe_supports_only_actual_empty_provider_no_request_sent")
            return "BLOCKED"
        }
        val source = JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude)
            .put("poiId", poiId).put("poiSubId", subId ?: JSONObject.NULL).put("poiProvider", provider).put("address", address)
            .put("displayLatitude", displayLatitude ?: JSONObject.NULL).put("displayLongitude", displayLongitude ?: JSONObject.NULL)
        val request = TripRequest(latitude = latitude, longitude = longitude, poiId = poiId, poiName = name, poiSubId = subId)
        val pending = TripQuery(++queryId, name)
        check(tripQuery.compareAndSet(null, pending))
        report.put("sourceExpected", source).put("requestTimeBudgetMs", 30_000)
            .put("tripReadOnlyContractConfirmed", false).put("tripResponseHasProviderSubIdOrRequestId", false)
            .put("tripRequestInput", JSONObject().put("latitude", latitude).put("longitude", longitude)
                .put("poiId", poiId).put("poiName", name).put("poiSubId", subId ?: JSONObject.NULL))
        signals.clear()
        tripRequestCount.incrementAndGet()
        trace("requestTrip_public_sdk_record", JSONObject().put("query", pending.id))
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        var unchanged = true
        var answered = true
        val observations = JSONArray().put(destinationJson(initial))
        var complete = false
        try {
            runOnMainSync { checkNotNull(sdk).requestTrip(request) }
            report.put("tripRequestReturned", true)
            var nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                val wait = (nextPoll - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).coerceAtMost(remaining.coerceAtLeast(1))
                val signal = signals.poll(wait, TimeUnit.MILLISECONDS)
                if (signal?.startsWith("error:") == true) { report.put("reason", signal); break }
                if (signal == null && android.os.SystemClock.elapsedRealtime() < nextPoll) continue
                val callbackReceivedBeforeQuery = pending.value != null
                val budget = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtMost(8_000)
                if (budget <= 0) break
                val actual = read(budget)
                if (actual == null) {
                    answered = false; report.put("reason", "trip_post_request_destination_query_timeout")
                    break
                }
                observations.put(destinationJson(actual))
                unchanged = unchanged && sameDestination(initial, actual)
                report.put("postTripDestination", destinationJson(actual))
                if (android.os.SystemClock.elapsedRealtime() > deadline) {
                    report.put("reason", "trip_probe_deadline_elapsed_before_complete_post_query")
                    break
                }
                if (tripError.get() != null) { report.put("reason", "error:" + tripError.get()); break }
                if (callbackReceivedBeforeQuery) { complete = true; break }
                nextPoll = android.os.SystemClock.elapsedRealtime() + if (pending.value != null) 1 else 2_000
            }
            val response = pending.value
            if (response != null) {
                report.put("tripObservation", response)
                val comparisons = JSONArray()
                val points = response.getJSONArray("routePoints")
                for (index in 0 until points.length()) {
                    val point = points.getJSONObject(index)
                    comparisons.put(JSONObject().put("pointIndex", index).put("pointType", point.getString("pointType"))
                        .put("nameMatchesInput", point.getString("poiName") == name)
                        .put("poiIdMatchesInput", point.getString("poiId") == poiId)
                        .put("coordinatesMatchInput", abs(point.getDouble("latitude") - latitude) <= 0.00001 &&
                            abs(point.getDouble("longitude") - longitude) <= 0.00001))
                }
                report.put("tripPointComparisonsToOriginalInput", comparisons)
            }
            report.put("destinationObservations", observations).put("allObservationQueriesAnswered", answered)
                .put("destinationIdentityUnchanged", if (answered || !unchanged) unchanged else JSONObject.NULL)
                .put("postQueryStartedAfterTripCallback", complete).put("tripProbeCompleted", complete)
            if (!report.has("reason")) report.put("reason", when {
                complete -> "trip_callback_and_subsequent_native_destination_observed_not_canonical_support"
                response == null -> "no_trip_info_callback_within_30s"
                else -> "trip_callback_without_complete_subsequent_native_query_within_30s"
            })
            return when {
                tripError.get() != null -> "FAILED"
                complete -> "OBSERVED"
                else -> "BLOCKED"
            }
        } finally { tripQuery.compareAndSet(pending, null) }
    }

    /** The operator supplies one actual public SDK bookmark record, never a fabricated POI. */
    private fun requestPublicPoi(initial: DestinationInfo?): String {
        if (initial == null) {
            report.put("reason", "initial_query_unanswered_public_poi_request_not_sent")
            return "BLOCKED"
        }
        val input = parsePublicDestinationInput()
        fun text(key: String, limit: Int, allowEmpty: Boolean): String {
            require(input.get(key) is String)
            return input.getString(key).also {
                require(it.length <= limit && (allowEmpty || it.isNotBlank()) && it.none(Char::isISOControl))
            }
        }
        val name = text("name", 100, false)
        val latitude = input.getDouble("latitude")
        val longitude = input.getDouble("longitude")
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        val poiId = text("poiId", 256, false)
        require(input.has("poiSubId"))
        val subId = if (input.isNull("poiSubId")) null else text("poiSubId", 256, false)
        val provider = text("poiProvider", 128, true)
        val address = text("address", 500, true)
        require(input.has("displayLatitude") && input.has("displayLongitude"))
        val displayLatitude = if (input.isNull("displayLatitude")) null else input.getDouble("displayLatitude")
        val displayLongitude = if (input.isNull("displayLongitude")) null else input.getDouble("displayLongitude")
        require((displayLatitude == null) == (displayLongitude == null))
        require(displayLatitude == null || (displayLatitude.isFinite() && displayLatitude in -90.0..90.0 &&
            checkNotNull(displayLongitude).isFinite() && displayLongitude in -180.0..180.0))
        val expected = JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude)
            .put("poiId", poiId).put("poiSubId", subId ?: JSONObject.NULL).put("poiProvider", provider).put("address", address)
            .put("displayLatitude", displayLatitude ?: JSONObject.NULL).put("displayLongitude", displayLongitude ?: JSONObject.NULL)
        report.put("sourceExpected", expected).put("requestPoiSubId", subId ?: "").put("requestTimeBudgetMs", 45_000)
            .put("sourceProvenance", "operator_supplied_actual_public_sdk_record_external_artifact_required")
        val route = RouteInfo(latitude = latitude, longitude = longitude, displayLatitude = displayLatitude,
            displayLongitude = displayLongitude, poiName = name, poiId = poiId, poiSubId = subId ?: "",
            address = address, poiProvider = provider)
        signals.clear()
        routeRequested.set(true)
        routeRequestCount.incrementAndGet()
        trace("requestRoute_public_sdk_record", JSONObject().put("input", expected))
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        runOnMainSync { checkNotNull(sdk).requestRoute(route) }
        var observed = false
        var matched = false
        var nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            val wait = (nextPoll - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).coerceAtMost(remaining.coerceAtLeast(1))
            val signal = signals.poll(wait, TimeUnit.MILLISECONDS)
            if (signal?.startsWith("error:") == true) { report.put("reason", signal); break }
            if (signal == null && android.os.SystemClock.elapsedRealtime() < nextPoll) continue
            val budget = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtMost(8_000)
            if (budget <= 0) break
            val actual = read(budget)
            if (actual == null) { report.put("reason", "post_request_query_timeout"); break }
            observed = true
            matched = actual.name == name && actual.poiId == poiId && actual.poiSubId == subId && actual.poiProvider == provider &&
                abs(actual.latitude - latitude) <= 0.00001 && abs(actual.longitude - longitude) <= 0.00001
            report.put("postRequestDestination", destinationJson(actual)).put("postRequestStrictIdentityMatched", matched)
                .put("postRequestIdentityChanged", !sameDestination(initial, actual))
                .put("postRequestAddressMatched", actual.address == address)
                .put("postRequestDisplayCoordinatesMatched", actual.displayLatitude == displayLatitude && actual.displayLongitude == displayLongitude)
            trace("public_poi_post_request_strict_match", JSONObject().put("matched", matched))
            if (routeError.get() != null) { report.put("reason", routeError.get()); break }
            if (matched) { report.put("reason", "actual_post_request_public_poi_strict_match"); break }
            nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
        }
        if (!report.has("reason")) report.put("reason", if (observed) "post_request_public_poi_identity_did_not_match" else "no_post_request_destination_callback_within_45s")
        return when {
            routeError.get() != null -> "FAILED"
            matched -> "VERIFIED"
            observed && !report.getString("reason").contains("timeout") -> "FAILED"
            else -> "BLOCKED"
        }
    }

    /** Requests the public screen, then observes guidance; no selection result is invented. */
    private fun requestSearchScreen(initial: DestinationInfo?): String {
        if (initial == null) {
            report.put("reason", "initial_query_unanswered_search_screen_not_requested")
            return "BLOCKED"
        }
        val keyword = requireNotNull(probeArguments.getString("keyword"))
        require(keyword.isNotBlank() && keyword.length <= 120 && keyword.none(Char::isISOControl))
        report.put("keyword", keyword).put("selectionResultCallbackContractConfirmed", false)
            .put("screenOpenedObserved", JSONObject.NULL)
        if (targetContext.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != PackageManager.PERMISSION_GRANTED) {
            report.put("reason", "NAVI_CUSTOM_MAP_not_granted_search_screen_not_requested")
            return "BLOCKED"
        }
        navigationScreenRequestCount.incrementAndGet()
        trace("requestNavigationScreen_SearchPlace", JSONObject().put("keyword", keyword))
        runOnMainSync { checkNotNull(sdk).requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }
        report.put("navigationScreenRequestReturned", true)
        val status = observe(initial)
        report.put("navigationScreenError", navigationScreenError.get() ?: JSONObject.NULL)
        if (navigationScreenError.get() != null) {
            report.put("reason", "search_screen_sdk_error:${navigationScreenError.get()}")
            return "FAILED"
        }
        if (status == "OBSERVED") report.put("reason", "search_screen_request_returned_native_destination_samples_observed")
        return status
    }

    /** Only exact, operator-supplied public test names may leave the SDK callback. */
    private fun readPublicBookmarks(initial: DestinationInfo?): String {
        if (initial == null) {
            report.put("reason", "initial_query_unanswered_bookmark_query_not_sent")
            return "BLOCKED"
        }
        val supplied = JSONArray(requireNotNull(probeArguments.getString("publicNamesJson")))
        require(supplied.length() in 1..5)
        val publicNames = (0 until supplied.length()).map { supplied.getString(it) }.toSet()
        require(publicNames.size == supplied.length() && publicNames.all { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) })
        report.put("publicNameAllowlist", JSONArray(publicNames.toList()))
        val pending = BookmarkQuery(++queryId, publicNames)
        check(bookmarkQuery.compareAndSet(null, pending))
        trace("getBookmarkInfo", JSONObject().put("query", pending.id))
        try {
            runOnMainSync { checkNotNull(sdk).getBookmarkInfo() }
            if (!pending.done.await(8, TimeUnit.SECONDS)) {
                report.put("reason", "bookmark_query_timeout_no_results_assumed")
                return "BLOCKED"
            }
            val actual = checkNotNull(pending.value)
            report.put("bookmarkObservation", actual)
            val after = read()
            report.put("postBookmarkDestination", after?.let(::destinationJson) ?: JSONObject.NULL)
                .put("destinationIdentityUnchanged", after?.let { sameDestination(initial, it) } ?: JSONObject.NULL)
            if (after == null) {
                report.put("reason", "post_bookmark_destination_query_timeout_not_assumed_unchanged")
                return "BLOCKED"
            }
            report.put("reason", if (actual.getInt("allowlistedEntryCount") == 0)
                "bookmark_callback_received_no_allowlisted_names_matched" else "allowlisted_public_bookmark_fields_observed")
            return "OBSERVED"
        } finally { bookmarkQuery.compareAndSet(pending, null) }
    }

    /** Read-only sampling while the operator uses the native UI. Never requests a route. */
    private fun observe(initial: DestinationInfo?): String {
        if (initial == null) {
            report.put("reason", "initial_query_unanswered_not_assumed_absent")
            return "BLOCKED"
        }
        val duration = (probeArguments.getString("durationMs") ?: "20000").toLong()
        require(duration in 1_000L..90_000L)
        report.put("observationDurationMs", duration)
        val deadline = android.os.SystemClock.elapsedRealtime() + duration
        val observations = JSONArray().put(destinationJson(initial))
        var unchanged = true
        var samplesComplete = true
        var nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val now = android.os.SystemClock.elapsedRealtime()
            val wait = (minOf(nextPoll, deadline) - now).coerceAtLeast(1)
            // Only waits for UI observation time; native events cannot cause a route request.
            signals.poll(wait, TimeUnit.MILLISECONDS)
            if (android.os.SystemClock.elapsedRealtime() < minOf(nextPoll, deadline)) continue
            val actual = read()
            if (actual == null) { samplesComplete = false; break }
            observations.put(destinationJson(actual))
            unchanged = unchanged && sameDestination(initial, actual)
            nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
        }
        report.put("destinationObservations", observations)
            .put("destinationIdentityUnchanged", if (samplesComplete || !unchanged) unchanged else JSONObject.NULL)
            .put("allObservationQueriesAnswered", samplesComplete)
            .put("reason", if (samplesComplete) "read_only_native_destination_samples_observed" else "observation_query_timeout_not_assumed_unchanged")
        return if (samplesComplete) "OBSERVED" else "BLOCKED"
    }

    /** Empty IDs are deliberate diagnostic input, never an invented POI or a search result. */
    private fun requestCoordinates(initial: DestinationInfo?): String {
        if (initial == null) {
            report.put("reason", "initial_query_unanswered_coordinate_request_not_sent")
            return "BLOCKED"
        }
        val latitude = requireNotNull(probeArguments.getString("latitude")).toDouble()
        val longitude = requireNotNull(probeArguments.getString("longitude")).toDouble()
        val name = requireNotNull(probeArguments.getString("name"))
        val address = requireNotNull(probeArguments.getString("address"))
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(name.isNotBlank() && name.length <= 160 && address.length <= 256)
        val input = JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude)
            .put("address", address).put("poiId", "").put("poiSubId", "").put("poiProvider", "")
        report.put("coordinateOnlyInput", input).put("officialCoordinateOnlySupportConfirmed", false)
        val route = RouteInfo(latitude = latitude, longitude = longitude, poiName = name,
            poiId = "", poiSubId = "", address = address, poiProvider = "")
        signals.clear()
        routeRequested.set(true)
        routeRequestCount.incrementAndGet()
        trace("requestRoute_empty_ids_diagnostic", JSONObject().put("input", input))
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        runOnMainSync { checkNotNull(sdk).requestRoute(route) }
        var observed = false
        var inputMatched = false
        var nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            val wait = (nextPoll - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).coerceAtMost(remaining.coerceAtLeast(1))
            val signal = signals.poll(wait, TimeUnit.MILLISECONDS)
            if (signal?.startsWith("error:") == true) { report.put("reason", signal); break }
            if (signal == null && android.os.SystemClock.elapsedRealtime() < nextPoll) continue
            val budget = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtMost(8_000)
            if (budget <= 0) break
            val actual = read(budget)
            if (actual == null) { report.put("reason", "post_request_query_timeout"); break }
            observed = true
            inputMatched = actual.name == name && abs(actual.latitude - latitude) <= 0.00001 && abs(actual.longitude - longitude) <= 0.00001
            report.put("postRequestDestination", destinationJson(actual)).put("postRequestNameAndCoordinatesMatchInput", inputMatched)
                .put("postRequestIdentityChanged", !sameDestination(initial, actual))
            trace("coordinate_only_post_request_observation", JSONObject().put("nameAndCoordinatesMatchInput", inputMatched))
            if (routeError.get() != null) { report.put("reason", routeError.get()); break }
            if (inputMatched) break
            nextPoll = android.os.SystemClock.elapsedRealtime() + 2_000
        }
        if (!report.has("reason")) report.put("reason", when {
            inputMatched -> "coordinate_only_input_match_observed_not_search_or_official_support"
            observed -> "coordinate_only_actual_destination_observed_without_input_match"
            else -> "no_post_request_destination_callback_within_45s"
        })
        return when {
            routeError.get() != null -> "FAILED"
            observed && inputMatched -> "OBSERVED"
            observed && !report.getString("reason").contains("timeout") -> "OBSERVED"
            else -> "BLOCKED"
        }
    }

    private fun sameDestination(first: DestinationInfo, second: DestinationInfo) =
        first.latitude == second.latitude && first.longitude == second.longitude &&
            first.displayLatitude == second.displayLatitude && first.displayLongitude == second.displayLongitude &&
            first.name == second.name && first.address == second.address && first.poiId == second.poiId &&
            first.poiSubId == second.poiSubId && first.poiProvider == second.poiProvider

    private fun parsePublicDestinationInput(): JSONObject {
        val supplied = requireNotNull(probeArguments.getString("publicDestinationJson"))
        require(supplied.length <= 4_096)
        val input = JSONObject(supplied)
        val required = setOf("name", "latitude", "longitude", "address", "poiId", "poiSubId",
            "poiProvider", "displayLatitude", "displayLongitude")
        require(input.keys().asSequence().toSet() == required)
        for (key in listOf("latitude", "longitude")) require(input.get(key) is Number)
        for (key in listOf("displayLatitude", "displayLongitude")) {
            if (!input.isNull(key)) require(input.get(key) is Number)
        }
        return input
    }

    private fun connect(): Boolean {
        val current = generation.incrementAndGet()
        val connected = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val events = object : NaviHelperEventListener {
            private fun active() = generation.get() == current
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                if (!active()) return
                val pending = query.get()
                trace("onDestinationInfo", JSONObject().put("query", pending?.id ?: JSONObject.NULL).put("destination", destinationJson(destinationInfo)))
                if (pending != null) { pending.value = destinationInfo; pending.done.countDown() }
            }
            override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
                if (!active()) return
                val pending = bookmarkQuery.get()
                val entries = JSONArray()
                if (pending != null) bookmarkInfoList.filter { it.name in pending.publicNames }.forEach { entries.put(bookmarkJson(it)) }
                val filtered = JSONObject().put("query", pending?.id ?: JSONObject.NULL)
                    .put("callbackSource", "NaviHelperEventListener.onBookmarkInfo")
                    .put("entryCount", bookmarkInfoList.size).put("allowlistedEntryCount", entries.length())
                    .put("omittedEntryCount", bookmarkInfoList.size - entries.length())
                    .put("entries", entries).put("exactPublicNameFilterApplied", true)
                trace("onBookmarkInfo", filtered)
                if (pending != null) { pending.value = filtered; pending.done.countDown() }
            }
            override fun onTripInfo(tripInfo: TripInfo) {
                if (!active()) return
                val pending = tripQuery.get()
                val points = tripInfo.summary.routePoints
                val entries = JSONArray()
                if (pending != null) points.filter { it.poiName == pending.publicName }.forEach { point ->
                    entries.put(JSONObject().put("latitude", point.latitude).put("longitude", point.longitude)
                        .put("poiName", point.poiName).put("poiId", point.poiId).put("pointType", point.pointType.name)
                        .put("poiType", point.poiType.name).put("settingType", point.settingType.name).put("ratioOnRoute", point.ratioOnRoute))
                }
                val filtered = JSONObject().put("query", pending?.id ?: JSONObject.NULL)
                    .put("callbackSource", "NaviHelperEventListener.onTripInfo").put("routePointCount", points.size)
                    .put("allowlistedPointCount", entries.length()).put("omittedPointCount", points.size - entries.length())
                    .put("routePoints", entries).put("exactPublicNameFilterApplied", true)
                trace("onTripInfo", filtered)
                if (pending != null) { pending.value = filtered; signals.offer("tripInfo") }
            }
            override fun onDestinationChanged() { if (active()) { trace("onDestinationChanged"); signals.offer("destinationChanged") } }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) { if (active()) { nativeRouteStartCallbackCount.incrementAndGet(); if (routeRequested.get()) routeStartedSeen.set(true); trace("onRouteStarted") } }
            override fun onRoutingCompleted() { if (active()) { if (routeRequested.get()) routingCompletedSeen.set(true); trace("onRoutingCompleted"); signals.offer("routingCompleted") } }
            override fun onRouteCancelled() { if (active()) { trace("onRouteCancelled"); signals.offer("routeCancelled_query_only") } }
            override fun onError(errorCode: NaviErrorCode) { if (active()) { trace("onError", JSONObject().put("code", errorCode.name)); if (routeRequested.get()) routeError.set("error:${errorCode.name}"); if (navigationScreenRequestCount.get() > 0) navigationScreenError.set(errorCode.name); if (tripRequestCount.get() > 0) tripError.set(errorCode.name); signals.offer("error:${errorCode.name}") } }
        }
        runOnMainSync {
            val helper = NaviHelper(targetContext.applicationContext)
            sdk = helper; listener = events
            helper.initialize(onConnected = {
                if (generation.get() == current) {
                    helper.addListener(events); connected.set(true); trace("connected"); ready.countDown()
                }
            }, onFailed = {
                if (generation.get() == current) { trace("connectionFailed", JSONObject().put("failureType", it.javaClass.simpleName)); ready.countDown() }
            })
        }
        return ready.await(15, TimeUnit.SECONDS) && connected.get()
    }

    private fun read(timeoutMs: Long = 8_000): DestinationInfo? {
        val pending = Query(++queryId)
        check(query.compareAndSet(null, pending))
        trace("getDestinationInfo", JSONObject().put("query", pending.id))
        try {
            runOnMainSync { checkNotNull(sdk).getDestinationInfo() }
            if (!pending.done.await(timeoutMs, TimeUnit.MILLISECONDS)) { trace("queryTimeout", JSONObject().put("query", pending.id)); return null }
            return pending.value
        } finally { query.compareAndSet(pending, null) }
    }

    private fun releaseSdk() {
        generation.incrementAndGet()
        query.set(null)
        bookmarkQuery.set(null)
        tripQuery.set(null)
        val helper = sdk; val events = listener
        sdk = null; listener = null
        if (helper != null) runCatching { runOnMainSync { if (events != null) helper.removeListener(events); helper.release() } }
    }

    private fun trace(name: String, data: JSONObject = JSONObject()) {
        data.put("event", name).put("at", System.currentTimeMillis()).put("session", generation.get())
        synchronized(events) { if (events.size < 128) events.add(data) }
    }

    private fun destinationJson(value: DestinationInfo) = JSONObject()
        .put("latitude", value.latitude).put("longitude", value.longitude)
        .put("displayLatitude", value.displayLatitude ?: JSONObject.NULL)
        .put("displayLongitude", value.displayLongitude ?: JSONObject.NULL)
        .put("name", value.name).put("address", value.address).put("poiId", value.poiId)
        .put("poiSubId", value.poiSubId ?: JSONObject.NULL).put("poiSubIdMissing", value.poiSubId.isNullOrBlank())
        .put("poiProvider", value.poiProvider)
        .put("remainDistance", value.remainDistance).put("remainTime", value.remainTime).put("tollFare", value.tollFare)

    private fun bookmarkJson(value: BookmarkInfo) = JSONObject()
        .put("name", value.name).put("latitude", value.latitude).put("longitude", value.longitude)
        .put("address", value.address)
        .put("displayLatitude", value.displayLatitude ?: JSONObject.NULL)
        .put("displayLongitude", value.displayLongitude ?: JSONObject.NULL)
        .put("poiId", value.poiId).put("poiSubId", value.poiSubId ?: JSONObject.NULL).put("poiProvider", value.poiProvider)
}
