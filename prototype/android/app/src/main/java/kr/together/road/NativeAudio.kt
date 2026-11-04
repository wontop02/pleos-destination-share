package kr.together.road

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-only microphone: no microphone service or background permission. */
class MicCapture {
    private val stopped = AtomicBoolean(); private val discarded = AtomicBoolean()
    @Volatile private var recorder: AudioRecord? = null
    fun stop(discard: Boolean = false) { if (discard) discarded.set(true); stopped.set(true); try { recorder?.stop() } catch (_: Exception) {} }
    @SuppressLint("MissingPermission")
    fun run(started: () -> Unit): ByteArray {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val samples = ShortArray(240000); var count = 0
        try {
            if (stopped.get()) throw CancellationException()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "이 기기는 16kHz 마이크 녹음을 지원하지 않아요." }
            val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum, 8192)).build()
            recorder = record; check(record.state == AudioRecord.STATE_INITIALIZED) { "마이크를 초기화하지 못했어요." }
            if (stopped.get()) throw CancellationException()
            record.startRecording(); check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "마이크 녹음을 시작하지 못했어요." }
            val began = SystemClock.elapsedRealtime(); started(); timer.schedule({ stop() }, 15, TimeUnit.SECONDS)
            while (!stopped.get() && count < samples.size && SystemClock.elapsedRealtime() - began < 15000) {
                val read = record.read(samples, count, minOf(2048, samples.size - count), AudioRecord.READ_BLOCKING)
                if (read < 0) { if (stopped.get()) break; error("마이크 읽기가 중단됐어요. 다시 녹음해 주세요.") }
                if (read > 0) count += read
            }
            if (discarded.get()) throw CancellationException()
            return Wav.encode(samples, count)
        } finally {
            timer.shutdownNow(); try { recorder?.stop() } catch (_: Exception) {}; recorder?.release(); recorder = null; samples.fill(0)
        }
    }
}

/** Bounded static PCM playback, with focus requested immediately before actual sound. Main thread only. */
class NativePlayer(context: Context, private val interrupted: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var complete: ((Boolean) -> Unit)? = null
    private var timeout: Runnable? = null
    fun stop() = finish(false)
    private fun finish(success: Boolean) {
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        val previous = track; track = null
        previous?.setPlaybackPositionUpdateListener(null)
        try { previous?.stop() } catch (_: Exception) {}; previous?.release()
        focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
        val callback = complete; complete = null; callback?.invoke(success)
    }
    fun play(pcm: Pcm, done: (Boolean) -> Unit) {
        stop()
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
                if (focus === request && change != AudioManager.AUDIOFOCUS_GAIN) { finish(false); interrupted() }
            }, main).build()
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "다른 앱이 소리를 사용 중이거나 오디오 포커스가 거절됐어요. 직접 다시 눌러 주세요." }
        focus = request
        try {
            val audio = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.bytes.size).build()
            track = audio; check(audio.state != AudioTrack.STATE_UNINITIALIZED)
            check(audio.write(pcm.bytes, 0, pcm.bytes.size) == pcm.bytes.size) { "원음을 오디오 장치로 전달하지 못했어요." }
            complete = done
            audio.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { if (this@NativePlayer.track === track) finish(true) }
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }, main)
            check(audio.setNotificationMarkerPosition(pcm.sampleCount) == AudioTrack.SUCCESS)
            audio.play(); check(audio.playState == AudioTrack.PLAYSTATE_PLAYING)
            timeout = Runnable { if (track === audio) { val finished = audio.playbackHeadPosition >= pcm.sampleCount; finish(finished); if (!finished) interrupted() } }.also { main.postDelayed(it, pcm.sampleCount / 16L + 2000) }
        } catch (error: Exception) { finish(false); throw error }
    }
}
