package com.itantra.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.itantra.models.AppSettings
import com.itantra.models.AudioSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Emergency path (AppFlow §8, TechSpec §3.5): STREAM_ALARM at max volume,
 * non-interruptible, vibrating, repeating until acknowledged or timeout.
 */
class EmergencyAlertManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val _alertText = MutableStateFlow("")
    val alertText: StateFlow<String> = _alertText

    fun trigger(audio: FloatArray, sampleRate: Int, settings: AppSettings, text: String = "") {
        stop()
        _alertText.value = text
        _isActive.value = true
        job = scope.launch {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val prevVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            try {
                if (settings.emergencyMaxVolume) {
                    runCatching {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                    }
                }
                vibrate(settings)
                val deadline =
                    System.currentTimeMillis() + settings.emergencyMaxDurationSec * 1000L
                var first = true
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (!first) {
                        delay(settings.emergencyAutoRepeatSec * 1000L)
                    }
                    first = false
                    if (!isActive) break
                    playOnce(audio, sampleRate)
                    vibrate(settings)
                }
            } finally {
                runCatching {
                    if (settings.emergencyMaxVolume) {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
                    }
                }
                stopVibration()
                _isActive.value = false
            }
        }
    }

    private suspend fun playOnce(audio: FloatArray, sampleRate: Int) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(4096, audio.size * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            t.setVolume(AudioTrack.getMaxVolume())
            t.play()
            var off = 0
            while (off < audio.size && _isActive.value) {
                val n = min(2048, audio.size - off)
                t.write(audio, off, n, AudioTrack.WRITE_BLOCKING)
                off += n
            }
            t.stop()
        } finally {
            t.release()
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(settings: AppSettings) {
        if (!settings.emergencyVibrationEnabled) return
        val vib = vibrator() ?: return
        runCatching {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        }
    }

    private fun stopVibration() {
        runCatching { vibrator()?.cancel() }
    }

    /** User acknowledged (or pipeline pre-empted): stop immediately. */
    fun stop() {
        job?.cancel()
        job = null
        stopVibration()
        _isActive.value = false
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    companion object {
        // Silence unused-import guard for sample-rate defaulting.
        @Suppress("unused")
        private const val FALLBACK_SR = AudioSpec.SAMPLE_RATE
    }
}
