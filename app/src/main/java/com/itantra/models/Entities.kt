package com.itantra.models

/** TTS modulation derived from received prosody (Schema §1.2). */
data class TtsSettings(
    val lengthScale: Float = 1.0f,
    val noiseScale: Float = 0.667f,
    val noiseW: Float = 0.8f,
    /** Pitch multiplier (system TTS path; neural VITS handles pitch natively). */
    val pitchScale: Float = 1.0f
)

/**
 * Prosody side-channel payload: 5 normalized floats, 5 bytes on wire.
 * Physical ranges: pitch 50-400 Hz, pitch-range 0-200 Hz, rate 0.5-5 wps.
 */
data class ProsodyData(
    val pitchMean: Float,
    val pitchRange: Float,
    val speakingRate: Float,
    val energy: Float,
    val pauseRatio: Float
) {
    fun toBytes(): ByteArray = byteArrayOf(
        clampToByte(pitchMean), clampToByte(pitchRange),
        clampToByte(speakingRate), clampToByte(energy), clampToByte(pauseRatio)
    )

    fun physicalPitchHz(): Float = PITCH_MIN_HZ + (pitchMean * (PITCH_MAX_HZ - PITCH_MIN_HZ))
    fun physicalRateWps(): Float = RATE_MIN_WPS + (speakingRate * (RATE_MAX_WPS - RATE_MIN_WPS))

    /** Schema §1.2 mapping + per-emotion preset overrides.
     *  pitchScale: received pitchMean deviates from the 50-400 Hz window
     *  center (0.5) — up to ±40% so speaker voice height survives transport. */
    fun toTtsSettings(emotionTag: Int): TtsSettings {
        val base = TtsSettings(
            lengthScale = 1.0f - (speakingRate - 0.5f) * 0.4f,
            noiseScale = 0.5f + energy * 0.7f,
            noiseW = 0.8f,
            pitchScale = 1.0f + (pitchMean - 0.5f) * 0.8f
        )
        return when (Emotion.fromId(emotionTag)) {
            Emotion.NEUTRAL -> TtsSettings(1.0f, 0.667f, 0.8f, 1.0f)
            Emotion.DISTRESS -> TtsSettings(0.8f, 0.9f, 1.0f, 1.2f)
            Emotion.ANGRY -> TtsSettings(0.7f, 1.2f, 1.2f, 0.85f)
            Emotion.CALM -> TtsSettings(1.2f, 0.4f, 0.6f, 0.9f)
            Emotion.HAPPY -> TtsSettings(1.1f, 0.8f, 0.9f, 1.15f)
        }.let { preset ->
            // Blend preset with live prosody so speaker urgency survives.
            preset.copy(
                lengthScale = (preset.lengthScale + base.lengthScale) / 2f,
                pitchScale = (preset.pitchScale + base.pitchScale) / 2f
            )
        }
    }

    companion object {
        const val PITCH_MIN_HZ = 50f
        const val PITCH_MAX_HZ = 400f
        const val PITCH_RANGE_MAX_HZ = 200f
        const val RATE_MIN_WPS = 0.5f
        const val RATE_MAX_WPS = 5.0f
        val NEUTRAL = ProsodyData(0.5f, 0.3f, 0.5f, 0.4f, 0.1f)

        fun fromBytes(bytes: ByteArray): ProsodyData {
            require(bytes.size == 5) { "ProsodyData requires exactly 5 bytes, got ${bytes.size}" }
            return ProsodyData(
                pitchMean = (bytes[0].toInt() and 0xFF) / 255f,
                pitchRange = (bytes[1].toInt() and 0xFF) / 255f,
                speakingRate = (bytes[2].toInt() and 0xFF) / 255f,
                energy = (bytes[3].toInt() and 0xFF) / 255f,
                pauseRatio = (bytes[4].toInt() and 0xFF) / 255f
            )
        }

        private fun clampToByte(value: Float): Byte =
            ((value.coerceIn(0f, 1f)) * 255).toInt().toByte()
    }
}

