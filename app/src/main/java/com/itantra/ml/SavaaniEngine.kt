package com.itantra.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.itantra.models.AudioSpec
import com.itantra.models.TranscriptionResult
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil

/**
 * SraVaani-1.0 ASR (ARTPARK-IISc) — FastConformer TDT transducer, single
 * multilingual model for all demo languages. ONNX export verified host-side:
 * encoder int8 [1,128,T] -> [1,1024,T'] (subsample 8); predict LSTM [1,1,640]
 * states; joint [1,1024,1]x[1,640,1] -> [1,1,5006] (vocab 5001 + 5 durations).
 *
 * Frontend: 128-dim log-mel from preproc.pt (window [400], fb [128,257],
 * n_fft 512, hop 160, preemph 0.97, mag_power 2, log + per-feature
 * mean-var normalize) — pre-baked matrix shipped in assets/savaani_frontend.json.
 *
 * Model files are BUNDLED in the APK at assets/savaani/ (offline-first
 * distribution) and seeded into filesDir on first launch (see BUNDLED_FILES):
 *   models/savaani/encoder_int8.onnx  476 MB
 *   models/savaani/predict.onnx        25 MB
 *   models/savaani/joint.onnx          17 MB
 *   models/savaani/tokens.txt         5000 SPM pieces
 *   assets/savaani_frontend.json      fbank matrix + params
 */
class SavaaniEngine(private val context: Context, private val threads: Int = 4) {

    private val lock = ReentrantLock()
    private var enc: OrtSession? = null
    private var pred: OrtSession? = null
    private var joint: OrtSession? = null
    private var vocab: List<String>? = null
    private var fb: Array<FloatArray>? = null     // [128][257]
    private var window: FloatArray? = null         // [400]
    private var loaded = false

    val isModelLoaded: Boolean get() = loaded

    companion object {
        const val DIR = "models/savaani"
        const val FRONTEND_ASSET = "savaani_frontend.json"
        const val SUBSAMPLE = 8
        const val NUM_MEL = 128
        const val SOS = 5000 // blank doubles as SOS

        /**
         * Weights BUNDLED in the APK at assets/savaani/ (v1.2+ distribution —
         * the app must work offline for users who received the shared APK; no
         * HF token / network needed for STT). Exact byte sizes for seed
         * validation; a partial copy (process killed mid-seed) fails the size
         * check and is re-extracted on the next launch.
         */
        const val ASSET_DIR = "savaani"
        val BUNDLED_FILES = mapOf(
            "encoder_int8.onnx" to 476_365_682L,
            "predict.onnx" to 25_931_447L,
            "joint.onnx" to 17_101_755L,
            "tokens.txt" to 73_896L
        )
    }

    fun initialize(): Boolean = initialize("hi")

    fun initialize(lang: String): Boolean {
        if (loaded) return true
        lock.withLock {
            closeLocked()
            seedFromAssetsLocked()
            val paths = File(context.filesDir, DIR)
            enc = loadSession(File(paths, "encoder_int8.onnx"))
            pred = loadSession(File(paths, "predict.onnx"))
            joint = loadSession(File(paths, "joint.onnx"))
            vocab = loadTokens(File(paths, "tokens.txt"))
            val fe = loadFrontend()
            fb = fe?.first
            window = fe?.second
            loaded = enc != null && pred != null && joint != null && vocab != null && fb != null
            if (!loaded) {
                val dir = File(context.filesDir, DIR)
                android.util.Log.w(
                    "Savaani",
                    "dir=${dir.absolutePath} exists=${dir.exists()} " +
                        "enc=${File(dir, "encoder_int8.onnx").exists()} " +
                        "pred=${File(dir, "predict.onnx").exists()} " +
                        "joint=${File(dir, "joint.onnx").exists()} " +
                        "tok=${File(dir, "tokens.txt").exists()} " +
                        "asset=${runCatching { context.assets.open(FRONTEND_ASSET).available() > 0 }.getOrDefault(false)}"
                )
            }
            android.util.Log.i(
                "Savaani",
                "init: enc=${enc != null} pred=${pred != null} joint=${joint != null} " +
                    "tok=${vocab != null} fbank=${fb != null} loaded=$loaded"
            )
            return loaded
        }
    }

