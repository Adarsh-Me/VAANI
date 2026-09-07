package com.itantra.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import com.itantra.models.AudioSpec
import com.itantra.models.StorageLayout
import com.itantra.models.TranscriptionResult
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * IndicConformer (hybrid CTC, NeMo INT8 export) over ONNX Runtime.
 *
 * Model family (catalog: asr-<lang> + shared asr-tok):
 *   inputs : audio_signal [1, 80, T] — FbankExtractor 80-dim log-mel,
 *            mean-normalized; length [1] = num fbank frames.
 *   output : logprobs [1, T', 5633]; blank id 5632; tokens.txt lines are
 *            "token id" with ▁ = space.
 * Language mask is baked into each per-language graph, so no language-id
 * input exists (the old LANGUAGE_ID_MAP tensor path is gone).
 *
 * Voice input REQUIRES the downloaded model + tokens; without them
 * [transcribe] returns empty text and the UI surfaces a loud error.
 */
class ASRManager(private val context: Context, private val threads: Int = 4) {

    private var session: ai.onnxruntime.OrtSession? = null
    private var vocab: List<String>? = null
    private var loadedLang: String? = null
    private val lock = ReentrantLock()
    val isModelLoaded: Boolean get() = session != null && vocab != null
    val currentLang: String? get() = loadedLang

    companion object {
        const val BLANK_ID = 5632
        const val NUM_MEL = 80
        fun asrModelPath(lang: String) = "models/asr/$lang/model.int8.onnx"
        const val TOKENS_PATH = "models/asr/tokens.txt"
    }

    fun initialize(): Boolean = initialize("hi")

    fun initialize(lang: String): Boolean {
        if (isModelLoaded && loadedLang == lang) return true
        lock.withLock {
            closeLocked()
            session = OnnxLoader.load(context, asrModelPath(lang), threads)
            vocab = loadVocab()
            if (session == null || vocab == null) {
                closeLocked()
                return false
            }
            loadedLang = lang
            return true
        }
    }

    private fun closeLocked() {
        runCatching { session?.close() }
        session = null
        vocab = null
        loadedLang = null
    }

    private fun loadVocab(): List<String>? {
        val f = ModelPaths(context).file(TOKENS_PATH)
        if (!f.exists()) return null
        // tokens.txt format: "token id" per line; index by id.
        val maxId = 5633
        val out = arrayOfNulls<String>(maxId)
        runCatching {
            f.readLines().forEach { line ->
                val idx = line.lastIndexOf(' ')
                if (idx > 0) {
                    val tok = line.substring(0, idx)
                    line.substring(idx + 1).trim().toIntOrNull()?.let { id ->
                        if (id in 0 until maxId) out[id] = tok
                    }
                }
            }
        }
        return out.toList().map { it ?: "" }.ifEmpty { null }
    }

    fun transcribe(audio: FloatArray, language: String): TranscriptionResult {
        val t0 = System.currentTimeMillis()
        val s = session
        val v = vocab
        if (s == null || v == null) {
            return TranscriptionResult("", language, 0f, System.currentTimeMillis() - t0)
        }
        return lock.withLock {
            try {
                val feats = FbankExtractor.extract(audio)
                if (feats.isEmpty()) {
                    return@withLock TranscriptionResult("", language, 0f, System.currentTimeMillis() - t0)
                }
                val numFrames = feats.size
                // audio_signal [1, 80, T]: channel-major layout.
                val flat = FloatArray(numFrames * NUM_MEL)
                for (t in 0 until numFrames) {
                    for (m in 0 until NUM_MEL) {
                        flat[m * numFrames + t] = feats[t][m]
                    }
                }
                val env = OrtEnvironment.getEnvironment()
                OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(flat), longArrayOf(1, NUM_MEL.toLong(), numFrames.toLong())
                ).use { audioT ->
                    OnnxTensor.createTensor(
                        env, java.nio.LongBuffer.wrap(longArrayOf(numFrames.toLong())), longArrayOf(1)
                    ).use { lenT ->
                        s.run(mapOf("audio_signal" to audioT, "length" to lenT)).use { out ->
                            val lp = out.get(0) as OnnxTensor
                            val shape = lp.info.shape // [1, T', 5633]
                            val tt = shape[1].toInt()
                            val voc = shape[2].toInt()
                            val lb = lp.floatBuffer
                            val text = ctcDecodeGreedy(lb, tt, voc, v)
                            return@withLock TranscriptionResult(
                                text, language, if (text.isBlank()) 0f else 0.8f,
                                System.currentTimeMillis() - t0
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ASR", "transcribe failed: ${e.message}")
                TranscriptionResult("", language, 0f, System.currentTimeMillis() - t0)
            }
        }
    }

    /** CTC greedy: argmax per frame, collapse repeats, drop blanks, join. */
    internal fun ctcDecodeGreedy(
        logits: java.nio.FloatBuffer, frames: Int, vocabSize: Int, v: List<String>
    ): String {
        val ids = IntArray(frames)
        for (t in 0 until frames) {
            var best = 0
            var bestV = Float.NEGATIVE_INFINITY
            val base = t * vocabSize
            for (i in 0 until vocabSize) {
                val x = logits.get(base + i)
                if (x > bestV) {
                    bestV = x; best = i
                }
            }
            ids[t] = best
        }
        return collapseCtc(ids, BLANK_ID, v)
    }

    /** Pure CTC collapse — unit-testable without ONNX.
     *  ▁ -> space AFTER concatenation: ▁ prefixes live inside multi-char
     *  tokens (▁स) and must not survive into output. */
    internal fun collapseCtc(ids: IntArray, blankId: Int, v: List<String>): String {
        val sb = StringBuilder()
        var prev = -1
        for (id in ids) {
            if (id == blankId) continue
            if (id == prev) continue
            prev = id
            sb.append(v.getOrNull(id) ?: "")
        }
        return sb.toString().replace("▁", " ").trim().replace(Regex("\\s+"), " ")
    }

    fun close() {
        lock.withLock { closeLocked() }
    }
}
