package com.itantra.models

/** Audio pipeline constants (TechSpec §3.1). */
object AudioSpec {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val CHUNK_SIZE = 512 // 32 ms @ 16 kHz
    const val CHUNK_MS = 32
}

/** Silero VAD streaming thresholds (TechSpec §3.2). */
object VadSpec {
    const val THRESHOLD = 0.5f
    const val MIN_SPEECH_DURATION_MS = 250
    const val MIN_SILENCE_DURATION_MS = 100
}

/** BLE GATT identifiers (Schema §3 / contract). */
object BleUuids {
    const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val TX_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // server -> client (notify)
    const val RX_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // client -> server (write)
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    const val MAX_CHAR_BYTES = 512
    const val MTU_REQUEST = 517
}

/** Wire-packet limits (Schema §3). */
object PacketLimits {
    const val MAX_SIZE_BYTES = 512
    const val MAX_TEXT_CHARS = 400
    const val PROTOCOL_VERSION = 1
    val SUPPORTED_VERSIONS = setOf(1)
    const val MAX_AGE_MS = 5 * 60 * 1000L
    const val FUTURE_SKEW_MS = 60 * 1000L
    const val DEFAULT_TTL = 3
}

/** File layout under filesDir / cacheDir (Schema §10). */
object StorageLayout {
    const val MODEL_DIR = "models"
    const val VAD_MODEL = "models/silero_vad.onnx"
    const val ASR_MODEL = "models/indic_conformer_int8.onnx"
    const val MT_ENCODER = "models/indictrans2_encoder_int8.onnx"
    const val MT_DECODER = "models/decoder_with_past_model.onnx"
    const val MT_DECODER_LEGACY = "models/indictrans2_decoder_int8.onnx"
    const val MT_TOKENIZER = "models/indictrans2_tokenizer.json"
    const val EMOTION_MODEL = "models/emotion2vec.onnx"
    const val MANIFEST_DIR = "models/manifests"
    fun ttsVoice(lang: String) = "models/tts_${lang}_IN-medium.onnx"
    fun ttsConfig(lang: String) = "models/tts_${lang}_IN-medium.json"
    /** sherpa-onnx voice bundle: extracted dir contents (model, tokens.txt, espeak-ng-data/). */
    fun ttsDir(lang: String) = "models/tts/$lang"
    fun ttsTarball(lang: String) = "models/tts_${lang}_IN-medium.tar.bz2"
    /** MMS-TTS voice dir: model.onnx + tokens.txt (willwade ONNX bundle). */
    fun mmsDir(lang: String) = "models/mms/$lang"
    const val AUDIO_CACHE = "audio"
    const val PACKET_CACHE = "packets"
    const val MAX_AUDIO_CACHE_MB = 100
}

/** Emotion classes. id 1 (distress) triggers the emergency path. */
enum class Emotion(val id: Int, val label: String) {
    NEUTRAL(0, "neutral"),
    DISTRESS(1, "distress"),
    ANGRY(2, "angry"),
    CALM(3, "calm"),
    HAPPY(4, "happy");

    companion object {
        fun fromId(id: Int): Emotion = values().firstOrNull { it.id == id } ?: NEUTRAL
    }
}

enum class MessageDirection { SENT, RECEIVED }

enum class DeliveryStatus { PENDING, SENT, DELIVERED, FAILED }

enum class InputMode { PUSH_TO_TALK, AUTO }

enum class TransportType { BLE, LOOPBACK }

enum class ConnectionState { DISCONNECTED, SCANNING, ADVERTISING, CONNECTING, CONNECTED }

enum class ProcessingStage {
    IDLE, LISTENING, VAD_SEGMENT, ASR, PROSODY, EMOTION,
    TRANSLATE, PACKETIZE, TRANSMIT, RECEIVE, TTS, PLAYBACK, DONE, FAILED
}

/** 16 app states (AppFlow §2.2). */
enum class AppState {
    NOT_INITIALIZED, ONBOARDING, PERMISSION_REQUIRED, DOWNLOADING_MODELS,
    INITIALIZING, READY, RECORDING, PROCESSING, TRANSMITTING, RECEIVING,
    PLAYBACK, EMERGENCY_ALERT, SETTINGS, ERROR, OFFLINE, LOW_BATTERY, LOW_STORAGE
}

enum class ErrorCode(val code: Int) {
    MODEL_MISSING(1001),
    MODEL_LOAD_FAILED(1002),
    MODEL_SHA_MISMATCH(1003),
    ASR_EMPTY(2001),
    ASR_INFERENCE_FAILED(2002),
    MT_FAILED(2003),
    TTS_FAILED(2004),
    BLE_PACKET_INVALID(3005),
    BLE_NOT_CONNECTED(3001),
    BLE_SEND_FAILED(3002),
    BLE_MTU_TOO_SMALL(3003),
    BLE_SCAN_FAILED(3004),
    AUDIO_CAPTURE_FAILED(4001),
    AUDIO_PLAYBACK_FAILED(4002),
    PERMISSION_DENIED(5001),
    STORAGE_LOW(6001)
}

/** 22 scheduled languages + demo subset (honest 3-language demo: hi/ta/bn). */
object Languages {
    val ALL: Map<String, String> = mapOf(
        "as" to "অসমীয়া (Assamese)",
        "bn" to "বাংলা (Bengali)",
        "brx" to "बड़ो (Bodo)",
        "doi" to "डोगरी (Dogri)",
        "gu" to "ગુજરાતી (Gujarati)",
        "hi" to "हिंदी (Hindi)",
        "kn" to "ಕನ್ನಡ (Kannada)",
        "ks" to "کٲشُر (Kashmiri)",
        "kok" to "कोंकणी (Konkani)",
        "mai" to "मैथिली (Maithili)",
        "ml" to "മലയാളം (Malayalam)",
        "mni" to "মৈতৈলোন্ (Manipuri)",
        "mr" to "मराठी (Marathi)",
        "ne" to "नेपाली (Nepali)",
        "or" to "ଓଡ଼ିଆ (Odia)",
        "pa" to "ਪੰਜਾਬੀ (Punjabi)",
        "sa" to "संस्कृतम् (Sanskrit)",
        "sat" to "ᱥᱟᱱᱛᱟᱲᱤ (Santali)",
        "sd" to "سنڌي (Sindhi)",
        "ta" to "தமிழ் (Tamil)",
        "te" to "తెలుగు (Telugu)",
        "ur" to "اردو (Urdu)"
    )

    /** Languages with bundled demo support. Scope locked to hi/ta/pa. */
    val DEMO = setOf("hi", "ta", "pa")
    const val DEFAULT_SOURCE = "hi"
    const val DEFAULT_TARGET = "ta"

    fun isSupported(code: String): Boolean = ALL.containsKey(code)
    fun displayName(code: String): String = ALL[code] ?: code
}
