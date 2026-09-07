package com.itantra.ml

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.itantra.models.AudioSpec
import com.itantra.models.StorageLayout
import com.itantra.models.SynthesisResult
import com.itantra.models.TtsSettings
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Offline neural TTS via sherpa-onnx JNI (Piper/VITS voices downloaded into
 * filesDir/models/tts/<lang>/ by ModelDownloader). Falls back to Android
 * system TTS for languages without a downloaded voice (docs fallback L1).
 * Both paths render to FloatArray PCM; playback/emergency code is agnostic.
 */
class TTSManager(private val context: Context, private val threads: Int = 2) {

    companion object {
        const val TAG = "TTSManager"
    }

    private var offlineTts: OfflineTts? = null
    private var onnxLang: String? = null
    private var onnxSampleRate: Int = AudioSpec.SAMPLE_RATE
    private val mms = HashMap<String, MmsTtsEngine>()
    private var systemTts: TextToSpeech? = null
    private var systemReady = false
    private val systemLangs = mutableSetOf<String>()

    val isOnnxLoaded: Boolean get() = offlineTts != null
    /** MMS neural voice ready for [lang] (ta/pa). */
    fun isMmsReady(lang: String): Boolean = mms[lang]?.isLoaded == true
    val isReady: Boolean get() = isOnnxLoaded || mms.values.any { it.isLoaded } || systemReady

    /** Loads sherpa-onnx voice for [lang] if present, else warms up system TTS. */
    suspend fun initialize(lang: String): Boolean {
        loadVoice(lang)
        loadMms(lang)
        ensureSystemTts()
        if (systemReady) {
            setSystemLang(lang)
        }
        return isReady
    }

    /** MMS-TTS neural voice for ta/pa (Meta MMS ONNX, CC-BY-NC-4.0). */
    fun loadMms(lang: String): Boolean {
        if (lang != "ta" && lang != "pa") return false
        val eng = mms.getOrPut(lang) { MmsTtsEngine(context, threads) }
        return eng.load(lang)
    }

