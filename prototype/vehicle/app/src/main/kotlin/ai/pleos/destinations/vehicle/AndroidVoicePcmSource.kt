package ai.pleos.destinations.vehicle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** App-owned microphone; stop/release is independent of the speech engine. */
internal class AndroidVoicePcmSource(private val context: Context) : VoicePcmSource {
    private val closed = AtomicBoolean()
    private val record: AudioRecord
    init {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 6_400)).build()
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("microphone_uninitialized") }
    }
    override fun start(): Boolean {
        if (closed.get()) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
        record.startRecording()
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }
    override fun read(samples: ShortArray): Int = if (closed.get()) AudioRecord.ERROR_INVALID_OPERATION
        else record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { record.stop() }; runCatching { record.release() }
    }
}
