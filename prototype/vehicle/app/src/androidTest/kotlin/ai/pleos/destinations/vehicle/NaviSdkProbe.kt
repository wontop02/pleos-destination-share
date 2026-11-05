package ai.pleos.destinations.vehicle

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
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

/** Public-SDK-only backend probe. It never starts an Activity or reads/operates UI. */
class NaviSdkProbe : Instrumentation() {
    private var mode = "query"
    private var probeArguments = Bundle()
    private val routeRequested = AtomicBoolean(false)
    private val routeStartedSeen = AtomicBoolean(false)
    private val routingCompletedSeen = AtomicBoolean(false)
    private val routeError = AtomicReference<String?>()
    private val generation = AtomicInteger()
    private val events = mutableListOf<JSONObject>()
    private val report = JSONObject().put("sdk", "ai.pleos.playground:NaviHelper:3.0.5")
    private var sdk: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private val query = AtomicReference<Query?>()
    private val signals = LinkedBlockingQueue<String>()
    private var queryId = 0
    private class Query(val id: Int) {
        val done = CountDownLatch(1)
        @Volatile var value: DestinationInfo? = null
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probeArguments = arguments ?: Bundle()
        mode = arguments?.getString("mode") ?: "query"
        start()
    }

    override fun onStart() {
        var status = "BLOCKED"
        try {
            require(mode in listOf("query", "route", "route-empty-subid", "route-dream-center"))
            report.put("mode", mode).put("startedAt", System.currentTimeMillis())
            if (!connect()) report.put("reason", "initial_service_connection_failed_or_timed_out")
            else {
                val initial = read()
                report.put("initialDestination", initial?.let(::destinationJson) ?: JSONObject.NULL)
                if (mode == "query") {
                    status = if (initial == null) "BLOCKED" else "OBSERVED"
                    report.put("reason", if (initial == null) "destination_query_unanswered_not_assumed_absent" else "actual_destination_callback_received")
                } else {
                    releaseSdk() // Late initial-query callbacks cannot become post-request evidence.
                    signals.clear()
                    if (!connect()) report.put("reason", "route_session_connection_failed_or_timed_out")
                    else {
                        val experiment = mode == "route-empty-subid"
                        val dreamCenter = mode == "route-dream-center"
                        // Public waypoint-settings example, source SHA recorded in evidence/sdk-api.md.
                        val name = if (dreamCenter) "소프트웨어드림센터" else "42dot"
                        val poiId = if (dreamCenter) "36khp2hdj2cfcm5nqhe3" else "36khp37hh8gqcm5nqhe5"
                        val latitude = if (experiment) requireNotNull(probeArguments.getString("sourceLat")).toDouble() else if (dreamCenter) 37.41275833 else 37.48544722
                        val longitude = if (experiment) requireNotNull(probeArguments.getString("sourceLng")).toDouble() else if (dreamCenter) 127.09481944 else 127.03636666
                        val address = if (experiment) requireNotNull(probeArguments.getString("sourceAddress")) else if (dreamCenter) "경기도 성남시 수정구 창업로40번길 6" else "서울시 강남구 남부순환로 2621"
                        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
                        val expectedSubId: String? = if (experiment) null else "0"
                        val requestSubId = if (experiment) "" else "0"
                        report.put("experimentalNullSubIdToEmptyString", experiment)
                            .put("sourceExpected", JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude)
                                .put("poiId", poiId).put("poiSubId", expectedSubId ?: JSONObject.NULL).put("poiProvider", "").put("address", address))
                            .put("requestPoiSubId", requestSubId)
                        val route = RouteInfo(latitude = latitude, longitude = longitude, poiName = name,
                            poiId = poiId, poiSubId = requestSubId, address = address)
                        signals.clear()
                        routeRequested.set(true)
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
        } catch (error: Exception) {
            status = "FAILED"
            report.put("reason", "probe_exception").put("failureType", error.javaClass.simpleName)
        } finally {
            releaseSdk()
            report.put("status", status).put("finishedAt", System.currentTimeMillis())
                .put("routeStartedObserved", routeStartedSeen.get()).put("routingCompletedObserved", routingCompletedSeen.get())
            synchronized(events) { report.put("events", JSONArray(events)) }
            runCatching { File(targetContext.noBackupFilesDir, "sdk-probe.json").writeText(report.toString(2)) }
            val result = Bundle().apply { putString("probe_json", report.toString()) }
            finish(if (status == "VERIFIED" || status == "OBSERVED") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
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
            override fun onDestinationChanged() { if (active()) { trace("onDestinationChanged"); signals.offer("destinationChanged") } }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) { if (active()) { if (routeRequested.get()) routeStartedSeen.set(true); trace("onRouteStarted") } }
            override fun onRoutingCompleted() { if (active()) { if (routeRequested.get()) routingCompletedSeen.set(true); trace("onRoutingCompleted"); signals.offer("routingCompleted") } }
            override fun onRouteCancelled() { if (active()) { trace("onRouteCancelled"); if (routeRequested.get()) routeError.set("error:route_cancelled"); signals.offer("error:route_cancelled") } }
            override fun onError(errorCode: NaviErrorCode) { if (active()) { trace("onError", JSONObject().put("code", errorCode.name)); if (routeRequested.get()) routeError.set("error:${errorCode.name}"); signals.offer("error:${errorCode.name}") } }
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
}
