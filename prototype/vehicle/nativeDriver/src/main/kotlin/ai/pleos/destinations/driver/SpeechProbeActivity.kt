package ai.pleos.destinations.driver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Test-only SDK surface. Microphone and supplied PCM are explicitly different inputs. */
class SpeechProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val events = JSONArray()
    private var sdk: SpeechToText? = null
    private var listener: ResultListener? = null
    private var generation = 0
    private var sdkGeneration = 0
    private data class Closing(val helper: SpeechToText, val listener: ResultListener?, val reason: String)
    private val closing = mutableMapOf<Int, Closing>()
    private var captureUsed = false
    private var ready = false
    private var running = false
    private lateinit var status: TextView
    private lateinit var recognized: TextView
    private lateinit var mic: Button
    private lateinit var pcm: Button
    private val report = JSONObject().put("sdk", "SpeechToText:3.3.1").put("mode", "ON_DEVICE")
        .put("routeRequestCount", 0).put("microphoneAudioPersisted", false).put("transcriptPersisted", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report.put("startedAt", System.currentTimeMillis()).put("pid", android.os.Process.myPid())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { textSize = 26f; text = "음성 SDK 진단 · ON_DEVICE" })
        status = TextView(this).apply { textSize = 20f }; root.addView(status)
        recognized = TextView(this).apply { textSize = 20f }; root.addView(recognized)
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 20f; minHeight = 84; setOnClickListener { action() }; root.addView(this)
        }
        mic = button("차량 마이크 인식") { begin(false) }
        pcm = button("진단 PCM 인식 · 사람 발화 아님") { begin(true) }
        button("입력 끝내기 · 결과 기다리기") {
            if (running) { trace("stopAwaitFinal"); runCatching { sdk?.stop() }; status.text = "입력 종료 · 결과 기다리는 중" }
        }
        button("중지") { dispose("operator_stop"); status.text = "중지됨" }
        button("다시 초기화") { initialize() }
        setContentView(root); initialize()
    }

    private fun trace(event: String, extra: JSONObject = JSONObject()) {
        extra.put("event", event).put("at", SystemClock.elapsedRealtime()); events.put(extra)
        while (events.length() > 128) events.remove(0)
        report.put("events", events).put("cleanupPending", closing.size)
        File(filesDir, "speech-probe.json").writeText(report.toString(2))
    }
    private fun initialize() {
        dispose("reinitialize"); val token = ++generation
        ready = false; running = false; captureUsed = false; recognized.text = ""; status.text = "초기화 중"; updateButtons()
        initializeWhenStopped(token)
    }
    private fun initializeWhenStopped(token: Int) {
        if (generation != token) return
        if (closing.isNotEmpty()) { main.postDelayed({ initializeWhenStopped(token) }, 50); return }
        try { connect(token) } catch (error: Exception) {
            status.text = "초기화 예외"; trace("exception", JSONObject().put("class", error.javaClass.simpleName))
        }
    }
    private fun connect(token: Int) {
        val helper = SpeechToText(applicationContext, Mode.ON_DEVICE); sdk = helper; sdkGeneration = token
        fun active() = generation == token && sdk === helper
        val callbacks = object : ResultListener {
            override fun onReady() { main.post { if (active()) trace("ready") } }
            override fun onStartedRecognition() { main.post { if (active()) trace("started") } }
            override fun onUpdated(stt: String, completed: Boolean) { main.post {
                if (!active()) return@post
                recognized.text = stt
                trace(if (completed) "final" else "partial", JSONObject().put("characters", stt.length))
                if (completed) { running = false; status.text = "확정 결과 수신"; updateButtons() }
            } }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) { main.post { if (active() && rms > 0 && !report.optBoolean("nonzeroRms")) { report.put("nonzeroRms", true); trace("nonzeroRms") } } }
            override fun onEndedRecognition() { main.post {
                if (closing.containsKey(token)) { releaseClosing(token, "endedBeforeRelease"); return@post }
                if (active()) {
                status.text = "인식 종료 · 결과 기다리는 중"; trace("ended")
                main.postDelayed({ if (active() && running) { running = false; status.text = "확정 결과 없음"; trace("noFinalAfterEnd"); updateButtons() } }, 2_000)
            } } }
            override fun onError(errorType: ErrorType) { main.post { if (active()) { running = false; status.text = errorType.name; trace("error", JSONObject().put("type", errorType.name)); updateButtons() } } }
        }
        listener = callbacks
        try {
            helper.initialize(object : OnInitializeListener {
                override fun onSuccess() { main.post {
                    if (!active()) return@post
                    helper.addListener(callbacks); helper.setLocale(Locale.KOREA)
                    ready = true; status.text = "초기화 성공"; trace("initializeSuccess"); updateButtons()
                } }
                override fun onFailure(errorType: ErrorType) { main.post {
                    if (!active()) return@post
                    status.text = "초기화 실패 · ${errorType.name}"; trace("initializeFailure", JSONObject().put("type", errorType.name)); updateButtons()
                } }
            })
            trace("initializeRequested")
        } catch (error: Exception) { status.text = "초기화 예외"; trace("exception", JSONObject().put("class", error.javaClass.simpleName)) }
        main.postDelayed({ if (active() && !ready) { status.text = "초기화 시간 초과"; trace("initializeTimeout"); dispose("initializeTimeout") } }, 10_000)
    }
    private fun begin(supplied: Boolean) {
        if (!ready || running || captureUsed) return
        val helper = sdk ?: return; val token = generation
        recognized.text = ""; running = true; captureUsed = true; report.put("input", if (supplied) "supplied_pcm_not_human_microphone" else "vehicle_microphone")
        report.put("nonzeroRms", false); status.text = "인식 중"; updateButtons()
        try {
            if (supplied) {
                val data = File(filesDir, "speech-fixture.pcm").readBytes()
                require(data.size in 3200..480000 && data.size % 2 == 0)
                report.put("suppliedPcmPrivateFile", "speech-fixture.pcm").put("suppliedPcmBytesOnDisk", data.size)
                trace("sendAudio", JSONObject().put("bytes", data.size)); helper.sendAudio(data); helper.completeAudioSend()
            } else { trace("requestMicrophone"); helper.request() }
        } catch (error: Exception) { running = false; status.text = "요청 실패"; trace("exception", JSONObject().put("class", error.javaClass.simpleName)); updateButtons() }
        main.postDelayed({ if (generation == token && running) { status.text = "인식 시간 초과"; dispose("recognitionTimeout") } }, 20_000)
    }
    private fun updateButtons() { mic.isEnabled = ready && !running && !captureUsed; pcm.isEnabled = ready && !running && !captureUsed }
    private fun dispose(reason: String) {
        generation += 1; ready = false; running = false
        val helper = sdk; sdk = null; val callbacks = listener; listener = null
        if (helper != null) {
            val token = sdkGeneration
            closing[token] = Closing(helper, callbacks, reason)
            trace("stopPending", JSONObject().put("reason", reason))
            runCatching { helper.stop() }
            main.postDelayed({ releaseClosing(token, "stopWaitElapsed") }, 3_000)
        }
        if (::mic.isInitialized) updateButtons()
    }
    private fun releaseClosing(token: Int, event: String) {
        val resource = closing.remove(token) ?: return
        if (resource.listener != null) runCatching { resource.helper.removeListener(resource.listener) }
        val released = runCatching { resource.helper.release() }.isSuccess
        trace(event, JSONObject().put("reason", resource.reason).put("released", released))
    }
    override fun onStop() { dispose("activityStopped"); if (::recognized.isInitialized) recognized.text = ""; super.onStop() }
}
