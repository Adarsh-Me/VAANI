package com.itantra.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.ml.ModelPaths
import com.itantra.models.AudioSpec
import com.itantra.models.StorageLayout
import com.itantra.models.VadSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Streaming VAD events. */
sealed interface VadEvent {
    data object SpeechStarted : VadEvent
    data class UtteranceReady(val audio: FloatArray) : VadEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UtteranceReady) return false
            return audio.contentEquals(other.audio)
        }
        override fun hashCode(): Int = audio.contentHashCode()
    }
}

/**
 * Silero VAD over ONNX Runtime with an energy-based fallback when the model
 * file has not been downloaded yet. Never throws from [processChunk].
 */
class VADManager(
    private val context: Context,
    private val threshold: Float = VadSpec.THRESHOLD,
    private val minSpeechMs: Int = VadSpec.MIN_SPEECH_DURATION_MS,
    private val minSilenceMs: Int = VadSpec.MIN_SILENCE_DURATION_MS
) {
    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    val isModelLoaded: Boolean get() = session != null

    // Streaming LSTM state for Silero ([2,1,64]) + trailing context (64 samples).
    private val lstmState = FloatArray(128)
    private var contextBuffer = FloatArray(64)

    /** Loads silero_vad.onnx. Returns false when absent -> energy fallback. */
    fun initialize(): Boolean {
        resetState()
        val file = ModelPaths(context).file(StorageLayout.VAD_MODEL)
        if (!file.exists()) return false
        return try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            session = env!!.createSession(file.absolutePath, opts)
            true
        } catch (e: Throwable) {
            // Native init (OrtEnvironment) can also throw — degrade to fallback.
            env = null
            session = null
            false
        }
    }

    fun resetState() {
        lstmState.fill(0f)
        contextBuffer = FloatArray(64)
    }

    /** Speech probability for one 512-sample chunk. */
    fun processChunk(chunk: FloatArray): Float {
        val s = session
        if (s == null || env == null) return energyProb(chunk)
        return try {
            runModel(s, env!!, chunk)
        } catch (e: Exception) {
            energyProb(chunk)
        }
    }

    private fun runModel(s: OrtSession, e: OrtEnvironment, chunk: FloatArray): Float {
        val padded = if (chunk.size == AudioSpec.CHUNK_SIZE) chunk
        else FloatArray(AudioSpec.CHUNK_SIZE) { chunk.getOrElse(it) { 0f } }

        OnnxTensor.createTensor(e, FloatBuffer.wrap(padded), longArrayOf(1, AudioSpec.CHUNK_SIZE.toLong())).use { input ->
            OnnxTensor.createTensor(e, FloatBuffer.wrap(lstmState.copyOf()), longArrayOf(2, 1, 64)).use { state ->
                OnnxTensor.createTensor(
                    e, LongBuffer.wrap(longArrayOf(AudioSpec.SAMPLE_RATE.toLong())), longArrayOf(1)
                ).use { sr ->
                    s.run(mapOf("input" to input, "state" to state, "sr" to sr)).use { out ->
                        val probTensor = out.get("output").get() as OnnxTensor
                        val prob = probTensor.floatBuffer.get(0)
                        val stateN = (out.get("stateN").get() as OnnxTensor).floatBuffer
                        for (i in 0 until 128) lstmState[i] = stateN.get(i)
                        contextBuffer = padded.copyOfRange(padded.size - 64, padded.size)
                        return prob.coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    /** Fallback: RMS energy mapped to a pseudo-probability. */
    private fun energyProb(chunk: FloatArray): Float {
        var sum = 0f
        for (v in chunk) sum += v * v
        val rms = sqrt(sum / chunk.size)
        // Speech-ish above ~0.02 RMS; saturate by 0.15.
        return ((rms - 0.015f) / 0.135f).coerceIn(0f, 1f)
    }

    /**
     * Sentence segmentation state machine: IDLE -> IN_SPEECH -> emit on
     * silence >= minSilenceMs with speech >= minSpeechMs.
     */
    fun segmentUtterance(chunks: Flow<FloatArray>): Flow<VadEvent> = flow {
        val speechBuf = ArrayList<Float>()
        var inSpeech = false
        var speechMs = 0
        var silenceMs = 0

        fun flush(): VadEvent? {
            if (speechMs >= minSpeechMs && speechBuf.isNotEmpty()) {
                val audio = speechBuf.toFloatArray()
                speechBuf.clear()
                inSpeech = false; speechMs = 0; silenceMs = 0
                return VadEvent.UtteranceReady(audio)
            }
            speechBuf.clear()
            inSpeech = false; speechMs = 0; silenceMs = 0
            return null
        }

        chunks.collect { chunk ->
            val p = processChunk(chunk)
            if (p > threshold) {
                if (!inSpeech) {
                    inSpeech = true
                    emit(VadEvent.SpeechStarted)
                }
                speechBuf.addAll(chunk.asList())
                speechMs += AudioSpec.CHUNK_MS
                silenceMs = 0
            } else if (inSpeech) {
                speechBuf.addAll(chunk.asList())
                silenceMs += AudioSpec.CHUNK_MS
                if (silenceMs >= minSilenceMs) {
                    flush()?.let { emit(it) }
                }
            }
        }
        // Stream end: emit trailing speech.
        if (inSpeech) flush()?.let { emit(it) }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
    }
}
