package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.itantra.models.AudioSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * PCM-float playback for received utterances. Serialized: one utterance
 * at a time; [stop] interrupts. Emergency alerts use EmergencyAlertManager.
 */
class AudioPlayer(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false
    @Volatile private var volume = 1.0f

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        runCatching { track?.setVolume(volume) }
    }

    suspend fun play(audio: FloatArray, sampleRate: Int = AudioSpec.SAMPLE_RATE) {
        mutex.withLock {
            stopped = false
            withContext(Dispatchers.IO) {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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
                track = t
                try {
                    t.setVolume(volume)
                    t.play()
                    var off = 0
                    while (off < audio.size && !stopped) {
                        val n = min(2048, audio.size - off)
                        t.write(audio, off, n, AudioTrack.WRITE_BLOCKING)
                        off += n
                    }
                    if (!stopped) {
                        // Drain: wait until playback head reaches the end.
                        val endMs = (audio.size * 1000L / sampleRate) + 120
                        val deadline = System.currentTimeMillis() + endMs
                        while (!stopped && System.currentTimeMillis() < deadline) {
                            val head = t.playbackHeadPosition
                            if (head >= audio.size) break
                            Thread.sleep(20)
                        }
                    }
                } finally {
                    runCatching { t.stop() }
                    t.release()
                    if (track === t) track = null
                }
            }
        }
    }

    fun stop() {
        stopped = true
    }

    fun streamType(): Int = AudioManager.STREAM_MUSIC
}