    /**
     * Expects the extracted voice bundle under filesDir/models/tts/<lang>/:
     * one .onnx model, tokens.txt, and the espeak-ng-data/ directory (all three
     * required — a missing espeak-ng-data is the classic silent-init failure).
     */
    private fun loadVoice(lang: String) {
        if (offlineTts != null && onnxLang == lang) return
        runCatching { offlineTts?.release() }
        offlineTts = null
        onnxLang = null

        val dir = ModelPaths(context).file(StorageLayout.ttsDir(lang))
        val modelFile = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }
        val tokens = File(dir, "tokens.txt")
        val espeakData = File(dir, "espeak-ng-data")
        if (modelFile == null || !tokens.exists() || !espeakData.isDirectory) {
            Log.i(TAG, "No sherpa-onnx voice for $lang under ${dir.absolutePath} — Using SYSTEM TTS fallback")
            return
        }
        try {
            val config = OfflineTtsConfig().apply {
                model = OfflineTtsModelConfig().apply {
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = espeakData.absolutePath,
                        // Prosody lengthScale maps to per-request speed at generate()
                        // time; noise scales stay at VITS defaults (0.667 / 0.8).
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f,
                    )
                    numThreads = threads
                    debug = false
                    provider = "cpu"
                }
            }
            val tts = OfflineTts(config = config)
            offlineTts = tts
            onnxLang = lang
            onnxSampleRate = tts.sampleRate()
            Log.i(TAG, "Loaded sherpa-onnx VITS voice for $lang (${modelFile.name}, sr=$onnxSampleRate)")
        } catch (e: Throwable) {
            // Includes UnsatisfiedLinkError when .so/ABI don't match the device.
            Log.w(TAG, "sherpa-onnx init failed for $lang: $e — Using SYSTEM TTS fallback")
            offlineTts = null
            onnxLang = null
        }
    }

    private suspend fun ensureSystemTts() {
        if (systemTts != null) return
        suspendCancellableCoroutine { cont ->
            var resumed = false
            fun done(ok: Boolean) {
                if (!resumed) {
                    resumed = true
                    systemReady = ok
                    cont.resume(ok)
                }
            }
            systemTts = TextToSpeech(context) { status ->
                done(status == TextToSpeech.SUCCESS)
            }
            cont.invokeOnCancellation { done(false) }
        }
        systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {}
            override fun onError(id: String?) {}
        })
    }

    fun setSystemLang(lang: String): Boolean {
        val tts = systemTts ?: return false
        val candidates = buildList {
            when (lang) {
                "hi" -> add(Locale("hi", "IN"))
                "ta" -> add(Locale("ta", "IN"))
                "bn" -> add(Locale("bn", "IN"))
                else -> add(Locale(lang))
            }
            add(Locale(lang))
            // Any installed voice whose language matches, regardless of region.
            runCatching {
                tts.availableLanguages?.forEach { l ->
                    if (l.language == lang && !contains(l)) add(l)
                }
            }
        }
        return try {
            for (locale in candidates) {
                val avail = tts.isLanguageAvailable(locale)
                if (avail != TextToSpeech.LANG_MISSING_DATA &&
                    avail != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts.language = locale
                    systemLangs.add(lang)
                    return true
                }
            }
            Log.w(TAG, "No system voice for $lang — keeping previous language")
            false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun synthesize(
        text: String,
        settings: TtsSettings = TtsSettings(),
        lang: String
    ): SynthesisResult? {
        if (text.isBlank()) return null
        val t0 = System.currentTimeMillis()
        // Lazy load: received messages can arrive in a language different from
        // the one initialize() was called with — try neural voices for it.
        if (onnxLang != lang) loadVoice(lang)
        if (offlineTts != null && onnxLang == lang) {
            onnxSynthesize(text, settings)?.let { return it }
        }
        if (!isMmsReady(lang)) loadMms(lang)
        // MMS neural voice for ta/pa — logged per call so logcat proves the engine.
        mms[lang]?.takeIf { it.isLoaded }?.let { eng ->
            eng.synthesize(text, settings, t0)?.let {
                Log.i(TAG, "TTS engine=mms-$lang ${it.audioData.size} samples")
                return it
            }
            Log.w(TAG, "MMS synthesis failed for $lang, trying system voice")
        }
        return systemSynthesize(text, lang, settings, t0)
    }

    /** Blocking VITS inference; runs off the main thread, PCM float @ model rate. */
    private suspend fun onnxSynthesize(text: String, settings: TtsSettings): SynthesisResult? {
        val tts = offlineTts ?: return null
        return withContext(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            try {
                // speed = 1 / lengthScale (lengthScale > 1 means slower speech).
                val speed = 1f / settings.lengthScale.coerceIn(0.4f, 2.5f)
                val audio = tts.generate(text, sid = 0, speed = speed)
                if (audio.samples.isEmpty()) {
                    Log.w(TAG, "sherpa-onnx produced no audio")
                    null
                } else {
                    SynthesisResult(
                        audioData = audio.samples,
                        sampleRate = audio.sampleRate,
                        durationMs = audio.samples.size * 1000L / audio.sampleRate,
                        inferenceTimeMs = System.currentTimeMillis() - t0,
                        engine = "onnx"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "sherpa-onnx synthesis failed: $e")
                null
            }
        }
    }

    private suspend fun systemSynthesize(text: String, lang: String, settings: TtsSettings, t0: Long): SynthesisResult? {
        val tts = systemTts ?: return null
        if (!systemReady) return null
        setSystemLang(lang)
        // Prosody -> speech-rate + pitch mapping (only path that can modulate pitch).
        runCatching { tts.setSpeechRate(1.0f / settings.lengthScale.coerceIn(0.4f, 2.5f)) }
        runCatching { tts.setPitch(settings.pitchScale.coerceIn(0.5f, 1.5f)) }
        val wav = File.createTempFile("tts_", ".wav", context.cacheDir)
        return try {
            val id = UUID.randomUUID().toString()
            suspendCancellableCoroutine { cont ->
                var done = false
                fun finish() {
                    if (!done) {
                        done = true
                        cont.resume(true)
                    }
                }
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) = finish()
                    override fun onError(u: String?) = finish()
                }
                tts.setOnUtteranceProgressListener(listener)
                val rc = tts.synthesizeToFile(text, null, wav, id)
                if (rc != TextToSpeech.SUCCESS) finish()
            }
            val pcm = readWavMono16k(wav) ?: return null
            SynthesisResult(
                audioData = pcm,
                durationMs = (pcm.size * 1000L / AudioSpec.SAMPLE_RATE),
                inferenceTimeMs = System.currentTimeMillis() - t0,
                engine = "system"
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { wav.delete() }
        }
    }

    /** Reads 16-bit PCM WAV (any rate -> naive resample to 16 kHz mono). */
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
            // Find "data" chunk.
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
        runCatching { systemTts?.stop() }
    }

    fun shutdown() {
        runCatching { offlineTts?.release() }
        offlineTts = null
        onnxLang = null
        runCatching { systemTts?.shutdown() }
        systemTts = null
        systemReady = false
    }
}
