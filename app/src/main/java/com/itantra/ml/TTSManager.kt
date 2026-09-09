package com.itantra.ml

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.itantra.models.AudioSpec
import com.itantra.models.SynthesisResult
import com.itantra.models.TtsSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * TTS: Hear2Read Indic voices (flite) via engine binding + Android system TTS.
 *
 * Hear2Read ships real Indic-language G2P voices (hi/ta/pa/bn/...) as a free
 * Android TTS engine app (org.hear2read.h2rng on Play Store). This manager:
 *  1. discovers installed TTS engines and prefers any org.hear2read.* engine
 *  2. falls back to the device default engine (Google TTS)
 * Both render to PCM float @16 kHz so playback/emergency stay unchanged.
 */
class TTSManager(private val context: Context, private val threads: Int = 2) {

    companion object {
        const val TAG = "TTSManager"
        const val H2R_PREFIX = "org.hear2read"
    }

    private var defaultTts: TextToSpeech? = null
    private var defaultReady = false
    private var h2rTts: TextToSpeech? = null
    private var h2rReady = false
    private var h2rEngine: String? = null
    private val flite = FliteEngine(context)
    private val systemLangs = mutableSetOf<String>()

    val isReady: Boolean get() = defaultReady || h2rReady || flite.isReady("hi")

    /** Play Store package of the Hear2Read voices, if installed. */
    fun hear2ReadEnginePackage(): String? {
        if (h2rEngine != null) return h2rEngine
        return try {
            val pm = context.packageManager
            val intent = Intent("android.intent.action.TTS_SERVICE")
            h2rEngine = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .firstOrNull { it.startsWith(H2R_PREFIX) }
            if (h2rEngine != null) {
                Log.i(TAG, "Hear2Read engine discovered: $h2rEngine")
            }
            h2rEngine
        } catch (e: Exception) {
            null
        }
    }

    suspend fun initialize(lang: String): Boolean {
        ensureDefaultTts()
        ensureH2RTts()
        if (defaultReady) setLang(lang)
        return isReady
    }

    private suspend fun ensureDefaultTts() {
        if (defaultTts != null) return
        createTts(null, assign = { defaultTts = it }) { ok -> defaultReady = ok }
    }

    /** Lazily binds to the Hear2Read engine when its app is installed. */
    private suspend fun ensureH2RTts(): Boolean {
        if (h2rReady) return true
        val pkg = hear2ReadEnginePackage() ?: return false
        if (h2rTts != null) return false // created, still initializing
        createTts(pkg, assign = { h2rTts = it }) { ok ->
            h2rReady = ok
            Log.i(TAG, "Hear2Read engine $pkg ready=$ok")
        }
        return h2rReady
    }

