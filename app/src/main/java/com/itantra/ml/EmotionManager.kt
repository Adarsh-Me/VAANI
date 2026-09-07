package com.itantra.ml

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.itantra.models.AudioSpec
import com.itantra.models.Emotion
import com.itantra.models.EmotionResult
import com.itantra.models.ProsodyData
import com.itantra.models.StorageLayout
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * emotion2vec classifier with a signal-heuristic fallback.
 * Fallback is deliberately conservative: it only fires DISTRESS on loud +
 * fast + low-pause speech, otherwise NEUTRAL with modest confidence.
 */
class EmotionManager(private val context: Context, private val threads: Int = 2) {

    private var session: ai.onnxruntime.OrtSession? = null
    private val lock = ReentrantLock()
    val isModelLoaded: Boolean get() = session != null

    fun initialize(): Boolean {
        session = OnnxLoader.load(context, StorageLayout.EMOTION_MODEL, threads)
        return isModelLoaded
    }

    fun classify(audio: FloatArray, prosody: ProsodyData? = null): EmotionResult {
        val s = session
        if (s != null) {
            lock.withLock {
                try {
                    return runModel(s, audio)
                } catch (e: Exception) {
                    // Fall through to heuristic.
                }
            }
        }
        return heuristic(audio, prosody)
    }

    private fun runModel(s: ai.onnxruntime.OrtSession, audio: FloatArray): EmotionResult {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        // 3 s window @16 kHz, peak-normalized, padded/truncated.
        val window = FloatArray(3 * AudioSpec.SAMPLE_RATE)
        val n = minOf(audio.size, window.size)
        var peak = 1e-6f
        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(audio[i]))
        for (i in 0 until n) window[i] = (audio[i] / peak).coerceIn(-1f, 1f)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, window.size.toLong())).use { input ->
            s.run(mapOf("input" to input)).use { out ->
                val logits = (out.get("output").get() as OnnxTensor).floatBuffer
                val raw = FloatArray(5) { logits.get(it) }
                val probs = softmax(raw)
                var best = 0
                for (i in 1 until probs.size) if (probs[i] > probs[best]) best = i
                val emo = Emotion.fromId(best)
                return EmotionResult(emo, probs[best].coerceIn(0f, 1f))
            }
        }
    }

    private fun heuristic(audio: FloatArray, prosody: ProsodyData?): EmotionResult {
        val p = prosody ?: ProsodyExtractor.extract(audio)
        var sum = 0f
        for (v in audio) sum += v * v
        val rms = sqrt(sum / maxOf(1, audio.size))
        return when {
            p.energy > 0.55f && p.speakingRate > 0.6f && p.pauseRatio < 0.25f ->
                EmotionResult(Emotion.DISTRESS, 0.6f)
            rms > 0.35f && p.pitchRange > 0.5f ->
                EmotionResult(Emotion.ANGRY, 0.55f)
            p.pauseRatio > 0.5f && p.energy < 0.2f ->
                EmotionResult(Emotion.CALM, 0.55f)
            p.speakingRate > 0.65f && p.pitchMean > 0.55f ->
                EmotionResult(Emotion.HAPPY, 0.5f)
            else -> EmotionResult(Emotion.NEUTRAL, 0.5f)
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val m = logits.max()
        var sum = 0f
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            out[i] = exp((logits[i] - m).toDouble()).toFloat()
            sum += out[i]
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    fun close() {
        lock.withLock {
            runCatching { session?.close() }
            session = null
        }
    }
}
