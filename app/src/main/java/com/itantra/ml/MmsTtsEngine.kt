package com.itantra.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.itantra.models.AudioSpec
import com.itantra.models.StorageLayout
import com.itantra.models.SynthesisResult
import com.itantra.models.TtsSettings
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * MMS-TTS voice (Meta MMS, ONNX single-file VITS) for Tamil + Punjabi.
 * willwade/mms-tts-multilingual-models-onnx: tam/model.onnx + pan/model.onnx
 * (opset 13, ~114 MB each). Signature: x[N,L] int64 ids, x_length[N] int64,
 * noise_scale/length_scale/noise_scale_w scalars -> y[N,1,T] float waveform
 * at 16 kHz. tokens.txt is "char<space>id" per line.
 */
class MmsTtsEngine(private val context: Context, private val threads: Int = 2) {

    companion object {
        const val TAG = "MmsTts"
        const val SAMPLE_RATE = 16000
        /** CC-BY-NC-4.0 (research/demo). Commercial use needs Meta's terms. */
        const val LICENSE = "CC-BY-NC-4.0"
        /**
         * MMS checkpoints RACE: the duration predictor emits ~2.4x-short
         * durations at nominal speed (measured host-side: a 9-word Tamil
         * sentence synthesized in 1.42 s — unintelligible). Multiply the
         * prosody lengthScale so neutral speech lands at a natural pace
         * (~3 s for that sentence at 2.3x).
         */
        const val MMS_SLOWDOWN = 2.3f
    }

    private var session: OrtSession? = null
    private var vocab: Map<String, Int>? = null
    private var loadedLang: String? = null
    val isLoaded: Boolean get() = session != null && vocab != null
    val currentLang: String? get() = loadedLang

    fun load(lang: String): Boolean {
        if (isLoaded && loadedLang == lang) return true
        close()
        val dir = ModelPaths(context).file(StorageLayout.mmsDir(lang))
        val model = File(dir, "model.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!model.exists() || model.length() < 10_000_000 || !tokens.exists()) {
            return false
        }
        return try {
            val map = HashMap<String, Int>()
            tokens.readLines().forEach { line ->
                val idx = line.lastIndexOf(' ')
                if (idx > 0) {
                    val ch = line.substring(0, idx)
                    line.substring(idx + 1).trim().toIntOrNull()?.let { map[ch] = it }
                }
            }
            if (map.isEmpty()) return false
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(model.absolutePath, opts)
            vocab = map
            loadedLang = lang
            Log.i(TAG, "MMS voice loaded for $lang (${model.length()} bytes, ${map.size} tokens)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MMS load failed for $lang: ${e.message}")
            close()
            false
        }
    }

    suspend fun synthesize(
        text: String, settings: TtsSettings, t0: Long
    ): SynthesisResult? {
        val s = session ?: return null
        val v = vocab ?: return null
        if (text.isBlank()) return null
        return try {
            // Char tokenize; unknown chars are dropped (MMS vocabs are script-complete).
            val ids = text.mapNotNull { v[it.toString()] }.take(240).toIntArray()
            if (ids.isEmpty()) {
                Log.w(TAG, "no MMS tokens for text")
                return null
            }
            val env = OrtEnvironment.getEnvironment()
            // MMS slowdown + prosody speed: lengthScale high = slower speech.
            val effectiveLengthScale = (settings.lengthScale * MMS_SLOWDOWN)
                .coerceIn(0.8f, 3.2f)
            val speed = 1f / effectiveLengthScale
            OnnxTensor.createTensor(
                env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()),
                longArrayOf(1, ids.size.toLong())
            ).use { x ->
                OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)
                ).use { xLen ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667f)), longArrayOf(1)).use { ns ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { ls ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.8f)), longArrayOf(1)).use { nsw ->
                                s.run(
                                    mapOf(
                                        "x" to x, "x_length" to xLen, "noise_scale" to ns,
                                        "length_scale" to ls, "noise_scale_w" to nsw
                                    )
                                ).use { out ->
                                    val y = out.get("y").get() as OnnxTensor
                                    val fb = y.floatBuffer
                                    val pcm = FloatArray(fb.remaining()) { fb.get() }
                                    if (pcm.isEmpty()) return null
                                    // Peak-normalize to 0.9: MMS output sits ~0.5
                                    // peak; consistent loudness across voices.
                                    var peak = 0f
                                    for (v in pcm) {
                                        val a = kotlin.math.abs(v)
                                        if (a > peak) peak = a
                                    }
                                    if (peak > 1e-4f) {
                                        val gain = (0.9f / peak).coerceIn(0.5f, 4f)
                                        for (i in pcm.indices) {
                                            pcm[i] = (pcm[i] * gain).coerceIn(-1f, 1f)
                                        }
                                    }
                                    SynthesisResult(
                                        audioData = pcm,
                                        sampleRate = SAMPLE_RATE,
                                        durationMs = pcm.size * 1000L / SAMPLE_RATE,
                                        inferenceTimeMs = System.currentTimeMillis() - t0,
                                        engine = "mms-$loadedLang"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MMS synthesis failed: ${e.message}")
            null
        }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        vocab = null
        loadedLang = null
    }
}