    private suspend fun createTts(
        enginePackage: String?,
        assign: (TextToSpeech) -> Unit,
        onReady: (Boolean) -> Unit
    ) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            fun done(ok: Boolean) {
                if (!resumed) {
                    resumed = true
                    onReady(ok)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            val listener = TextToSpeech.OnInitListener { status -> done(status == TextToSpeech.SUCCESS) }
            val tts = if (enginePackage == null) TextToSpeech(context, listener)
            else TextToSpeech(context, listener, enginePackage)
            assign(tts)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                override fun onError(id: String?, errorCode: Int) {}
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {}
            })
            cont.invokeOnCancellation { done(false) }
        }
    }

    fun setLang(lang: String): Boolean {
        val locale = when (lang) {
            "hi" -> Locale("hi", "IN")
            "ta" -> Locale("ta", "IN")
            "bn" -> Locale("bn", "IN")
            "pa" -> Locale("pa", "IN")
            else -> Locale(lang)
        }
        var ok = false
        // Prefer Hear2Read for Indic languages (real G2P), then default engine.
        for (tts in listOf(h2rTts, defaultTts)) {
            val t = tts ?: continue
            val ready = if (tts === h2rTts) h2rReady else defaultReady
            if (!ready) continue
            ok = runCatching {
                val avail = t.isLanguageAvailable(locale)
                if (avail != TextToSpeech.LANG_MISSING_DATA &&
                    avail != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    t.language = locale
                    systemLangs.add(lang)
                    true
                } else false
            }.getOrDefault(false)
            if (ok) return true
        }
        Log.w(TAG, "No voice for $lang across engines")
        return false
    }

    suspend fun synthesize(
        text: String,
        settings: TtsSettings = TtsSettings(),
        lang: String
    ): SynthesisResult? {
        if (text.isBlank()) return null
        val t0 = System.currentTimeMillis()
        ensureH2RTts()
        // Bundled Flite engine first for every language it has voices for
        // (hi/ta/gu/mr/te — Hear2Read flitevox from festvox).
        if (lang in flite.supportedLangs()) {
            if (flite.prepare(lang)) {
                val rate = FloatArray(1) { 16000f }
                val pcm = flite.speak(text, lang, rate)
                if (pcm != null) {
                    val sr = rate[0].toInt().coerceAtLeast(8000)
                    return SynthesisResult(
                        audioData = pcm,
                        sampleRate = sr,
                        durationMs = (pcm.size * 1000L / sr),
                        inferenceTimeMs = System.currentTimeMillis() - t0,
                        engine = "flite-$lang"
                    ).also { Log.i(TAG, "TTS engine=flite-$lang ${pcm.size} samples @${sr}Hz") }
                }
            }
        }
        val engine = when {
            h2rReady && systemLangs.contains(lang) -> h2rTts
            defaultReady && systemLangs.contains(lang) -> defaultTts
            h2rReady -> { setLang(lang); h2rTts }
            else -> { setLang(lang); defaultTts }
        } ?: return null
        val engineName = if (engine === h2rTts) "hear2read" else "system"
        return systemSynthesize(engine, engineName, text, settings, t0)
    }

    private suspend fun systemSynthesize(
        tts: TextToSpeech,
        engineName: String,
        text: String,
        settings: TtsSettings,
        t0: Long
    ): SynthesisResult? = withContext(Dispatchers.IO) {
        // Prosody -> speech-rate + pitch mapping.
        runCatching { tts.setSpeechRate(1.0f / settings.lengthScale.coerceIn(0.4f, 2.5f)) }
        runCatching { tts.setPitch(settings.pitchScale.coerceIn(0.5f, 1.5f)) }
        val wav = File.createTempFile("tts_", ".wav", context.cacheDir)
        try {
            val id = UUID.randomUUID().toString()
            suspendCancellableCoroutine { cont ->
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) = cont.resume(Unit)
                    override fun onError(u: String?) = cont.resume(Unit)
                }
                tts.setOnUtteranceProgressListener(listener)
                val rc = tts.synthesizeToFile(text, null, wav, id)
                if (rc != TextToSpeech.SUCCESS) cont.resume(Unit)
            }
            val pcm = readWavMono16k(wav) ?: return@withContext null
            SynthesisResult(
                audioData = pcm,
                durationMs = (pcm.size * 1000L / AudioSpec.SAMPLE_RATE),
                inferenceTimeMs = System.currentTimeMillis() - t0,
                engine = engineName
            ).also { Log.i(TAG, "TTS engine=$engineName ${pcm.size} samples") }
        } catch (e: Exception) {
            Log.w(TAG, "synthesize failed: ${e.message}")
            null
        } finally {
            runCatching { wav.delete() }
        }
    }

    /** Reads 16-bit PCM WAV (any rate -> resample to 16 kHz mono). */
    internal fun readWavMono16k(f: File): FloatArray? {
        return try {
            val bytes = f.readBytes()
            if (bytes.size < 44) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(24)
            val sampleRate = buf.int
            buf.position(34)
            val bits = buf.short.toInt()
            if (bits != 16) return null
            var dataStart = 44
            var i = 12
            while (i + 8 <= bytes.size) {
                val tag = String(bytes, i, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (tag == "data") {
                    dataStart = i + 8
                    break
                }
                i += 8 + size
            }
            val shorts = (bytes.size - dataStart) / 2
            if (shorts <= 0) return null
            val raw = FloatArray(shorts) { k ->
                val s = ByteBuffer.wrap(bytes, dataStart + k * 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                (s / 32768f).coerceIn(-1f, 1f)
            }
            resample(raw, sampleRate, AudioSpec.SAMPLE_RATE)
        } catch (e: Exception) {
            null
        }
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        val ratio = from.toDouble() / to
        val n = (input.size / ratio).toInt().coerceAtLeast(1)
        return FloatArray(n) { i -> input[(i * ratio).toInt().coerceIn(0, input.size - 1)] }
    }

    fun stop() {
        runCatching { defaultTts?.stop() }
        runCatching { h2rTts?.stop() }
    }

    fun shutdown() {
        runCatching { defaultTts?.shutdown() }
        runCatching { h2rTts?.shutdown() }
        defaultTts = null
        h2rTts = null
        defaultReady = false
        h2rReady = false
    }
}
