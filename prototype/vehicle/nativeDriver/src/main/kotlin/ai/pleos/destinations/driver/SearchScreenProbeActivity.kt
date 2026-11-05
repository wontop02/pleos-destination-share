package ai.pleos.destinations.driver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Test-only foreground search entry point. It never requests or cancels a route. */
class SearchScreenProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val events = JSONArray()
    private val samples = JSONArray()
    private val report = JSONObject().put("sdk", "ai.pleos.playground:NaviHelper:3.0.5")
        .put("mode", "foreground-activity-search").put("routeRequestCount", 0)
        .put("navigationScreenRequestCount", 0).put("screenOpenedObserved", JSONObject.NULL)
        .put("observationCompleted", false).put("sdkDisposed", false)
    private var sdk: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var generation = 0
    private var connected = false
    private var resumed = false
    private var clicked = false
    private var screenRequested = false
    private var finished = false
    private var keyword = ""
    private var initial: DestinationInfo? = null
    private var unchanged = true
    private var observationEnd = 0L
    private var queryId = 0
    private var pending: Query? = null
    private lateinit var search: Button
    private lateinit var status: TextView
    private class Query(val id: Int, val label: String, val receive: (DestinationInfo) -> Unit) {
        lateinit var timeout: Runnable
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report.put("startedAt", System.currentTimeMillis()).put("targetPackage", packageName)
            .put("processId", android.os.Process.myPid()).put("sdkContext", "SearchScreenProbeActivity")
        keyword = intent.getStringExtra("keyword") ?: ""
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        layout.addView(TextView(this).apply { textSize = 24f; text = "순정 검색 전경 진단" })
        layout.addView(TextView(this).apply { textSize = 20f; text = "키워드: " + keyword })
        status = TextView(this).apply { textSize = 20f; text = "순정 내비 연결 중" }
        layout.addView(status)
        search = Button(this).apply {
            textSize = 20f; text = "순정 장소 검색"; minHeight = dp(56); isEnabled = false
            setOnClickListener { searchFromClick() }
        }
        layout.addView(search)
        layout.addView(Button(this).apply {
            textSize = 20f; text = "진단 중지 · 닫기"; minHeight = dp(56)
            setOnClickListener { finishProbe("CANCELLED", "operator_stopped", false); finish() }
        })
        setContentView(layout)
        if (keyword.isBlank() || keyword.length > 120 || keyword.any(Char::isISOControl)) {
            finishProbe("FAILED", "invalid_keyword", false)
            return
        }
        report.put("keyword", keyword)
        connect()
    }

    override fun onResume() { super.onResume(); resumed = true; trace("activityResumed") }
    override fun onPause() { resumed = false; trace("activityPaused"); super.onPause() }
    override fun onStop() {
        trace(if (clicked && !finished) "activityStopped_bounded_native_ui_observation_continues" else "activityStopped")
        if (!clicked && !finished) finishProbe("CANCELLED", "activity_stopped_before_click", false)
        super.onStop()
    }
    override fun onDestroy() {
        if (!finished) finishProbe("CANCELLED", "activity_destroyed_before_completion", false)
        else trace("activityDestroyed_after_disposal")
        super.onDestroy()
    }

    private fun connect() {
        report.put("phase", "connecting")
        val epoch = ++generation
        // NaviHelper's public constructor accepts Android Context. This is the visible Activity.
        val helper = NaviHelper(this)
        sdk = helper
        fun active() = !finished && generation == epoch && sdk === helper
        val callbacks = object : NaviHelperEventListener {
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                main.post {
                    if (!active()) return@post
                    val query = pending
                    trace("onDestinationInfo", JSONObject().put("query", query?.id ?: JSONObject.NULL)
                        .put("destination", destinationJson(destinationInfo)))
                    if (query != null) {
                        pending = null; main.removeCallbacks(query.timeout); query.receive(destinationInfo)
                    }
                }
            }
            override fun onDestinationChanged() { main.post { if (active()) trace("onDestinationChanged") } }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                main.post { if (active()) {
                    report.put("nativeRouteStartCallbackCount", report.optInt("nativeRouteStartCallbackCount") + 1)
                    trace("onRouteStarted")
                } }
            }
            override fun onRoutingCompleted() { main.post { if (active()) trace("onRoutingCompleted") } }
            override fun onRouteCancelled() { main.post { if (active()) trace("onRouteCancelled") } }
            override fun onError(errorCode: NaviErrorCode) {
                main.post { if (active()) {
                    report.put("lastSdkErrorCode", errorCode.name)
                    trace("onError", JSONObject().put("code", errorCode.name))
                } }
            }
        }
        listener = callbacks
        try {
            helper.initialize(onConnected = {
                main.post {
                    if (!active()) return@post
                    try {
                        helper.addListener(callbacks); connected = true
                        report.put("phase", "ready")
                        status.text = "연결됨 · 버튼을 눌러 검색"
                        search.isEnabled = true
                        trace("connected")
                    } catch (error: Exception) { fail("listener_registration_failed", error) }
                }
            }, onFailed = { error ->
                main.post { if (active()) fail("sdk_connection_failed", error) }
            })
            main.postDelayed({
                if (active() && !connected) finishProbe("BLOCKED", "sdk_connection_timeout_15s", false)
            }, 15_000)
            persist()
        } catch (error: Exception) { fail("sdk_initialize_exception", error) }
    }

    private fun searchFromClick() {
        if (finished || clicked || !connected || !resumed) return
        clicked = true; search.isEnabled = false
        report.put("clickAt", System.currentTimeMillis()).put("phase", "pre_search_query")
        status.text = "호출 전 실제 목적지 조회 중"
        trace("userSearchButtonClick")
        read("before_search") { before ->
            initial = before; samples.put(destinationJson(before))
            report.put("initialDestination", destinationJson(before))
            if (!resumed) { finishProbe("BLOCKED", "activity_not_resumed_before_screen_call", false); return@read }
            report.put("phase", "screen_call").put("activityResumedAtScreenCall", true)
                .put("callOrigin", "user_button_click_after_live_pre_query")
                .put("navigationScreenRequestCount", 1)
            screenRequested = true
            trace("requestNavigationScreen_SearchPlace")
            try {
                checkNotNull(sdk).requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
                report.put("navigationScreenRequestReturned", true).put("phase", "observing")
                    .put("observationDurationMs", 20_000).put("observationStartedAt", System.currentTimeMillis())
                observationEnd = SystemClock.elapsedRealtime() + 20_000
                status.text = "검색 요청 반환 · 20초 실제 목적지 관측 중"
                persist(); scheduleSample()
            } catch (error: Exception) { fail("screen_request_exception", error) }
        }
    }

    private fun scheduleSample() {
        if (finished) return
        val delay = (observationEnd - SystemClock.elapsedRealtime()).coerceIn(1, 2_000)
        main.postDelayed({
            if (finished) return@postDelayed
            read("after_search") { actual ->
                samples.put(destinationJson(actual))
                unchanged = unchanged && sameDestination(checkNotNull(initial), actual)
                report.put("destinationObservations", samples).put("lastPostRequestDestination", destinationJson(actual))
                persist()
                if (SystemClock.elapsedRealtime() >= observationEnd) {
                    val error = report.optString("lastSdkErrorCode")
                    finishProbe(if (error.isEmpty()) "OBSERVED" else "FAILED",
                        if (error.isEmpty()) "foreground_screen_request_and_native_destination_samples_observed" else "sdk_error:" + error, true)
                } else scheduleSample()
            }
        }, delay)
    }

    private fun read(label: String, receive: (DestinationInfo) -> Unit) {
        if (finished) return
        check(pending == null)
        val query = Query(++queryId, label, receive)
        pending = query
        query.timeout = Runnable {
            if (!finished && pending === query) {
                pending = null
                report.put("allObservationQueriesAnswered", false)
                trace("queryTimeout", JSONObject().put("query", query.id).put("label", label))
                finishProbe("BLOCKED", label + "_query_timeout_8s", false)
            }
        }
        main.postDelayed(query.timeout, 8_000)
        trace("getDestinationInfo", JSONObject().put("query", query.id).put("label", label))
        try { checkNotNull(sdk).getDestinationInfo() }
        catch (error: Exception) { fail(label + "_query_exception", error) }
    }

    private fun fail(reason: String, error: Throwable) {
        report.put("failureType", error.javaClass.simpleName)
        finishProbe("FAILED", reason, false)
    }

    private fun finishProbe(result: String, reason: String, complete: Boolean) {
        if (finished) return
        finished = true; connected = false; generation += 1
        main.removeCallbacksAndMessages(null); pending = null
        search.isEnabled = false
        report.put("status", result).put("reason", reason).put("phase", "disposing")
            .put("observationCompleted", complete).put("destinationObservations", samples)
            .put("destinationIdentityUnchanged", if (complete || !unchanged) unchanged else JSONObject.NULL)
            .put("allObservationQueriesAnswered", complete).put("screenRequestIssued", screenRequested)
        val helper = sdk; val callbacks = listener
        sdk = null; listener = null
        val failures = JSONArray()
        if (helper != null) {
            if (callbacks != null) try { helper.removeListener(callbacks) } catch (error: Exception) { failures.put("removeListener:" + error.javaClass.simpleName) }
            try { helper.release(); report.put("sdkDisposed", true) } catch (error: Exception) { failures.put("release:" + error.javaClass.simpleName) }
        } else report.put("sdkDisposed", true)
        report.put("cleanupFailures", failures).put("finishedAt", System.currentTimeMillis()).put("phase", "finished")
        status.text = result + " · " + reason
        trace("probeFinished")
    }

    private fun trace(name: String, detail: JSONObject = JSONObject()) {
        if (events.length() < 128) events.put(detail.put("event", name).put("at", System.currentTimeMillis()).put("session", generation))
        persist()
    }

    private fun persist() {
        report.put("events", events)
        val file = AtomicFile(File(noBackupFilesDir, "search-screen-activity.json"))
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite(); output.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(output)
        } catch (_: Exception) { if (output != null) file.failWrite(output) }
    }

    private fun sameDestination(first: DestinationInfo, second: DestinationInfo) =
        first.latitude == second.latitude && first.longitude == second.longitude &&
            first.displayLatitude == second.displayLatitude && first.displayLongitude == second.displayLongitude &&
            first.name == second.name && first.address == second.address && first.poiId == second.poiId &&
            first.poiSubId == second.poiSubId && first.poiProvider == second.poiProvider

    private fun destinationJson(value: DestinationInfo) = JSONObject()
        .put("latitude", value.latitude).put("longitude", value.longitude)
        .put("displayLatitude", value.displayLatitude ?: JSONObject.NULL).put("displayLongitude", value.displayLongitude ?: JSONObject.NULL)
        .put("name", value.name).put("address", value.address).put("poiId", value.poiId)
        .put("poiSubId", value.poiSubId ?: JSONObject.NULL).put("poiProvider", value.poiProvider)
        .put("remainDistance", value.remainDistance).put("remainTime", value.remainTime).put("tollFare", value.tollFare)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
