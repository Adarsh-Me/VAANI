package com.itantra.ml

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kaldi-style log-mel filterbank frontend for sherpa-onnx CTC models:
 * 16 kHz mono -> frames of 80 log-mel energies (25 ms window, 10 ms shift),
 * per-utterance mean normalized. Pure Kotlin, no native deps.
 */
object FbankExtractor {

    const val NUM_MEL_BINS = 80
    const val SAMPLE_RATE = 16000
    private const val FRAME_LEN_MS = 25
    private const val FRAME_SHIFT_MS = 10
    private const val FFT_SIZE = 512
    private const val PREEMPH = 0.97f
    private const val LOW_FREQ = 20f
    private const val HIGH_FREQ = 8000f

    private val frameLen = SAMPLE_RATE * FRAME_LEN_MS / 1000 // 400
    private val frameShift = SAMPLE_RATE * FRAME_SHIFT_MS / 1000 // 160

    private val hamming = FloatArray(frameLen) { i ->
        (0.54f - 0.46f * cos(2 * PI * i / (frameLen - 1))).toFloat()
    }

    // Mel filterbank: [NUM_MEL_BINS][FFT_SIZE/2+1], built once.
    private val melFilters: Array<FloatArray> by lazy { buildMelFilters() }

    /** @return frames x 80, mean-normalized. Empty input -> empty array. */
    fun extract(audio: FloatArray): Array<FloatArray> {
        if (audio.size < frameLen) return emptyArray()
        val numFrames = 1 + (audio.size - frameLen) / frameShift
        val feats = Array(numFrames) { FloatArray(NUM_MEL_BINS) }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        var prev = 0f
        for (f in 0 until numFrames) {
            val start = f * frameShift
            // Pre-emphasis + window + zero-pad into FFT buffer.
            for (i in 0 until frameLen) {
                val s = audio[start + i]
                val p = s - PREEMPH * prev
                prev = s
                re[i] = p * hamming[i]
                im[i] = 0f
            }
            for (i in frameLen until FFT_SIZE) {
                re[i] = 0f
                im[i] = 0f
            }
            fft(re, im)
            // Power spectrum bins 0..256.
            for (m in 0 until NUM_MEL_BINS) {
                var e = 0f
                val filt = melFilters[m]
                for (k in 0..FFT_SIZE / 2) {
                    val w = filt[k]
                    if (w != 0f) {
                        val mag = re[k] * re[k] + im[k] * im[k]
                        e += w * mag
                    }
                }
                feats[f][m] = e
            }
            // Reset pre-emphasis across frames? Kaldi keeps continuity via
            // overlapping windows; restart per frame is close enough.
            prev = audio[start + frameLen - 1]
        }
        // Log + per-utterance mean normalization (sherpa convention).
        val means = FloatArray(NUM_MEL_BINS)
        for (f in 0 until numFrames) {
            for (m in 0 until NUM_MEL_BINS) {
                val v = ln(maxOf(feats[f][m], 1e-10f))
                feats[f][m] = v
                means[m] += v
            }
        }
        for (m in 0 until NUM_MEL_BINS) means[m] = means[m] / numFrames
        for (f in 0 until numFrames) {
            for (m in 0 until NUM_MEL_BINS) feats[f][m] = feats[f][m] - means[m]
        }
        return feats
    }

    private fun melScale(hz: Float): Float = 2595f * kotlin.math.log10(1 + hz / 700f)
    private fun invMel(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    private fun buildMelFilters(): Array<FloatArray> {
        val bins = FFT_SIZE / 2 + 1
        val out = Array(NUM_MEL_BINS) { FloatArray(bins) }
        val lowMel = melScale(LOW_FREQ)
        val highMel = melScale(HIGH_FREQ)
        val points = FloatArray(NUM_MEL_BINS + 2) { i ->
            invMel(lowMel + i * (highMel - lowMel) / (NUM_MEL_BINS + 1))
        }
        val freqs = FloatArray(bins) { k -> k * SAMPLE_RATE.toFloat() / FFT_SIZE }
        for (m in 0 until NUM_MEL_BINS) {
            val f0 = points[m]
            val f1 = points[m + 1]
            val f2 = points[m + 2]
            for (k in 0 until bins) {
                val f = freqs[k]
                out[m][k] = when {
                    f < f0 || f > f2 -> 0f
                    f <= f1 -> (f - f0) / (f1 - f0)
                    else -> (f2 - f) / (f2 - f1)
                }
            }
        }
        return out
    }

    /** In-place iterative radix-2 FFT (size must be a power of two). */
    internal fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j and bit.inv()
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cwr = 1f
                var cwi = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
