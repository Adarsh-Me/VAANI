package com.itantra.ml

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Bundled Flite TTS (Hear2Read Indic flite fork). Multi-voice: flitevox files
 * from festvox.org voxdata-v2.0.0 (public, Hear2Read's own voice server):
 *   hi -> hindi_tdil.flitevox   (demo_indic_tdil_hin, verified)
 *   ta -> tamil_sxv.flitevox
 *   gu -> gujarati_axb.flitevox
 *   mr -> marathi_slp.flitevox
 *   te -> telugu_knr.flitevox
 * Registration sequence matches Hear2Read's shipped engine exactly.
 * (pa/bn have no public flite voice — they fall back.)
 */
class FliteEngine(private val context: Context) {

    companion object {
        const val TAG = "FliteTTS"
        // lang -> (asset name, expected bytes 0 = unknown size accept)
        val VOICE_ASSETS: Map<String, Pair<String, Long>> = mapOf(
            "hi" to ("flite/hindi_tdil.flitevox" to 21_538_000L),
            "ta" to ("flite/tamil_sxv.flitevox" to 0L),
            "gu" to ("flite/gujarati_axb.flitevox" to 0L),
            "mr" to ("flite/marathi_slp.flitevox" to 0L),
            "te" to ("flite/telugu_knr.flitevox" to 0L)
        )

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

    private external fun nativeLoadVoice(path: String, lang: String): Boolean
    private external fun nativeSpeak(text: String, lang: String, outRate: FloatArray?): FloatArray

    private val loaded = mutableSetOf<String>()
    private var seeded = false

    /** Copies bundled voices from assets to filesDir (once). */
    private fun seedFromAssets(): Boolean {
        if (seeded) return true
        if (!loadNative()) {
            Log.w(TAG, "libttsflite.so unavailable")
            return false
        }
        runCatching {
            for ((_, pair) in VOICE_ASSETS) {
                val asset = pair.first
                val name = asset.substringAfterLast('/')
                val dest = File(context.filesDir, "models/flite/$name")
                if (dest.exists() && dest.length() > 1_000_000) continue
                context.assets.open(asset).use { input ->
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, name + ".tmp")
                    tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                    if (tmp.length() > 1_000_000) {
                        dest.delete()
                        tmp.renameTo(dest)
                    } else tmp.delete()
                }
            }
        }.onFailure {
            Log.w(TAG, "voice seed failed: ${it.message}")
            return false
        }
        seeded = true
        return true
    }

    /** Load the voice for [lang]; true when speak() will work. */
    fun prepare(lang: String): Boolean {
        if (lang in loaded) return true
        if (!seedFromAssets()) return false
        val pair = VOICE_ASSETS[lang] ?: return false
        val name = pair.first.substringAfterLast('/')
        val f = File(context.filesDir, "models/flite/$name")
        if (!f.exists()) return false
        val ok = runCatching { nativeLoadVoice(f.absolutePath, lang) }.getOrDefault(false)
        Log.i(TAG, "voice load lang=$lang ok=$ok (${f.length()} bytes)")
        if (ok) loaded.add(lang)
        return ok
    }

    /** @return PCM floats [-1,1] at [outRate], or null. */
    fun speak(text: String, lang: String, outRate: FloatArray): FloatArray? {
        if (lang !in loaded) return null
        val pcm = runCatching { nativeSpeak(text, lang, outRate) }.getOrNull()
        if (pcm == null || pcm.isEmpty()) return null
        Log.i(TAG, "spoke $lang ${pcm.size} samples @ ${outRate[0].toInt()}Hz")
        return pcm
    }

    fun isReady(lang: String): Boolean = lang in loaded
    fun supportedLangs(): Set<String> = VOICE_ASSETS.keys
}
