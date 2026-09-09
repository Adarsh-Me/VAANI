package com.itantra.ml

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Bundled Flite TTS (Hear2Read Indic flite fork). JNI against libttsflite.so
 * built from tools/flite-src + tools/flite-android/itantra_flite_jni.cc.
 *
 * Voice: demo_indic_tdil_hin-875-cmpt.flitevox (Hindi, public in the
 * Hear2Read/Voices repo, MIT-style license) seeded from assets/flite/.
 * Engine registration matches Hear2Read's shipped app exactly.
 */
class FliteEngine(private val context: Context) {

    companion object {
        const val TAG = "FliteTTS"
        const val VOICE_ASSET = "flite/hindi_tdil.flitevox"
        const val VOICE_FILE = "models/flite/hindi_tdil.flitevox"
        const val VOICE_BYTES = 21_538_000L

        // The .so carries flite + our JNI; only for arm64 (release strips x86_64
        // anyway; keep both ABIs built so debug on emulator works).
        @Volatile private var nativeReady: Boolean? = null

        private fun loadNative(): Boolean {
            nativeReady?.let { return it }
            synchronized(this) {
                nativeReady?.let { return it }
                nativeReady = runCatching { System.loadLibrary("ttsflite") }.isSuccess
                return nativeReady!!
            }
        }
    }

    private external fun nativeLoadVoice(path: String): Boolean
    private external fun nativeSpeak(text: String, outRate: FloatArray?): FloatArray

    @Volatile private var voiceLoaded = false

    /** Seeds the flitevox from assets if needed, then loads it. */
    fun initialize(): Boolean {
        if (voiceLoaded) return true
        if (!loadNative()) {
            Log.w(TAG, "libttsflite.so unavailable")
            return false
        }
        val dest = File(context.filesDir, VOICE_FILE)
        if (!dest.exists() || dest.length() != VOICE_BYTES) {
            runCatching {
                context.assets.open(VOICE_ASSET).use { input ->
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, dest.name + ".tmp")
                    tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                    if (tmp.length() == VOICE_BYTES) {
                        dest.delete()
                        tmp.renameTo(dest)
                    } else tmp.delete()
                }
            }.onFailure {
                Log.w(TAG, "voice seed failed: ${it.message}")
                return false
            }
        }
        voiceLoaded = runCatching { nativeLoadVoice(dest.absolutePath) }.getOrDefault(false)
        Log.i(TAG, "flite voice load=$voiceLoaded (${dest.length()} bytes)")
        return voiceLoaded
    }

    /** @return PCM floats [-1,1] at [outRate], or null. */
    fun speak(text: String, outRate: FloatArray): FloatArray? {
        if (!voiceLoaded) return null
        val pcm = runCatching { nativeSpeak(text, outRate) }.getOrNull()
        if (pcm == null || pcm.isEmpty()) return null
        Log.i(TAG, "spoke ${pcm.size} samples @ ${outRate[0].toInt()}Hz")
        return pcm
    }

    val isReady: Boolean get() = voiceLoaded
}
