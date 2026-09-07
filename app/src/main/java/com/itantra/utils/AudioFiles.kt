package com.itantra.utils

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV cache for synthesized utterances (cacheDir/audio/{utteranceId}.wav).
 * 16-bit PCM mono 16 kHz + 44 B header. Enforces the 100 MB cap (oldest first).
 */
class AudioFiles(private val context: Context, private val maxBytes: Long = 100L * 1024 * 1024) {

    fun dir(): File = File(context.cacheDir, "audio").apply { mkdirs() }

    fun saveUtterance(utteranceId: String, audio: FloatArray, sampleRate: Int): String {
        val f = File(dir(), "$utteranceId.wav")
        writeWavMono16(f, audio, sampleRate)
        enforceCap()
        return f.absolutePath
    }

    fun load(path: String): FloatArray? {
        return try {
            val bytes = File(path).readBytes()
            if (bytes.size < 44) return null
            val n = (bytes.size - 44) / 2
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(n) { i ->
                (buf.getShort(44 + i * 2).toInt() / 32768f).coerceIn(-1f, 1f)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cacheSizeBytes(): Long = dir().listFiles()?.sumOf { it.length() } ?: 0

    private fun enforceCap() {
        val files = dir().listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            runCatching { f.delete() }
        }
    }

    companion object {
        fun writeWavMono16(f: File, audio: FloatArray, sampleRate: Int) {
            val data = ByteArray(audio.size * 2)
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            for (v in audio) {
                buf.putShort(((v.coerceIn(-1f, 1f)) * 32767).toInt().toShort())
            }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + data.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(data.size)
            f.outputStream().use {
                it.write(header.array())
                it.write(data)
            }
        }
    }
}