/** Central domain object: one communication exchange (Schema §1.1). */
data class ChatMessage(
    val id: String,
    val utteranceId: String,
    val sourceLang: String,
    val targetLang: String,
    val originalText: String,
    val translatedText: String? = null,
    val translationFailed: Boolean = false,
    /** True when the visible translation came from the phrasebook, not neural MT. */
    val mtFallback: Boolean = false,
    val emotionTag: Int = 0,
    val emotionLabel: String = Emotion.NEUTRAL.label,
    val emotionConfidence: Float = 0f,
    val prosody: ProsodyData = ProsodyData.NEUTRAL,
    val direction: MessageDirection = MessageDirection.SENT,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val wasPlayed: Boolean = false,
    val isEmergency: Boolean = emotionTag == Emotion.DISTRESS.id
) {
    val requiresTranslation: Boolean
        get() = sourceLang != targetLang && !translationFailed
    val displayText: String
        get() = translatedText ?: originalText
}

/** Send-side pipeline output. */
data class ProcessingResult(
    val transcription: TranscriptionResult,
    val prosody: ProsodyData,
    val emotion: EmotionResult,
    val message: ChatMessage,
    val totalProcessingMs: Long
)

data class ReceiveResult(
    val message: ChatMessage,
    val translated: Boolean,
    val audioPath: String?,
    val isEmergency: Boolean,
    val totalProcessingMs: Long
)

/** ASR output. confidence < 0 means "model unavailable, text typed/simulated". */
data class TranscriptionResult(
    val text: String,
    val language: String,
    val confidence: Float,
    val inferenceTimeMs: Long
)

/** Translation output. */
data class TranslationResult(
    val text: String,
    val sourceLang: String,
    val targetLang: String,
    val fallbackUsed: Boolean,
    val inferenceTimeMs: Long
)

/** Emotion classification output. */
data class EmotionResult(
    val emotion: Emotion,
    val confidence: Float,
    val isEmergency: Boolean = emotion == Emotion.DISTRESS
) {
    val emotionId: Int get() = emotion.id
}

/** TTS output. */
data class SynthesisResult(
    val audioData: FloatArray,
    val sampleRate: Int = AudioSpec.SAMPLE_RATE,
    val durationMs: Long,
    val inferenceTimeMs: Long,
    val engine: String // "onnx" | "system"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesisResult) return false
        return sampleRate == other.sampleRate && durationMs == other.durationMs &&
            engine == other.engine && audioData.contentEquals(other.audioData)
    }
    override fun hashCode(): Int = 31 * sampleRate + audioData.contentHashCode()
}

/** User-configurable application state (Schema §1.4). */
data class AppSettings(
    val sourceLanguage: String = Languages.DEFAULT_SOURCE,
    val targetLanguage: String = Languages.DEFAULT_TARGET,
    val autoDetectLanguage: Boolean = false,
    val enabledLanguages: Set<String> = Languages.DEMO,
    val sampleRate: Int = AudioSpec.SAMPLE_RATE,
    val chunkSize: Int = AudioSpec.CHUNK_SIZE,
    val vadThreshold: Float = VadSpec.THRESHOLD,
    val minSpeechDurationMs: Int = VadSpec.MIN_SPEECH_DURATION_MS,
    val minSilenceDurationMs: Int = VadSpec.MIN_SILENCE_DURATION_MS,
    val inputMode: InputMode = InputMode.PUSH_TO_TALK,
    val maxRecordingDurationSec: Int = 30,
    val holdThresholdMs: Long = 100,
    val transportType: TransportType = TransportType.LOOPBACK,
    val meshEnabled: Boolean = false,
    val meshTtl: Int = PacketLimits.DEFAULT_TTL,
    val emergencyMaxVolume: Boolean = true,
    val emergencyVibrationEnabled: Boolean = true,
    val emergencyAutoRepeatSec: Int = 5,
    val emergencyMaxDurationSec: Int = 60,
    val inferenceThreads: Int = 4,
    val batterySaverMode: Boolean = false,
    val maxAudioCacheMB: Int = StorageLayout.MAX_AUDIO_CACHE_MB,
    val updatedAt: Long = System.currentTimeMillis()
)