    private fun loadSession(f: File): OrtSession? {
        if (!f.exists() || f.length() == 0L) return null
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        return OrtEnvironment.getEnvironment().createSession(f.absolutePath, opts)
    }

    /**
     * One-time stream-copy of the bundled weights from assets/savaani/ into
     * filesDir/models/savaani/. OrtSession needs a real file path, so assets
     * cannot be used in place. Skipped when files already match the expected
     * byte sizes (repeat launches cost 4 stat() calls). A size mismatch —
     * partial seed from a killed process, or leftover from an older install —
     * deletes and re-extracts the affected file.
     */
    private fun seedFromAssetsLocked() {
        val dir = File(context.filesDir, DIR)
        for ((name, expected) in BUNDLED_FILES) {
            val dst = File(dir, name)
            if (dst.exists() && dst.length() == expected) continue
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.delete()
                    val tmp = File(dir, "$name.seed")
                    java.io.FileOutputStream(tmp).use { input.copyTo(it, 1 shl 20) }
                    if (tmp.length() != expected) {
                        throw java.io.IOException(
                            "seed size mismatch: $name ${tmp.length()} != $expected"
                        )
                    }
                    if (dst.exists()) dst.delete()
                    if (!tmp.renameTo(dst)) throw java.io.IOException("seed rename failed: $name")
                }
            } catch (e: Exception) {
                android.util.Log.w("Savaani", "asset seed failed for $name: ${e.message}")
                // Keep any pre-existing file — it may still be usable if this
                // was a re-check with a transient asset read failure.
            }
        }
    }

    private fun loadTokens(f: File): List<String>? {
        if (!f.exists()) return null
        val max = SavaaniDecoder.VOCAB_SIZE + 1
        val out = arrayOfNulls<String>(max)
        runCatching {
            f.readLines().forEach { line ->
                val i = line.lastIndexOf(' ')
                if (i > 0) {
                    val piece = line.substring(0, i)
                    line.substring(i + 1).trim().toIntOrNull()?.let { id ->
                        if (id in 0 until max) out[id] = piece
                    }
                }
            }
        }
        val list = out.toList().map { it ?: "" }
        return if (list.any { it.isNotEmpty() }) list else null
    }

    /** fbank matrix from assets JSON: {"fb": [[257]x128], "window": [400], ...} */
    private fun loadFrontend(): Pair<Array<FloatArray>, FloatArray>? {
        return runCatching {
            val json = context.assets.open(FRONTEND_ASSET).bufferedReader().readText()
            val obj = org.json.JSONObject(json)
            val fbArr = obj.getJSONArray("fb")
            val fb = Array(fbArr.length()) { r ->
                val row = fbArr.getJSONArray(r)
                FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
            }
            val winArr = obj.getJSONArray("window")
            val win = FloatArray(winArr.length()) { i -> winArr.getDouble(i).toFloat() }
            fb to win
        }.getOrNull()
    }

    /** 128-dim log-mel: preemph -> hann window (preproc) -> FFT 512 -> mel(fb) -> log(guard) -> per-feature mean-var. */
    internal fun computeFeatures(audio: FloatArray): Array<FloatArray>? {
        val fbm = fb ?: return null
        val win = window ?: return null
        val frameLen = win.size          // 400
        val hop = 160                    // preproc hop_length
        val fftSize = 512                // preproc n_fft
        if (audio.size < frameLen) return null
        val numFrames = 1 + (audio.size - frameLen) / hop
        val bins = fftSize / 2 + 1       // 257
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val power = FloatArray(bins)
        val feats = Array(numFrames) { FloatArray(NUM_MEL) }
        for (f in 0 until numFrames) {
            val start = f * hop
            var prev = 0f
            for (i in 0 until frameLen) {
                val s = audio[start + i]
                re[i] = ((s - 0.97f * prev) * win[i])
                prev = s
                im[i] = 0f
            }
            for (i in frameLen until fftSize) { re[i] = 0f; im[i] = 0f }
            FbankExtractor.fft(re, im)
            for (k in 0 until bins) {
                val mag = re[k] * re[k] + im[k] * im[k]
                power[k] = mag * mag // mag_power = 2.0
            }
            for (m in 0 until NUM_MEL) {
                var e = 0f
                val filt = fbm[m]
                for (k in 0 until bins) {
                    if (filt[k] != 0f) e += filt[k] * power[k]
                }
                feats[f][m] = kotlin.math.ln(e + 5.960464477539063e-08f)
            }
        }
        // normalize: per_feature -> mean-var per channel over time
        for (m in 0 until NUM_MEL) {
            var sum = 0f
            for (f in 0 until numFrames) sum += feats[f][m]
            val mean = sum / numFrames
            var sq = 0f
            for (f in 0 until numFrames) {
                val d = feats[f][m] - mean
                sq += d * d
            }
            val std = kotlin.math.sqrt(sq / numFrames).coerceAtLeast(1e-10f)
            for (f in 0 until numFrames) feats[f][m] = (feats[f][m] - mean) / std
        }
        return feats
    }

    fun transcribe(audio: FloatArray, language: String): TranscriptionResult {
        val t0 = System.currentTimeMillis()
        val lockHeld = lock
        val encS = enc
        val predS = pred
        val jointS = joint
        val v = vocab
        if (!loaded || encS == null || predS == null || jointS == null || v == null) {
            return TranscriptionResult("", language, 0f, System.currentTimeMillis() - t0)
        }
        return lock.withLock {
            try {
                val feats = computeFeatures(audio) ?: return@withLock TranscriptionResult(
                    "", language, 0f, System.currentTimeMillis() - t0
                )
                val T = feats.size
                val flat = FloatArray(NUM_MEL * T)
                for (t in 0 until T) for (m in 0 until NUM_MEL) flat[m * T + t] = feats[t][m]
                val env = OrtEnvironment.getEnvironment()
                OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(flat), longArrayOf(1, NUM_MEL.toLong(), T.toLong())
                ).use { sig ->
                    OnnxTensor.createTensor(
                        env, LongBuffer.wrap(longArrayOf(T.toLong())), longArrayOf(1)
                    ).use { lenT ->
                        encS.run(mapOf("audio_signal" to sig, "length" to lenT)).use { outE ->
                            val encOut = (outE.get(0) as OnnxTensor)
                            val encLenT = (outE.get(1) as OnnxTensor)
                            val ebuf = encOut.floatBuffer
                            val T2 = (encOut.info.shape[2]).toInt()
                            val decoded = mutableListOf<Int>()
                            // prediction state
                            var h1 = FloatArray(1 * 1 * SavaaniDecoder.PRED_DIM)
                            var h2 = FloatArray(1 * 1 * SavaaniDecoder.PRED_DIM)
                            var tokens = intArrayOf(SavaaniDecoder.BLANK_ID)
                            var frame = 0
                            var symbols = 0
                            var steps = 0
                            val maxSteps = T2 * SavaaniDecoder.MAX_SYMBOLS_PER_FRAME
                            while (frame < T2 && steps < maxSteps) {
                                steps++
                                OnnxTensor.createTensor(
                                    env,
                                    LongBuffer.wrap(longArrayOf(tokens.last().toLong())),
                                    longArrayOf(1, 1)
                                ).use { tokT ->
                                    OnnxTensor.createTensor(
                                        env, FloatBuffer.wrap(h1), longArrayOf(1, 1, SavaaniDecoder.PRED_DIM.toLong())
                                    ).use { h1T ->
                                        OnnxTensor.createTensor(
                                            env, FloatBuffer.wrap(h2), longArrayOf(1, 1, SavaaniDecoder.PRED_DIM.toLong())
                                        ).use { h2T ->
                                            predS.run(
                                                mapOf("targets" to tokT, "h_in_1" to h1T, "h_in_2" to h2T)
                                            ).use { outP ->
                                                val predT = (outP.get(0) as OnnxTensor)
                                                val nh1T = (outP.get(1) as OnnxTensor)
                                                val nh2T = (outP.get(2) as OnnxTensor)
                                                val col = frame.coerceAtMost(T2 - 1)
                                                val e = FloatArray(SavaaniDecoder.ENC_DIM)
                                                for (i in 0 until SavaaniDecoder.ENC_DIM) {
                                                    e[i] = ebuf.get(i * T2 + col)
                                                }
                                                OnnxTensor.createTensor(
                                                    env, FloatBuffer.wrap(e), longArrayOf(1, SavaaniDecoder.ENC_DIM.toLong(), 1)
                                                ).use { encT ->
                                                    OnnxTensor.createTensor(
                                                        env,
                                                        FloatBuffer.wrap(predT.floatBuffer.array().clone()),
                                                        longArrayOf(1, SavaaniDecoder.PRED_DIM.toLong(), 1)
                                                    ).use { predT2 ->
                                                        jointS.run(mapOf("enc" to encT, "pred" to predT2)).use { outJ ->
                                                            val logitsT = (outJ.get(0) as OnnxTensor)
                                                            val lb = logitsT.floatBuffer
                                                            val base = lb.remaining() / SavaaniDecoder.LOGIT_STRIDE.coerceAtLeast(1)
                                                            // flatten [1,1,1,5006]
                                                            val flatJ = FloatArray(SavaaniDecoder.LOGIT_STRIDE)
                                                            for (i in 0 until SavaaniDecoder.LOGIT_STRIDE) flatJ[i] = lb.get(i)
                                                            // token part
                                                            var tok = 0; var best = Float.NEGATIVE_INFINITY
                                                            for (i in 0..SavaaniDecoder.VOCAB_SIZE) {
                                                                val x = flatJ[i]
                                                                if (x > best) { best = x; tok = i }
                                                            }
                                                            // duration part
                                                            var dur = 1; var dbest = Float.NEGATIVE_INFINITY
                                                            for (i in 0 until SavaaniDecoder.DUR_HEADS) {
                                                                val x = flatJ[SavaaniDecoder.VOCAB_SIZE + 1 + i]
                                                                if (x > dbest) { dbest = x; dur = i }
                                                            }
                                                            if (tok != SavaaniDecoder.BLANK_ID) {
                                                                decoded.add(tok)
                                                                tokens = tokens + tok
                                                                symbols++
                                                            }
                                                            frame += maxOf(dur, 1).coerceAtMost(5)
                                                            if (tok != SavaaniDecoder.BLANK_ID) symbols = symbols.coerceAtMost(symbols)
                                                            h1 = nh1T.floatBuffer.array().clone()
                                                            h2 = nh2T.floatBuffer.array().clone()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (symbols > SavaaniDecoder.MAX_SYMBOLS_PER_FRAME * 4) break
                            }
                            val text = SavaaniDecoder.toText(decoded, v)
                            return@withLock TranscriptionResult(
                                text, language,
                                if (text.isBlank()) 0f else 0.85f,
                                System.currentTimeMillis() - t0
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Savaani", "transcribe failed: ${e.message}")
                TranscriptionResult("", language, 0f, System.currentTimeMillis() - t0)
            }
        }
    }

    private fun closeLocked() {
        runCatching { enc?.close() }
        runCatching { pred?.close() }
        runCatching { joint?.close() }
        enc = null; pred = null; joint = null; vocab = null
        fb = null; window = null; loaded = false
    }

    fun close() {
        lock.withLock { closeLocked() }
    }
}
