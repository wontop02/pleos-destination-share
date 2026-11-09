package ai.pleos.destinations.vehicle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/** Foreground app microphone → in-memory PCM → public ON_DEVICE speech engine. */
internal class PleosVoiceInputGateway(context: Context, private val trace: (String) -> Unit) : VoiceInputGateway {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: SpeechToText? = null
    private var callbacks: ResultListener? = null
    private var generation = 0L
    private var sdkGeneration = 0L
    private var capture: VoicePcmCapture? = null
    private var pendingPcm: ByteArray? = null
    private var submitted = false
    private val cleanup = VoiceInputCleanup({ SystemClock.elapsedRealtime() }, trace)
    override fun start(listener: VoiceInputGateway.Listener) {
        close(); val token = generation
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.failed(VoiceInputFailure.PERMISSION); return
        }
        beginWhenStopped(token, listener)
    }
    private fun beginWhenStopped(token: Long, listener: VoiceInputGateway.Listener) {
        if (token != generation) return
        if (cleanup.waiting) { main.postDelayed({ beginWhenStopped(token, listener) }, 50); return }
        try { initialize(token, listener) }
        catch (_: SecurityException) { if (token == generation) listener.failed(VoiceInputFailure.PERMISSION) }
        catch (_: Exception) { if (token == generation) listener.failed(VoiceInputFailure.ENGINE) }
    }
    private fun initialize(token: Long, listener: VoiceInputGateway.Listener) {
        val helper = SpeechToText(app, Mode.ON_DEVICE); sdk = helper
        sdkGeneration = token
        fun active() = token == generation && sdk === helper
        fun dispatch(action: () -> Unit) { main.post { if (active()) action() } }
        val results = object : ResultListener {
            override fun onReady() { dispatch { trace("sdkReady") } }
            override fun onStartedRecognition() { dispatch { trace("sdkStartedRecognition") } }
            override fun onUpdated(stt: String, completed: Boolean) { dispatch { if (submitted) listener.text(stt, completed) } }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() { main.post {
                if (cleanup.contains(token)) cleanup.ended(token)
                else if (active() && submitted) listener.ended()
            } }
            override fun onError(errorType: ErrorType) { dispatch { listener.failed(failure(errorType)) } }
        }
        callbacks = results
        helper.initialize(object : OnInitializeListener {
            override fun onSuccess() { dispatch {
                trace("sdkInitializedOnDevice")
                try {
                    helper.addListener(results); helper.setLocale(Locale.KOREA)
                    submitted = false
                    val recording = VoicePcmCapture({ AndroidVoicePcmSource(app) }, { SystemClock.elapsedRealtime() },
                        { action -> main.post(action) },
                        { if (active()) { trace("appMicrophoneRecording"); listener.listening() } },
                        { bytes ->
                            if (!active()) bytes.fill(0)
                            else {
                                capture = null; trace("appMicrophoneClosed"); listener.ended()
                                if (!active()) bytes.fill(0)
                                else try {
                                    pendingPcm = bytes; submitted = true
                                    helper.sendAudio(bytes); helper.completeAudioSend(); trace("sdkPcmCompleted")
                                } catch (_: Exception) { bytes.fill(0); listener.failed(VoiceInputFailure.ENGINE) }
                            }
                        }, { error -> if (active()) listener.failed(error) })
                    capture = recording; recording.start()
                } catch (_: SecurityException) { listener.failed(VoiceInputFailure.PERMISSION) }
                catch (_: Exception) { listener.failed(VoiceInputFailure.ENGINE) }
            } }
            override fun onFailure(errorType: ErrorType) { dispatch { listener.failed(failure(errorType)) } }
        })
    }
    override fun stop() { capture?.finish() }
    override fun close() {
        generation += 1
        capture?.cancel(); capture = null; pendingPcm?.fill(0); pendingPcm = null; submitted = false
        val helper = sdk; sdk = null; val listener = callbacks; callbacks = null
        if (helper != null) {
            cleanup.retire(sdkGeneration, { helper.stop() }) {
                if (listener != null) runCatching { helper.removeListener(listener) }
                if (runCatching { helper.release() }.isSuccess) trace("sdkReleased") else trace("sdkReleaseFailed")
            }
            main.postDelayed({ cleanup.tick() }, 3_000)
        }
    }
    private fun failure(type: ErrorType) = when (type) {
        ErrorType.PERMISSION_DENIED -> VoiceInputFailure.PERMISSION
        ErrorType.MIC_ERROR, ErrorType.AUDIO_ERROR -> VoiceInputFailure.MICROPHONE
        ErrorType.NOT_SUPPORTED_API, ErrorType.UNAVAILABLE_LANGUAGE -> VoiceInputFailure.UNSUPPORTED
        ErrorType.TIMEOUT -> VoiceInputFailure.TIMEOUT
        else -> VoiceInputFailure.ENGINE
    }
}
