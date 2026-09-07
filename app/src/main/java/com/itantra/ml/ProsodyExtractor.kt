package com.itantra.ml

import com.itantra.models.AudioSpec
import com.itantra.models.ProsodyData
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tier A prosody extraction: pure signal processing, no neural model.
 * Outputs 5 normalized floats -> 5 bytes on the wire (~6 bytes with tag).
 */
object ProsodyExtractor {

    private const val FRAME_SAMPLES = 160 // 10 ms @ 16 kHz
    private const val HOP_SAMPLES = 80
    private const val F0_MIN_HZ = 60f
    private const val F0_MAX_HZ = 400f
    private const val SILENCE_ABS = 0.01f

    fun extract(
        audio: FloatArray,
        sampleRate: Int = AudioSpec.SAMPLE_RATE,
        text: String = ""
    ): ProsodyData {
        if (audio.isEmpty()) return ProsodyData.NEUTRAL

        var sumSq = 0.0
        var silent = 0
        for (v in audio) {
            sumSq += (v * v).toDouble()
            if (abs(v) < SILENCE_ABS) silent++
        }
        val rms = sqrt(sumSq / audio.size).toFloat()
        val pauseRatio = (silent.toFloat() / audio.size).coerceIn(0f, 1f)

        // F0 per frame via autocorrelation; keep voiced frames only.
        val f0s = ArrayList<Float>()
        var pos = 0
        val lagMin = (sampleRate / F0_MAX_HZ).toInt().coerceAtLeast(1)
        val lagMax = (sampleRate / F0_MIN_HZ).toInt()
        while (pos + FRAME_SAMPLES <= audio.size) {
            f0(frame(audio, pos), sampleRate, lagMin, lagMax)?.let { f0s.add(it) }
            pos += HOP_SAMPLES
        }
        val pitchMeanHz = if (f0s.isEmpty()) 150f else f0s.average().toFloat()
        val pitchRangeHz = if (f0s.size < 2) 20f else (f0s.max() - f0s.min())

        val durationSec = audio.size.toFloat() / sampleRate
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val wps = if (durationSec > 0.2f && words > 0) words / durationSec else 2.0f

        return ProsodyData(
            pitchMean = ((pitchMeanHz - ProsodyData.PITCH_MIN_HZ) /
                (ProsodyData.PITCH_MAX_HZ - ProsodyData.PITCH_MIN_HZ)).coerceIn(0f, 1f),
            pitchRange = (pitchRangeHz / ProsodyData.PITCH_RANGE_MAX_HZ).coerceIn(0f, 1f),
            speakingRate = ((wps - ProsodyData.RATE_MIN_WPS) /
                (ProsodyData.RATE_MAX_WPS - ProsodyData.RATE_MIN_WPS)).coerceIn(0f, 1f),
            energy = rms.coerceIn(0f, 1f),
            pauseRatio = pauseRatio
        )
    }

    private fun frame(audio: FloatArray, start: Int): FloatArray =
        audio.copyOfRange(start, start + FRAME_SAMPLES)

    /** Returns F0 in Hz, or null for unvoiced/silent frames. */
    private fun f0(frame: FloatArray, sampleRate: Int, lagMin: Int, lagMax: Int): Float? {
        var energy = 0f
        for (v in frame) energy += v * v
        if (energy < 1e-6f) return null
        var bestLag = -1
        var bestCorr = 0f
        val maxLag = minOf(lagMax, frame.size - 1)
        for (lag in lagMin..maxLag) {
            var corr = 0f
            for (i in 0 until frame.size - lag) corr += frame[i] * frame[i + lag]
            corr /= (frame.size - lag)
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null
        // Voiced only if correlation is strong relative to frame energy.
        if (bestCorr < energy / frame.size * 0.3f) return null
        return sampleRate.toFloat() / bestLag
    }
}
