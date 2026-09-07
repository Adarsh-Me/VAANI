package com.itantra.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.itantra.models.AudioSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture: 16 kHz mono PCM-float, 512-sample (32 ms) chunks.
 * Backpressure: drops oldest beyond ~2 s (BufferOverflow.DROP_OLDEST).
 */
class AudioCaptureEngine(private val context: Context) {

    private var recorder: AudioRecord? = null
    private val capturing = AtomicBoolean(false)

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    val errors = MutableSharedFlow<Throwable>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @SuppressLint("MissingPermission")
    fun startCapture(): Flow<FloatArray> = flow {
        if (!capturing.compareAndSet(false, true)) {
            throw IllegalStateException("stopCapture() must be called before startCapture()")
        }
        val minBuf = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferSize = maxOf(minBuf, AudioSpec.CHUNK_SIZE * 4 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize
        )
        recorder = rec
        try {
            rec.startRecording()
            _isCapturing.value = true
            val chunk = FloatArray(AudioSpec.CHUNK_SIZE)
            while (capturing.get()) {
                val read = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    errors.tryEmit(RuntimeException("AudioRecord read error: $read"))
                    break
                }
                if (read > 0) emit(chunk.copyOf(read))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            recorder = null
            capturing.set(false)
            _isCapturing.value = false
        }
    }.flowOn(Dispatchers.IO)

    fun stopCapture() {
        capturing.set(false)
    }
}
